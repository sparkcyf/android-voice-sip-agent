package io.github.sparkcyf.voicesipagent.voice

fun interface IncomingCallHandler {
    /** Takes ownership of [leg]; must eventually answer or hang it up. */
    fun onIncomingCall(leg: CallLeg)
}

data class EndpointStatus(val ready: Boolean, val detail: String)

fun interface EndpointStatusListener {
    fun onStatusChanged(endpoint: CallEndpoint, status: EndpointStatus)
}

/**
 * A source/sink of calls on one technology. The gateway only talks to endpoints through this
 * interface, so adding a new voice or control SDK means adding another implementation.
 */
interface CallEndpoint {
    val name: String
    val status: EndpointStatus

    fun setIncomingCallHandler(handler: IncomingCallHandler?)
    fun addStatusListener(listener: EndpointStatusListener)
    fun removeStatusListener(listener: EndpointStatusListener)

    /**
     * Starts an outbound call to [target] on [line] (see [CallLeg.line]; null = the endpoint's
     * default). Never throws for call failures: a leg that cannot be placed is returned already
     * [LegState.ENDED] (and its listeners still fire).
     */
    fun dial(target: String, caller: Party, line: Int? = null): CallLeg
}
