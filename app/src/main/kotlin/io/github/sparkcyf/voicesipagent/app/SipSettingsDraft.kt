package io.github.sparkcyf.voicesipagent.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import io.github.sparkcyf.voicesipagent.pjsua2.SrtpPolicy

/** Retained across activity recreation; credentials stay in memory rather than saved-state Bundles. */
class SipSettingsDraft : ViewModel() {
    var server by mutableStateOf("")
    var port by mutableStateOf("5060")
    var user by mutableStateOf("")
    var password by mutableStateOf("")
    var target by mutableStateOf("")
    var prefixes by mutableStateOf("")
    var domain by mutableStateOf("")
    var stack by mutableStateOf(SipStack.PJSUA2)
    var transport by mutableStateOf(SipTransport.UDP)
    var srtp by mutableStateOf(SrtpPolicy.OPTIONAL)
    var tlsVerify by mutableStateOf(true)
    /** Normalized PEM bundle; empty = Android system CAs. */
    var tlsCa by mutableStateOf("")
    private var saved: GatewaySettings? = null

    private val validatedPort: Int?
        get() = port.toIntOrNull()?.takeIf { it in 1..65535 }

    val portError: String?
        get() = if (validatedPort == null) "Enter a port number from 1 to 65535." else null

    /** Follow saved or queued changes to untouched fields, while keeping unfinished edits. */
    fun syncSaved(applied: GatewaySettings, pending: GatewaySettings? = null) {
        val current = pending ?: applied
        val previous = saved
        if (previous == null || server == previous.server) server = current.server
        if (previous == null || port == previous.port.toString()) port = current.port.toString()
        if (previous == null || user == previous.username) user = current.username
        if (previous == null || password == previous.password) password = current.password
        if (previous == null || target == previous.inboundTarget) target = current.inboundTarget
        if (previous == null || prefixes == previous.allowedPrefixes) prefixes = current.allowedPrefixes
        if (previous == null || domain == previous.domain) domain = current.domain
        if (previous == null || stack == previous.sipStack) stack = current.sipStack
        if (previous == null || transport == (SipTransport.of(previous.sipTransport) ?: SipTransport.UDP))
            transport = SipTransport.of(current.sipTransport) ?: SipTransport.UDP
        if (previous == null || srtp == (SrtpPolicy.of(previous.srtp) ?: SrtpPolicy.OPTIONAL))
            srtp = SrtpPolicy.of(current.srtp) ?: SrtpPolicy.OPTIONAL
        if (previous == null || tlsVerify == previous.tlsVerify) tlsVerify = current.tlsVerify
        if (previous == null || tlsCa == previous.tlsCa) tlsCa = current.tlsCa
        saved = current
    }

    fun hasUnsavedChanges(applied: GatewaySettings, pending: GatewaySettings? = null): Boolean =
        portError != null || !snapshot().hasSameSipSettings(pending ?: applied)

    private fun snapshot() = GatewaySettings(
        server = server, port = requireNotNull(validatedPort) { "PBX port must be between 1 and 65535." },
        username = user, password = password, inboundTarget = target, allowedPrefixes = prefixes,
        domain = domain, sipStack = stack, sipTransport = transport.id, srtp = srtp.id,
        tlsVerify = tlsVerify, tlsCa = tlsCa,
    )

    /** Capture the form at the click, before the runtime queues or applies it on another thread. */
    fun settingsUpdate(): (GatewaySettings) -> GatewaySettings {
        val snapshot = snapshot()
        return { current -> current.copy(
            server = snapshot.server, port = snapshot.port, username = snapshot.username, password = snapshot.password,
            inboundTarget = snapshot.inboundTarget, allowedPrefixes = snapshot.allowedPrefixes, domain = snapshot.domain,
            sipStack = snapshot.sipStack, sipTransport = snapshot.sipTransport, srtp = snapshot.srtp,
            tlsVerify = snapshot.tlsVerify, tlsCa = snapshot.tlsCa,
        ) }
    }
}

/** Gateway preferences do not count as edits to the SIP form. */
internal fun GatewaySettings.hasSameSipSettings(other: GatewaySettings): Boolean =
    server == other.server && port == other.port && username == other.username && password == other.password &&
        inboundTarget == other.inboundTarget && allowedPrefixes == other.allowedPrefixes && domain == other.domain &&
        sipStack == other.sipStack && sipTransport == other.sipTransport && srtp == other.srtp &&
        tlsVerify == other.tlsVerify && tlsCa == other.tlsCa
