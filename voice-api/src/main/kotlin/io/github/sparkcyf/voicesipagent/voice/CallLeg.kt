package io.github.sparkcyf.voicesipagent.voice

enum class LegDirection { INBOUND, OUTBOUND }

enum class LegState {
    /** Created, nothing signalled yet. */
    NEW,

    /** Outbound: request sent / dialing. Inbound: offered to us, not yet alerting. */
    PROCEEDING,

    /** Remote (outbound) or we (inbound) are alerting. */
    RINGING,
    CONNECTED,
    ENDED,
}

data class Party(val number: String?, val displayName: String? = null)

/**
 * One side of a call on one technology (cellular, SIP, an AI voice agent, ...).
 * Implementations must be thread-safe; listener callbacks may arrive on any thread.
 */
interface CallLeg {
    val id: String

    /** Backend name, e.g. "sip" or "cellular". */
    val kind: String
    val direction: LegDirection
    val remote: Party

    /** Inbound legs: what the caller dialed (e.g. the SIP Request-URI user). */
    val target: String?

    /**
     * Which line of a multi-line endpoint the call uses, e.g. the cellular SIM slot index. Inbound
     * legs report the line the call arrived on (or was requested for); null = unknown/default.
     */
    val line: Int? get() = null
    val state: LegState
    val endCause: HangupCause?

    /** Present while audio can flow; announced through [Listener.onMediaChanged]. */
    val media: MediaPort?

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    /** Inbound only: signal alerting to the caller (e.g. SIP 180). */
    fun ring()

    /** Inbound only: accept the call. */
    fun answer()

    fun hangup(cause: HangupCause = HangupCause.NORMAL)

    fun sendDtmf(digit: Char, durationMs: Int = DEFAULT_DTMF_MS)

    interface Listener {
        fun onStateChanged(leg: CallLeg, state: LegState) {}
        fun onMediaChanged(leg: CallLeg, media: MediaPort?) {}
        fun onDtmf(leg: CallLeg, digit: Char, durationMs: Int) {}
    }

    companion object {
        const val DEFAULT_DTMF_MS = 160
    }
}
