package io.github.sparkcyf.voicesipagent.voice

import java.util.concurrent.CopyOnWriteArrayList

/** State and listener bookkeeping shared by leg implementations. */
abstract class AbstractCallLeg(
    final override val id: String,
    final override val kind: String,
    final override val direction: LegDirection,
    final override val remote: Party,
    final override val target: String?,
    final override val line: Int? = null,
) : CallLeg {
    private val listeners = CopyOnWriteArrayList<CallLeg.Listener>()

    @Volatile final override var state: LegState = LegState.NEW
        private set

    @Volatile final override var endCause: HangupCause? = null
        private set

    @Volatile final override var media: MediaPort? = null
        private set

    override fun addListener(listener: CallLeg.Listener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: CallLeg.Listener) {
        listeners.remove(listener)
    }

    /** Moves forward to [next]; ignores regressions and anything after ENDED. Returns true if changed. */
    protected fun transition(next: LegState, cause: HangupCause? = null): Boolean {
        synchronized(this) {
            val cur = state
            if (cur == LegState.ENDED || next.ordinal <= cur.ordinal) return false
            if (next == LegState.ENDED) endCause = cause ?: HangupCause.NORMAL
            state = next
        }
        if (next == LegState.ENDED) publishMedia(null)
        listeners.forEach { it.onStateChanged(this, next) }
        return true
    }

    protected fun publishMedia(port: MediaPort?) {
        if (media === port) return
        media = port
        listeners.forEach { it.onMediaChanged(this, port) }
    }

    protected fun publishDtmf(digit: Char, durationMs: Int) {
        listeners.forEach { it.onDtmf(this, digit, durationMs) }
    }

    override fun toString(): String = "$kind/$id($direction,$state)"
}
