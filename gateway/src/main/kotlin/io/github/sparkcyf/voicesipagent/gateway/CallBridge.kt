package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One inbound leg tied to the outbound leg we created for it. */
class BridgeSession internal constructor(
    val id: Int,
    /** Name of the endpoint the call came from (e.g. "cellular" -> SIP). */
    val origin: String,
    val inbound: CallLeg,
) {
    var outbound: CallLeg? = null
        internal set
    val startedAtMs: Long = System.currentTimeMillis()
    var connectedAtMs: Long? = null
        internal set
    var media: MediaBridge? = null
        internal set
    var endedAtMs: Long? = null
        internal set
    internal var answerTimer: ScheduledFuture<*>? = null
    internal var inboundAnswerRequested = false
    internal var mediaTimer: ScheduledFuture<*>? = null

    override fun toString() =
        "session#$id[$origin ${inbound.kind}:${inbound.state} -> ${outbound?.kind}:${outbound?.state}]"
}

fun interface BridgeListener {
    fun onSessionChanged(session: BridgeSession)
}

/**
 * Bridges calls between two [CallEndpoint]s (e.g. cellular and SIP). The bridge only ever sees
 * the abstract leg interface, so either side can be swapped for another voice backend.
 *
 * Signalling rules (inbound leg A from endpoint X, outbound leg B on endpoint Y):
 *  - A arrives -> dial B; B proceeding/ringing -> A.ring(); B connected -> A.answer().
 *  - Media is cross-connected once both legs expose a port.
 *  - Either side ending ends the other; failures before answer propagate their cause.
 *  - DTMF from one leg is replayed on the other.
 */
class CallBridge(
    private val sideA: CallEndpoint,
    private val sideB: CallEndpoint,
    private val routeAtoB: RouteTarget,
    private val routeBtoA: RouteTarget,
    private val config: GatewayConfig,
    private val dialPolicyForA: DialPolicy = DialPolicy { null },
    private val log: (String) -> Unit = {},
) {
    private val executor: ScheduledExecutorService =
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "call-bridge").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
            continueExistingPeriodicTasksAfterShutdownPolicy = false
        }
    private val listeners = CopyOnWriteArrayList<BridgeListener>()
    private val stopping = AtomicBoolean(false)
    private var nextId = 1

    @Volatile var session: BridgeSession? = null
        private set

    fun start() {
        sideA.setIncomingCallHandler { leg -> postIncoming(leg) { onInbound(leg, sideA, sideB, routeAtoB) } }
        sideB.setIncomingCallHandler { leg -> postIncoming(leg) { onInbound(leg, sideB, sideA, routeBtoA) } }
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        sideA.setIncomingCallHandler(null)
        sideB.setIncomingCallHandler(null)
        post {
            // A running task may still schedule timers. Shut down only after it and cleanup finish.
            try { session?.let { endSession(it, HangupCause.NORMAL) } }
            finally { executor.shutdown() }
        }
    }

    fun addListener(l: BridgeListener) = listeners.addIfAbsent(l)
    fun removeListener(l: BridgeListener) = listeners.remove(l)

    /** Blocks until every task posted so far has run (tests). */
    internal fun drain() {
        // A submit racing executor termination is cancelled instead of rejected.
        try { executor.submit {}.get() } catch (_: RejectedExecutionException) {} catch (_: CancellationException) {}
    }

    private fun post(block: () -> Unit): Boolean = try {
        executor.execute {
            try {
                block()
            } catch (t: Throwable) {
                log("bridge task failed: $t")
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun postIncoming(leg: CallLeg, block: () -> Unit) {
        // An endpoint may have captured this handler before stop removed it.
        if (stopping.get() || !post {
            if (stopping.get()) leg.hangup(HangupCause.UNAVAILABLE) else block()
        }) leg.hangup(HangupCause.UNAVAILABLE)
    }

    private fun onInbound(leg: CallLeg, from: CallEndpoint, to: CallEndpoint, route: RouteTarget) {
        val busy = session
        if (busy != null) {
            log("busy with $busy; ${config.busyPolicy} new ${from.name} call")
            if (config.busyPolicy == BusyPolicy.REJECT || from === sideB) leg.hangup(HangupCause.BUSY)
            return
        }
        val target = route.targetFor(leg)
        if (target.isNullOrEmpty()) {
            log("no route for ${from.name} call to ${redactNumber(leg.target)}")
            leg.hangup(HangupCause.NOT_FOUND)
            return
        }
        if (to === sideA) {
            val veto = dialPolicyForA.check(target)
                ?: if (config.allowedDialPrefixes.isNotEmpty() && config.allowedDialPrefixes.none(target::startsWith)) HangupCause.FORBIDDEN else null
            if (veto != null) {
                log("refusing to dial ${redactNumber(target)} on ${to.name}: $veto")
                leg.hangup(veto)
                return
            }
        }
        val s = BridgeSession(nextId++, from.name, leg)
        session = s
        leg.addListener(LegWatcher(s, inbound = true))
        log("$s: ${from.name} ${redactNumber(leg.remote.number)} -> ${to.name} ${redactNumber(target)}")
        val out = to.dial(target, leg.remote)
        s.outbound = out
        out.addListener(LegWatcher(s, inbound = false))
        s.answerTimer = executor.schedule({
            if (session === s && s.connectedAtMs == null) {
                log("$s: answer timeout")
                endSession(s, HangupCause.NO_ANSWER)
            }
        }, config.answerTimeoutMs, TimeUnit.MILLISECONDS)
        // The outbound leg may have finished synchronously (e.g. placement refused).
        reevaluate(s)
        notifyChanged(s)
    }

    private inner class LegWatcher(val s: BridgeSession, val inbound: Boolean) : CallLeg.Listener {
        override fun onStateChanged(leg: CallLeg, state: LegState) { post { reevaluate(s); notifyChanged(s) } }
        override fun onMediaChanged(leg: CallLeg, media: MediaPort?) { post { reevaluate(s) } }
        override fun onDtmf(leg: CallLeg, digit: Char, durationMs: Int) {
            post {
                val other = if (inbound) s.outbound else s.inbound
                if (other?.state == LegState.CONNECTED) other.sendDtmf(digit, durationMs)
            }
        }
    }

    /** Drives the session from the current leg states; idempotent. */
    private fun reevaluate(s: BridgeSession) {
        if (session !== s) return
        val a = s.inbound
        val b = s.outbound ?: return

        if (a.state == LegState.ENDED || b.state == LegState.ENDED) {
            val cause = if (a.state == LegState.ENDED) {
                if (s.connectedAtMs == null) HangupCause.CANCELLED else HangupCause.NORMAL
            } else {
                if (s.connectedAtMs == null) b.endCause ?: HangupCause.UNAVAILABLE else HangupCause.NORMAL
            }
            endSession(s, cause)
            return
        }
        if ((b.state == LegState.PROCEEDING || b.state == LegState.RINGING) && a.state == LegState.PROCEEDING) a.ring()
        if (b.state == LegState.CONNECTED && a.state < LegState.CONNECTED && !s.inboundAnswerRequested) {
            // Answer is asynchronous: media callbacks can arrive before acceptance is confirmed.
            s.inboundAnswerRequested = true
            a.answer()
        }
        if (a.state == LegState.CONNECTED && b.state == LegState.CONNECTED) {
            if (s.connectedAtMs == null) {
                s.connectedAtMs = System.currentTimeMillis()
                s.answerTimer?.cancel(false)
                log("$s: both legs connected")
                s.mediaTimer = executor.schedule({
                    if (session === s && s.media == null) {
                        log("$s: media did not come up")
                        endSession(s, HangupCause.INTERNAL_ERROR)
                    }
                }, config.mediaSetupTimeoutMs, TimeUnit.MILLISECONDS)
            }
            val ma = a.media
            val mb = b.media
            if (s.media == null && ma != null && mb != null) {
                if (ma.format != mb.format) {
                    log("$s: incompatible media formats ${ma.format} / ${mb.format}")
                    endSession(s, HangupCause.INTERNAL_ERROR)
                    return
                }
                s.media = MediaBridge(ma, mb).also { it.start() }
                s.mediaTimer?.cancel(false)
                log("$s: media bridged")
            } else if (s.media != null && (ma == null || mb == null)) {
                s.media?.stop()
                s.media = null
            }
        }
    }

    private fun endSession(s: BridgeSession, cause: HangupCause) {
        if (session !== s) return
        session = null
        s.endedAtMs = System.currentTimeMillis()
        s.answerTimer?.cancel(false)
        s.mediaTimer?.cancel(false)
        s.media?.stop()
        s.media = null
        if (s.inbound.state != LegState.ENDED) s.inbound.hangup(cause)
        s.outbound?.let { if (it.state != LegState.ENDED) it.hangup(if (cause == HangupCause.CANCELLED) HangupCause.CANCELLED else cause) }
        log("$s ended: $cause")
        notifyChanged(s)
    }

    private fun notifyChanged(s: BridgeSession) = listeners.forEach { it.onSessionChanged(s) }
}
