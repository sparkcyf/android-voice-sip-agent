package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.sip.media.RtpParams
import io.github.sparkcyf.voicesipagent.sip.media.RtpSession
import io.github.sparkcyf.voicesipagent.sip.media.Sdp
import io.github.sparkcyf.voicesipagent.sip.media.SdpAudio
import io.github.sparkcyf.voicesipagent.voice.AbstractCallLeg
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ScheduledFuture

/**
 * One INVITE dialog and its RTP stream. Public [io.github.sparkcyf.voicesipagent.voice.CallLeg] methods may be
 * called from any thread; everything else runs on the UA thread.
 */
internal class SipCall(
    private val ua: SipUserAgent,
    direction: LegDirection,
    remote: Party,
    target: String?,
    line: Int?,
    val callId: String,
    val localTag: String,
) : AbstractCallLeg(callId.take(8), "sip", direction, remote, target, line) {
    private val log = ua.logger
    private val config = ua.sipConfig

    // Dialog state (RFC 3261 §12)
    private lateinit var localUri: NameAddr
    private lateinit var remoteUri: NameAddr
    private var remoteTag: String? = null
    private lateinit var remoteTarget: String
    private var routeSet: List<String> = emptyList()
    private var localCseq = 0L

    // Outbound INVITE
    private var inviteReq: SipRequest? = null
    private var inviteTx: ClientTransaction? = null
    private var ackFor2xx: SipRequest? = null
    private var authAttempts = 0
    private var cancelPending = false
    private var cancelSent = false
    private var ringTimer: ScheduledFuture<*>? = null

    // Inbound INVITE
    private var inviteServerTx: ServerTransaction? = null
    private var awaitingAck: ServerTransaction? = null
    private var offer: SdpAudio? = null

    // Media
    private var rtpSocket: DatagramSocket? = null
    private var rtp: RtpSession? = null
    private var rtpParams: RtpParams? = null
    private val sdpSessionId = ua.newSdpSessionId()
    private var sdpVersion = 0L
    private var finished = false

    // ------------------------------------------------------------ CallLeg API (any thread)

    override fun ring() = ua.post {
        val tx = inviteServerTx ?: return@post
        if (direction != LegDirection.INBOUND || tx.isFinal || state != LegState.PROCEEDING) return@post
        tx.respond(ua.buildResponse(tx.request, 180, localTag).also { it.headers.add("Contact", "<${ua.contactUri()}>") })
        transition(LegState.RINGING)
    }

    override fun answer() = ua.post { doAnswer() }

    override fun hangup(cause: HangupCause) = ua.post { doHangup(cause) }

    override fun sendDtmf(digit: Char, durationMs: Int) {
        rtp?.sendDtmf(digit, durationMs)
    }

    // ------------------------------------------------------------ outbound

    fun startOutbound(target: String, caller: Party) {
        val acct = config.account
        val display = caller.displayName ?: caller.number ?: acct.displayName
        localUri = NameAddr(display, ua.aor)
        remoteUri = NameAddr(null, "sip:$target@${acct.domain}")
        remoteTarget = remoteUri.uri
        ua.registerCall(this)
        try {
            rtpSocket = ua.newRtpSocket()
        } catch (e: Exception) {
            log.warn("RTP bind failed", e)
            end(HangupCause.INTERNAL_ERROR)
            finish()
            return
        }
        log.info("dialing ${redactNumber(target)} (call $id)")
        sendInvite(null, caller)
        transition(LegState.PROCEEDING)
        ringTimer = ua.schedule(config.ringTimeoutMs) {
            if (state < LegState.CONNECTED) {
                log.info("call $id ring timeout")
                doHangup(HangupCause.NO_ANSWER)
            }
        }
    }

    private var callerForRetry: Party? = null

    private fun sendInvite(auth: Pair<String, String>?, caller: Party?) {
        if (caller != null) callerForRetry = caller
        val req = ua.newRequest("INVITE", remoteTarget, localUri.withTag(localTag), remoteUri, callId, ++localCseq)
        req.headers.add("Contact", "<${ua.contactUri()}>")
        ua.addCapabilities(req)
        callerForRetry?.number?.let { req.headers.add("P-Asserted-Identity", "<sip:$it@${config.account.domain}>") }
        line?.let { req.headers.add(SipUserAgent.LINE_HEADER, it.toString()) }
        auth?.let { req.headers.add(it.first, it.second) }
        req.headers.add("Content-Type", "application/sdp")
        req.body = localSdp(config.codecs.map { it.payloadType to it }, Sdp.DEFAULT_TE_PT, "sendrecv").toByteArray()
        req.headers.remove("Accept")
        inviteReq = req
        inviteTx = ua.sendClient(req) { r -> onInviteResponse(req, r) }
    }

    private fun onInviteResponse(req: SipRequest, r: SipResponse?) {
        if (req !== inviteReq) return
        if (r == null) {
            end(HangupCause.NETWORK_ERROR)
            finish()
            return
        }
        if (r.isProvisional) {
            if (r.code > 100) r.to?.tag?.let { remoteTag = it }
            if (cancelPending && !cancelSent) sendCancel()
            if ((r.code == 180 || r.code == 183) && state != LegState.ENDED) transition(LegState.RINGING)
            return
        }
        if (r.isSuccess) {
            remoteTag = r.to?.tag
            r.headers["Contact"]?.let(NameAddr::parse)?.let { remoteTarget = it.uri }
            routeSet = r.headers.getAll("Record-Route").reversed()
            sendAck2xx(r)
            if (state == LegState.ENDED) {
                // We hung up while the INVITE was in flight; tear the new dialog down.
                sendBye()
                return
            }
            val answer = Sdp.parse(r.bodyText)
            val codec = answer?.firstSupportedCodec()
            if (answer == null || codec == null) {
                log.warn("call $id: unusable SDP answer")
                sendBye()
                end(HangupCause.INTERNAL_ERROR)
                return
            }
            ringTimer?.cancel(false)
            startMedia(paramsFor(answer, codec))
            log.info("call $id connected (${codec.name})")
            transition(LegState.CONNECTED)
            publishMedia(rtp)
            return
        }
        if ((r.code == 401 || r.code == 407) && authAttempts++ < 2 && state != LegState.ENDED) {
            val auth = ua.authorizationFor(r, "INVITE", req.uri)
            if (auth != null) {
                cancelSent = false
                sendInvite(auth, null)
                return
            }
        }
        log.info("call $id failed: ${r.code} ${r.reason}")
        end(SipCodes.toCause(r.code))
        finish()
    }

    fun onStray2xx(r: SipResponse) {
        // Retransmitted 2xx: our ACK was lost.
        if (r.cseq?.seq == inviteReq?.cseq?.seq) ackFor2xx?.let(ua::sendRaw)
    }

    private fun sendAck2xx(r: SipResponse) {
        val cseq = r.cseq?.seq ?: localCseq
        val ack = ua.newRequest("ACK", remoteTarget, localUri.withTag(localTag), remoteWithTag(), callId, cseq, routeSet)
        ack.headers.remove("User-Agent")
        ackFor2xx = ack
        ua.sendRaw(ack)
    }

    private fun sendCancel() {
        val invite = inviteReq ?: return
        cancelSent = true
        val h = SipHeaders()
        h.add("Via", invite.headers.getAll("Via").first())
        invite.headers.getAll("Route").forEach { h.add("Route", it) }
        h.add("Max-Forwards", "70")
        h.add("From", invite.headers["From"]!!)
        h.add("To", invite.headers["To"]!!)
        h.add("Call-ID", callId)
        h.add("CSeq", "${invite.cseq!!.seq} CANCEL")
        ua.sendClient(SipRequest("CANCEL", invite.uri, h)) {}
    }

    // ------------------------------------------------------------ inbound

    /** Validates a new INVITE; responds and returns false if we cannot take it. */
    fun acceptOffer(req: SipRequest, tx: ServerTransaction): Boolean {
        inviteServerTx = tx
        localUri = req.to!!.let { NameAddr(it.displayName, it.uri) }
        val from = req.from!!
        remoteUri = NameAddr(from.displayName, from.uri)
        remoteTag = from.tag
        remoteTarget = req.headers["Contact"]?.let(NameAddr::parse)?.uri ?: from.uri
        routeSet = req.headers.getAll("Record-Route")
        tx.respond(ua.buildResponse(req, 100))
        val sdp = if (req.contentType == "application/sdp") Sdp.parse(req.bodyText) else null
        if (sdp == null || sdp.firstSupportedCodec() == null) {
            log.warn("call $id: no acceptable SDP offer")
            tx.respond(ua.buildResponse(req, 488, localTag))
            return false
        }
        offer = sdp
        try {
            rtpSocket = ua.newRtpSocket()
        } catch (e: Exception) {
            log.warn("RTP bind failed", e)
            tx.respond(ua.buildResponse(req, 500, localTag))
            return false
        }
        transition(LegState.PROCEEDING)
        return true
    }

    private fun doAnswer() {
        val tx = inviteServerTx ?: return
        val sdp = offer ?: return
        if (direction != LegDirection.INBOUND || tx.isFinal || state >= LegState.CONNECTED) return
        val codec = sdp.firstSupportedCodec()!!
        val resp = ua.buildResponse(tx.request, 200, localTag)
        resp.headers.add("Contact", "<${ua.contactUri()}>")
        ua.addCapabilities(resp)
        resp.headers.add("Content-Type", "application/sdp")
        resp.body = localSdp(listOf(sdp.payloadFor(codec) to codec), sdp.telephoneEventPt, Sdp.answerDirection(sdp.direction)).toByteArray()
        tx.onAckTimeout = {
            if (state == LegState.CONNECTED) {
                log.warn("call $id: 200 OK never ACKed")
                sendBye()
                end(HangupCause.NETWORK_ERROR)
            }
        }
        awaitingAck = tx
        tx.respond(resp)
        startMedia(paramsFor(sdp, codec))
        log.info("call $id answered (${codec.name})")
        transition(LegState.CONNECTED)
        publishMedia(rtp)
    }

    fun onCancel(tx: ServerTransaction) {
        if (tx !== inviteServerTx || tx.isFinal) return
        tx.respond(ua.buildResponse(tx.request, 487, localTag))
        log.info("call $id cancelled by caller")
        end(HangupCause.CANCELLED)
        finish()
    }

    fun onAck(req: SipRequest) {
        awaitingAck?.onAck()
        awaitingAck = null
        if (req.contentType == "application/sdp") Sdp.parse(req.bodyText)?.let(::applyRemoteSdp)
    }

    fun onInDialogRequest(req: SipRequest, tx: ServerTransaction) {
        if (req.to?.tag == null) {
            tx.respond(ua.buildResponse(req, 482))
            return
        }
        when (req.method) {
            "BYE" -> {
                tx.respond(ua.buildResponse(req, 200))
                log.info("call $id: remote hung up")
                end(HangupCause.NORMAL)
                finish()
            }
            "INVITE", "UPDATE" -> onSessionRefresh(req, tx)
            "INFO" -> {
                parseDtmfInfo(req)?.let { (d, ms) -> publishDtmf(d, ms) }
                tx.respond(ua.buildResponse(req, 200))
            }
            "OPTIONS", "NOTIFY" -> tx.respond(ua.buildResponse(req, 200))
            else -> tx.respond(ua.buildResponse(req, 405).also(ua::addCapabilities))
        }
    }

    /** re-INVITE / UPDATE: hold, codec or address changes, and session-timer refreshes. */
    private fun onSessionRefresh(req: SipRequest, tx: ServerTransaction) {
        val isInvite = req.method == "INVITE"
        if (isInvite && state != LegState.CONNECTED) {
            tx.respond(ua.buildResponse(req, 491))
            return
        }
        req.headers["Contact"]?.let(NameAddr::parse)?.let { remoteTarget = it.uri }
        val sdp = if (req.contentType == "application/sdp") Sdp.parse(req.bodyText) else null
        val resp = ua.buildResponse(req, 200)
        resp.headers.add("Contact", "<${ua.contactUri()}>")
        val cur = rtpParams
        if (sdp != null) {
            val codec = sdp.firstSupportedCodec()
            if (codec == null) {
                tx.respond(ua.buildResponse(req, 488))
                return
            }
            applyRemoteSdp(sdp)
            resp.headers.add("Content-Type", "application/sdp")
            resp.body = localSdp(listOf(sdp.payloadFor(codec) to codec), sdp.telephoneEventPt, Sdp.answerDirection(sdp.direction)).toByteArray()
        } else if (cur != null && isInvite) {
            // Offerless re-INVITE: we offer what we have; the answer arrives in the ACK.
            resp.headers.add("Content-Type", "application/sdp")
            resp.body = localSdp(listOf(cur.payloadType to cur.codec), cur.telephoneEventPt, "sendrecv").toByteArray()
        }
        if (isInvite) awaitingAck = tx
        tx.respond(resp)
    }

    private fun applyRemoteSdp(sdp: SdpAudio) {
        val codec = sdp.firstSupportedCodec() ?: return
        val p = paramsFor(sdp, codec)
        if (p != rtpParams) log.info("call $id media update: ${codec.name} hold=${sdp.isOnHold}")
        startMedia(p)
    }

    // ------------------------------------------------------------ teardown

    private fun doHangup(cause: HangupCause) {
        if (state == LegState.ENDED) return
        if (direction == LegDirection.INBOUND) {
            val tx = inviteServerTx
            if (tx != null && !tx.isFinal) {
                tx.respond(ua.buildResponse(tx.request, SipCodes.forCause(cause), localTag))
                end(cause)
                finish()
            } else {
                sendBye()
                end(cause)
            }
            return
        }
        if (state == LegState.CONNECTED) {
            sendBye()
            end(cause)
            return
        }
        // Outbound, not yet answered: CANCEL once a provisional has arrived (RFC 3261 §9.1).
        end(cause)
        val tx = inviteTx
        if (tx == null || tx.completed) {
            finish()
        } else if (tx.gotProvisional) {
            sendCancel()
        } else {
            cancelPending = true
        }
    }

    private fun sendBye() {
        val bye = ua.newRequest("BYE", remoteTarget, localUri.withTag(localTag), remoteWithTag(), callId, ++localCseq, routeSet)
        ua.sendClient(bye) { r -> if (r == null || !r.isProvisional) finish() }
    }

    private fun remoteWithTag(): NameAddr = remoteTag?.let(remoteUri::withTag) ?: remoteUri

    private fun end(cause: HangupCause) {
        ringTimer?.cancel(false)
        stopMedia()
        transition(LegState.ENDED, cause)
    }

    private fun finish() {
        if (finished) return
        finished = true
        ringTimer?.cancel(false)
        stopMedia()
        rtpSocket?.close()
        ua.removeCall(this)
    }

    fun checkRtpTimeout(now: Long) {
        val r = rtp ?: return
        val p = rtpParams ?: return
        if (state != LegState.CONNECTED || !p.sendEnabled) return
        if (now - r.lastReceiveAtMs > config.rtpTimeoutMs) {
            log.warn("call $id: no RTP for ${config.rtpTimeoutMs} ms, hanging up")
            doHangup(HangupCause.NETWORK_ERROR)
        }
    }

    // ------------------------------------------------------------ media helpers

    private fun paramsFor(sdp: SdpAudio, codec: io.github.sparkcyf.voicesipagent.sip.media.AudioCodec) = RtpParams(
        remote = InetSocketAddress(InetAddress.getByName(sdp.address), sdp.port),
        codec = codec,
        payloadType = sdp.payloadFor(codec),
        telephoneEventPt = sdp.telephoneEventPt,
        sendEnabled = !sdp.isOnHold && sdp.port != 0,
    )

    private fun startMedia(p: RtpParams) {
        rtpParams = p
        val cur = rtp
        if (cur != null) {
            cur.update(p)
            return
        }
        val sock = rtpSocket ?: return
        rtp = RtpSession(sock, p) { d, ms -> publishDtmf(d, ms) }.also { it.start() }
    }

    private fun stopMedia() {
        rtp?.close()
        rtp = null
    }

    private fun localSdp(codecs: List<Pair<Int, io.github.sparkcyf.voicesipagent.sip.media.AudioCodec>>, tePt: Int?, direction: String): String =
        Sdp.build(sdpSessionId, sdpVersion++, ua.localIp, rtpSocket?.localPort ?: 0, codecs, tePt, direction)

    private fun parseDtmfInfo(req: SipRequest): Pair<Char, Int>? {
        val type = req.contentType ?: return null
        val body = req.bodyText.trim()
        return when (type) {
            "application/dtmf-relay" -> {
                val kv = body.lines().associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim() }
                val sig = kv["signal"]?.firstOrNull() ?: return null
                sig to (kv["duration"]?.toIntOrNull() ?: 160)
            }
            "application/dtmf" -> body.firstOrNull()?.let { it to 160 }
            else -> null
        }
    }

    /** Diagnostics for UIs and logs. */
    val mediaStats: String
        get() = rtp?.let { "rtp tx=${it.packetsSent} rx=${it.packetsReceived} underruns=${it.jitterUnderruns}" } ?: "no media"
}
