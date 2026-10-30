package io.github.sparkcyf.voicesipagent.app

import android.os.Bundle
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import io.github.sparkcyf.voicesipagent.pjsua2.SrtpPolicy
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.CallAudioStatus
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.telephony.DefaultSim

class MainActivity : DialerRoleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        val editor = ViewModelProvider(this)[SipSettingsDraft::class.java]
        editor.syncSaved(runtime.settings, runtime.pendingSettingsFlow.value)
        GatewayService.start(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val message by roleMessage.collectAsState()
                Scaffold { pad -> Screen(runtime, editor, Modifier.padding(pad), ::requestDialer, ::switchDialer,
                    ::chooseDialerInSettings, ::cancelDialerChange, ::setTakeoverFromUi, message) }
            }
        }
    }
}

@Composable
private fun Screen(rt: GatewayRuntime, editor: SipSettingsDraft, modifier: Modifier, requestDialer: () -> Unit, switchDialer: () -> Unit,
    chooseDialer: () -> Unit, cancelDialer: () -> Unit, setTakeover: (Boolean) -> Unit, roleMessage: String) {
    val h by rt.health.collectAsState()
    val callAudio by rt.callAudioStatus.collectAsState()
    val directMuteIncompatible by rt.directMuteIncompatible.collectAsState()
    val pendingSettings by rt.pendingSettingsFlow.collectAsState()
    val log by AppLog.tail.collectAsState()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Voice SIP Agent", style = MaterialTheme.typography.headlineSmall)
        val cur by rt.settingsFlow.collectAsState()
        Section("Takeover") {
            Toggle("Take over calls", cur.takeover, setTakeover) {
                when {
                    h.takeoverOffPending -> "Turning off after the current call"
                    h.takeoverOnPending -> "Turning on after current calls end"
                    h.dialerChangeRunning -> "Changing the default dialer"
                    h.dialerChangePending -> if (h.dialerChangeAcquire) "Waiting to become the default dialer" else "Waiting to restore your previous dialer"
                    cur.takeover -> if (h.cellularReady && !h.loopbackTest) "Ready to bridge calls to the PBX" else h.readinessDetail
                    h.holdsDialer && cur.keepDefaultDialer -> "Gateway off; this app remains the default phone app"
                    h.holdsDialer -> "Gateway off; dialer restoration needs attention"
                    else -> "Gateway off; calls stay with your dialer"
                }
            }
            if (h.takeover || h.holdsDialer) {
                Check("Call access", h.callAccessReady,
                    if (h.effectiveCallAccess == CallAccess.DIALER) {
                        if (h.holdsDialer) "This app is the default dialer" else "Default dialer selection required"
                    } else if (Build.VERSION.SDK_INT == 30) "Alongside-dialer access is unavailable on Android 11"
                    else "Alongside your current dialer")
            }
            if (h.holdsDialer || h.effectiveCallAccess == CallAccess.DIALER) {
                Text(when {
                    !h.holdsDialer -> "Enable takeover to make this app the default phone app."
                    cur.keepDefaultDialer -> "This app stays the default when takeover is off."
                    else -> "Turning takeover off tries to restore your previous phone app."
                }, style = MaterialTheme.typography.bodySmall)
            }
            if (h.effectiveCallAccess == CallAccess.DIALER && !h.holdsDialer) {
                if (cur.takeover && !h.dialerChangePending) {
                    Button(onClick = { setTakeover(true) }, enabled = h.dialerRoleAvailable && !h.dialerChangeRunning) { Text("Enable takeover") }
                }
                if (!h.dialerRoleAvailable) Text("Default dialer selection is unavailable on this device.")
            }
            if (roleMessage.isNotEmpty()) Text(roleMessage)
            if (h.holdsDialer || h.dialerChangeFailed) {
                if (h.holdsDialer && cur.takeover && h.shizuku == ShizukuConnector.State.CONNECTED) {
                    OutlinedButton(onClick = switchDialer, enabled = !h.dialerChangeRunning) { Text("Restore previous dialer") }
                }
                OutlinedButton(onClick = chooseDialer, enabled = !h.dialerChangeRunning) { Text("Choose dialer in Settings") }
            }
            if (h.dialerChangePending) OutlinedButton(onClick = cancelDialer) { Text("Cancel dialer change") }
            Toggle("Mute call audio on this phone", cur.muteEarpiece, { rt.setMuteEarpiece(it) }) {
                val unavailable = h.privileged != null &&
                    (h.privileged?.get(HealthKeys.LOCAL_SUPPRESSION) != true || !h.audioReady)
                if (cur.muteEarpiece && unavailable)
                    "Playback muting cannot be attempted with the current setup. The phone microphone remains muted during gateway calls. Changes apply to the next call."
                else if (cur.muteEarpiece && directMuteIncompatible)
                    "Muting stopped direct telephony audio on this phone, so it is skipped when that method is used. Turn this off and on again to retest."
                else
                    "When enabled, attempts to silence the earpiece or speaker. The phone microphone remains muted during gateway calls. Changes apply to the next call."
            }
            DefaultSimChoice(cur.defaultSimSlot, h) { rt.setDefaultSimSlot(it) }
            if (h.settingsPending && pendingSettings?.hasSameSipSettings(cur) != false) {
                Text("Changes pending until calls end", style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Health") {
            val p = h.privileged
            val actionsEnabled = h.gatewayRunning && cur.takeover && !h.dialerChangeRunning
            Text(when {
                h.takeover -> h.readinessDetail
                h.takeoverOnPending -> "Health checks will start after current calls end."
                else -> "Health checks are paused while takeover is off."
            }, style = MaterialTheme.typography.bodySmall)
            Check("Shizuku", h.shizuku == ShizukuConnector.State.CONNECTED,
                if (h.takeover) "${h.shizuku} ${h.shizukuDetail}" else "Stopped while takeover is off")
            Check("Call audio access", h.audioReady)
            Check("Doze allowlisted", p?.get(HealthKeys.DOZE_ALLOWLISTED) == true)
            Check("SIP registration", h.sip.ready,
                if (h.takeover) h.sip.detail else "Stopped while takeover is off")
            Text("SIM slots: " + h.sims.joinToString { "${it.slot} ${it.carrier}" }.ifEmpty { "none" },
                style = MaterialTheme.typography.bodySmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (h.shizuku == ShizukuConnector.State.PERMISSION_REQUIRED || h.shizuku == ShizukuConnector.State.PERMISSION_DENIED) {
                    Button(onClick = { rt.shizuku.requestPermission() }, enabled = actionsEnabled) { Text("Grant Shizuku") }
                }
                OutlinedButton(onClick = { rt.runSetup() },
                    enabled = actionsEnabled && h.shizuku == ShizukuConnector.State.CONNECTED) { Text("Re-run setup") }
                OutlinedButton(onClick = { rt.restartSip() },
                    enabled = actionsEnabled && cur.sipConfigured) { Text("Reconnect SIP") }
            }
        }
        Section("Call") {
            Text(h.calls.summary)
            Text(h.session ?: "No active session")
            if (callAudio.active) {
                Text("Current audio method: ${if (callAudio.available) callAudio.backendLabel else "status unavailable"}")
                Text("Local playback (Android): " + when {
                    !callAudio.available -> "mute state unavailable"
                    !callAudio.muteRequested -> "muting not requested"
                    callAudio.outputMute == CallAudioStatus.APPLIED -> "mute request applied"
                    callAudio.outputMute == CallAudioStatus.PRESERVED -> "existing mute preserved"
                    callAudio.outputMute == CallAudioStatus.NOT_APPLIED -> "mute request not applied"
                    callAudio.outputMute == CallAudioStatus.UNSUPPORTED -> "muting unsupported with this audio method"
                    else -> "mute state unknown"
                })
                if (callAudio.available && callAudio.recoveryDetail.isNotEmpty()) {
                    Text("Audio recovered: ${callAudio.recoveryDetail}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Next call: playback muting ${if (cur.muteEarpiece) "requested" else "off"}.",
                    style = MaterialTheme.typography.bodySmall)
            } else if (h.calls.owned > 0 || h.session != null) {
                Text("Call audio is not open.")
            }
            if (callAudio.restorationPending) {
                Text("Local call playback restoration is unconfirmed. Sound may remain muted.",
                    color = MaterialTheme.colorScheme.error)
                if (callAudio.ownsOutputChange && h.shizuku == ShizukuConnector.State.CONNECTED) {
                    OutlinedButton(onClick = { rt.retryAudioRestoration() },
                        enabled = !callAudio.active && h.calls.count == 0 && h.session == null) {
                        Text("Retry restoring playback")
                    }
                } else {
                    Text("If call sound stays muted, raise the call volume during a normal phone call.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Toggle("Echo incoming calls (test)", h.loopbackTest, { rt.setLoopbackTest(it) }) {
                "While the gateway is on, answers incoming cellular calls and echoes the caller's audio instead of connecting the PBX. Changes apply after the current call ends."
            }
            if (h.loopbackTest) {
                Text("Echo test enabled", style = MaterialTheme.typography.titleSmall)
                Button(onClick = { rt.setLoopbackTest(false) }) { Text("Disable echo test") }
            }
        }
        SettingsSection(rt, editor)
        var advanced by rememberSaveable { mutableStateOf(false) }
        OutlinedButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") }
        if (advanced) {
            Section("Platform integration") {
                val preferredCallAccess = pendingSettings?.callAccess ?: cur.callAccess
                Choice("Call access", listOf(CallAccess.AUTO, CallAccess.COMPANION, CallAccess.DIALER), preferredCallAccess,
                    { when (it) { CallAccess.AUTO -> "Automatic"; CallAccess.COMPANION -> "Alongside dialer"; else -> "Default dialer" } },
                    enabled = { it != CallAccess.COMPANION || Build.VERSION.SDK_INT >= 31 }) {
                    rt.applySettings { current -> current.copy(callAccess = it) }
                }
                val callAccessHint = when (preferredCallAccess) {
                    CallAccess.AUTO -> if (Build.VERSION.SDK_INT < 31)
                        "Android 11 requires this app as the default phone app."
                    else "Works alongside your phone app unless this app is the default."
                    CallAccess.COMPANION -> when {
                        Build.VERSION.SDK_INT < 31 -> "Requires Android 12 or later."
                        h.holdsDialer -> "Restore your usual phone app to use this mode."
                        else -> "Keeps your current phone app."
                    }
                    else -> if (h.holdsDialer) null
                        else "Enable takeover or tap Make default phone app to switch."
                }
                if (callAccessHint != null) Text(callAccessHint, style = MaterialTheme.typography.bodySmall)
                if (preferredCallAccess != cur.callAccess) {
                    Text("Applies after current calls end.", style = MaterialTheme.typography.bodySmall)
                }
                val dialerControlsEnabled = h.effectiveCallAccess == CallAccess.DIALER && !h.dialerChangeRunning
                Toggle("Keep this app as the default phone app when gateway is off", cur.keepDefaultDialer,
                    { rt.setKeepDefaultDialer(it) }, enabled = dialerControlsEnabled) {
                    "When disabled, turning takeover off tries to restore your previous phone app."
                }
                if (!h.holdsDialer && h.dialerRoleAvailable) {
                    OutlinedButton(onClick = requestDialer, enabled = dialerControlsEnabled) { Text("Make default phone app") }
                }
                OutlinedButton(onClick = chooseDialer) { Text("Choose dialer in Settings") }
                Text("Manual dialer selection leaves takeover unchanged. Selecting Default dialer above applies to the next switch-on.",
                    style = MaterialTheme.typography.bodySmall)
                val preferredAudioMethod = pendingSettings?.audioMethod ?: cur.audioMethod
                Choice("Cellular audio", listOf(AudioMethod.AUTO, AudioMethod.LEGACY, AudioMethod.OFFICIAL), preferredAudioMethod,
                    { when (it) { AudioMethod.AUTO -> "Automatic"; AudioMethod.LEGACY -> "Direct telephony"; else -> "Cellular redirection" } },
                    enabled = { it != AudioMethod.OFFICIAL || Build.VERSION.SDK_INT >= 33 }) {
                    rt.applySettings { current -> current.copy(audioMethod = it) }
                }
                Text(when (preferredAudioMethod) {
                    AudioMethod.AUTO -> "Prefers cellular call redirection; falls back to direct telephony during setup."
                    AudioMethod.LEGACY -> "Uses direct cellular audio capture and playback, without redirection fallback."
                    else -> "Uses cellular call redirection only. Requires Android 13+ and privileged access."
                } + " Changes apply to the next call.", style = MaterialTheme.typography.bodySmall)
                if (preferredAudioMethod != cur.audioMethod) {
                    Text("Applies after current calls end.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Section("Diagnostics") {
                Text("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
                Text("requestedCallAccess=${cur.callAccess} effective=${h.effectiveCallAccess} audio=${cur.audioMethod}")
                if (callAudio.backend.isNotEmpty()) Text("Last reported audio method: ${callAudio.backendLabel}")
                if (callAudio.muteDetail.isNotEmpty()) Text("Playback mute: ${callAudio.muteDetail}")
                if (callAudio.restoreDetail.isNotEmpty()) Text("Playback restoration: ${callAudio.restoreDetail}")
                Text(h.privileged?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "Waiting for helper")
                Text(h.privilegedDetail)
                Text(h.lastSetupLog)
                Text("Audio access and frame counters do not verify remote delivery. Verify both directions on this device.")
            }
        }
        Section("Log") {
            Text(log.takeLast(60).reversed().joinToString("\n"), fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun SettingsSection(rt: GatewayRuntime, editor: SipSettingsDraft) = with(editor) {
    val cur by rt.settingsFlow.collectAsState()
    val pending by rt.pendingSettingsFlow.collectAsState()
    val health by rt.health.collectAsState()
    LaunchedEffect(cur, pending) { syncSaved(cur, pending) }
    val builtin = stack == SipStack.BUILTIN
    val unsaved = hasUnsavedChanges(cur, pending)
    val sipPending = pending?.hasSameSipSettings(cur) == false
    val portValidationError = portError
    Section("Settings") {
        Choice("SIP stack", SipStack.entries, stack, { if (it == SipStack.BUILTIN) "${it.id} (deprecated)" else it.id }) {
            stack = it
            // The built-in stack is UDP only.
            if (it == SipStack.BUILTIN) {
                if (port == transport.defaultPort.toString()) port = SipTransport.UDP.defaultPort.toString()
                transport = SipTransport.UDP
            }
        }
        Choice("Transport", SipTransport.entries, transport, { it.id }, enabled = { !builtin || it == SipTransport.UDP }) {
            // Follow the default port when it was still the previous transport's default.
            if (port == transport.defaultPort.toString()) port = it.defaultPort.toString()
            transport = it
        }
        Choice("SRTP (TLS only)", SrtpPolicy.entries, srtp, { it.id }, enabled = { !builtin && transport == SipTransport.TLS }) {
            srtp = it
        }
        Field("PBX host", server) { server = it }
        Field("PBX port", port, KeyboardType.Number, error = portValidationError) { port = it }
        Field("SIP domain (empty = PBX host)", domain) { domain = it }
        Field("Extension / user", user) { user = it }
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Field("Cellular inbound -> SIP target (blank = username)", target) { target = it }
        Field("Allowed dial-out prefixes (comma separated, empty = any)", prefixes) { prefixes = it }
        if (unsaved) Text("Unsaved changes", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
        if (sipPending) Text("Changes pending until calls end", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { rt.applySettings(settingsUpdate()) }, enabled = portValidationError == null) { Text("Save & apply") }
        if (health.calls.count > 0 || health.session != null) {
            if (!sipPending) Text("Saved changes apply after the current call ends.", style = MaterialTheme.typography.bodySmall)
        } else if (!cur.takeover) {
            Text("Saved SIP settings are used when takeover is next enabled.", style = MaterialTheme.typography.bodySmall)
        }
        Toggle("Unregister while the phone cannot take calls", cur.holdSipWhenNotReady,
            { rt.setHoldSipWhenNotReady(it) }) {
            "At startup, waits for the phone to be ready before registering with the PBX. " +
                "Later problems get ${GatewayRuntime.SIP_HOLD_GRACE_MS / 1000} s to recover before unregistering so the PBX can fail over. " +
                "Registration changes wait for calls to end; registers again as soon as the phone is ready."
        }
        Toggle("pjsip debug log", cur.sipDebugLog, { rt.setSipDebugLog(it) }) {
            "Info-level logs with masked numbers"
        }
    }
}

@Composable
private fun DefaultSimChoice(selected: Int, h: HealthState, onSelect: (Int) -> Unit) {
    val slots = (h.sims.map { it.slot } + listOfNotNull(selected.takeIf { it >= 0 })).distinct().sorted()
    Choice("SIM for PBX calls without X-VSA-SIMSLOT", listOf(DefaultSim.SYSTEM) + slots + DefaultSim.REFUSE, selected,
        { when (it) { DefaultSim.SYSTEM -> "System"; DefaultSim.REFUSE -> "Refuse"; else -> "SIM $it" } }, onSelect = onSelect)
    val carrier = h.sims.firstOrNull { it.slot == selected }?.carrier
    Text(when (selected) {
        DefaultSim.SYSTEM -> "Follows Android's default SIM for calls (now: " +
            (h.systemSimSlot?.let { s -> "SIM $s ${h.sims.firstOrNull { it.slot == s }?.carrier.orEmpty()}".trim() } ?: "ask every time") + ")."
        DefaultSim.REFUSE -> "The PBX must choose the SIM with X-VSA-SIMSLOT."
        else -> "Always SIM $selected${carrier?.let { " ($it)" } ?: ""}; refused if that slot has no active SIM."
    } + " Applies to the next call.", style = MaterialTheme.typography.bodySmall)
    h.simWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            options.forEachIndexed { i, opt ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = opt == selected,
                    onClick = { onSelect(opt) },
                    enabled = enabled(opt),
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(name(opt)) }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit,
    enabled: Boolean = true, detail: () -> String) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = color)
            Text(detail(), style = MaterialTheme.typography.bodySmall, color = color)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text,
    error: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth(),
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean, detail: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
