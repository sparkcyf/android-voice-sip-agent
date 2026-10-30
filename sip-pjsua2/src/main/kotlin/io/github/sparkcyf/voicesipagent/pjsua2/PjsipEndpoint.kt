package io.github.sparkcyf.voicesipagent.pjsua2

import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.pjmedia_srtp_use
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A single-account SIP endpoint backed by pjsua2, exposed as a [CallEndpoint]. Trunk semantics:
 * Request-URI user or X-VSA-Dial = number to dial, PAI = caller, X-VSA-SIMSLOT = [CallLeg.line].
 */
class PjsipEndpoint(
    val config: PjsipConfig,
    internal val log: PjsipLog = PjsipLog.Silent,
) : CallEndpoint {
    override val name: String = "sip"

    private val statusListeners = CopyOnWriteArrayList<EndpointStatusListener>()
    @Volatile private var incomingHandler: IncomingCallHandler? = null
    @Volatile override var status: EndpointStatus = STOPPED
        private set
    private val ids = AtomicInteger(1)

    // pjsua thread only
    internal var account: GwAccount? = null
        private set
    internal val calls = HashMap<Int, PjsipCall>()
    private var serverAddrs: Set<InetAddress> = emptySet()
    private var rtpCheck: ScheduledFuture<*>? = null
    @Volatile private var stopped = false
    private val workerFailed = AtomicBoolean()
    private val failureListener: (Throwable) -> Unit = ::onWorkerFailure
    private var unregistered = false

    private val hostPort = if (config.port == config.transport.defaultPort) config.server else "${config.server}:${config.port}"
    private val transportParam = if (config.transport == SipTransport.UDP) "" else ";transport=${config.transport.id}"
    /** Over TCP/TLS, or when the domain is not the server, every request goes through the server as proxy. */
    private val useProxy = config.transport != SipTransport.UDP || config.domain != config.server
    /** URIs use the domain; when that is the server itself (and no proxy is used), keep its port too. */
    private val uriHost = if (config.domain == config.server && !useProxy) hostPort else config.domain
    internal val aor = "sip:${config.username}@$uriHost"
    internal fun uriFor(user: String) = "sip:$user@$uriHost"

    // ------------------------------------------------------------ lifecycle

    /** Starts asynchronously so native initialization and DNS cannot block gateway controls. */
    fun start() {
        if (stopped || workerFailed.get()) return
        setStatus(EndpointStatus(false, "registering"))
        startup.execute {
            if (stopped) return@execute
            try {
                PjsuaLib.ensureStarted(log)
                if (!PjsuaLib.addFailureListener(failureListener)) {
                    onWorkerFailure(IllegalStateException("worker stopped during startup"))
                    return@execute
                }
                if (stopped || workerFailed.get()) {
                    PjsuaLib.removeFailureListener(failureListener)
                    return@execute
                }
                val addrs = runCatching { InetAddress.getAllByName(config.server).toSet() }
                    .onFailure { log.warn("cannot resolve ${config.server}: ${it.message}") }
                    .getOrDefault(emptySet())
                if (stopped || workerFailed.get()) {
                    PjsuaLib.removeFailureListener(failureListener)
                    return@execute
                }
                val accepted = PjsuaLib.tryPost {
                    if (stopped || workerFailed.get()) return@tryPost
                    serverAddrs = addrs
                    try {
                        createAccount()
                        rtpCheck = PjsuaLib.every(5000) { calls.values.toList().forEach { it.checkRtp(System.currentTimeMillis()) } }
                        log.info("pjsua2 endpoint started: $config")
                    } catch (e: Exception) {
                        log.warn("account setup failed", e)
                        setStatus(EndpointStatus(false, "setup failed: ${e.message}"))
                    }
                }
                if (!accepted) onWorkerFailure(IllegalStateException("worker stopped during startup"))
            } catch (e: Throwable) {
                log.warn("SIP start failed", e)
                setStatus(EndpointStatus(false, "start failed: ${e.message}"))
            }
        }
    }

    /**
     * Hangs up calls and unregisters. Blocks up to [timeoutMs]. With [releaseTransport] the local SIP
     * transport is closed gracefully too; socket release requires [stopLibrary]. Otherwise it is
     * kept for the next endpoint. A wait timeout does not cancel the queued cleanup.
     */
    fun stop(timeoutMs: Long = 2000, releaseTransport: Boolean = true) {
        stopped = true
        runCatching { setStatus(STOPPED) }.onFailure { log.warn("SIP stopped status notification failed", it) }
        if (!PjsuaLib.running) return
        val done = CountDownLatch(1)
        val accepted = PjsuaLib.tryPost {
            try {
                runCatching { rtpCheck?.cancel(false) }.onFailure { log.warn("RTP polling cleanup failed", it) }
                calls.values.toList().forEach { leg ->
                    runCatching { leg.doHangup(HangupCause.NORMAL) }.onFailure { log.warn("call hangup failed", it) }
                }
                account?.let { acc ->
                    // Registration may still be pending. Wait for an accepted unregister before a
                    // successor reuses the same Contact, or a late unregister could remove its binding.
                    runCatching {
                        acc.setRegistration(false)
                        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
                        while (!unregistered && System.nanoTime() < deadline) PjsuaLib.ep.libHandleEvents(20)
                        if (!unregistered) log.warn("SIP unregister wait timed out; continuing cleanup")
                    }.onFailure { log.warn("SIP unregister failed", it) }
                    // shutdown() detaches from pjsua; the Java wrapper must be freed explicitly.
                    runCatching { acc.shutdown() }.onFailure { log.warn("account shutdown failed", it) }
                    try {
                        runCatching { acc.delete() }.onFailure { log.warn("account deletion failed", it) }
                    } finally { account = null }
                }
                if (releaseTransport) runCatching { PjsuaLib.closeTransport(config.transport, config.localPort) }
                    .onFailure { log.warn("transport cleanup failed", it) }
            } finally {
                PjsuaLib.removeFailureListener(failureListener)
                done.countDown()
            }
        }
        if (!accepted) {
            // Keep the failure listener: the exiting owner may still need to clean up the account.
            log.warn("SIP endpoint cleanup rejected: worker is stopping")
        } else if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            log.warn("SIP endpoint cleanup wait timed out; queued cleanup will continue")
        }
    }

    /** Owner thread, or startup before an account exists if the worker already exited. */
    internal fun onWorkerFailure(failure: Throwable) {
        if (!workerFailed.compareAndSet(false, true)) return
        try {
            setStatus(EndpointStatus(false, "SIP worker failed: ${PjsuaLib.redact(failure.message.orEmpty())}"))
        } finally {
            rtpCheck?.cancel(false)
            calls.values.toList().forEach { leg ->
                runCatching { leg.failForWorkerLoss() }.onFailure { log.warn("call failure cleanup failed", it) }
            }
            calls.clear()
            val acc = account
            account = null
            // Call wrappers must be deleted before their account, outside any native callback.
            if (acc != null) PjsuaLib.post {
                try { acc.shutdown() } finally { acc.delete() }
            }
        }
    }

    /** Network changed: pjsua rebinds, re-registers and re-INVITEs active calls with the new address. */
    fun refreshRegistration() {
        if (!stopped && PjsuaLib.running) PjsuaLib.handleIpChange()
    }

    private fun createAccount() {
        if (config.transport != SipTransport.TLS && config.srtp != SrtpPolicy.OFF) {
            log.info("SRTP ${config.srtp.id} ignored: needs TLS signalling, using plain RTP")
        }
        val tid = PjsuaLib.transport(config.transport, config.localPort)
        val ac = AccountConfig()
        ac.idUri = aor
        ac.regConfig.apply {
            registrarUri = "sip:$hostPort$transportParam"
            timeoutSec = config.registerExpiresSec.toLong()
            retryIntervalSec = 30
            firstRetryIntervalSec = 5
        }
        ac.sipConfig.apply {
            authCreds.add(AuthCredInfo("digest", "*", config.authUsername, 0, config.password))
            transportId = tid
            if (useProxy) proxies.add("sip:$hostPort$transportParam;lr")
        }
        ac.natConfig.apply {
            sipOutboundUse = 0
        }
        ac.mediaConfig.apply {
            srtpUse = when {
                config.transport != SipTransport.TLS -> pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
                config.srtp == SrtpPolicy.OPTIONAL -> pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
                config.srtp == SrtpPolicy.MANDATORY -> pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY
                else -> pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
            }
            transportConfig.port = config.rtpPortStart.toLong()
            transportConfig.portRange = config.rtpPortCount.toLong()
        }
        val acc = GwAccount()
        acc.create(ac, true)
        ac.delete()
        account = acc
    }

    // ------------------------------------------------------------ CallEndpoint

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
        val leg = PjsipCall(this, LegDirection.OUTBOUND, Party(target), target, line, "p${ids.getAndIncrement()}")
        if (stopped || !PjsuaLib.running) {
            leg.doHangup(HangupCause.INTERNAL_ERROR)
            return leg
        }
        val accepted = PjsuaLib.tryPost {
            when {
                workerFailed.get() -> leg.failForWorkerLoss()
                stopped -> leg.doHangup(HangupCause.UNAVAILABLE)
                else -> leg.startOutbound(target, caller)
            }
        }
        // Rejected work never created native resources; finish on the caller without touching calls.
        if (!accepted) leg.doHangup(HangupCause.NETWORK_ERROR)
        return leg
    }

    @Synchronized
    private fun setStatus(s: EndpointStatus) {
        if ((stopped && s != STOPPED) || (workerFailed.get() && s.ready) || s == status) return
        status = s
        statusListeners.forEach { it.onStatusChanged(this, s) }
    }

    // ------------------------------------------------------------ pjsua callbacks

    private fun onRegState(prm: OnRegStateParam) {
        if (workerFailed.get()) return
        if (stopped) {
            if (prm.code / 100 == 2 && prm.expiration == 0L) unregistered = true
            return
        }
        val code = prm.code
        val ok = code / 100 == 2 && prm.expiration > 0
        val s = if (ok) EndpointStatus(true, "registered (pjsua2, ${prm.expiration}s)")
        else EndpointStatus(false, "register failed: $code ${prm.reason}")
        if (s.ready != status.ready || !s.ready) log.info("registration: $code ${prm.reason}")
        setStatus(s)
    }

    private fun onIncoming(acc: GwAccount, prm: OnIncomingCallParam) {
        val rdata = prm.rdata
        val src = rdata.srcAddress
        val msg = rdata.wholeMsg
        val target = Invite.target(msg)
        val caller = Invite.caller(msg)
        val line = Invite.line(msg)
        val leg = PjsipCall(this, LegDirection.INBOUND, caller, target, line, "p${ids.getAndIncrement()}")
        val call = leg.GwCall(acc, prm.callId)
        log.info("incoming INVITE to ${redactNumber(target)} from ${redactNumber(caller.number)}" +
            (line?.let { " slot $it" } ?: ""))
        val handler = incomingHandler
        val refuse = when {
            config.acceptOnlyFromServer && !fromServer(src) -> 403
            line == Invite.INVALID_LINE -> 400
            handler == null || stopped || workerFailed.get() -> 480
            else -> 0
        }
        if (refuse != 0) {
            if (refuse == 403) log.warn("rejecting INVITE from unexpected source")
            if (refuse == 400) log.warn("rejecting INVITE with invalid ${Invite.LINE_HEADER}")
            val op = CallOpParam()
            op.statusCode = refuse
            runCatching { call.hangup(op) }
            op.delete()
            PjsuaLib.post { call.delete() }
            return
        }
        leg.attachInbound(call)
        handler!!.onIncomingCall(leg)
    }

    private fun fromServer(src: String): Boolean {
        if (serverAddrs.isEmpty()) return true
        val host = src.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return addr in serverAddrs
    }

    companion object {
        private val STOPPED = EndpointStatus(false, "stopped")
        // Keep blocking initialization, DNS and teardown ordered without holding up gateway-runtime.
        private val startup = ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
            Thread(task, "sip-startup").apply { isDaemon = true }
        }

        /** Completes after native destruction releases sockets. Stop all endpoints before calling. */
        fun stopLibrary(): CompletableFuture<Unit> {
            val released = CompletableFuture<Unit>()
            startup.execute {
                val completion = PjsuaLib.releaseCompletion()
                runCatching { PjsuaLib.stop() }.onFailure { PjsuaLib.log.warn("SIP library shutdown failed", it) }
                // A bounded wait can expire while destruction is still running. Keep waiting
                // asynchronously for actual socket release rather than retrying accepted cleanup.
                completion.whenComplete { _, failure ->
                    if (failure == null) released.complete(Unit) else released.completeExceptionally(failure)
                }
            }
            return released
        }

        /** Passes pjsip's info-level log lines through (call/registration traces). Off by default. */
        fun setVerboseLogging(on: Boolean) {
            PjsuaLib.verbose = on
        }
    }

    internal inner class GwAccount : Account() {
        override fun onRegState(prm: OnRegStateParam) = this@PjsipEndpoint.onRegState(prm)
        override fun onIncomingCall(prm: OnIncomingCallParam) = onIncoming(this, prm)
    }
}

/** Minimal parsing of a received INVITE for the trunk semantics pjsua does not expose directly. */
internal object Invite {
    private val USER = Regex("^[^ ]+ (?:sips?|tel):([^@;> ]+)")

    /** Header carrying the number to dial when the Request-URI user must stay the gateway's own AoR. */
    const val DIAL_HEADER = "X-VSA-Dial"

    /** Logical SIM slot (0-based, as Android numbers them) of the cellular call, both directions. */
    const val LINE_HEADER = "X-VSA-SIMSLOT"

    /** [line] result for a present header that is not a slot index. */
    const val INVALID_LINE = -1

    /** [LINE_HEADER] as a slot index; null if absent, [INVALID_LINE] if malformed. */
    fun line(msg: String): Int? = header(msg, LINE_HEADER, null)?.let { v ->
        v.toIntOrNull()?.takeIf { it >= 0 } ?: INVALID_LINE
    }

    /**
     * The number the PBX wants us to dial: [DIAL_HEADER] if present (needed behind registrar-based
     * proxies such as Flexisip, which route on the Request-URI user), else the Request-URI user.
     */
    fun target(msg: String): String? =
        header(msg, DIAL_HEADER, null)?.takeIf { it.isNotBlank() } ?: requestUser(msg)

    fun requestUser(msg: String): String? = USER.find(msg.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)

    fun caller(msg: String): Party {
        val pai = header(msg, "P-Asserted-Identity", null)
        val from = header(msg, "From", "f")
        val src = pai ?: from
        return Party(number = src?.let(::uriUser) ?: from?.let(::uriUser), displayName = src?.let(::display))
    }

    private fun header(msg: String, name: String, compact: String?): String? {
        for (line in msg.lineSequence().drop(1)) {
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val n = line.substring(0, colon).trim()
            if (n.equals(name, true) || (compact != null && n.equals(compact, true))) return line.substring(colon + 1).trim()
        }
        return null
    }

    private fun uriUser(v: String): String? = Regex("(?:sips?|tel):([^@;> ]+)").find(v)?.groupValues?.get(1)

    private fun display(v: String): String? {
        val lt = v.indexOf('<')
        if (lt <= 0) return null
        return v.substring(0, lt).trim().trim('"').ifEmpty { null }
    }
}
