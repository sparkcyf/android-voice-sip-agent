package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.telecom.TelecomManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.Manifest
import io.github.sparkcyf.voicesipagent.gateway.BridgeSession
import io.github.sparkcyf.voicesipagent.gateway.CallBridge
import io.github.sparkcyf.voicesipagent.gateway.EchoEndpoint
import io.github.sparkcyf.voicesipagent.gateway.GatewayConfig
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import io.github.sparkcyf.voicesipagent.privileged.CallAccessRequirements
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.pjsua2.PjsipConfig
import io.github.sparkcyf.voicesipagent.pjsua2.PjsipEndpoint
import io.github.sparkcyf.voicesipagent.pjsua2.PjsipLog
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import io.github.sparkcyf.voicesipagent.pjsua2.SrtpPolicy
import io.github.sparkcyf.voicesipagent.sip.SipAccount
import io.github.sparkcyf.voicesipagent.sip.SipConfig
import io.github.sparkcyf.voicesipagent.sip.SipLog
import io.github.sparkcyf.voicesipagent.sip.SipUserAgent
import io.github.sparkcyf.voicesipagent.telephony.CellularEndpoint
import io.github.sparkcyf.voicesipagent.telephony.DefaultSim
import io.github.sparkcyf.voicesipagent.telephony.SimSlot
import io.github.sparkcyf.voicesipagent.telephony.CallSnapshot
import io.github.sparkcyf.voicesipagent.telephony.GatewayInCallService
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor

data class HealthState(
    val shizuku: ShizukuConnector.State = ShizukuConnector.State.SHIZUKU_NOT_RUNNING,
    val shizukuDetail: String = "",
    /** Null until the privileged service has reported. */
    val privileged: Map<String, Boolean>? = null,
    val privilegedDetail: String = "",
    val sip: EndpointStatus = EndpointStatus(false, "not configured"),
    val takeover: Boolean = true,
    /** Gateway dependencies can run only after the foreground service has started. */
    val gatewayRunning: Boolean = false,
    /** Takeover was switched off during a call; it takes effect when that call ends. */
    val takeoverOffPending: Boolean = false,
    val takeoverOnPending: Boolean = false,
    val inCallServiceBound: Boolean = false,
    val loopbackTest: Boolean = false,
    val session: String? = null,
    val lastSetupLog: String = "",
    val holdsDialer: Boolean = false,
    val dialerRoleAvailable: Boolean = false,
    val effectiveCallAccess: Int = CallAccess.AUTO,
    val callAccessReady: Boolean = false,
    val audioReady: Boolean = false,
    val cellularReady: Boolean = false,
    val readinessDetail: String = "Checking gateway setup",
    val calls: CallSnapshot = CallSnapshot(),
    val settingsPending: Boolean = false,
    val dialerChangePending: Boolean = false,
    val dialerChangeRunning: Boolean = false,
    val dialerChangeFailed: Boolean = false,
    val dialerChangeAcquire: Boolean = false,
    val sims: List<SimSlot> = emptyList(),
    /** Slot of Android's default SIM for calls; null when Android asks per call. */
    val systemSimSlot: Int? = null,
    /** Set when PBX calls without X-VSA-SIMSLOT cannot be placed with the current outbound default. */
    val simWarning: String? = null,
) {

    val privilegedReady: Boolean
        get() = callAccessReady && audioReady && shizuku == ShizukuConnector.State.CONNECTED

    /** Takeover needs persistence even before setup; a retained dialer role needs a reminder. */
    val needsForegroundService: Boolean
        get() = takeover || holdsDialer || session != null || calls.owned > 0
}

/**
 * Owns every long-lived component and wires them together:
 * Shizuku -> privileged setup -> cellular endpoint readiness; settings -> SIP UA; both -> CallBridge.
 * The bridge's B side is the SIP UA normally, or [EchoEndpoint] in loopback-test mode.
 * Switching takeover off withdraws companion access and SIP, then restores the previous dialer
 * unless the user chose to retain the role. Role-qualified recovery components stay enabled.
 */
class GatewayRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val worker = newWorker()
    val settingsStore = SettingsStore(app)
    private val _settings = MutableStateFlow(settingsStore.load())
    val settingsFlow: StateFlow<GatewaySettings> = _settings
    val settings: GatewaySettings get() = _settings.value

    // Service entry points can run before the worker initializes. Use the actual saved switch and
    // role immediately so opening the app or receiving BOOT_COMPLETED cannot flash an idle notification.
    private val _health = MutableStateFlow(run {
        val holds = holdsDialer()
        HealthState(takeover = settings.takeover, holdsDialer = holds,
            effectiveCallAccess = CallAccess.effective(settings.callAccess, Build.VERSION.SDK_INT, holds))
    })
    val health: StateFlow<HealthState> = _health
    private val _dialerMessage = MutableStateFlow("")
    val dialerMessage: StateFlow<String> = _dialerMessage

    val shizuku = ShizukuConnector(app) { AppLog.i(it) }
    private fun audioFormat() = if (settings.sipStack == SipStack.PJSUA2)
        PcmFormat.WIDEBAND else PcmFormat.NARROWBAND

    private val _directMuteIncompatible = MutableStateFlow(settingsStore.directMuteIncompatible())
    /** Voice-call stream mute stopped direct capture on this build; direct calls skip it. */
    val directMuteIncompatible: StateFlow<Boolean> = _directMuteIncompatible

    private val audioOpener = PrivilegedAudioOpener(shizuku, { settings.audioMethod },
        { settings.muteEarpiece }, ::audioFormat,
        onStatusChanged = { worker.execute { updateAudioStatusPolling() } },
        directMuteBlocked = { _directMuteIncompatible.value },
        onDirectMuteIncompatible = {
            settingsStore.setDirectMuteIncompatible(true)
            _directMuteIncompatible.value = true
            AppLog.i("earpiece mute stopped direct call capture; skipping it for direct audio on this build")
        })
    val callAudioStatus get() = audioOpener.status

    val cellular = CellularEndpoint(app,
        audioOpener,
        logger = { AppLog.i(it) },
        defaultSlot = { settings.defaultSimSlot },
        inboundExcluded = { settings.inboundExcludedSlots },
    )
    /** [SipUserAgent] or [PjsipEndpoint], per [GatewaySettings.sipStack]. */
    private var sip: CallEndpoint? = null
    /** Includes socket cleanup still queued after a bounded stop wait. */
    private var sipSocketRelease = CompletableFuture.completedFuture(Unit)
    private var bridge: CallBridge? = null
    private var echo: EchoEndpoint? = null
    private var started = false
    private val _pendingSettings = MutableStateFlow<GatewaySettings?>(null)
    val pendingSettingsFlow: StateFlow<GatewaySettings?> = _pendingSettings
    private var pendingSettings: GatewaySettings?
        get() = _pendingSettings.value
        set(value) { _pendingSettings.value = value }
    private var restartPending = false
    private var loopbackPending: Boolean? = null
    private var setupPending = false
    private var foregroundRequested: Boolean? = null
    private var serviceToken: Any? = null
    private var foregroundRunning = false
    private val gatewayEnabled get() = foregroundRunning && _health.value.takeover
    private var pendingDialerChange: DialerChange? = null
    private var dialerRetry: ScheduledFuture<*>? = null
    private var healthCheck: ScheduledFuture<*>? = null
    private var audioStatusCheck: ScheduledFuture<*>? = null
    private val sipGate = SipRegistrationGate(SIP_HOLD_GRACE_MS) { SystemClock.elapsedRealtime() }
    private var sipHoldCheck: ScheduledFuture<*>? = null
    private var sipHoldCheckAt: Long? = null
    /** Why SIP is currently held unregistered by [startSip]; null while it may register. */
    @Volatile private var sipHeld: String? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val dialerHandoffStore = DialerHandoffStore(app)
    private val dialerHandoff = DialerHandoff(app.packageName, dialerHandoffStore, object : DialerHandoff.Backend {
        private fun service() = shizuku.service ?: error("Shizuku not connected. Use system dialer selection.")
        override fun state() = DialerRoleResult.fromBundle(service().dialerState()).let {
            it.state ?: error(it.message.ifEmpty { "Could not read the default dialer." })
        }
        override fun acquire(expectedHolder: String) = DialerRoleResult.fromBundle(service().becomeDefaultDialer(expectedHolder))
        override fun restore(previousHolder: String) = DialerRoleResult.fromBundle(service().restoreDefaultDialer(previousHolder))
    })
    private val takeoverControl = TakeoverControl(object : TakeoverControl.Host {
        override val enabled get() = _health.value.takeover
        override val active get() = hasActiveWork()
        override val needsDialer get() = _health.value.effectiveCallAccess == CallAccess.DIALER
        override val holdsDialer get() = _health.value.holdsDialer
        override val keepDialer get() = settings.keepDefaultDialer
        override fun saveEnabled(on: Boolean) {
            if (settings.takeover != on) {
                settingsStore.save(settings.copy(takeover = on).also { _settings.value = it })
            }
            pendingSettings = pendingSettings?.copy(takeover = on)
        }
        override fun cancelDialerChange() = clearDialerChange()
        override fun prepareDialerChange(acquire: Boolean) = prepareDialerChangeRequest(acquire)
        override fun showPending(on: Boolean?) {
            _health.update { it.copy(takeoverOffPending = on == false, takeoverOnPending = on == true) }
        }
        override fun applyEnabled() = applyTakeover()
    })

    private val sipLog = object : SipLog {
        override fun info(msg: String) = AppLog.i("sip: $msg")
        override fun warn(msg: String, t: Throwable?) = AppLog.w("sip: $msg", t)
    }
    private val pjLog = object : PjsipLog {
        override fun info(msg: String) = AppLog.i("sip: $msg")
        override fun warn(msg: String, t: Throwable?) = AppLog.w("sip: $msg", t)
    }

    fun start() = worker.execute {
        if (started) return@execute
        started = true
        cellular.onCallsChanged = { snapshot ->
            _health.update { it.copy(calls = snapshot, inCallServiceBound = CellularEndpoint.isInCallServiceBound) }
            worker.execute {
                syncForegroundService()
                if (!cellular.hasCalls && _health.value.session == null) applyDeferredChanges()
            }
        }
        AppLog.i("runtime starting ($settings)")
        shizuku.addListener { state, svc ->
            val detail = shizuku.detail
            worker.execute {
                if (shizuku.state != state || shizuku.service !== svc) return@execute
                _health.update { it.copy(shizuku = state, shizukuDetail = detail) }
                if (state == ShizukuConnector.State.CONNECTED && svc != null) {
                    audioOpener.refreshStatus()
                    if (pendingDialerChange?.acquire == true) attemptDialerChange()
                    runSetup()
                } else {
                    audioOpener.helperDisconnected()
                    updatePrivileged(null, detail)
                }
            }
        }
        configureCallAccess()
        _health.update { it.copy(takeover = settings.takeover) }
        updateReadiness()
        if (!_health.value.takeover) shizuku.stop()
    }

    /** Track ownership before promotion so destruction also cancels a failed start. */
    internal fun onServiceCreated(service: Any) = worker.execute {
        serviceToken = service
        foregroundRunning = false
    }

    /** Called only after startForeground succeeds. All gateway work stays off the main thread. */
    internal fun onForegroundStarted(service: Any) = worker.execute {
        if (serviceToken !== service) return@execute
        foregroundRunning = true
        foregroundRequested = true
        startGateway()
        updateReadiness()
    }

    internal fun onForegroundStopped(service: Any) = worker.execute {
        if (serviceToken !== service) return@execute
        serviceToken = null
        foregroundRunning = false
        // Destruction releases resources, never changes roles or immediately restarts the service.
        // An explicit launch or a later switch-on can request it again.
        foregroundRequested = _health.value.needsForegroundService
        if (pendingDialerChange != null) clearDialerChange()
        stopGateway()
        configureCallAccess()
        updateReadiness()
    }

    private fun startGateway() {
        if (!gatewayEnabled || healthCheck != null) return
        configureCallAccess()
        startBackgroundTasks()
        startSip()
        rebuildBridge()
    }

    /** Stop producers before their dependencies; retain the helper for an explicit role handoff. */
    private fun stopGateway() {
        stopBackgroundTasks()
        runCatching { stopBridge() }.onFailure { AppLog.w("bridge cleanup failed", it) }
        runCatching { stopSip(releaseLibrary = true) }.onFailure { AppLog.w("SIP cleanup failed", it) }
        sipGate.reset()
        sipHeld = null
        scheduleSipHoldCheck(null)
        audioOpener.closeForShutdown()
        shizuku.service?.let { svc ->
            runCatching { svc.setupCallAccess(app.packageName, false, _health.value.effectiveCallAccess) }
                .onFailure { AppLog.w("companion cleanup failed", it) }
        }
        if (pendingDialerChange == null) runCatching { shizuku.stop() }
            .onFailure { AppLog.w("Shizuku cleanup failed", it) }
        _health.update { it.copy(privileged = null, sip = EndpointStatus(false,
            if (it.takeover) "gateway stopped" else "takeover off")) }
    }

    private fun startBackgroundTasks() {
        if (healthCheck != null) return
        worker.allowCoreThreadTimeOut(false)
        shizuku.start()
        watchNetwork()
        // Telecom or Shizuku restarts can disrupt call access. Recheck health and repair companion
        // setup while idle if access is unavailable; a missing override alone does not trigger setup.
        healthCheck = worker.scheduleWithFixedDelay(::periodicCheck, 60, 60, TimeUnit.SECONDS)
    }

    private fun stopBackgroundTasks() {
        healthCheck?.cancel(false)
        healthCheck = null
        dialerRetry?.cancel(false)
        dialerRetry = null
        audioStatusCheck?.cancel(false)
        audioStatusCheck = null
        val callback = networkCallback
        networkCallback = null
        if (callback != null) runCatching {
            app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        }.onFailure { AppLog.w("network callback cleanup failed", it) }
        setupPending = false
        worker.allowCoreThreadTimeOut(true)
    }

    /** Observe only an open tap; idle and saved-off operation add no recurring audio work. */
    private fun updateAudioStatusPolling() {
        if (!gatewayEnabled || !callAudioStatus.value.active) {
            audioStatusCheck?.cancel(false)
            audioStatusCheck = null
        } else if (audioStatusCheck == null) {
            audioStatusCheck = worker.scheduleWithFixedDelay({
                audioOpener.refreshStatus()
                updateAudioStatusPolling()
            }, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun retryAudioRestoration() = worker.execute {
        if (hasActiveWork() || callAudioStatus.value.active) return@execute
        // Include calls that our InCallService may not currently see. Unknown means wait.
        val idle = runCatching {
            shizuku.service?.let { DialerRoleResult.fromBundle(it.dialerState()).state?.inCall == false }
        }.getOrNull() == true
        if (idle) audioOpener.retryRestoration()
    }

    // ------------------------------------------------------------ privileged setup / health

    fun runSetup() = worker.execute {
        if (!gatewayEnabled) { setupPending = false; return@execute }
        if (hasActiveWork()) {
            setupPending = true
            _health.update { it.copy(settingsPending = true) }
            return@execute
        }
        setupPending = false
        val svc = shizuku.service ?: return@execute updatePrivileged(null, "Shizuku not connected")
        try {
            refreshRoleState()
            val b = svc.setupCallAccess(app.packageName, _health.value.takeover, _health.value.effectiveCallAccess)
            _health.update { it.copy(lastSetupLog = b.getString("setup_log").orEmpty()) }
            applyHealthBundle(b)
        } catch (e: Exception) {
            AppLog.w("privileged setup failed", e)
            updatePrivileged(null, "setup failed: ${e.message}")
        }
    }

    private fun periodicCheck() {
        if (healthCheck == null) return
        refreshRoleState()
        val svc = shizuku.service ?: return updateReadiness()
        try {
            val b = svc.health(app.packageName)
            applyHealthBundle(b)
            if (_health.value.takeover && _health.value.effectiveCallAccess == CallAccess.COMPANION &&
                !_health.value.callAccessReady && !hasActiveWork()) {
                AppLog.i("health degraded; re-running setup")
                runSetup()
            }
        } catch (e: Exception) {
            AppLog.w("health check failed", e)
            updatePrivileged(null, "Health check failed: ${e.message}")
        }
        _health.update { it.copy(inCallServiceBound = CellularEndpoint.isInCallServiceBound) }
    }

    private fun applyHealthBundle(b: Bundle) {
        val map = HealthKeys.BOOLEAN_KEYS.associateWith { b.getBoolean(it) }
        updatePrivileged(map, b.getString(HealthKeys.AUDIO_DETAIL).orEmpty())
    }

    private fun updatePrivileged(map: Map<String, Boolean>?, detail: String) {
        _health.update { it.copy(privileged = map, privilegedDetail = detail) }
        updateReadiness()
        if (map != null) AppLog.i("privileged health: ${map.entries.joinToString { "${it.key}=${it.value}" }} $detail")
    }

    private fun holdsDialer() = app.getSystemService(TelecomManager::class.java).defaultDialerPackage == app.packageName

    private fun refreshRoleState() {
        val holds = holdsDialer()
        val available = app.getSystemService(RoleManager::class.java).isRoleAvailable(RoleManager.ROLE_DIALER)
        _health.update { it.copy(holdsDialer = holds,
            dialerRoleAvailable = available,
            effectiveCallAccess = CallAccess.effective(settings.callAccess, Build.VERSION.SDK_INT, holds)) }
    }

    private fun configureCallAccess() {
        refreshRoleState()
        cellular.setPreferredDialerService(_health.value.holdsDialer)
        if (!cellular.hasCalls) CellularEndpoint.setCompanionComponentEnabled(app,
            gatewayEnabled && _health.value.effectiveCallAccess == CallAccess.COMPANION && Build.VERSION.SDK_INT >= 31)
        // The dialer activity/UI service remain enabled, including when takeover is off.
    }

    fun refreshCallAccess() = worker.execute {
        configureCallAccess()
        if (shizuku.service != null) audioOpener.refreshStatus()
        updateReadiness()
        // Changing companion registration can change binding. Defer app-controlled setup while
        // Telecom has any call, including calls outside the bridge.
        if (gatewayEnabled) {
            if (!hasActiveWork()) runSetup() else setupPending = true
        }
    }

    @Suppress("DEPRECATION")
    private fun componentEnabled(type: Class<*>, service: Boolean): Boolean = runCatching {
        val component = ComponentName(app, type)
        val pm = app.packageManager
        val info = if (service) pm.getServiceInfo(component, PackageManager.GET_META_DATA)
            else pm.getActivityInfo(component, 0)
        val setting = pm.getComponentEnabledSetting(component)
        val enabled = setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled)
        enabled && info.applicationInfo.enabled && (!service ||
            (info as android.content.pm.ServiceInfo).permission == Manifest.permission.BIND_INCALL_SERVICE)
    }.getOrDefault(false)

    private class CellularCheck(val access: Boolean, val audio: Boolean, val accessProblem: String?,
        /** Why the cellular side cannot carry gateway calls, ignoring SIP; null when it can. */
        val problem: String?)

    private fun checkCellular(): CellularCheck {
        refreshRoleState()
        val h = _health.value
        val p = h.privileged
        val requirements = CallAccessRequirements(h.effectiveCallAccess, Build.VERSION.SDK_INT,
            h.holdsDialer, h.dialerRoleAvailable,
            componentEnabled(GatewayDialerActivity::class.java, false) && componentEnabled(GatewayDialerService::class.java, true),
            componentEnabled(GatewayInCallService::class.java, true),
            p?.get(HealthKeys.MANAGE_ONGOING_CALLS) == true, p?.get(HealthKeys.COMPANION_OVERRIDE) == true,
            listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE).all {
                app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            })
        val access = requirements.ready
        val audio = when (settings.audioMethod) {
            AudioMethod.OFFICIAL -> p?.get(HealthKeys.REDIRECT_AUDIO) == true
            AudioMethod.LEGACY -> p?.get(HealthKeys.DIRECT_AUDIO) == true
            else -> p?.get(HealthKeys.DIRECT_AUDIO) == true || p?.get(HealthKeys.REDIRECT_AUDIO) == true
        }
        val problem = when {
            h.dialerChangeRunning -> "Changing the default dialer"
            !gatewayEnabled -> "Gateway service not running"
            // Without Shizuku, privileged call-access health is unknown; report the root cause.
            h.shizuku != ShizukuConnector.State.CONNECTED -> shizukuProblem(h.shizuku)
            !access -> requirements.problem!!
            !audio -> "Selected call audio unavailable: ${h.privilegedDetail}"
            else -> null
        }
        return CellularCheck(access, audio, requirements.problem, problem)
    }

    private fun simWarning(default: Int, sims: List<SimSlot>, systemSim: Int?): String? = when {
        default == DefaultSim.SYSTEM && sims.size > 1 && systemSim == null ->
            "Android has no default SIM for calls, so PBX calls without X-VSA-SIMSLOT are refused. " +
                "Choose a SIM here or in Android SIM settings."
        default >= 0 && sims.isNotEmpty() && sims.none { it.slot == default } ->
            "No active SIM in slot $default: PBX calls without X-VSA-SIMSLOT are refused."
        else -> null
    }

    /** Effective outbound default for status output, e.g. "system(1)", "0" or "refuse". */
    private fun defaultSimLabel(h: HealthState) = DefaultSim.id(settings.defaultSimSlot) +
        if (settings.defaultSimSlot == DefaultSim.SYSTEM) "(${h.systemSimSlot ?: "ask"})" else ""

    private fun inboundOffLabel(s: GatewaySettings) = s.inboundExcludedSlots.sorted().joinToString(",").ifEmpty { "none" }

    // A restarted Shizuku server may not push its binder to this running app until an activity
    // comes to the foreground (see AGENT.md), so tell the user how to reconnect.
    private fun shizukuProblem(state: ShizukuConnector.State) =
        if (state == ShizukuConnector.State.SHIZUKU_NOT_RUNNING) "Shizuku not running. After starting it, open this app to reconnect"
        else "Shizuku not connected"

    private fun updateReadiness() {
        val c = checkCellular()
        val h = _health.value
        val access = c.access
        val audio = c.audio
        val detail = when {
            h.dialerChangeRunning -> "Changing the default dialer"
            h.takeoverOnPending -> "Enabling gateway after current calls end"
            h.takeoverOffPending -> "Turning gateway off after current calls end"
            h.dialerChangePending -> if (h.dialerChangeAcquire)
                "Waiting to become the default dialer" else "Waiting to restore the previous dialer"
            !h.takeover -> if (h.holdsDialer) "Gateway off; this app still holds the dialer role" else "Gateway off"
            !gatewayEnabled -> "Gateway service not running"
            h.shizuku != ShizukuConnector.State.CONNECTED -> shizukuProblem(h.shizuku)
            !access -> c.accessProblem!!
            !audio -> "Selected call audio unavailable: ${h.privilegedDetail}"
            !h.loopbackTest && !h.sip.ready -> "SIP unavailable: ${h.sip.detail}"
            else -> "Gateway ready"
        }
        val ready = c.problem == null && (h.loopbackTest || h.sip.ready)
        val sims = cellular.sims.active()
        val systemSim = cellular.sims.slotOf(cellular.sims.defaultAccount())
        _health.update { it.copy(gatewayRunning = gatewayEnabled, callAccessReady = access,
            audioReady = audio, cellularReady = ready, readinessDetail = detail, sims = sims, systemSimSlot = systemSim,
            simWarning = simWarning(settings.defaultSimSlot, sims, systemSim)) }
        cellular.setReadiness(ready, detail)
        updateSipHold(c.problem)
        syncForegroundService()
    }

    // ------------------------------------------------------------ SIP registration hold

    /**
     * Whether SIP should stay unregistered because the cellular side is not ready (SIP itself is
     * not part of that readiness), and why. Also schedules the grace-period recheck.
     */
    private fun sipHoldReason(problem: String?): String? {
        val s = settings
        val d = sipGate.evaluate(active = gatewayEnabled && s.takeover && s.sipConfigured && s.holdSipWhenNotReady,
            ready = problem == null)
        scheduleSipHoldCheck(d.recheckAtMs)
        return if (d.hold) problem ?: "cellular side not ready" else null
    }

    private fun scheduleSipHoldCheck(atMs: Long?) {
        if (atMs == sipHoldCheckAt) return
        sipHoldCheck?.cancel(false)
        sipHoldCheck = null
        sipHoldCheckAt = atMs
        if (atMs == null) return
        val delay = (atMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        sipHoldCheck = worker.schedule({
            sipHoldCheck = null
            sipHoldCheckAt = null
            updateReadiness()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun heldStatus(reason: String) = EndpointStatus(false, "held unregistered: $reason")

    /** Called from [updateReadiness]; registration changes run as a separate task, never nested. */
    private fun updateSipHold(problem: String?) {
        val reason = sipHoldReason(problem)
        when {
            (reason != null) != (sipHeld != null) -> worker.execute(::applySipHold)
            reason != null && reason != sipHeld -> {
                sipHeld = reason
                _health.update { it.copy(sip = heldStatus(reason)) }
            }
        }
    }

    /** Calls and bridge sessions keep the current registration; call-end handling re-evaluates. */
    private fun applySipHold() {
        if (!gatewayEnabled || hasActiveWork()) return
        val reason = sipHoldReason(checkCellular().problem)
        if ((reason != null) == (sipHeld != null)) return
        AppLog.i(if (reason != null) "unregistering SIP: $reason" else "cellular side ready; registering SIP")
        startSip()
        // The echo test bridge does not use SIP; keep it and any session it carries.
        if (!_health.value.loopbackTest) rebuildBridge()
        updateReadiness()
    }

    private fun syncForegroundService() {
        val h = _health.value
        if (foregroundRequested == h.needsForegroundService) return
        if (GatewayService.sync(app, h)) foregroundRequested = h.needsForegroundService
    }

    private fun hasActiveWork() = cellular.hasCalls || bridge?.session != null || _health.value.session != null

    fun showDialerMessage(message: String) { _dialerMessage.value = message }

    /** Called only from an explicit, unlocked UI action. */
    fun changeDefaultDialer(acquire: Boolean) = worker.execute {
        clearDialerChange()
        prepareDialerChangeRequest(acquire)
        attemptDialerChange()
    }

    private fun clearDialerChange() {
        dialerRetry?.cancel(false)
        dialerRetry = null
        pendingDialerChange = null
        _health.update { it.copy(dialerChangePending = false, dialerChangeFailed = false, dialerChangeAcquire = false) }
        _dialerMessage.value = ""
        if (!_health.value.takeover) shizuku.stop()
    }

    private fun prepareDialerChangeRequest(acquire: Boolean) {
        _health.update { it.copy(dialerChangeAcquire = acquire) }
        try {
            val prepared = if (acquire) {
                // Null can mean an empty role; the helper rechecks the holder before any change.
                val holder = app.getSystemService(TelecomManager::class.java).defaultDialerPackage.orEmpty()
                dialerHandoff.prepareAcquisition(PreviousDialer(android.os.Process.myUid() / 100_000, holder))
            } else dialerHandoff.prepare(false)
            pendingDialerChange = prepared.change
            prepared.result?.let(::finishDialerChange)
        } catch (e: Exception) {
            finishDialerChange(DialerRoleResult(DialerRoleResult.FAILED,
                "Could not prepare the dialer change: ${e.message}. Use system default-app settings."))
        }
    }

    private fun finishDialerChange(result: DialerRoleResult) {
        clearDialerChange()
        _health.update { it.copy(dialerChangeFailed = result.status == DialerRoleResult.FAILED) }
        _dialerMessage.value = result.message
        AppLog.i("dialer change: ${result.status} ${result.message}")
        refreshCallAccess()
    }

    private fun attemptDialerChange() {
        val change = pendingDialerChange ?: return
        dialerRetry?.cancel(false)
        dialerRetry = null
        if (change.acquire && (!_health.value.takeover || shizuku.service == null)) {
            _health.update { it.copy(dialerChangePending = true) }
            _dialerMessage.value = "Waiting for Shizuku. You can cancel the dialer change."
            updateReadiness()
            return
        }
        if (hasActiveWork()) { deferDialerChange(); return }
        takeoverControl.applyIfIdle()
        _health.update { it.copy(dialerChangeRunning = true, dialerChangePending = false) }
        val result = try {
            updateReadiness()
            dialerHandoff.execute(change)
        } catch (e: Exception) {
            DialerRoleResult(DialerRoleResult.FAILED, "Dialer change failed: ${e.message}. Use system default-app settings.")
        } finally {
            _health.update { it.copy(dialerChangeRunning = false) }
        }
        if (result.deferred) deferDialerChange() else finishDialerChange(result)
        updateReadiness()
    }

    private fun deferDialerChange() {
        if (!_health.value.takeover) {
            finishDialerChange(DialerRoleResult(DialerRoleResult.FAILED,
                "Gateway off. Choose your phone app in system Settings after current calls end."))
            return
        }
        _health.update { it.copy(dialerChangePending = true) }
        _dialerMessage.value = "Waiting for current calls to end. You can cancel the dialer change."
        // Before acquisition our InCallService may not see the other dialer's calls. The helper
        // queries Telecom, including self-managed calls, on each retry and before each command.
        dialerRetry = worker.schedule({ dialerRetry = null; attemptDialerChange() }, 1, TimeUnit.SECONDS)
    }

    fun cancelDialerChange() = worker.execute {
        val wasPending = pendingDialerChange != null
        clearDialerChange()
        if (wasPending) _dialerMessage.value = "Dialer change cancelled."
    }

    /** Public role-dialog fallback still saves a return destination before Android can kill us. */
    fun rememberDialerForSystemRequest(): Boolean {
        val previous = app.getSystemService(TelecomManager::class.java).defaultDialerPackage
            ?: return false.also { showDialerMessage("Could not read the previous dialer. Use system default-app settings.") }
        if (previous == app.packageName) return true
        val saved = dialerHandoffStore.save(PreviousDialer(android.os.Process.myUid() / 100_000, previous))
        if (!saved) showDialerMessage("Could not save the previous dialer; no role change was attempted.")
        return saved
    }

    private fun sipStatus(endpoint: CallEndpoint, status: EndpointStatus) = worker.execute {
        if (sip !== endpoint) return@execute
        _health.update { it.copy(sip = status) }
        updateReadiness()
    }

    // ------------------------------------------------------------ SIP

    private fun startSip() {
        if (!gatewayEnabled) return
        stopSip()
        sipHeld = null
        val s = settings
        if (!s.takeover) {
            _health.update { it.copy(sip = EndpointStatus(false, "takeover off")) }
            return
        }
        if (!s.sipConfigured) {
            _health.update { it.copy(sip = EndpointStatus(false, "not configured")) }
            return
        }
        sipHoldReason(checkCellular().problem)?.let { reason ->
            // stopSip() above waited for the un-REGISTER and kept the pjsua2 transport for the next start.
            sipHeld = reason
            AppLog.i("SIP held unregistered: $reason")
            _health.update { it.copy(sip = heldStatus(reason)) }
            return
        }
        val transport = SipTransport.of(s.sipTransport)
        val srtp = SrtpPolicy.of(s.srtp)
        if (srtp == null) {
            AppLog.w("unknown SRTP policy '${s.srtp}'")
            _health.update { it.copy(sip = EndpointStatus(false, "unknown srtp ${s.srtp}")) }
            return
        }
        PjsipEndpoint.setVerboseLogging(s.sipDebugLog)
        if (transport == null || (s.sipStack == SipStack.BUILTIN && transport != SipTransport.UDP)) {
            AppLog.w("SIP transport '${s.sipTransport}' not supported by the ${s.sipStack.id} stack")
            _health.update { it.copy(sip = EndpointStatus(false, "transport ${s.sipTransport} unsupported by ${s.sipStack.id}")) }
            return
        }
        if (s.sipStack == SipStack.BUILTIN) AppLog.w("the built-in SIP stack is deprecated; switch to pjsua2")
        try {
            val ep: CallEndpoint = when (s.sipStack) {
                SipStack.BUILTIN -> SipUserAgent(
                    SipConfig(
                        account = SipAccount(
                            server = s.server,
                            port = s.port,
                            username = s.username,
                            password = s.password,
                            domain = s.domain.ifBlank { s.server },
                            displayName = "gateway",
                        ),
                        localPort = s.localSipPort,
                    ),
                    sipLog,
                ).also { ua ->
                    ua.addStatusListener { ep, st -> sipStatus(ep, st) }
                }
                SipStack.PJSUA2 -> PjsipEndpoint(
                    PjsipConfig(
                        server = s.server,
                        port = s.port,
                        transport = transport,
                        srtp = srtp,
                        username = s.username,
                        password = s.password,
                        domain = s.domain.ifBlank { s.server },
                        displayName = "gateway",
                        localPort = s.localSipPort,
                    ),
                    pjLog,
                ).also { pj ->
                    pj.addStatusListener { ep, st -> sipStatus(ep, st) }
                }
            }
            sip = ep
            _health.update { it.copy(sip = ep.status) }
            AppLog.i("SIP stack: ${s.sipStack.id}")
            sipSocketRelease.whenComplete { _, failure -> worker.execute {
                // A later restart, hold or switch-off may have replaced this endpoint while waiting.
                if (sip !== ep || !gatewayEnabled) return@execute
                try {
                    if (failure != null) throw IllegalStateException("previous SIP socket release failed", failure)
                    when (ep) {
                        is SipUserAgent -> ep.start()
                        is PjsipEndpoint -> ep.start()
                    }
                } catch (t: Throwable) {
                    AppLog.w("SIP start failed", t)
                    _health.update { it.copy(sip = EndpointStatus(false, "start failed: ${t.message}")) }
                    updateReadiness()
                }
            } }
        } catch (e: Throwable) {
            AppLog.w("SIP start failed", e)
            _health.update { it.copy(sip = EndpointStatus(false, "start failed: ${e.message}")) }
        }
    }

    private fun stopSip(releaseLibrary: Boolean = false) {
        val previous = sip
        sip = null
        try {
            when (val s = previous) {
                is SipUserAgent -> trackSipSocketRelease(s.stop())
                is PjsipEndpoint -> {
                    // Keep the SIP socket only if pjsua2 is about to take it over again.
                    val n = settings
                    s.stop(
                        releaseTransport = releaseLibrary || !(n.takeover && n.sipStack == SipStack.PJSUA2 &&
                            n.localSipPort == s.config.localPort && SipTransport.of(n.sipTransport) == s.config.transport),
                    )
                }
            }
        } finally {
            if (releaseLibrary || !_health.value.takeover || settings.sipStack != SipStack.PJSUA2)
                trackSipSocketRelease(PjsipEndpoint.stopLibrary())
        }
    }

    private fun trackSipSocketRelease(released: CompletableFuture<Unit>) {
        sipSocketRelease = CompletableFuture.allOf(sipSocketRelease, released).thenApply { }
    }

    private fun refreshSipRegistration() {
        when (val s = sip) {
            is SipUserAgent -> s.refreshRegistration()
            is PjsipEndpoint -> s.refreshRegistration()
        }
    }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastAddrs: String? = null
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (networkCallback !== this) return
                val addrs = lp.linkAddresses.joinToString { it.address.hostAddress.orEmpty() }
                if (addrs != lastAddrs) {
                    if (lastAddrs != null) {
                        AppLog.i("network addresses changed; re-registering")
                        worker.execute {
                            if (networkCallback === this && _health.value.takeover) refreshSipRegistration()
                        }
                    }
                    lastAddrs = addrs
                }
            }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    // ------------------------------------------------------------ bridge

    private fun stopBridge() {
        val previous = bridge
        bridge = null
        val previousEcho = echo
        echo = null
        _health.update { it.copy(session = null) }
        try { previous?.stop() } finally { previousEcho?.close() }
    }

    private fun rebuildBridge() {
        stopBridge()
        if (!gatewayEnabled) return
        val s = settings
        if (!s.takeover) {
            bridge = null
            AppLog.i("bridge idle: takeover off")
            return
        }
        val loopback = _health.value.loopbackTest
        val sideB: CallEndpoint? = if (loopback) EchoEndpoint(format = audioFormat()).also { echo = it } else sip
        if (sideB == null) {
            bridge = null
            AppLog.i("bridge idle: SIP not configured")
            return
        }
        val inboundTarget = s.inboundTarget.ifBlank { s.username }
        val b = CallBridge(
            sideA = cellular,
            sideB = sideB,
            routeAtoB = { inboundTarget },
            routeBtoA = { leg -> leg.target?.takeIf { it != s.username } },
            config = GatewayConfig(
                cellularInboundTarget = inboundTarget,
                busyPolicy = s.busyPolicy,
                allowedDialPrefixes = s.allowedPrefixes.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            ),
            dialPolicyForA = { n -> when {
                !_health.value.cellularReady -> HangupCause.UNAVAILABLE
                cellular.isEmergencyNumber(n) -> HangupCause.FORBIDDEN
                else -> null
            } },
            log = { AppLog.i("bridge: $it") },
        )
        b.addListener { session ->
            val description = describe(session)
            worker.execute {
                if (bridge !== b) return@execute
                _health.update { it.copy(session = description) }
                syncForegroundService()
                if (session.endedAtMs != null && !cellular.hasCalls) applyDeferredChanges()
            }
        }
        bridge = b
        b.start()
        AppLog.i("bridge ready: cellular <-> ${sideB.name}")
    }

    private fun describe(s: BridgeSession): String? {
        if (s.endedAtMs != null) return null
        val out = s.outbound
        return "${s.origin}: ${redactNumber(s.inbound.remote.number)} ${s.inbound.state} -> ${out?.kind} ${out?.state ?: LegState.NEW}"
    }

    // ------------------------------------------------------------ external controls

    /** Evaluate edits on the worker against the latest queued settings, never an older UI snapshot. */
    fun applySettings(update: (GatewaySettings) -> GatewaySettings) = worker.execute {
        val new = update(pendingSettings ?: settings)
        applySettingsNow(new)
    }

    private fun applySettingsNow(new: GatewaySettings) {
        require(new.audioMethod in 0..2 && new.callAccess in 0..2) { "unknown platform setting" }
        if (hasActiveWork()) {
            pendingSettings = new
            _health.update { it.copy(settingsPending = true) }
            AppLog.i("settings will apply after current calls end")
            return
        }
        val old = settings
        // A new edit can arrive after Telecom reports idle but before its deferred-change task.
        // This commit already includes the queued edits; do not replay their older snapshot later.
        pendingSettings = null
        _health.update { it.copy(settingsPending = setupPending || restartPending || loopbackPending != null) }
        settingsStore.save(new)
        _settings.value = new
        AppLog.i("settings saved ($new)")
        val platformOnly = old.copy(audioMethod = new.audioMethod, callAccess = new.callAccess,
            muteEarpiece = new.muteEarpiece, sipDebugLog = new.sipDebugLog,
            keepDefaultDialer = new.keepDefaultDialer, holdSipWhenNotReady = new.holdSipWhenNotReady,
            defaultSimSlot = new.defaultSimSlot, inboundExcludedSlots = new.inboundExcludedSlots) == new
        PjsipEndpoint.setVerboseLogging(new.sipDebugLog)
        if (!platformOnly) { startSip(); rebuildBridge() }
        configureCallAccess()
        runSetup()
        updateReadiness()
    }

    /** pjsip info-level logging; takes effect immediately, persisted for the next start. */
    fun setSipDebugLog(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(sipDebugLog = on).also { _settings.value = it })
        pendingSettings = pendingSettings?.copy(sipDebugLog = on)
        PjsipEndpoint.setVerboseLogging(on)
        AppLog.i("SIP debug log ${if (on) "on" else "off"}")
    }

    /** Read at each call's audio setup, so no restart is needed. */
    fun setMuteEarpiece(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(muteEarpiece = on).also { _settings.value = it })
        pendingSettings = pendingSettings?.copy(muteEarpiece = on)
        // Changing the setting is the explicit way to retest a device marked incompatible.
        settingsStore.setDirectMuteIncompatible(false)
        _directMuteIncompatible.value = false
        AppLog.i("earpiece mute ${if (on) "on" else "off"} (from next call)")
    }

    /** Outbound SIM for PBX calls without X-VSA-SIMSLOT; read at each placement, so no restart is needed. */
    fun setDefaultSimSlot(slot: Int) = worker.execute {
        require(DefaultSim.isValid(slot)) { "invalid default SIM $slot" }
        settingsStore.save(settings.copy(defaultSimSlot = slot).also { _settings.value = it })
        pendingSettings = pendingSettings?.copy(defaultSimSlot = slot)
        AppLog.i("outbound default SIM: ${DefaultSim.id(slot)}")
        updateReadiness()
    }

    /** Whether incoming calls on [slot] are bridged; read at each incoming call, so no restart is needed. */
    fun setSimInbound(slot: Int, on: Boolean) = worker.execute {
        require(slot >= 0) { "invalid SIM slot $slot" }
        fun Set<Int>.with() = if (on) this - slot else this + slot
        settingsStore.save(settings.let { it.copy(inboundExcludedSlots = it.inboundExcludedSlots.with()) }.also { _settings.value = it })
        pendingSettings = pendingSettings?.let { it.copy(inboundExcludedSlots = it.inboundExcludedSlots.with()) }
        AppLog.i("incoming calls on SIM $slot ${if (on) "taken over" else "left to the phone app"} (from next call)")
    }

    /** Changes the next switch-off, without touching the current call or any queued handoff. */
    fun setKeepDefaultDialer(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(keepDefaultDialer = on).also { _settings.value = it })
        pendingSettings = pendingSettings?.copy(keepDefaultDialer = on)
    }

    /** Re-evaluated at once; a resulting (un)registration waits until calls end. */
    fun setHoldSipWhenNotReady(on: Boolean) = worker.execute {
        settingsStore.save(settings.copy(holdSipWhenNotReady = on).also { _settings.value = it })
        pendingSettings = pendingSettings?.copy(holdSipWhenNotReady = on)
        AppLog.i("hold SIP while cellular not ready ${if (on) "on" else "off"}")
        updateReadiness()
    }

    /** The master switch. Switching off mid-call waits for that call to end. */
    fun setTakeover(on: Boolean) = worker.execute {
        refreshRoleState()
        takeoverControl.request(on)
        attemptDialerChange()
        updateReadiness()
    }

    private fun applyTakeover() {
        val on = settings.takeover
        _health.update { it.copy(takeover = on, takeoverOffPending = false, takeoverOnPending = false) }
        configureCallAccess()
        if (on) startGateway() else stopGateway()
        // Withdraws or restores the Telecom companion override.
        runSetup()
        updateReadiness()
        AppLog.i(if (on) "call takeover ON" else "call takeover OFF")
    }

    fun setLoopbackTest(on: Boolean) = worker.execute {
        if (hasActiveWork()) { loopbackPending = on; _health.update { it.copy(settingsPending = true) }; return@execute }
        if (_health.value.loopbackTest == on) return@execute
        _health.update { it.copy(loopbackTest = on) }
        AppLog.i("loopback test ${if (on) "ON: inbound cellular calls are answered and echoed" else "OFF"}")
        rebuildBridge()
        updateReadiness()
    }

    fun restartSip() = worker.execute {
        if (hasActiveWork()) { restartPending = true; _health.update { it.copy(settingsPending = true) }; return@execute }
        startSip()
        rebuildBridge()
        updateReadiness()
    }

    private fun applyDeferredChanges() {
        if (hasActiveWork()) return
        takeoverControl.applyIfIdle()
        if (pendingDialerChange != null) attemptDialerChange()
        val queued = pendingSettings
        pendingSettings = null
        val restart = restartPending
        restartPending = false
        val loopback = loopbackPending
        loopbackPending = null
        _health.update { it.copy(settingsPending = false) }
        if (queued != null) applySettingsNow(queued.copy(takeover = settings.takeover))
        if (loopback != null) setLoopbackTest(loopback)
        if (restart) restartSip()
        configureCallAccess()
        updateReadiness()
        if (setupPending || queued != null || _health.value.privileged == null ||
            (_health.value.effectiveCallAccess == CallAccess.COMPANION && !_health.value.callAccessReady)) runSetup()
    }

    /**
     * Runs [block] on the runtime thread after every task queued so far, and after the tasks those
     * queue in turn (one level). This provides a state snapshot after dispatch, not a completion
     * result: task exceptions, deferred changes and asynchronous registration are not tracked here.
     */
    internal fun afterQueued(block: () -> Unit) = worker.execute { worker.execute(block) }

    /** One-line summary for adb replies; mirrors [statusReport] without per-call detail. */
    fun statusLine(): String {
        val h = _health.value
        val audio = callAudioStatus.value
        return "ready=${h.cellularReady} (${h.readinessDetail}) takeover=${onOff(h.takeover)}" +
            (if (h.takeoverOffPending || h.takeoverOnPending) " pending" else "") +
            " sip=${if (h.sip.ready) "ready" else "down"} (${h.sip.detail}) stack=${settings.sipStack.id}" +
            " shizuku=${h.shizuku} audio_method=${settings.audioMethod}:${audioMethodName(settings.audioMethod)}" +
            " audioReady=${h.audioReady} tap=${if (audio.active) audio.backend else "idle"}" +
            " earpiece_mute=${onOff(settings.muteEarpiece)} loopback=${onOff(h.loopbackTest)}" +
            " sims=${h.sims.joinToString(",").ifEmpty { "none" }} default_sim=${defaultSimLabel(h)}" +
            " inbound_off=${inboundOffLabel(settings)}" +
            " calls=${h.calls.summary}" + (if (h.settingsPending) " settingsPending" else "")
    }

    /** Saved or queued selections (not a verified audio route), never the password itself. */
    fun settingsLine(): String {
        val s = pendingSettings ?: settings
        return "stack=${s.sipStack.id} server=${s.server}:${s.port}/${s.sipTransport} srtp=${s.srtp}" +
            " domain=${s.domain.ifEmpty { "(server)" }} user=${s.username}" +
            " password=${if (s.password.isEmpty()) "unset" else "set"} target=${s.inboundTarget.ifEmpty { "(user)" }}" +
            " prefixes=${s.allowedPrefixes.ifEmpty { "(any)" }}" +
            " audio_method=${s.audioMethod}:${audioMethodName(s.audioMethod)} call_access=${s.callAccess}" +
            " earpiece_mute=${onOff(s.muteEarpiece)} hold_sip=${onOff(s.holdSipWhenNotReady)} sip_debug=${onOff(s.sipDebugLog)}" +
            " default_sim=${DefaultSim.id(s.defaultSimSlot)} inbound_off=${inboundOffLabel(s)}" +
            (if (pendingSettings != null) " (applies after current calls end)" else "")
    }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun audioMethodName(method: Int) = when (method) {
        AudioMethod.LEGACY -> "direct"
        AudioMethod.OFFICIAL -> "redirection"
        else -> "auto"
    }

    fun statusReport(): String {
        val h = _health.value
        return buildString {
            appendLine("takeover=${h.takeover} pendingOff=${h.takeoverOffPending} pendingOn=${h.takeoverOnPending} " +
                "keepDefaultDialer=${settings.keepDefaultDialer} muteEarpiece=${settings.muteEarpiece}")
            appendLine("shizuku=${h.shizuku} (${h.shizukuDetail})")
            appendLine("privileged=${h.privileged} ${h.privilegedDetail}")
            appendLine("sip=${h.sip.detail} ready=${h.sip.ready} stack=${settings.sipStack.id} " +
                "transport=${settings.sipTransport} srtp=${settings.srtp} debugLog=${settings.sipDebugLog}")
            appendLine("sipHoldWhenNotReady=${settings.holdSipWhenNotReady} held=${sipHeld ?: "no"}")
            appendLine("cellular=${cellular.status.detail} inCallServiceBound=${CellularEndpoint.isInCallServiceBound} " +
                "service=${CellularEndpoint.boundServiceDescription ?: "none"}")
            appendLine("sdk=${Build.VERSION.SDK_INT} callAccess=${settings.callAccess} effective=${h.effectiveCallAccess} " +
                "defaultDialer=${h.holdsDialer} audioMethod=${settings.audioMethod} audioReady=${h.audioReady}")
            appendLine("dialerChangePending=${h.dialerChangePending} running=${h.dialerChangeRunning} ${_dialerMessage.value}")
            appendLine("sims=${h.sims.joinToString(",").ifEmpty { "none" }} defaultSim=${defaultSimLabel(h)}" +
                " inboundOff=${inboundOffLabel(settings)}" +
                (h.simWarning?.let { " warning=$it" } ?: ""))
            appendLine("calls=${h.calls.summary} settingsPending=${h.settingsPending}")
            appendLine("loopback=${h.loopbackTest} session=${h.session}")
            appendLine("callAudio=${callAudioStatus.value}")
            shizuku.service?.let { appendLine("tap=${runCatching { it.audioStats() }.getOrDefault("?")}") }
        }
    }

    companion object {
        @Volatile private var instance: GatewayRuntime? = null

        /** Outlasts brief Shizuku reconnects; shorter than a typical PBX qualify/failover window. */
        internal const val SIP_HOLD_GRACE_MS = 45_000L

        internal fun newWorker() = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "gateway-runtime").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(1, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }

        fun get(context: Context): GatewayRuntime =
            instance ?: synchronized(this) { instance ?: GatewayRuntime(context).also { instance = it } }
    }
}
