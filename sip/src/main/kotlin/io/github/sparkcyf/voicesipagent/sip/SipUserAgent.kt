package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A single-account SIP user agent over UDP, exposed to the rest of the app as a [CallEndpoint].
 * All protocol state lives on one scheduler thread; public methods post onto it.
 *
 * Deprecated: new features target the pjsua2 stack. This one only keeps trunk compatibility.
 */
class SipUserAgent(
    val config: SipConfig,
    private val log: SipLog = SipLog.Stdout,
    /** Override to supply the address peers should reach us on (e.g. the Wi-Fi interface). */
    private val localAddressProvider: ((InetAddress) -> InetAddress?)? = null,
) : CallEndpoint {
    override val name: String = "sip"

    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "sip-ua").apply { isDaemon = true } }
        .apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
            continueExistingPeriodicTasksAfterShutdownPolicy = false
        }
    private val random = SecureRandom()
    internal lateinit var transport: SipTransport
    private lateinit var serverAddr: InetSocketAddress
    internal var localIp: String = "0.0.0.0"
        private set
    internal val localPort: Int get() = transport.localPort

    private val clientTxs = HashMap<String, ClientTransaction>()
    private val serverTxs = HashMap<String, ServerTransaction>()
    internal val calls = HashMap<String, SipCall>()

    private val statusListeners = CopyOnWriteArrayList<EndpointStatusListener>()
    @Volatile private var incomingHandler: IncomingCallHandler? = null
    @Volatile override var status: EndpointStatus = EndpointStatus(false, "stopped")
        private set

    // Registration state
    private val regCallId = newCallId()
    private val regTag = newTag()
    private var regCseq = 0L
    private var regRefresh: ScheduledFuture<*>? = null
    private var regExpiry: ScheduledFuture<*>? = null
    private var regExpiresAtNanos: Long? = null
    private var regGeneration = 0L
    private var regAuthAttempts = 0
    private var started = false
    private var stopping = false
    private var stopped: CompletableFuture<Unit>? = null

    private val account get() = config.account
    internal val aor: String get() = "sip:${account.username}@${account.domain}"

    // ---------------------------------------------------------------- lifecycle

    fun start() = post {
        if (started) return@post
        started = true
        stopping = false
        transport = SipTransport(config.localPort, log) { msg, from -> post { onMessage(msg, from) } }
        transport.start()
        log.info("started on UDP $localPort -> ${account.server}:${account.port}")
        executor.scheduleWithFixedDelay({ checkMedia() }, 5, 5, TimeUnit.SECONDS)
        register()
    }

    /** Best-effort unregister (up to [timeoutMs]), then waits up to 1 s for local socket release. */
    @Synchronized
    fun stop(timeoutMs: Long = 2000): CompletableFuture<Unit> {
        stopped?.let { return it }
        val released = CompletableFuture<Unit>()
        stopped = released
        val done = CountDownLatch(1)
        post {
            try {
                if (!started) { done.countDown(); return@post }
                stopping = true
                ++regGeneration
                clearRegistrationExpiry()
                calls.values.toList().forEach { leg ->
                    runCatching { leg.hangup(HangupCause.NORMAL) }.onFailure { log.warn("call hangup failed", it) }
                }
                regRefresh?.cancel(false)
                setStatus(false, "stopping")
                if (!::serverAddr.isInitialized) { done.countDown(); return@post }
                sendRegister(0, null) { done.countDown() }
            } catch (t: Throwable) {
                log.warn("SIP unregister failed", t)
                done.countDown()
            }
        }
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS))
                log.warn("SIP unregister wait timed out; releasing local socket")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("SIP unregister wait interrupted; releasing local socket")
        } finally {
            // Completion means local release, independently of an unregister reply or status listener.
            try {
                executor.execute {
                    try {
                        if (::transport.isInitialized) transport.close()
                        released.complete(Unit)
                    } catch (t: Throwable) {
                        log.warn("SIP socket release failed", t)
                        released.completeExceptionally(t)
                    } finally {
                        started = false
                        runCatching { setStatus(false, "stopped") }
                            .onFailure { log.warn("SIP stopped status notification failed", it) }
                    }
                }
            } catch (t: Throwable) {
                log.warn("SIP socket cleanup rejected", t)
                released.completeExceptionally(t)
            } finally { executor.shutdown() }
        }
        try {
            released.get(1000, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            log.warn("SIP socket release wait timed out; queued cleanup will continue")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: java.util.concurrent.ExecutionException) {
            // The failure was logged by the cleanup task and is available to its caller.
        }
        return released
    }

    /** Call when the network changes: refreshes our address and re-registers. */
    fun refreshRegistration() = post {
        if (!started || stopping) return@post
        register()
    }

    override fun setIncomingCallHandler(handler: IncomingCallHandler?) {
        incomingHandler = handler
    }

    override fun addStatusListener(listener: EndpointStatusListener) {
        statusListeners.addIfAbsent(listener)
    }

    override fun removeStatusListener(listener: EndpointStatusListener) {
        statusListeners.remove(listener)
    }

    override fun dial(target: String, caller: Party, line: Int?): CallLeg {
        val call = SipCall(this, LegDirection.OUTBOUND, Party(target), target, line, newCallId(), newTag())
        post { call.startOutbound(target, caller) }
        return call
    }

    // ---------------------------------------------------------------- registration

    private fun register() {
        if (stopping) return
        regRefresh?.cancel(false)
        ++regGeneration
        regAuthAttempts = 0
        // A REGISTER refresh does not invalidate the binding already held by the registrar.
        // Readiness loss tears down gateway calls, so retain it until failure or actual expiry.
        val registered = regExpiresAtNanos?.let { it - System.nanoTime() > 0 } == true
        setStatus(registered, if (registered) "refreshing registration" else "registering")
        try { resolve() } catch (e: Exception) {
            registerFailed("cannot resolve server: ${e.message}")
            return
        }
        sendRegister(config.registerExpiresSec, null)
    }

    private fun sendRegister(
        expires: Int,
        auth: Pair<String, String>?,
        generation: Long = regGeneration,
        onDone: (() -> Unit)? = null,
    ) {
        val req = newRequest(
            method = "REGISTER",
            uri = "sip:${account.domain}",
            from = NameAddr(account.displayName, aor).withTag(regTag),
            to = NameAddr(account.displayName, aor),
            callId = regCallId,
            cseq = ++regCseq,
        )
        req.headers.add("Contact", "<${contactUri()}>")
        req.headers.add("Expires", expires.toString())
        auth?.let { req.headers.add(it.first, it.second) }
        val sentAtNanos = System.nanoTime()
        sendClient(req) { r ->
            if (generation != regGeneration || (stopping && expires > 0)) return@sendClient
            when {
                r == null -> {
                    onDone?.invoke()
                    if (expires > 0) registerFailed("timeout")
                }
                r.isProvisional -> Unit
                (r.code == 401 || r.code == 407) && regAuthAttempts++ < 2 -> {
                    val authHeader = authorizationFor(r, "REGISTER", req.uri)
                    if (authHeader == null) {
                        onDone?.invoke()
                        registerFailed("unsupported challenge")
                    } else {
                        sendRegister(expires, authHeader, generation, onDone)
                    }
                }
                r.isSuccess -> {
                    onDone?.invoke()
                    regAuthAttempts = 0
                    if (expires == 0) return@sendClient
                    val granted = grantedExpires(r) ?: expires
                    registrationSucceeded(granted, sentAtNanos)
                }
                else -> {
                    onDone?.invoke()
                    if (expires > 0) registerFailed("${r.code} ${r.reason}")
                }
            }
        }
    }

    private fun registrationSucceeded(granted: Int, sentAtNanos: Long) {
        // Use a monotonic clock and count from transmission, conservatively including response delay.
        val expiresAt = sentAtNanos + TimeUnit.SECONDS.toNanos(granted.toLong())
        val remainingNanos = expiresAt - System.nanoTime()
        if (granted <= 0 || remainingNanos <= 0) {
            registerFailed("registration expired")
            return
        }
        clearRegistrationExpiry()
        regExpiresAtNanos = expiresAt
        regExpiry = schedule(TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1) {
            clearRegistrationExpiry()
            setStatus(false, "registration expired")
        }
        setStatus(true, "registered ($granted s)")
        log.info("registered as ${account.username}, expires=$granted")
        val grantedMs = granted.toLong() * 1000
        val refreshIn = maxOf(grantedMs * 85 / 100, minOf(grantedMs - 5000, 30_000))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sentAtNanos)
        // Even a short registrar grant must renew before expiry; a 5 s floor can exceed its lifetime.
        regRefresh = schedule(maxOf(refreshIn - elapsedMs, 0)) { register() }
    }

    private fun clearRegistrationExpiry() {
        regExpiry?.cancel(false)
        regExpiry = null
        regExpiresAtNanos = null
    }

    private fun grantedExpires(r: SipResponse): Int? {
        val mine = contactUri()
        for (c in r.headers.getAll("Contact")) {
            val na = NameAddr.parse(c) ?: continue
            if (na.uri.equals(mine, ignoreCase = true)) na.params["expires"]?.toIntOrNull()?.let { return it }
        }
        return r.headers["Expires"]?.trim()?.toIntOrNull()
    }

    private fun registerFailed(why: String) {
        clearRegistrationExpiry()
        log.warn("registration failed: $why; retrying in 30 s")
        setStatus(false, "registration failed: $why")
        regRefresh = schedule(30_000) { register() }
    }

    private fun setStatus(ready: Boolean, detail: String) {
        val s = EndpointStatus(ready, detail)
        if (s == status) return
        status = s
        statusListeners.forEach { it.onStatusChanged(this, s) }
    }

    internal fun authorizationFor(r: SipResponse, method: String, uri: String): Pair<String, String>? {
        val proxy = r.code == 407
        val header = r.headers[if (proxy) "Proxy-Authenticate" else "WWW-Authenticate"] ?: return null
        val challenge = DigestAuth.parseChallenge(header) ?: return null
        return try {
            (if (proxy) "Proxy-Authorization" else "Authorization") to
                DigestAuth.authorize(challenge, method, uri, account.authUsername, account.password)
        } catch (e: IllegalArgumentException) {
            log.warn("cannot answer challenge", e)
            null
        }
    }

    // ---------------------------------------------------------------- message dispatch

    private fun onMessage(msg: SipMessage, from: InetSocketAddress) {
        if (config.acceptOnlyFromServer && from.address != serverAddr.address) {
            log.warn("dropping SIP from untrusted ${from.address.hostAddress}")
            return
        }
        when (msg) {
            is SipResponse -> onResponse(msg)
            is SipRequest -> onRequest(msg, from)
        }
    }

    private fun onResponse(r: SipResponse) {
        val method = r.cseq?.method ?: return
        val tx = clientTxs[txKey(r.topVia?.branch, method)]
        if (tx != null) {
            tx.onResponse(r)
        } else if (method == "INVITE" && r.isSuccess) {
            calls[r.callId]?.onStray2xx(r)
        }
    }

    private fun onRequest(req: SipRequest, from: InetSocketAddress) {
        if (req.callId == null || req.cseq == null || req.topVia == null || req.headers["From"] == null || req.headers["To"] == null) {
            if (req.method != "ACK") transport.send(buildResponse(req, 400), from)
            return
        }
        if (req.method == "ACK") {
            serverTxs[txKey(req.topVia?.branch, "INVITE")]?.onAck()
            calls[req.callId]?.onAck(req)
            return
        }
        serverTxs[txKey(req.topVia?.branch, req.method)]?.let {
            it.onRetransmission()
            return
        }
        val tx = ServerTransaction(this, req, from)
        serverTxs[tx.key] = tx
        val call = calls[req.callId]
        val toTag = req.to?.tag
        when {
            req.method == "CANCEL" -> {
                val inviteTx = serverTxs[txKey(req.topVia?.branch, "INVITE")]
                if (inviteTx == null || call == null) {
                    tx.respond(buildResponse(req, 481))
                } else {
                    tx.respond(buildResponse(req, 200, toTag = call.localTag))
                    call.onCancel(inviteTx)
                }
            }
            call != null && (toTag != null || req.method == "INVITE") -> call.onInDialogRequest(req, tx)
            toTag != null -> tx.respond(buildResponse(req, 481))
            req.method == "INVITE" -> onNewInvite(req, tx)
            req.method == "OPTIONS" -> tx.respond(buildResponse(req, 200, toTag = newTag()).also { addCapabilities(it) })
            req.method == "NOTIFY" -> tx.respond(buildResponse(req, 200, toTag = newTag()))
            req.method == "BYE" || req.method == "INFO" || req.method == "UPDATE" -> tx.respond(buildResponse(req, 481))
            else -> tx.respond(buildResponse(req, 405).also { addCapabilities(it) })
        }
    }

    private fun onNewInvite(req: SipRequest, tx: ServerTransaction) {
        val handler = incomingHandler
        // X-VSA-Dial: the number to dial when the PBX keeps the Request-URI user as our own AoR
        // (needed behind registrar-based proxies such as Flexisip).
        val target = req.headers["X-VSA-Dial"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: SipUri.parse(req.uri)?.user ?: req.to?.sipUri?.user
        val pai = req.headers["P-Asserted-Identity"]?.let(NameAddr::parse)
        val fromAddr = req.from!!
        val caller = Party(
            number = pai?.sipUri?.user ?: fromAddr.sipUri?.user,
            displayName = pai?.displayName ?: fromAddr.displayName,
        )
        val lineHeader = req.headers[LINE_HEADER]?.trim()
        val line = lineHeader?.toIntOrNull()?.takeIf { it >= 0 }
        val call = SipCall(this, LegDirection.INBOUND, caller, target, line, req.callId!!, newTag())
        log.info("incoming INVITE to ${redactNumber(target)} from ${redactNumber(caller.number)}")
        if (lineHeader != null && line == null) {
            log.warn("rejecting INVITE with invalid $LINE_HEADER")
            tx.respond(buildResponse(req, 400, toTag = call.localTag))
            return
        }
        if (handler == null || stopping) {
            tx.respond(buildResponse(req, 480, toTag = call.localTag))
            return
        }
        if (!call.acceptOffer(req, tx)) return
        calls[call.callId] = call
        handler.onIncomingCall(call)
    }

    // ---------------------------------------------------------------- helpers used by SipCall

    internal fun post(block: () -> Unit) {
        if (executor.isShutdown) return
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                log.warn("UA task failed", t)
            }
        }
    }

    internal fun schedule(delayMs: Long, block: () -> Unit): ScheduledFuture<*> =
        executor.schedule({
            try {
                block()
            } catch (t: Throwable) {
                log.warn("UA timer failed", t)
            }
        }, delayMs, TimeUnit.MILLISECONDS)

    internal fun sendClient(req: SipRequest, handler: (SipResponse?) -> Unit): ClientTransaction {
        val tx = ClientTransaction(this, req, serverAddr, handler)
        clientTxs[tx.key] = tx
        tx.start()
        return tx
    }

    /** Fire-and-forget (ACK for 2xx). */
    internal fun sendRaw(req: SipRequest) = transport.send(req, serverAddr)

    internal fun removeClientTx(tx: ClientTransaction) {
        if (clientTxs[tx.key] === tx) clientTxs.remove(tx.key)
    }

    internal fun removeServerTx(tx: ServerTransaction) {
        if (serverTxs[tx.key] === tx) serverTxs.remove(tx.key)
    }

    internal fun removeCall(call: SipCall) {
        if (calls[call.callId] === call) calls.remove(call.callId)
    }

    internal fun registerCall(call: SipCall) {
        calls[call.callId] = call
    }

    internal fun contactUri(): String = "sip:${account.username}@$localIp:$localPort"

    internal fun newRequest(
        method: String,
        uri: String,
        from: NameAddr,
        to: NameAddr,
        callId: String,
        cseq: Long,
        routes: List<String> = emptyList(),
        branch: String = newBranch(),
    ): SipRequest {
        val h = SipHeaders()
        h.add("Via", "SIP/2.0/UDP $localIp:$localPort;branch=$branch;rport")
        routes.forEach { h.add("Route", it) }
        h.add("Max-Forwards", "70")
        h.add("From", from.toString())
        h.add("To", to.toString())
        h.add("Call-ID", callId)
        h.add("CSeq", "$cseq $method")
        h.add("User-Agent", config.userAgent)
        return SipRequest(method, uri, h)
    }

    internal fun buildResponse(req: SipRequest, code: Int, toTag: String? = null): SipResponse {
        val h = SipHeaders()
        req.headers.getAll("Via").forEach { h.add("Via", it) }
        if (req.method == "INVITE" && code in 101..299) h.copyFrom(req.headers, "Record-Route")
        h.add("From", req.headers["From"]!!)
        val to = req.headers["To"]!!
        h.add("To", if (toTag != null && code > 100 && NameAddr.parse(to)?.tag == null) "$to;tag=$toTag" else to)
        h.add("Call-ID", req.callId!!)
        h.add("CSeq", req.headers["CSeq"]!!)
        h.add("Server", config.userAgent)
        return SipResponse(code, SipCodes.reason(code), h)
    }

    internal fun addCapabilities(r: SipMessage) {
        r.headers.set("Allow", ALLOW)
        r.headers.set("Accept", "application/sdp")
    }

    internal fun newRtpSocket(): DatagramSocket = io.github.sparkcyf.voicesipagent.sip.media.RtpSession.bindSocket(null, config.rtpPortRange)

    internal fun newBranch() = "z9hG4bK" + hex(10)
    internal fun newTag() = hex(6)
    private fun newCallId() = hex(12)
    internal fun newSdpSessionId() = (random.nextLong() and Long.MAX_VALUE) % 1_000_000_000L
    internal val logger: SipLog get() = log
    internal val sipConfig: SipConfig get() = config

    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun resolve() {
        val server = InetAddress.getByName(account.server)
        serverAddr = InetSocketAddress(server, account.port)
        localIp = (localAddressProvider?.invoke(server) ?: routeTo(server))?.hostAddress ?: "0.0.0.0"
    }

    /** The local address the OS would use to reach [dest]. */
    private fun routeTo(dest: InetAddress): InetAddress? = try {
        DatagramSocket().use { s ->
            s.connect(dest, 9)
            s.localAddress.takeUnless { it.isAnyLocalAddress }
        }
    } catch (e: Exception) {
        null
    }

    private fun checkMedia() {
        val now = System.currentTimeMillis()
        calls.values.toList().forEach { it.checkRtpTimeout(now) }
    }

    companion object {
        const val ALLOW = "INVITE, ACK, CANCEL, BYE, OPTIONS, INFO, NOTIFY, UPDATE"

        /** Logical SIM slot of the cellular call; same header as the pjsua2 stack. */
        const val LINE_HEADER = "X-VSA-SIMSLOT"
    }
}
