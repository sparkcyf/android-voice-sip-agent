package io.github.sparkcyf.voicesipagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import io.github.sparkcyf.voicesipagent.pjsua2.SrtpPolicy
import io.github.sparkcyf.voicesipagent.telephony.DefaultSim
import java.util.concurrent.atomic.AtomicBoolean

/**
 * adb-only control (protected by the DUMP permission). Examples:
 *   adb shell am broadcast -n io.github.sparkcyf.voicesipagent/io.github.sparkcyf.voicesipagent.app.AdbControlReceiver --es op status
 *   ... --es op provision --es server pbx.example.org --ei port 5060 --es user gateway --es password '***' [--es target gateway]
 *   ... --es op setup | sip-restart | loopback --ez on true | takeover --ez on false
 *   ... --es op earpiece-mute --ez on false
 *   ... --es op sip-stack --es stack pjsua2|builtin
 *   ... --es op default-sim --es sim system|refuse|<slot>   (SIM for PBX calls without X-VSA-SIMSLOT)
 *   ... --es op sim-inbound --ei slot 0 --ez on false      (leave that SIM's incoming calls to the phone app)
 *   ... --es op provision --es server sip.example.org --ei port 5061 --es transport tls --es domain sip.example.org [--es srtp optional]
 *   ... --es op provision --ei audio_method 2      (0 auto, 1 redirection, 2 direct)
 *   ... --es op sip-debug --ez on true      (pjsip info-level logs; off by default)
 *   ... --es op provision --ez hold_sip false  (keep SIP registered while the cellular side is not ready)
 *
 * Every op acknowledges submission with an observed runtime snapshot, e.g.
 *   Broadcast completed: result=1, data="ok: ready=true (Gateway ready) takeover=on sip=ready ..."
 * [RESULT_OK] = 1, [RESULT_ERROR] = 2 (bad input or submission/reporting failure),
 * [RESULT_TIMEOUT] = 3 (queued or running; see logcat). An acknowledgement does not guarantee completion.
 * result=0 with no data means the broadcast never reached this receiver (e.g. a misspelled component).
 * Replies never contain the SIP password.
 */
class AdbControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ordered = isOrderedBroadcast
        val reply = Reply(goAsync(), ordered)
        val op: String
        val submit: (GatewayRuntime) -> (() -> String)
        try {
            op = intent.argument<String>("op") ?: invalid("missing --es op")
            submit = parseCommand(intent, op)
        } catch (e: InvalidArgument) {
            reply.send(RESULT_ERROR, "error: ${e.message}")
            return
        } catch (_: Exception) {
            reply.send(RESULT_ERROR, "error: could not read command arguments")
            return
        }
        try {
            reply.startTimeout(op)
            val rt = GatewayRuntime.get(context)
            val describe = submit(rt)
            AppLog.i("adb control: accepted $op")
            rt.afterQueued {
                val data = try { describe() } catch (_: Exception) {
                    reply.send(RESULT_ERROR, "error: could not read command state; check logs before retrying")
                    return@afterQueued
                }
                reply.send(RESULT_OK, "ok: $data")
            }
        } catch (_: Exception) {
            // Submission may have succeeded before acknowledgement scheduling failed.
            reply.send(RESULT_ERROR, "error: could not submit command or schedule reply; check logs before retrying")
        }
    }

    /** Validate and capture extras before submitting anything; merge settings on the runtime worker. */
    private fun parseCommand(intent: Intent, op: String): (GatewayRuntime) -> (() -> String) {
        val allowed = when (op) {
            "status", "setup", "sip-restart" -> emptySet()
            "loopback", "takeover", "earpiece-mute", "sip-debug" -> setOf("on")
            "sip-stack" -> setOf("stack")
            "default-sim" -> setOf("sim")
            "sim-inbound" -> setOf("slot", "on")
            "provision" -> setOf("server", "port", "transport", "srtp", "user", "password", "domain",
                "target", "prefixes", "audio_method", "call_access", "hold_sip")
            else -> invalid("unknown op; see README adb control")
        }
        if (!intent.extras?.keySet().orEmpty().all { it == "op" || it in allowed })
            invalid("unexpected argument for $op")
        return when (op) {
            "status" -> { rt ->
                rt.afterQueued { rt.statusReport().lines().filter { it.isNotBlank() }.forEach { AppLog.i("status: $it") } }
                rt::statusLine
            }
            "setup" -> { rt -> rt.runSetup(); rt::statusLine }
            "sip-restart" -> { rt -> rt.restartSip(); rt::statusLine }
            "loopback", "takeover", "earpiece-mute", "sip-debug" -> {
                val on = intent.argument<Boolean>("on") ?: true
                val action: (GatewayRuntime) -> (() -> String) = { rt ->
                    when (op) {
                        "loopback" -> rt.setLoopbackTest(on)
                        "takeover" -> rt.setTakeover(on)
                        "earpiece-mute" -> rt.setMuteEarpiece(on)
                        "sip-debug" -> rt.setSipDebugLog(on)
                    }
                    if (op == "earpiece-mute" || op == "sip-debug") rt::settingsLine else rt::statusLine
                }
                action
            }
            "sip-stack" -> {
                val stack = SipStack.of(intent.argument<String>("stack"))
                    ?: invalid("sip-stack needs --es stack builtin|pjsua2")
                val action: (GatewayRuntime) -> (() -> String) = { rt ->
                    rt.applySettings { it.copy(sipStack = stack) }
                    rt::settingsLine
                }
                action
            }
            "default-sim" -> {
                val slot = DefaultSim.of(intent.argument<String>("sim"))
                    ?: invalid("default-sim needs --es sim system|refuse|<slot>")
                val action: (GatewayRuntime) -> (() -> String) = { rt ->
                    rt.setDefaultSimSlot(slot)
                    rt::settingsLine
                }
                action
            }
            "sim-inbound" -> {
                val slot = intent.argument<Int>("slot")?.takeIf { it >= 0 }
                    ?: invalid("sim-inbound needs --ei slot <slot> [--ez on true|false]")
                val on = intent.argument<Boolean>("on") ?: true
                val action: (GatewayRuntime) -> (() -> String) = { rt ->
                    rt.setSimInbound(slot, on)
                    rt::settingsLine
                }
                action
            }
            "provision" -> {
                val server = intent.argument<String>("server")
                val port = intent.argument<Int>("port")
                val transport = intent.argument<String>("transport")?.let {
                    SipTransport.of(it)?.id ?: invalid("transport must be udp|tcp|tls")
                }
                val srtp = intent.argument<String>("srtp")?.let {
                    SrtpPolicy.of(it)?.id ?: invalid("srtp must be off|optional|mandatory")
                }
                val user = intent.argument<String>("user")
                val password = intent.argument<String>("password")
                val domain = intent.argument<String>("domain")
                val target = intent.argument<String>("target")
                val prefixes = intent.argument<String>("prefixes")
                val audioMethod = intent.argument<Int>("audio_method")
                val callAccess = intent.argument<Int>("call_access")
                val holdSip = intent.argument<Boolean>("hold_sip")
                if (port != null && port !in 1..65535) invalid("port must be 1..65535")
                if (audioMethod != null && audioMethod !in 0..2) invalid("audio_method must be 0|1|2")
                if (callAccess != null && callAccess !in 0..2) invalid("call_access must be 0|1|2")
                val action: (GatewayRuntime) -> (() -> String) = { rt ->
                    rt.applySettings { cur ->
                        cur.copy(
                            server = server ?: cur.server, port = port ?: cur.port,
                            sipTransport = transport ?: cur.sipTransport, srtp = srtp ?: cur.srtp,
                            username = user ?: cur.username, password = password ?: cur.password,
                            domain = domain ?: cur.domain, inboundTarget = target ?: cur.inboundTarget,
                            allowedPrefixes = prefixes ?: cur.allowedPrefixes,
                            audioMethod = audioMethod ?: cur.audioMethod, callAccess = callAccess ?: cur.callAccess,
                            holdSipWhenNotReady = holdSip ?: cur.holdSipWhenNotReady,
                        )
                    }
                    rt::settingsLine
                }
                action
            }
            else -> error("unreachable")
        }
    }

    @Suppress("DEPRECATION") // Inspect types rather than letting Intent silently return defaults.
    private inline fun <reified T> Intent.argument(name: String): T? {
        if (!hasExtra(name)) return null
        val value = extras?.get(name)
        val flag = when (T::class) {
            String::class -> "--es"
            Int::class -> "--ei"
            Boolean::class -> "--ez"
            else -> error("unsupported argument type")
        }
        if (value !is T) invalid("$name must use $flag")
        return value
    }

    private class InvalidArgument(message: String) : IllegalArgumentException(message)
    private fun invalid(message: String): Nothing = throw InvalidArgument(message)

    /** Completes the pending broadcast exactly once, whichever of reply and timeout comes first. */
    private class Reply(private val pending: PendingResult, private val ordered: Boolean) {
        private val done = AtomicBoolean()
        private var handler: Handler? = null
        private var timeout: Runnable? = null

        fun startTimeout(op: String) {
            val callback = Runnable {
                send(RESULT_TIMEOUT, "timeout: op '$op' queued or running after ${TIMEOUT_MS / 1000} s; check logs before retrying")
            }
            timeout = callback
            handler = Handler(Looper.getMainLooper()).also { it.postDelayed(callback, TIMEOUT_MS) }
        }

        fun send(code: Int, data: String) {
            if (!done.compareAndSet(false, true)) return
            timeout?.let { handler?.removeCallbacks(it) }
            try {
                if (ordered) pending.setResult(code, data, null)
            } finally {
                pending.finish()
            }
            if (code == RESULT_ERROR) AppLog.w("adb control: $data")
        }
    }

    companion object {
        const val RESULT_OK = 1
        const val RESULT_ERROR = 2
        const val RESULT_TIMEOUT = 3
        /** Well under the 10 s foreground-broadcast limit. */
        private const val TIMEOUT_MS = 5_000L
    }
}
