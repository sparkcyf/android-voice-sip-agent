package io.github.sparkcyf.voicesipagent.telephony

import android.content.Context
import android.os.Handler
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.Party
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Real controller/leg callback sequences with a deterministic main queue and audio executor. */
class CellularOwnershipTest {
    private val mainTasks = ArrayDeque<Runnable>()
    private val delayedTasks = ArrayDeque<Runnable>()
    private val audioTasks = ArrayDeque<Runnable>()
    private val callbacks = mutableMapOf<Call, MutableList<Call.Callback>>()
    private val main = mock(Handler::class.java)
    private val context = mock(Context::class.java)
    private val telecom = mock(TelecomManager::class.java)
    private val service = mock(GatewayInCallService::class.java)
    private lateinit var endpoint: CellularEndpoint
    private lateinit var leg: CellularLeg
    private lateinit var call: Call
    private var openCount = 0
    private var openFailure = false
    private var failHandleClose = false
    private var earlyLoss = false
    private var opened: Handle? = null
    private var lost: ((String) -> Unit)? = null
    private var originalMute = false
    private val simAccount = mock(PhoneAccountHandle::class.java)
    private val sim1Account = mock(PhoneAccountHandle::class.java)
    private var defaultSim: PhoneAccountHandle? = simAccount
    private var defaultSlot = DefaultSim.SYSTEM
    private val sims = object : SimSlots {
        override fun active() = listOf(SimSlot(0, 1, "Test", simAccount), SimSlot(1, 5, "Other", sim1Account))
        override fun slotOf(account: PhoneAccountHandle?) = when (account) { null -> null; sim1Account -> 1; else -> 0 }
        override fun defaultAccount() = defaultSim
    }

    private class Handle(private val failClose: Boolean) : CallAudioHandle {
        override val port = mock(MediaPort::class.java)
        var closes = 0
        override fun close() { closes++; if (failClose) error("close failed") }
    }

    @Before fun setup() {
        doAnswer { mainTasks.add(it.getArgument(0)); true }.`when`(main).post(any(Runnable::class.java))
        doAnswer { delayedTasks.add(it.getArgument(0)); true }.`when`(main).postDelayed(any(Runnable::class.java), anyLong())
        doAnswer { delayedTasks.add(it.getArgument(0)); true }.`when`(main)
            .postDelayed(any(Runnable::class.java), any(), anyLong())
        doReturn(context).`when`(context).applicationContext
        doReturn(telecom).`when`(context).getSystemService(TelecomManager::class.java)
        doReturn(mock(TelephonyManager::class.java)).`when`(context).getSystemService(TelephonyManager::class.java)
        val account = mock(PhoneAccount::class.java)
        doReturn(true).`when`(account).hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
        doReturn(account).`when`(telecom).getPhoneAccount(any(PhoneAccountHandle::class.java))
        doAnswer { originalMute }.`when`(service).microphoneMuted
        endpoint = CellularEndpoint(context, CallAudioOpener { onLost ->
            openCount++
            lost = onLost
            if (earlyLoss) onLost("socket died during open")
            if (openFailure) error("mode not settled")
            Handle(failHandleClose).also { opened = it }
        }, {}, main, Executor { audioTasks += it }, sims, { defaultSlot })
        endpoint.setReadiness(true, "ready")
        endpoint.setIncomingCallHandler { leg = it as CellularLeg }
        CellularEndpoint.attachService(service)
        call = makeCall(Call.STATE_RINGING)
    }

    @After fun teardown() {
        endpoint.close()
        drainMain()
        while (audioTasks.isNotEmpty()) { audioTasks.removeFirst().run(); drainMain() }
        CellularEndpoint.detachService(service)
    }

    @Suppress("DEPRECATION") // Stub the getter used on API 30.
    private fun makeCall(initial: Int, direction: Int = Call.Details.DIRECTION_INCOMING): Call {
        val c = mock(Call::class.java)
        val details = mock(Call.Details::class.java)
        doReturn(initial).`when`(c).state
        doReturn(details).`when`(c).details
        doReturn(direction).`when`(details).callDirection
        doReturn(mock(PhoneAccountHandle::class.java)).`when`(details).accountHandle
        callbacks[c] = mutableListOf()
        doAnswer { callbacks.getValue(c).add(it.getArgument(0)); null }.`when`(c)
            .registerCallback(any(Call.Callback::class.java), eq(main))
        doAnswer { callbacks.getValue(c).remove(it.getArgument<Call.Callback>(0)); null }.`when`(c)
            .unregisterCallback(any(Call.Callback::class.java))
        return c
    }

    @Suppress("DEPRECATION") // Keep the API 30 getter in step with the state callback.
    private fun state(c: Call, value: Int) {
        doReturn(value).`when`(c).state
        callbacks.getValue(c).toList().forEach { it.onStateChanged(c, value) }
    }

    private fun drainMain() { while (mainTasks.isNotEmpty()) mainTasks.removeFirst().run() }
    private fun active() {
        endpoint.onCallAdded(service, call)
        leg.answer(); drainMain()
        state(call, Call.STATE_ACTIVE)
    }
    private fun open() { audioTasks.removeFirst().run(); drainMain() }

    @Test fun locallyAnsweredCallIsReleasedWithoutMuteTapOrDisconnect() {
        endpoint.onCallAdded(service, call)
        state(call, Call.STATE_ACTIVE)
        state(call, Call.STATE_ACTIVE) // A duplicate callback must not reclaim the call.
        drainMain()

        assertEquals(LegState.ENDED, leg.state)
        assertEquals(HangupCause.CANCELLED, leg.endCause)
        assertFalse(endpoint.owns(leg))
        assertTrue(endpoint.hasCalls)
        assertTrue(audioTasks.isEmpty())
        assertEquals(0, openCount)
        assertNull(leg.media)
        verify(service, never()).setMuted(anyBoolean())
        verify(call, never()).answer(anyInt())
        verify(call, never()).disconnect()
        verify(call, never()).reject(anyBoolean(), nullable(String::class.java))

        // Later gateway cleanup must leave the released handset call alone.
        leg.hangup(); drainMain()
        endpoint.setReadiness(false, "gateway disabled"); drainMain()
        state(call, Call.STATE_DISCONNECTED)
        endpoint.onCallRemoved(service, call)
        assertEquals(HangupCause.CANCELLED, leg.endCause)
        assertFalse(endpoint.hasCalls)
        verify(call, never()).disconnect()
    }

    @Test fun queuedGatewayAnswerAfterLocalAnswerDoesNotReclaimCall() {
        endpoint.onCallAdded(service, call)
        leg.answer() // Gateway acceptance is queued on main, but the user answers first.
        state(call, Call.STATE_ACTIVE)
        drainMain()

        assertEquals(HangupCause.CANCELLED, leg.endCause)
        assertFalse(endpoint.owns(leg))
        assertTrue(audioTasks.isEmpty())
        verify(call, never()).answer(anyInt())
        verify(call, never()).disconnect()
        verify(service, never()).setMuted(anyBoolean())
    }

    @Suppress("DEPRECATION") // Simulate Telecom updating state before delivering its callback.
    @Test fun alreadyActiveCallDoesNotBecomeGatewayOwnedWhenAnswerTaskRuns() {
        endpoint.onCallAdded(service, call)
        leg.answer()
        doReturn(Call.STATE_ACTIVE).`when`(call).state
        drainMain()
        state(call, Call.STATE_ACTIVE)

        assertEquals(HangupCause.CANCELLED, leg.endCause)
        assertTrue(audioTasks.isEmpty())
        verify(call, never()).answer(anyInt())
        verify(call, never()).disconnect()
        verify(service, never()).setMuted(anyBoolean())
    }

    @Test fun hangupCancelsQueuedOpenBeforeTelecomDisconnects() {
        active()
        leg.hangup(HangupCause.NORMAL)
        drainMain()
        open()
        assertEquals(0, openCount)
        assertNull(leg.media)
        verify(call).disconnect()
        verify(service).setMuted(false)
    }

    @Test fun lateCompletionAfterHangupIsClosedWithoutPublication() {
        active()
        audioTasks.removeFirst().run() // completion is waiting on the main queue
        // A real hangup runs on main while the audio executor is blocked; put it before completion.
        val completion = mainTasks.removeFirst()
        leg.hangup(HangupCause.NORMAL)
        drainMain()
        mainTasks.add(completion)
        drainMain()
        assertEquals(1, opened!!.closes)
        assertNull(leg.media)
    }

    @Test fun hangupCancelsScheduledRetry() {
        openFailure = true
        active(); open()
        assertEquals(1, openCount)
        leg.hangup(HangupCause.NORMAL); drainMain()
        verify(main).removeCallbacksAndMessages(leg)
        delayedTasks.removeFirst().run()
        assertTrue(audioTasks.isEmpty())
        assertEquals(1, openCount)
    }

    @Test fun staleLossFromFailedAttemptDoesNotKillSuccessfulRetry() {
        openFailure = true
        active(); open()
        val oldLost = lost!!
        openFailure = false
        delayedTasks.removeFirst().run()
        open()
        oldLost("late failure"); drainMain()
        assertEquals(LegState.CONNECTED, leg.state)
        assertNotNull(leg.media)
        verify(call, never()).disconnect()
    }

    @Test fun repeatedLossAndThrowingCloseStillRestoreMuteAndEndOnce() {
        failHandleClose = true
        active(); open()
        lost!!("helper died"); lost!!("socket eof"); drainMain()
        assertEquals(LegState.ENDED, leg.state)
        assertEquals(HangupCause.NETWORK_ERROR, leg.endCause)
        assertEquals(1, opened!!.closes)
        verify(call).disconnect()
        verify(service).setMuted(false)
    }

    @Test fun additionalManualCallStopsOwnedTapAndLeavesForeignCallUntouched() {
        active(); open()
        val manual = makeCall(Call.STATE_ACTIVE, Call.Details.DIRECTION_OUTGOING)
        endpoint.onCallAdded(service, manual)
        assertEquals(LegState.ENDED, leg.state)
        assertEquals(1, opened!!.closes)
        verify(call).disconnect()
        verify(manual, never()).disconnect()
        verify(service).setMuted(false)
        assertTrue(endpoint.hasCalls)
    }

    @Test fun originalUserMuteSurvivesGatewayHangup() {
        originalMute = true
        active(); open()
        leg.hangup(); drainMain()
        verify(service, never()).setMuted(anyBoolean())
    }

    @Test fun activeIncomingCallOnRebindIsObservedWithoutBeingClaimed() {
        state(call, Call.STATE_ACTIVE)
        endpoint.onCallAdded(service, call)
        assertTrue(endpoint.hasCalls)
        assertFalse(::leg.isInitialized)
        assertTrue(audioTasks.isEmpty())
        verify(call, never()).disconnect()
        verify(service, never()).setMuted(anyBoolean())
    }

    @Test fun managedVoipCallIsNotClaimedEvenWhenRinging() {
        doReturn(null).`when`(telecom).getPhoneAccount(any(PhoneAccountHandle::class.java))
        endpoint.onCallAdded(service, call)
        assertFalse(::leg.isInitialized)
        assertTrue(endpoint.hasCalls)
        verify(call, never()).disconnect()
    }

    @Test fun readinessLossClosesAudioAndRefusesLaterCompletion() {
        active(); open()
        endpoint.setReadiness(false, "helper unavailable"); drainMain()
        assertEquals(LegState.ENDED, leg.state)
        assertNull(leg.media)
        assertEquals(1, opened!!.closes)
        verify(service).setMuted(false)
    }

    @Test fun lossBeforeInstallationDiscardsTheReturningHandle() {
        earlyLoss = true
        active(); open()
        assertEquals(LegState.ENDED, leg.state)
        assertNull(leg.media)
        assertEquals(1, opened!!.closes)
        verify(call).disconnect()
    }

    @Test fun cancelledPlacementDisconnectsItsLateCallWithoutOpeningAudio() {
        mockStatic(SystemClock::class.java).use {
            mockStatic(Uri::class.java).use { uris ->
                mockConstruction(Bundle::class.java).use {
                    val number = "5551234567"
                    val uri = mock(Uri::class.java)
                    doReturn(number).`when`(uri).schemeSpecificPart
                    uris.`when`<Uri> { Uri.fromParts("tel", number, null) }.thenReturn(uri)
                    val outgoing = endpoint.dial(number, Party(null))
                    drainMain()
                    verify(telecom).placeCall(eq(uri), any(Bundle::class.java))
                    outgoing.hangup(HangupCause.CANCELLED); drainMain()
                    assertEquals(LegState.ENDED, outgoing.state)
                    val late = makeCall(Call.STATE_DIALING, Call.Details.DIRECTION_OUTGOING)
                    val details = late.details
                    doReturn(uri).`when`(details).handle
                    endpoint.onCallAdded(service, late)
                    verify(late).disconnect()
                    assertTrue(audioTasks.isEmpty())
                    verify(service, never()).setMuted(anyBoolean())
                }
            }
        }
    }

    @Test fun inboundLegReportsItsSimSlot() {
        endpoint.onCallAdded(service, call)
        assertEquals(0, leg.line)
    }

    @Test fun missingSlotOrDefaultSimRefusesPlacement() {
        val unknown = endpoint.dial("5551234567", Party(null), line = 2)
        drainMain()
        assertEquals(HangupCause.UNAVAILABLE, unknown.endCause)
        defaultSim = null
        val noDefault = endpoint.dial("5551234567", Party(null))
        drainMain()
        assertEquals(HangupCause.UNAVAILABLE, noDefault.endCause)
        verify(telecom, never()).placeCall(any(Uri::class.java), any(Bundle::class.java))
        assertFalse(endpoint.hasCalls)
    }

    @Test fun configuredDefaultSlotRefusesWithoutFallingBack() {
        defaultSlot = DefaultSim.REFUSE
        val refused = endpoint.dial("5551234567", Party(null))
        drainMain()
        assertEquals(HangupCause.UNAVAILABLE, refused.endCause)
        defaultSlot = 2 // no SIM there; the system default must not be used instead
        val missing = endpoint.dial("5551234567", Party(null))
        drainMain()
        assertEquals(HangupCause.UNAVAILABLE, missing.endCause)
        verify(telecom, never()).placeCall(any(Uri::class.java), any(Bundle::class.java))
    }

    @Test fun configuredDefaultSlotSelectsItsAccount() {
        defaultSlot = 1
        mockStatic(SystemClock::class.java).use {
            mockStatic(Uri::class.java).use { uris ->
                mockConstruction(Bundle::class.java).use { bundles ->
                    uris.`when`<Uri> { Uri.fromParts(anyString(), anyString(), any()) }.thenReturn(mock(Uri::class.java))
                    endpoint.dial("5551234567", Party(null))
                    drainMain()
                    verify(bundles.constructed().single()).putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim1Account)
                    verify(telecom).placeCall(any(Uri::class.java), any(Bundle::class.java))
                }
            }
        }
    }

    @Test fun permissionRevocationBeforeQueuedPlacementRefusesCall() {
        val outgoing = endpoint.dial("5551234567", Party(null))
        doReturn(android.content.pm.PackageManager.PERMISSION_DENIED).`when`(context)
            .checkSelfPermission(android.Manifest.permission.CALL_PHONE)
        drainMain()
        assertEquals(LegState.ENDED, outgoing.state)
        assertEquals(HangupCause.FORBIDDEN, outgoing.endCause)
        verify(telecom, never()).placeCall(any(Uri::class.java), any(Bundle::class.java))
        assertFalse(endpoint.hasCalls)
    }

    @Test fun disconnectingInvalidatesAudioBeforeDisconnected() {
        active(); open()
        state(call, Call.STATE_DISCONNECTING)
        assertNull(leg.media)
        assertEquals(1, opened!!.closes)
        verify(service).setMuted(false)
        state(call, Call.STATE_DISCONNECTED)
        assertEquals(LegState.ENDED, leg.state)
    }

    @Test fun holdStopsOwnedCallAndCancelsAudio() {
        active(); open()
        state(call, Call.STATE_HOLDING)
        assertEquals(LegState.ENDED, leg.state)
        assertEquals(1, opened!!.closes)
        assertNull(leg.media)
        verify(call).disconnect()
        verify(service).setMuted(false)
    }

    @Test fun exhaustedRetriesEndCallAndRestoreMute() {
        openFailure = true
        active(); open()
        repeat(4) { delayedTasks.removeFirst().run(); open() }
        assertEquals(5, openCount)
        assertEquals(LegState.ENDED, leg.state)
        assertTrue(delayedTasks.isEmpty())
        verify(call).disconnect()
        verify(service).setMuted(false)
    }

    @Test fun disconnectFailureCannotPreventAudioCleanupOrLegTermination() {
        active(); open()
        doThrow(IllegalStateException("Telecom disconnected")).`when`(call).disconnect()
        lost!!("helper lost"); drainMain()
        assertEquals(LegState.ENDED, leg.state)
        assertEquals(1, opened!!.closes)
        verify(service).setMuted(false)
    }

    @Test fun muteRestoreFailureCannotPreventLegTermination() {
        active(); open()
        doThrow(IllegalStateException("service disconnected")).`when`(service).setMuted(false)
        lost!!("helper lost"); drainMain()
        assertEquals(LegState.ENDED, leg.state)
        assertEquals(1, opened!!.closes)
        verify(call).disconnect()
    }

    @Test fun serviceLossFinishesLegAlreadyWaitingForDisconnect() {
        active(); open()
        leg.hangup(); drainMain()
        CellularEndpoint.detachService(service)
        assertEquals(LegState.ENDED, leg.state)
        assertEquals(1, opened!!.closes)
        assertFalse(endpoint.hasCalls)
    }

    @Test fun oldServiceCallbacksCannotClearReplacementCalls() {
        active(); open()
        val replacement = mock(GatewayInCallService::class.java)
        CellularEndpoint.attachService(replacement)
        assertEquals(LegState.ENDED, leg.state)
        val manual = makeCall(Call.STATE_ACTIVE, Call.Details.DIRECTION_OUTGOING)
        endpoint.onCallAdded(replacement, manual)
        endpoint.onCallRemoved(service, manual)
        CellularEndpoint.detachService(service)
        assertTrue(endpoint.hasCalls)
        assertTrue(CellularEndpoint.isInCallServiceBound)
        verify(manual, never()).disconnect()
        CellularEndpoint.detachService(replacement)
    }

    @Test fun dialerBindingDuringCallDoesNotReplaceOwnerOrOpenDuplicateAudio() {
        val dialer = mock(GatewayInCallService::class.java)
        CellularEndpoint.attachManagedService(service, false)
        try {
            active(); open()
            endpoint.setPreferredDialerService(true); drainMain()
            CellularEndpoint.attachManagedService(dialer, true)
            val duplicate = makeCall(Call.STATE_ACTIVE)
            endpoint.onCallAdded(dialer, duplicate)
            assertEquals(LegState.CONNECTED, leg.state)
            assertEquals(0, opened!!.closes)
            assertEquals(1, openCount)
            verify(call, never()).disconnect()
            verify(duplicate, never()).disconnect()
            state(call, Call.STATE_DISCONNECTED)
            endpoint.onCallRemoved(service, call)
            endpoint.setPreferredDialerService(true); drainMain()
            val manual = makeCall(Call.STATE_ACTIVE, Call.Details.DIRECTION_OUTGOING)
            endpoint.onCallAdded(dialer, manual)
            assertTrue(endpoint.hasCalls)
            assertEquals(1, openCount)
            verify(manual, never()).disconnect()
        } finally {
            CellularEndpoint.detachManagedService(dialer)
            CellularEndpoint.detachManagedService(service)
            endpoint.setPreferredDialerService(false); drainMain()
        }
    }
}
