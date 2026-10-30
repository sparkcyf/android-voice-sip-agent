package io.github.sparkcyf.voicesipagent.telephony

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executor

/**
 * The phone's cellular line as a [CallEndpoint], driven through the companion InCallService.
 *
 * Only calls this endpoint owns are touched: inbound calls offered to the gateway, and outbound
 * calls it placed itself. Calls the user dials by hand, or that arrive while such a call is in
 * progress, are left to the stock phone app.
 *
 * Legs carry the logical SIM slot as [CallLeg.line]. Outbound calls are always placed on an
 * explicit SIM account: the requested slot, else [defaultSlot] (see [DefaultSim]). A missing
 * slot is refused rather than letting Telecom ask the user, pick a SIM or fall back to another.
 * Incoming calls on slots in [inboundExcluded] are left to the phone app.
 */
class CellularEndpoint(
    context: Context,
    internal val audioOpener: CallAudioOpener,
    private val logger: (String) -> Unit,
    private val main: Handler = Handler(Looper.getMainLooper()),
    internal val audioExecutor: Executor = CellularLeg.audioExecutor,
    val sims: SimSlots = SystemSimSlots(context.applicationContext),
    /** Read at each placement without a requested slot. */
    private val defaultSlot: () -> Int = { DefaultSim.SYSTEM },
    /** Slots whose incoming calls stay with the phone app; read at each incoming call. */
    private val inboundExcluded: () -> Set<Int> = { emptySet() },
) : CallEndpoint {
    override val name = "cellular"

    private val app = context.applicationContext
    private val telecom = app.getSystemService(TelecomManager::class.java)
    private val telephony = app.getSystemService(TelephonyManager::class.java)
    private val statusListeners = CopyOnWriteArrayList<EndpointStatusListener>()
    private val ids = AtomicInteger(1)
    @Volatile private var handler: IncomingCallHandler? = null

    /** Legs we own, keyed by Telecom call. Main thread only. */
    private val legs = HashMap<Call, CellularLeg>()
    private val observed = LinkedHashMap<Call, Call.Callback>()
    private val mute = MuteLease<CellularLeg, GatewayInCallService>(
        { it.microphoneMuted }, { svc, value -> svc.setMuted(value) })
    @Volatile private var closed = false
    @Volatile var hasCalls = false; private set
    var onCallsChanged: ((CallSnapshot) -> Unit)? = null

    /** Outbound placements waiting for Telecom's onCallAdded. Main thread only. */
    private val pending = ArrayList<Pending>()

    private class Pending(val leg: CellularLeg, val digits: String, val atMs: Long, val slot: Int?)

    @Volatile override var status = EndpointStatus(false, "companion not configured")
        private set

    init {
        current = this
    }

    fun close() {
        closed = true
        if (current === this) current = null
        main.post { clearCalls("endpoint closed") }
    }

    internal fun log(msg: String) = logger(msg)

    /** Readiness is decided by the privileged health check (companion override etc.). */
    fun setReadiness(ready: Boolean, detail: String) {
        val s = EndpointStatus(ready, detail)
        if (s == status) return
        status = s
        statusListeners.forEach { it.onStatusChanged(this, s) }
        if (!ready) main.post {
            if (!status.ready) (legs.values + pending.map { it.leg }).distinct().forEach {
                it.abortAudio("gateway unavailable: $detail")
            }
        }
    }

    override fun setIncomingCallHandler(handler: IncomingCallHandler?) {
        this.handler = handler
    }

    override fun addStatusListener(listener: EndpointStatusListener) {
        statusListeners.addIfAbsent(listener)
    }

    override fun removeStatusListener(listener: EndpointStatusListener) {
        statusListeners.remove(listener)
    }

    fun isEmergencyNumber(number: String): Boolean = try {
        telephony.isEmergencyNumber(number)
    } catch (e: Exception) {
        // Fail closed: if we cannot tell, treat it as emergency and refuse to dial.
        true
    }

    override fun dial(target: String, caller: Party, line: Int?): CallLeg {
        val leg = CellularLeg("c${ids.getAndIncrement()}", LegDirection.OUTBOUND, Party(target), target, line, this, main)
        val digits = target.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        when {
            digits.isEmpty() -> leg.failPlacement(HangupCause.NOT_FOUND)
            isEmergencyNumber(digits) -> {
                log("refusing emergency number from gateway")
                leg.failPlacement(HangupCause.FORBIDDEN)
            }
            app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED -> {
                log("CALL_PHONE not granted")
                leg.failPlacement(HangupCause.INTERNAL_ERROR)
            }
            else -> main.post { place(leg, digits) }
        }
        return leg
    }

    private fun place(leg: CellularLeg, digits: String) {
        if (leg.stopping) return
        if (closed) { leg.failPlacement(HangupCause.UNAVAILABLE); return }
        if (!status.ready) { leg.failPlacement(HangupCause.UNAVAILABLE); return }
        // Permission can be revoked between dial() and this main-thread placement.
        if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            leg.failPlacement(HangupCause.FORBIDDEN)
            return
        }
        if (hasCalls || pending.isNotEmpty()) {
            log("line busy with a non-gateway call")
            leg.failPlacement(HangupCause.BUSY)
            return
        }
        val slot = leg.line ?: defaultSlot()
        val account = when (slot) {
            DefaultSim.SYSTEM -> sims.defaultAccount()
            DefaultSim.REFUSE -> null
            else -> sims.accountFor(slot)
        } ?: run {
            log(when (slot) {
                DefaultSim.SYSTEM -> "no slot requested and Android has no default SIM for calls"
                DefaultSim.REFUSE -> "no slot requested; the outbound default is set to refuse"
                else -> "no active SIM in slot $slot"
            })
            leg.failPlacement(HangupCause.UNAVAILABLE)
            return
        }
        val placedSlot = sims.slotOf(account)
        val p = Pending(leg, digits, SystemClock.elapsedRealtime(), placedSlot)
        pending += p
        reportCalls()
        try {
            val extras = Bundle()
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
            telecom.placeCall(Uri.fromParts("tel", digits, null), extras)
            log("placeCall ${redactNumber(digits)} on slot ${placedSlot ?: "?"} (leg ${leg.id})")
        } catch (e: SecurityException) {
            pending -= p
            reportCalls()
            log("placeCall permission denied")
            leg.failPlacement(HangupCause.FORBIDDEN)
            return
        } catch (e: Exception) {
            pending -= p
            reportCalls()
            log("placeCall failed: $e")
            leg.failPlacement(HangupCause.NETWORK_ERROR)
            return
        }
        main.postDelayed({
            if (pending.remove(p)) {
                reportCalls()
                log("leg ${leg.id}: Telecom never reported the call")
                leg.failPlacement(HangupCause.NETWORK_ERROR)
            }
        }, PLACEMENT_TIMEOUT_MS)
    }

    // ------------------------------------------------------------ InCallService callbacks (main thread)

    internal fun onCallAdded(source: GatewayInCallService, call: Call) {
        if (source !== service || closed) return
        if (observed.containsKey(call)) return
        val watcher = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) { reconcileCalls(); reportCalls() }
            override fun onDetailsChanged(call: Call, details: Call.Details) { reconcileCalls(); reportCalls() }
        }
        observed[call] = watcher
        call.registerCallback(watcher, main)
        hasCalls = true
        val details = call.details
        val number = details.handle?.schemeSpecificPart.orEmpty()
        when (details.callDirection) {
            Call.Details.DIRECTION_OUTGOING -> {
                val match = pending.firstOrNull { sameNumber(it.digits, number) && isCellular(call) }
                if (match == null) {
                    log("ignoring user-placed outgoing call")
                    reconcileCalls(); reportCalls()
                    return
                }
                pending -= match
                if (!match.leg.stopping) legs[call] = match.leg
                match.leg.bind(call)
                val slot = sims.slotOf(details.accountHandle)
                if (slot != match.slot) {
                    // Never bridge a call on a SIM the PBX did not ask for.
                    log("leg ${match.leg.id} placed on slot ${slot ?: "?"} instead of ${match.slot}")
                    match.leg.hangup(HangupCause.UNAVAILABLE)
                }
            }
            Call.Details.DIRECTION_INCOMING -> {
                val h = handler
                val slot = sims.slotOf(details.accountHandle)
                if (!takesInbound(slot)) {
                    log("leaving incoming call ${redactNumber(number)} on slot ${slot ?: "?"} to the phone app: not taken over")
                    reconcileCalls(); reportCalls()
                    return
                }
                if (h == null || !status.ready || call.telecomState != Call.STATE_RINGING || legs.isNotEmpty() ||
                    pending.isNotEmpty() || hasForeignCall(except = call) || !isCellular(call) ||
                    (number.isNotEmpty() && isEmergencyNumber(number))) {
                    log("leaving incoming call ${redactNumber(number)} to the phone app")
                    reconcileCalls(); reportCalls()
                    return
                }
                val leg = CellularLeg("c${ids.getAndIncrement()}", LegDirection.INBOUND,
                    Party(number.ifEmpty { null }, details.callerDisplayName), null, slot, this, main)
                legs[call] = leg
                leg.bind(call)
                log("incoming cellular call ${redactNumber(number)} on slot ${slot ?: "?"} -> gateway (leg ${leg.id})")
                h.onIncomingCall(leg)
            }
            else -> log("ignoring call with unknown direction")
        }
        reconcileCalls(); reportCalls()
    }

    /** An unknown slot is taken over only while no SIM is excluded. */
    private fun takesInbound(slot: Int?): Boolean {
        val excluded = inboundExcluded()
        return if (slot == null) excluded.isEmpty() else slot !in excluded
    }

    internal fun onCallRemoved(source: GatewayInCallService, call: Call) {
        if (source !== service) return
        observed.remove(call)?.let { runCatching { call.unregisterCallback(it) } }
        legs.remove(call)?.onRemoved()
        reportCalls()
    }

    internal fun onLegEnded(leg: CellularLeg) {
        legs.entries.removeAll { it.value === leg }
        // Keep a cancelled placement match until Telecom reports it or the timeout expires,
        // so a late owned call is disconnected rather than mistaken for a manual call.
        reportCalls()
    }

    /** True if Telecom has a live call we do not own (e.g. the user's own conversation). */
    private fun hasForeignCall(except: Call? = null): Boolean {
        return observed.keys.any { it !== except && it !in legs && it.telecomState != Call.STATE_DISCONNECTED }
    }

    internal fun owns(leg: CellularLeg) = leg.call?.let { legs[it] === leg } == true

    /** App-controlled service changes wait for all calls; role loss still invalidates readiness. */
    fun setPreferredDialerService(dialer: Boolean) {
        main.post {
            preferDialerService = dialer
            selectManagedService()
        }
    }

    internal fun canTap(leg: CellularLeg): Boolean = !closed && !leg.stopping && status.ready && service != null &&
        leg.call?.let { legs[it] === leg && it.telecomState == Call.STATE_ACTIVE && isCellular(it) } == true &&
        observed.keys.count { it.telecomState != Call.STATE_DISCONNECTED } == 1

    internal fun acquireMute(leg: CellularLeg): Boolean {
        if (!canTap(leg)) return false
        val svc = service ?: return false
        return mute.acquire(leg, svc)
    }

    internal fun releaseMute(leg: CellularLeg) {
        mute.release(leg)
    }

    private fun reconcileCalls() {
        if (observed.keys.count { it.telecomState != Call.STATE_DISCONNECTED } > 1) {
            legs.values.toList().forEach { it.abortAudio("additional call makes telephony routing unsafe") }
        }
        legs.values.toList().filter { it.call?.let(::isCellular) != true }.forEach {
            it.abortAudio("call is outside the cellular gateway scope")
        }
    }

    private fun isCellular(call: Call): Boolean {
        val details = call.details
        if (details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED) ||
            details.hasProperty(Call.Details.PROPERTY_IS_EXTERNAL_CALL)) return false
        val account = details.accountHandle ?: return false
        // A managed VoIP ConnectionService can also appear in InCallService. Only SIM accounts
        // are eligible for the global modem tap, even if their handle uses the tel scheme.
        return runCatching { telecom.getPhoneAccount(account)?.hasCapabilities(
            android.telecom.PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true }.getOrDefault(false)
    }

    private fun clearCalls(reason: String) {
        (legs.values + pending.map { it.leg }).distinct().forEach {
            runCatching { it.onServiceLost(reason) }.onFailure { log("call cleanup failed: ${it.javaClass.simpleName}") }
        }
        legs.clear()
        observed.forEach { (call, callback) -> runCatching { call.unregisterCallback(callback) } }
        observed.clear()
        // Retain cancelled placement matches until their existing timeout or a late call report.
        reportCalls()
    }

    private fun reportCalls() {
        val live = observed.keys.filter { it.telecomState != Call.STATE_DISCONNECTED }
        hasCalls = live.isNotEmpty() || pending.isNotEmpty()
        onCallsChanged?.invoke(CallSnapshot(live.size, live.any { it.telecomState == Call.STATE_RINGING },
            live.count { it in legs }, if (live.isEmpty()) "No cellular call" else
                live.joinToString { if (it in legs) "Gateway call: ${stateLabel(it.telecomState)}" else "Other call: ${stateLabel(it.telecomState)}" }))
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val da = a.filter(Char::isDigit)
        val db = b.filter(Char::isDigit)
        if (da.isEmpty() || db.isEmpty()) return false
        val n = minOf(da.length, db.length, 8)
        return da.takeLast(n) == db.takeLast(n)
    }

    companion object {
        private const val PLACEMENT_TIMEOUT_MS = 10_000L

        @Volatile internal var current: CellularEndpoint? = null
        @Volatile private var service: GatewayInCallService? = null
        private val managedServices = LinkedHashMap<GatewayInCallService, Boolean>()
        private var preferDialerService = false

        val isInCallServiceBound: Boolean get() = service != null
        val boundServiceDescription: String? get() = service?.serviceDescription

        internal fun attachManagedService(s: GatewayInCallService, dialer: Boolean) {
            managedServices[s] = dialer
            selectManagedService()
        }

        internal fun detachManagedService(s: GatewayInCallService) {
            managedServices.remove(s)
            detachService(s)
            selectManagedService()
        }

        private fun selectManagedService() {
            if (current?.hasCalls == true && service != null) return
            val candidate = managedServices.entries.lastOrNull { it.value == preferDialerService }?.key ?: return
            if (service === candidate) return
            attachService(candidate)
            // A standby service may already have reported calls while the old service was retained.
            // Reconcile them without claiming existing active calls as new gateway arrivals.
            candidate.calls.toList().forEach { current?.onCallAdded(candidate, it) }
        }

        /**
         * Enables or disables our InCallService component. Disabled, Telecom never binds us and the
         * phone behaves as if the app were not installed.
         */
        fun setCompanionComponentEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, GatewayInCallService::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        fun attachService(s: GatewayInCallService) {
            if (service === s) return
            if (service != null) current?.clearCalls("InCallService replaced")
            service = s
            current?.log("${s.serviceDescription} bound")
        }

        fun detachService(s: GatewayInCallService) {
            if (service !== s) return
            service = null
            current?.clearCalls("InCallService disconnected")
            current?.log("${s.serviceDescription} unbound")
        }

        private fun stateLabel(state: Int) = when (state) {
            Call.STATE_RINGING -> "incoming"
            Call.STATE_ACTIVE -> "active"
            Call.STATE_HOLDING -> "held"
            else -> "connecting"
        }
    }
}

data class CallSnapshot(val count: Int = 0, val incoming: Boolean = false, val owned: Int = 0,
    val summary: String = "No cellular call")
