package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.AbstractCallLeg
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import io.github.sparkcyf.voicesipagent.voice.VoiceFormat
import java.util.ArrayDeque
import java.util.Timer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.schedule

/**
 * Test endpoint: every dialed leg answers after [answerDelayMs] and plays back whatever it hears
 * [echoDelayMs] later. Bridging the cellular endpoint to this verifies the audio tap end to end.
 */
class EchoEndpoint(
    private val answerDelayMs: Long = 1000,
    private val echoDelayMs: Int = 200,
    private val format: PcmFormat = PcmFormat.NARROWBAND,
) : CallEndpoint, AutoCloseable {
    override val name = "echo"
    @Volatile private var closed = false
    override val status get() = EndpointStatus(!closed, if (closed) "stopped" else "echo test")
    private val timer = Timer("echo-endpoint", true)
    private val ids = AtomicInteger(1)

    override fun setIncomingCallHandler(handler: IncomingCallHandler?) {}
    override fun addStatusListener(listener: EndpointStatusListener) {}
    override fun removeStatusListener(listener: EndpointStatusListener) {}

    @Synchronized
    override fun dial(target: String, caller: Party, line: Int?): CallLeg {
        val leg = EchoLeg("e${ids.getAndIncrement()}", target, echoDelayMs / VoiceFormat.FRAME_MS, format)
        if (closed) { leg.hangup(HangupCause.UNAVAILABLE); return leg }
        leg.proceed()
        timer.schedule(answerDelayMs) {
            synchronized(this@EchoEndpoint) { if (!closed) leg.connect() }
        }
        return leg
    }

    @Synchronized
    override fun close() {
        closed = true
        timer.cancel()
    }

    private class EchoLeg(id: String, target: String, delayFrames: Int, format: PcmFormat) :
        AbstractCallLeg(id, "echo", LegDirection.OUTBOUND, Party(target), target) {
        private val port = DelayPort(delayFrames, format)
        fun proceed() = transition(LegState.RINGING)
        fun connect() {
            if (transition(LegState.CONNECTED)) publishMedia(port)
        }
        override fun ring() {}
        override fun answer() {}
        override fun hangup(cause: HangupCause) { transition(LegState.ENDED, cause) }
        override fun sendDtmf(digit: Char, durationMs: Int) {}
    }

    private class DelayPort(private val frames: Int, override val format: PcmFormat) : MediaPort {
        private val line = ArrayDeque<ShortArray>()
        override val isClockSource = false
        override fun setClockListener(listener: FrameClockListener?) {}
        @Synchronized override fun readFrame(): ShortArray =
            if (line.size > frames) line.poll() else format.silence()
        @Synchronized override fun writeFrame(frame: ShortArray) {
            line.add(frame)
            while (line.size > frames + 5) line.poll()
        }
    }
}
