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
import java.util.concurrent.CopyOnWriteArrayList

class FakePort(
    override val isClockSource: Boolean,
    private val fill: Short,
    override val format: PcmFormat = PcmFormat.NARROWBAND,
) : MediaPort {
    var clock: FrameClockListener? = null
    val written = CopyOnWriteArrayList<ShortArray>()
    override fun setClockListener(listener: FrameClockListener?) { clock = listener }
    override fun readFrame() = ShortArray(format.frameSamples) { fill }
    override fun writeFrame(frame: ShortArray) { written += frame }
}

class FakeLeg(kind: String, dir: LegDirection, remote: Party, target: String?, line: Int? = null) :
    AbstractCallLeg("$kind-${target ?: remote.number}", kind, dir, remote, target, line) {
    val actions = CopyOnWriteArrayList<String>()
    val dtmf = CopyOnWriteArrayList<Char>()
    var autoConfirmAnswer = true

    override fun ring() { actions += "ring"; transition(LegState.RINGING) }
    override fun answer() { actions += "answer"; if (autoConfirmAnswer) transition(LegState.CONNECTED) }
    override fun hangup(cause: HangupCause) { actions += "hangup:$cause"; transition(LegState.ENDED, cause) }
    override fun sendDtmf(digit: Char, durationMs: Int) { dtmf += digit }

    // Test drivers, simulating the remote side / network
    fun remoteProceeding() = transition(LegState.PROCEEDING)
    fun remoteRinging() = transition(LegState.RINGING)
    fun remoteAnswered() = transition(LegState.CONNECTED)
    fun remoteEnded(cause: HangupCause) = transition(LegState.ENDED, cause)
    fun mediaUp(port: MediaPort) = publishMedia(port)
    fun remoteDtmf(c: Char) = publishDtmf(c, 100)
}

class FakeEndpoint(override val name: String) : CallEndpoint {
    var handler: IncomingCallHandler? = null
    val dialed = CopyOnWriteArrayList<FakeLeg>()
    var onDial: (FakeLeg) -> Unit = { it.remoteProceeding() }
    override val status = EndpointStatus(true, "ok")
    override fun setIncomingCallHandler(handler: IncomingCallHandler?) { this.handler = handler }
    override fun addStatusListener(listener: EndpointStatusListener) {}
    override fun removeStatusListener(listener: EndpointStatusListener) {}
    override fun dial(target: String, caller: Party, line: Int?): CallLeg {
        val leg = FakeLeg(name, LegDirection.OUTBOUND, Party(target), target, line)
        dialed += leg
        onDial(leg)
        return leg
    }

    fun incoming(number: String, target: String? = null, line: Int? = null): FakeLeg {
        val leg = FakeLeg(name, LegDirection.INBOUND, Party(number), target, line)
        leg.remoteProceeding()
        handler!!.onIncomingCall(leg)
        return leg
    }
}
