package io.github.sparkcyf.voicesipagent.pjsua2

import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.IpChangeParam
import org.pjsip.pjsua2.LogEntry
import org.pjsip.pjsua2.LogWriter
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pj_ssl_sock_proto
import org.pjsip.pjsua2.pj_log_decoration
import org.pjsip.pjsua2.pjsip_transport_type_e
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledFuture

/**
 * The process-wide pjsua2 library.
 *
 * pjsua is a per-process singleton. Ordinary endpoint restarts reuse it; disabling the gateway
 * destroys it on its own thread so native audio clocks and event polling also stop.
 * Everything that touches pjsua runs on the single "pjsua" thread. That thread also polls the SIP
 * ioqueue (threadCnt = 0) and runs pjsua2's pending jobs (mainThreadOnly), so all SIP callbacks
 * arrive on it too. The only other threads that enter Java are pjmedia's (the conference clock
 * calling AudioMediaPort), and those never call back into pjsua.
 */
internal object PjsuaLib {
    private const val USER_AGENT = "voice-sip-agent/0.2 pjsua2"
    private const val IDLE_POLL_MS = 100L
    private const val ACTIVE_POLL_MS = 10L
    private val DIGITS = Regex("\\d{5,}")

    private val worker = PjsuaWorker(::init, ::poll, ::release,
        onFailure = { log.warn("pjsua task failed", it) }, onTerminated = ::workerTerminated)
    private val failureListeners = CopyOnWriteArraySet<(Throwable) -> Unit>()
    @Volatile var log: PjsipLog = PjsipLog.Silent

    /** pjsip's own info-level (3) lines; errors and warnings (1-2) always pass. */
    @Volatile var verbose = false

    // pjsua thread only
    private var endpoint: Endpoint? = null
    val ep: Endpoint get() = endpoint ?: error("pjsua2 stopped")
    private var logWriter: LogWriter? = null
    /** Open transports by type and local port; TLS ones remember the certificate policy they were created with. */
    private val transports = HashMap<Pair<SipTransport, Int>, Pair<Int, TlsPolicy?>>()

    val running: Boolean get() = worker.running

    /** Subscribe immediately after ensureStarted, before DNS or account creation. */
    fun addFailureListener(listener: (Throwable) -> Unit): Boolean {
        failureListeners.add(listener)
        if (running) return true
        // Do not miss an exit that raced initialization/subscription. No account exists yet.
        failureListeners.remove(listener)
        return false
    }

    fun removeFailureListener(listener: (Throwable) -> Unit) { failureListeners.remove(listener) }

    private fun workerTerminated(failure: Throwable) {
        log.warn("pjsua worker terminated", failure)
        val listeners = failureListeners.toList()
        failureListeners.clear()
        listeners.forEach { listener ->
            runCatching { listener(failure) }.onFailure { log.warn("SIP failure cleanup failed", it) }
        }
    }

    /** Loads and initialises the library on first use. Throws if that fails. */
    @Synchronized
    fun ensureStarted(log: PjsipLog) {
        this.log = log
        worker.start()
    }

    fun post(task: () -> Unit) { worker.post(task) }

    fun tryPost(task: () -> Unit): Boolean = worker.post(task)

    fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*> =
        worker.schedule(delayMs, task)

    fun every(periodMs: Long, task: () -> Unit): ScheduledFuture<*> =
        worker.every(periodMs, task)

    @Synchronized
    fun stop() = worker.stop()

    fun releaseCompletion() = worker.releaseCompletion()

    /** The local address may have changed: rebind, re-register and re-INVITE active calls. */
    fun handleIpChange() = post {
        try {
            ep.handleIpChange(IpChangeParam())
        } catch (e: Exception) {
            log.warn("handleIpChange failed", e)
        }
    }

    /**
     * Shared transport per type and local port; kept open so endpoint restarts can rebind instantly.
     * A TLS transport keeps the certificate policy it was created with. pjsip keeps its connections open
     * after the listener closes and reuses them for new accounts, so a different [tls] needs a new library.
     */
    fun transport(type: SipTransport, port: Int, tls: TlsPolicy = TlsPolicy()): Int {
        val policy = tls.takeIf { type == SipTransport.TLS }
        transports[type to port]?.let { (id, current) ->
            check(current == policy) { "TLS certificate policy changed; restart the pjsua2 library first" }
            return id
        }
        val cfg = TransportConfig()
        cfg.port = port.toLong()
        val pjType = when (type) {
            SipTransport.UDP -> pjsip_transport_type_e.PJSIP_TRANSPORT_UDP
            SipTransport.TCP -> pjsip_transport_type_e.PJSIP_TRANSPORT_TCP
            SipTransport.TLS -> {
                cfg.tlsConfig.apply {
                    caBuf = policy!!.caPem ?: systemCaPem()
                    verifyServer = policy.verifyServer
                    proto = (pj_ssl_sock_proto.PJ_SSL_SOCK_PROTO_TLS1_2 or pj_ssl_sock_proto.PJ_SSL_SOCK_PROTO_TLS1_3).toLong()
                }
                policy!!.caPem?.let { pem ->
                    TlsCertificates.certificates(pem).forEach { log.info("TLS trusts only: ${TlsCertificates.describe(it)}") }
                }
                if (!policy.verifyServer) log.warn("TLS server certificate verification is OFF: any certificate is accepted")
                pjsip_transport_type_e.PJSIP_TRANSPORT_TLS
            }
        }
        val id = ep.transportCreate(pjType, cfg).also { cfg.delete() }
        transports[type to port] = id to policy
        return id
    }

    /** Graceful: pjsua destroys the socket once pending transactions (e.g. the unregister) finish. */
    fun closeTransport(type: SipTransport, port: Int) {
        val id = transports.remove(type to port)?.first ?: return
        try {
            ep.transportClose(id)
        } catch (e: Exception) {
            log.warn("transportClose failed: ${e.message}")
        }
    }

    /** The Android system trust store as one PEM bundle for OpenSSL. */
    private fun systemCaPem(): String {
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val sb = StringBuilder()
        var n = 0
        for (alias in ks.aliases()) {
            if (!alias.startsWith("system:")) continue
            val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
            sb.append(TlsCertificates.pem(cert))
            n++
        }
        log.info("TLS trust store: $n system CAs")
        return sb.toString()
    }

    fun redact(s: String): String = DIGITS.replace(s) { "**" + it.value.takeLast(2) }

    private fun poll() {
        // Port 0 is always present. Native sockets and timer deadlines can wake this wait early;
        // app commands may wait up to 100 ms while no call media is attached.
        val timeout = if (ep.mediaActivePorts() <= 1) IDLE_POLL_MS else ACTIVE_POLL_MS
        ep.libHandleEvents(timeout)
    }

    private fun init() {
        System.loadLibrary("c++_shared")
        System.loadLibrary("pjsua2")
        endpoint = Endpoint()
        ep.libCreate()
        val cfg = EpConfig()
        cfg.uaConfig.apply {
            threadCnt = 0
            mainThreadOnly = true
            maxCalls = 4
            userAgent = USER_AGENT
        }
        logWriter = object : LogWriter() {
            override fun write(entry: LogEntry) {
                // msgLogging is off, so no SIP message dumps; digit runs (numbers) are still masked.
                if (entry.level > 2 && !verbose) return
                val msg = "pj: " + redact(entry.msg.trimEnd())
                if (entry.level <= 2) log.warn(msg) else log.info(msg)
            }
        }
        cfg.logConfig.apply {
            writer = logWriter
            level = 3
            consoleLevel = 3 // gates the writer callback too, not just stdout
            msgLogging = 0
            decor = pj_log_decoration.PJ_LOG_HAS_SENDER.toLong()
        }
        cfg.medConfig.apply {
            // Match the 16 kHz mono tap/bridge, 20 ms. PJMEDIA resamples for G.711.
            // EC belongs at the remote microphone endpoint. Permit per-codec VAD below;
            // noVad=true would override the Opus setting when PJSUA creates a stream.
            clockRate = 16000
            sndClockRate = 16000
            channelCount = 1
            audioFramePtime = 20
            ptime = 20
            noVad = false
            ecTailLen = 0
        }
        ep.libInit(cfg)
        cfg.delete()
        // Opus maps VAD to its built-in DTX. G.711 must continue sending audio frames.
        for ((id, vad) in listOf("opus/48000/2" to true, "PCMU/8000" to false, "PCMA/8000" to false)) {
            val param = ep.codecGetParam(id)
            val setting = param.setting
            try {
                setting.vad = vad
                ep.codecSetParam(id, param)
            } finally {
                setting.delete()
                param.delete()
            }
        }
        val opus = ep.codecOpusConfig
        try {
            opus.sample_rate = 16000
            opus.channel_cnt = 1
            opus.frm_ptime = 20
            opus.frm_ptime_denum = 1
            opus.bit_rate = 20000
            opus.complexity = 5
            opus.cbr = false
            // Regenerates Opus fmtp from its codec parameters, including usedtx=1.
            ep.codecOpusConfig = opus
        } finally {
            opus.delete()
        }
        ep.libStart()
        // No sound device at all: the null device's master port clocks the conference bridge.
        ep.audDevManager().setNullDev()
        // RFC 7587 requires opus/48000/2 in SDP even for mono 16 kHz audio. PJMEDIA's
        // Opus config supplies maxplaybackrate/sprop-maxcapturerate=16000 in fmtp.
        ep.codecSetPriority("opus/48000/2", 255)
        ep.codecSetPriority("PCMU/8000", 254)
        ep.codecSetPriority("PCMA/8000", 253)
        log.info("pjsua2 ${ep.libVersion().full} started")
    }

    private fun release() {
        val previous = endpoint
        try { previous?.libDestroy() } finally {
            // Keep the Java log director alive until native destruction is complete.
            try { previous?.delete() } finally {
                endpoint = null
                try { logWriter?.delete() } finally {
                    logWriter = null
                    transports.clear()
                }
            }
        }
    }
}
