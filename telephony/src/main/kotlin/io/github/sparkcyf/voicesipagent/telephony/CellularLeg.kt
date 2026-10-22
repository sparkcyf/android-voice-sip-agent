package io.github.sparkcyf.voicesipagent.telephony

import android.os.Handler
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import io.github.sparkcyf.voicesipagent.voice.AbstractCallLeg
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import java.util.ArrayDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A Telecom call as a [io.github.sparkcyf.voicesipagent.voice.CallLeg]. Telecom methods run on the main thread.
 * Audio is opened through [CallAudioOpener] after the call goes ACTIVE (the interception API only
 * works in MODE_IN_CALL), and the microphone is muted so only injected audio reaches the far end.
 */
class CellularLeg internal constructor(
    id: String,
    direction: LegDirection,
    remote: Party,
    target: String?,
    private val endpoint: CellularEndpoint,
    private val main: Handler,
) : AbstractCallLeg(id, "cellular", direction, remote, target) {
    @Volatile internal var call: Call? = null
        private set
    @Volatile private var hangupRequested: HangupCause? = null
    /** Inbound: the bridge asked us to answer, so an ACTIVE state is ours rather than the user's. */
    @Volatile private var answerRequested = false
    private val audio = AudioLease { endpoint.log("cell $id audio close failed: ${it.javaClass.simpleName}") }
    internal val stopping: Boolean get() = hangupRequested != null || state == LegState.ENDED
    private var audioAttempt = 0
    private val dtmfQueue = ArrayDeque<Pair<Char, Int>>()
    private var dtmfPlaying = false

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = onTelecomState(state)
    }

    internal fun bind(c: Call) {
        call = c
        c.registerCallback(callback, main)
        if (stopping) { disconnectSafely(c); return }
        onTelecomState(c.telecomState)
    }

    internal fun onRemoved() {
        call?.let { runCatching { it.unregisterCallback(callback) } }
        if (state != LegState.ENDED) finish(causeFromTelecom())
    }

    /** Placement failed before Telecom ever reported the call. */
    internal fun failPlacement(cause: HangupCause) = main.post { finish(cause) }

    private fun onTelecomState(s: Int) {
        if (s == Call.STATE_DISCONNECTED) { finish(causeFromTelecom()); return }
        if (stopping) return
        when (s) {
            Call.STATE_NEW, Call.STATE_CONNECTING, Call.STATE_DIALING, Call.STATE_RINGING,
            Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_PULLING_CALL -> transition(LegState.PROCEEDING)
            Call.STATE_ACTIVE -> {
                if (direction == LegDirection.INBOUND && !answerRequested && state < LegState.CONNECTED) {
                    // Answered on the phone itself: leave the call to the user with a live microphone
                    // and no tap; ending the leg cancels the SIP side without disconnecting Telecom.
                    endpoint.log("cell $id answered on the phone; releasing it to the user")
                    finish(HangupCause.CANCELLED)
                    return
                }
                if (transition(LegState.CONNECTED)) {
                    endpoint.log("cell $id active")
                    if (!endpoint.canTap(this)) return abortAudio("call no longer eligible")
                    try {
                        check(endpoint.acquireMute(this)) { "microphone mute state unavailable" }
                    } catch (e: Exception) {
                        return abortAudio("microphone mute failed: ${e.javaClass.simpleName}")
                    }
                    openAudio(audio.begin())
                }
            }
            Call.STATE_HOLDING -> if (state == LegState.CONNECTED) abortAudio("gateway call put on hold")
            Call.STATE_DISCONNECTING -> {
                hangupRequested = HangupCause.NORMAL
                stopMedia()
            }
        }
    }

    private fun openAudio(ticket: AudioLease.Ticket) {
        if (!audio.isCurrent(ticket) || !endpoint.canTap(this)) return
        val opener = endpoint.audioOpener
        endpoint.audioExecutor.execute {
            if (!audio.isCurrent(ticket)) return@execute
            try {
                val h = opener.open { reason -> main.post { onAudioLost(ticket, reason) } }
                main.post {
                    if (state == LegState.CONNECTED && endpoint.canTap(this)) {
                        if (audio.install(ticket, h)) {
                            endpoint.log("cell $id audio up")
                            publishMedia(h.port)
                        }
                    } else {
                        runCatching { h.close() }.onFailure { endpoint.log("cell $id stale audio close failed") }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    // MODE_IN_CALL can lag the ACTIVE callback slightly; retry briefly.
                    if (!audio.isCurrent(ticket) || !endpoint.canTap(this)) return@post
                    if (state == LegState.CONNECTED && ++audioAttempt < 5) {
                        endpoint.log("cell $id audio open failed (${e.message}); retry $audioAttempt")
                        later(300L * audioAttempt) {
                            if (audio.isCurrent(ticket) && endpoint.canTap(this)) openAudio(audio.begin())
                        }
                    } else if (state == LegState.CONNECTED) {
                        endpoint.log("cell $id audio unavailable: ${e.message}")
                        abortAudio("audio setup failed")
                    }
                }
            }
        }
    }

    private fun onAudioLost(ticket: AudioLease.Ticket, reason: String) {
        if (state != LegState.CONNECTED || !audio.isCurrent(ticket)) return
        endpoint.log("cell $id audio lost: $reason")
        abortAudio("audio lost")
    }

    internal fun abortAudio(reason: String) {
        if (stopping) return
        endpoint.log("cell $id stopping: $reason")
        hangupRequested = HangupCause.NETWORK_ERROR
        stopMedia()
        call?.let(::disconnectSafely)
        finish(HangupCause.NETWORK_ERROR)
    }

    internal fun onServiceLost(reason: String) {
        if (state == LegState.ENDED) return
        endpoint.log("cell $id stopping: $reason")
        val cause = hangupRequested ?: HangupCause.NETWORK_ERROR
        hangupRequested = cause
        stopMedia()
        call?.let(::disconnectSafely)
        finish(cause)
    }

    private fun stopMedia() {
        main.removeCallbacksAndMessages(this)
        audio.invalidate()
        runCatching { publishMedia(null) }.onFailure { endpoint.log("cell $id media listener failed") }
        runCatching { endpoint.releaseMute(this) }.onFailure { endpoint.log("cell $id mute restore failed") }
        dtmfQueue.clear()
        dtmfPlaying = false
        call?.let { runCatching { it.stopDtmfTone() } }
    }

    private fun finish(cause: HangupCause) {
        if (state == LegState.ENDED) return
        stopMedia()
        call?.let { runCatching { it.unregisterCallback(callback) } }
        try {
            if (transition(LegState.ENDED, cause)) endpoint.log("cell $id ended: $cause")
        } finally { endpoint.onLegEnded(this) }
    }

    private fun causeFromTelecom(): HangupCause {
        hangupRequested?.let { return it }
        val dc = call?.details?.disconnectCause ?: return hangupRequested ?: HangupCause.NORMAL
        val answered = state == LegState.CONNECTED
        val reason = dc.reason.orEmpty().uppercase()
        return when (dc.code) {
            DisconnectCause.BUSY -> HangupCause.BUSY
            DisconnectCause.REJECTED -> HangupCause.REJECTED
            DisconnectCause.LOCAL -> hangupRequested ?: HangupCause.NORMAL
            DisconnectCause.REMOTE -> if (answered) HangupCause.NORMAL else HangupCause.UNAVAILABLE
            DisconnectCause.MISSED -> HangupCause.NO_ANSWER
            DisconnectCause.CANCELED -> HangupCause.CANCELLED
            DisconnectCause.RESTRICTED -> HangupCause.FORBIDDEN
            DisconnectCause.ERROR -> if ("INVALID_NUMBER" in reason || "UNOBTAINABLE" in reason) HangupCause.NOT_FOUND else HangupCause.NETWORK_ERROR
            DisconnectCause.ANSWERED_ELSEWHERE, DisconnectCause.CALL_PULLED -> HangupCause.NORMAL
            else -> if (answered) HangupCause.NORMAL else HangupCause.UNAVAILABLE
        }
    }

    // ------------------------------------------------------------ CallLeg API

    override fun ring() {
        // The phone is already ringing for inbound cellular calls; nothing to signal.
    }

    override fun answer() {
        main.post {
            if (stopping || !endpoint.owns(this)) return@post
            val c = call ?: return@post
            if (c.telecomState == Call.STATE_RINGING) {
                answerRequested = true
                c.answer(VideoProfile.STATE_AUDIO_ONLY)
            }
        }
    }

    override fun hangup(cause: HangupCause) {
        main.post {
            if (state == LegState.ENDED) return@post
            hangupRequested = cause
            stopMedia()
            val c = call
            if (c == null) {
                // Outbound placement still pending: end now, disconnect if Telecom reports it later.
                finish(cause)
            } else {
                disconnectSafely(c)
            }
        }
    }

    private fun disconnect(c: Call) {
        if (c.telecomState == Call.STATE_RINGING && direction == LegDirection.INBOUND) c.reject(false, null) else c.disconnect()
    }

    private fun disconnectSafely(c: Call) {
        runCatching { disconnect(c) }.onFailure { endpoint.log("cell $id disconnect failed: ${it.javaClass.simpleName}") }
    }

    override fun sendDtmf(digit: Char, durationMs: Int) {
        main.post {
            if (stopping || !endpoint.canTap(this)) return@post
            dtmfQueue.add(digit to durationMs.coerceIn(MIN_DTMF_MS, 1000))
            if (!dtmfPlaying) playNextDtmf()
        }
    }

    private fun playNextDtmf() {
        val c = call
        val next = dtmfQueue.poll()
        if (c == null || next == null || state != LegState.CONNECTED || stopping) {
            dtmfPlaying = false
            return
        }
        dtmfPlaying = true
        c.playDtmfTone(next.first)
        later(next.second.toLong()) {
            if (!stopping && call === c) {
                c.stopDtmfTone()
                later(DTMF_GAP_MS, ::playNextDtmf)
            }
        }
    }

    // Keeps token-based scheduling in one place for a future backport below API 28.
    private fun later(delay: Long, task: () -> Unit) {
        main.postDelayed(task, this, delay)
    }

    companion object {
        private const val MIN_DTMF_MS = 100
        private const val DTMF_GAP_MS = 60L
        // Audio opens remain serialized, but no thread survives an idle/disabled gateway.
        internal val audioExecutor = ThreadPoolExecutor(0, 1, 100, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()) { task -> Thread(task, "cell-audio-open").apply { isDaemon = true } }
    }
}
