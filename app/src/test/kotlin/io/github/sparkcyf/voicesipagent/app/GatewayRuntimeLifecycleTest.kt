package io.github.sparkcyf.voicesipagent.app

import android.app.role.RoleManager
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import io.github.sparkcyf.voicesipagent.gateway.CallBridge
import io.github.sparkcyf.voicesipagent.gateway.DialPolicy
import io.github.sparkcyf.voicesipagent.pjsua2.PjsipConfig
import io.github.sparkcyf.voicesipagent.pjsua2.PjsipEndpoint
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.telephony.CallSnapshot
import io.github.sparkcyf.voicesipagent.telephony.CellularEndpoint
import io.github.sparkcyf.voicesipagent.telephony.SimSlots
import io.github.sparkcyf.voicesipagent.sip.SipUserAgent
import io.github.sparkcyf.voicesipagent.sip.SipConfig
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import org.junit.Assert.*
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GatewayRuntimeLifecycleTest {
    @Test fun switchingToBuiltinWaitsForNativeSocketRelease(): Unit =
        Fixture(enabled = true, holder = "gateway", healthy = true).use { f ->
            f.connect()
            withStartupBlocked { release ->
                f.runtime.applySettings { it.copy(sipStack = SipStack.BUILTIN, sipTransport = "udp") }
                f.drain()
                val replacement = f.builtin.constructed().single()
                verify(f.endpoints.constructed().single()).stop(releaseTransport = true)
                verify(replacement, never()).start()
                release()
                f.drain()
                verify(replacement).start()
            }
        }

    @Test fun switchingFromBuiltinWaitsForItsSocketEvenAcrossAnotherRestart(): Unit =
        Fixture(enabled = true, holder = "gateway", healthy = true).use { f ->
            f.connect()
            f.runtime.applySettings { it.copy(sipStack = SipStack.BUILTIN, sipTransport = "udp") }
            f.awaitNativeShutdown()
            val old = f.builtin.constructed().single()
            verify(old).start()
            val released = CompletableFuture<Unit>()
            doReturn(released).`when`(old).stop(anyLong())
            f.runtime.applySettings { it.copy(sipStack = SipStack.PJSUA2) }
            f.drain()
            val cancelled = f.endpoints.constructed().last()
            verify(cancelled, never()).start()
            f.runtime.restartSip()
            f.drain()
            val current = f.endpoints.constructed().last()
            verify(current, never()).start()
            released.complete(Unit)
            f.drain()
            verify(cancelled, never()).start()
            verify(current).start()
        }

    @Test fun failedSocketReleaseLeavesReplacementUnavailableAndTakeoverEnabled(): Unit =
        Fixture(enabled = true, holder = "gateway", healthy = true).use { f ->
            f.connect()
            f.runtime.applySettings { it.copy(sipStack = SipStack.BUILTIN, sipTransport = "udp") }
            f.awaitNativeShutdown()
            val released = CompletableFuture<Unit>()
            released.completeExceptionally(IllegalStateException("socket cleanup failed"))
            doReturn(released).`when`(f.builtin.constructed().single()).stop(anyLong())
            f.runtime.applySettings { it.copy(username = "changed") }
            // Successful native teardown must not hide the built-in socket release failure.
            f.awaitNativeShutdown()
            verify(f.builtin.constructed().last(), never()).start()
            assertFalse(f.runtime.health.value.cellularReady)
            assertEquals("start failed: previous SIP socket release failed", f.runtime.health.value.sip.detail)
            assertTrue(f.runtime.settings.takeover)
        }

    private fun withStartupBlocked(block: (() -> Unit) -> Unit) {
        val startup = startupExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        startup.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            block { release.countDown(); startup.submit {}.get(3, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            startup.submit {}.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun sipWorkerFailureClearsReadinessAndPreservesTakeoverUntilExplicitReconnect(): Unit =
        Fixture(enabled = true, holder = "gateway", healthy = true).use { f ->
            f.connect()
            f.sipStatus(EndpointStatus(true, "registered"))
            assertTrue(f.runtime.health.value.cellularReady)
            val oldEndpoint = f.endpoints.constructed().single()
            val oldListener = f.sipListener!!

            val failure = EndpointStatus(false, "SIP worker failed: poll failed")
            f.sipStatus(failure)

            assertFalse(f.runtime.health.value.cellularReady)
            assertEquals(failure, f.runtime.health.value.sip)
            assertTrue(f.runtime.settings.takeover)
            assertTrue(f.runtime.health.value.holdsDialer)
            verify(f.runtime.cellular).setReadiness(false, "SIP unavailable: ${failure.detail}")
            assertEquals(1, f.endpoints.constructed().size)
            verify(f.helper, never()).restoreDefaultDialer(anyString())

            f.runtime.restartSip()
            f.drain()
            assertEquals(2, f.endpoints.constructed().size)
            f.sipStatus(EndpointStatus(true, "registered again"))
            oldListener.onStatusChanged(oldEndpoint, failure)
            f.drain()
            assertTrue(f.runtime.health.value.cellularReady)
        }

    @Test fun switchOnCapturesDialerBeforeHelperConnects() {
        for (initialHolder in listOf("phone", null)) {
            Fixture(holder = initialHolder).use { f ->
                f.runtime.setTakeover(true)
                f.drain()
                assertTrue(f.runtime.health.value.dialerChangePending)
                assertFalse(f.runtime.health.value.dialerChangeFailed)
                verify(f.helper, never()).becomeDefaultDialer(anyString())
                f.connect()
                verify(f.helper).becomeDefaultDialer(initialHolder.orEmpty())
                assertEquals("gateway", f.holder)
                assertFalse(f.runtime.health.value.dialerChangePending)
            }
        }
    }

    @Test fun cancelledAcquisitionIsNotReplayedOnHelperConnection(): Unit = Fixture().use { f ->
        f.runtime.setTakeover(true)
        f.drain()
        f.runtime.cancelDialerChange()
        f.drain()
        f.connect()
        verify(f.helper, never()).becomeDefaultDialer(anyString())
    }

    @Test fun changedDialerWhileReconnectingIsLeftInPlace() {
        for (initialHolder in listOf("phone", null)) {
            Fixture(holder = initialHolder).use { f ->
                f.runtime.setTakeover(true)
                f.drain()
                assertTrue(f.runtime.health.value.dialerChangePending)
                f.holder = "chosen.phone"
                f.connect()
                verify(f.helper, never()).becomeDefaultDialer(anyString())
                assertFalse(f.runtime.health.value.dialerChangePending)
                assertEquals("chosen.phone", f.holder)
            }
        }
    }

    @Test fun switchingOffCancelsAcquisitionBeforeHelperConnects(): Unit = Fixture().use { f ->
        f.runtime.setTakeover(true)
        f.drain()
        f.runtime.setTakeover(false)
        f.drain()
        f.connect()
        verify(f.helper, never()).becomeDefaultDialer(anyString())
        assertFalse(f.runtime.health.value.dialerChangePending)
    }

    @Test fun rejectedForegroundLaunchAndQueuedControlsCannotStartResources(): Unit =
        Fixture(enabled = true, rejectLaunch = true).use { f ->
            f.runtime.restartSip()
            f.runtime.setLoopbackTest(true)
            f.runtime.applySettings { it.copy(inboundTarget = "changed") }
            f.runtime.runSetup()
            f.drain()
            verify(f.context, atLeastOnce()).startForegroundService(any(Intent::class.java))
            verify(f.connector, never()).start()
            verify(f.network, never()).registerDefaultNetworkCallback(any(ConnectivityManager.NetworkCallback::class.java))
            assertTrue(f.endpoints.constructed().isEmpty())
            assertTrue(f.bridges.constructed().isEmpty())
            assertFalse(f.runtime.health.value.cellularReady)
        }

    @Test fun reminderOnlyPromotionDoesNotStartGateway(): Unit = Fixture(holder = "gateway").use { f ->
        f.promote()
        verify(f.connector, never()).start()
        assertTrue(f.endpoints.constructed().isEmpty())
        assertTrue(f.bridges.constructed().isEmpty())
    }

    @Test fun destructionBeforePromotionCancelsAcquisitionInsteadOfReplayingOnRestart(): Unit = Fixture().use { f ->
        f.runtime.setTakeover(true)
        f.runtime.onServiceCreated(f.serviceToken)
        f.drain()
        assertTrue(f.runtime.health.value.dialerChangePending)
        verify(f.connector, never()).start()
        assertTrue(f.endpoints.constructed().isEmpty())
        clearInvocations(f.context)

        // Promotion failed; destruction arrives without onForegroundStarted.
        f.runtime.onForegroundStopped(f.serviceToken)
        f.drain()
        assertFalse(f.runtime.health.value.dialerChangePending)
        verify(f.context, never()).startForegroundService(any(Intent::class.java))

        f.serviceToken = Any()
        f.connect()
        verify(f.helper, never()).becomeDefaultDialer(anyString())
        assertEquals("phone", f.holder)
    }

    @Test fun destructionContinuesCleanupAfterFailureWithoutRoleChangesOrAutomaticRestart(): Unit =
        Fixture(enabled = true).use { f ->
            f.connect()
            val endpoint = f.endpoints.constructed().single()
            val bridge = f.bridges.constructed().single()
            verify(endpoint).start()
            doThrow(IllegalStateException("bridge cleanup failed")).`when`(bridge).stop()
            clearInvocations(f.context)
            f.runtime.onForegroundStopped(f.serviceToken)
            f.runtime.onForegroundStopped(f.serviceToken)
            f.runtime.refreshCallAccess()
            f.runtime.restartSip()
            f.drain()
            verify(bridge).stop()
            verify(endpoint).stop(releaseTransport = true)
            verify(f.network).unregisterNetworkCallback(any(ConnectivityManager.NetworkCallback::class.java))
            verify(f.connector).stop()
            verify(f.helper, never()).becomeDefaultDialer(anyString())
            verify(f.helper, never()).restoreDefaultDialer(anyString())
            verify(f.context, never()).startForegroundService(any(Intent::class.java))
            assertEquals(1, f.endpoints.constructed().size)
            assertFalse(f.runtime.health.value.cellularReady)
            assertTrue(f.runtime.settings.takeover)
        }

    @Test fun destructionCancelsQueuedRestorationWithoutChangingTheRoleWhenCallsEnd(): Unit =
        Fixture(enabled = true, holder = "gateway").use { f ->
            f.connect()
            `when`(f.runtime.cellular.hasCalls).thenReturn(true)
            f.runtime.setTakeover(false)
            f.drain()
            assertTrue(f.runtime.health.value.takeoverOffPending)
            f.runtime.onForegroundStopped(f.serviceToken)
            f.drain()
            `when`(f.runtime.cellular.hasCalls).thenReturn(false)
            f.callsChanged(CallSnapshot())
            f.drain()
            verify(f.helper, never()).restoreDefaultDialer(anyString())
            assertFalse(f.runtime.health.value.takeover)
            assertTrue(f.runtime.health.value.holdsDialer)
            assertEquals(1, f.endpoints.constructed().size)
        }

    @Test fun oldServiceCannotStopReplacementAndExplicitRestartRecreatesResources(): Unit =
        Fixture(enabled = true).use { f ->
            f.connect()
            val old = f.serviceToken
            f.serviceToken = Any()
            f.runtime.onServiceCreated(f.serviceToken)
            f.runtime.onForegroundStopped(old)
            f.drain()
            verify(f.endpoints.constructed().single(), never()).stop(anyLong(), anyBoolean())
            f.promote()
            f.runtime.onForegroundStopped(f.serviceToken)
            f.drain()
            f.promote()
            assertEquals(2, f.endpoints.constructed().size)
            verify(f.endpoints.constructed().last()).start()
            verify(f.connector, times(2)).start()
        }

    @Test fun switchOffDuringCallKeepsResourcesUntilCallEnds(): Unit = Fixture(enabled = true).use { f ->
        f.connect()
        `when`(f.runtime.cellular.hasCalls).thenReturn(true)
        f.runtime.setTakeover(false)
        f.drain()
        assertTrue(f.runtime.health.value.takeoverOffPending)
        verify(f.endpoints.constructed().single(), never()).stop(anyLong(), anyBoolean())
        `when`(f.runtime.cellular.hasCalls).thenReturn(false)
        f.callsChanged(CallSnapshot())
        f.drain()
        verify(f.endpoints.constructed().single()).stop(releaseTransport = true)
        verify(f.bridges.constructed().single()).stop()
        assertFalse(f.runtime.health.value.takeover)
        assertFalse(f.runtime.health.value.takeoverOffPending)
    }

    @Test fun settingsQueuedDuringCallAccumulateAndApplyTogether(): Unit = Fixture(enabled = true).use { f ->
        f.connect()
        `when`(f.runtime.cellular.hasCalls).thenReturn(true)
        f.runtime.applySettings { it.copy(audioMethod = AudioMethod.LEGACY) }
        f.runtime.applySettings { it.copy(callAccess = CallAccess.AUTO) }
        f.runtime.applySettings { it.copy(inboundTarget = "queued-target") }
        f.drain()
        assertTrue(f.runtime.health.value.settingsPending)
        assertEquals(AudioMethod.AUTO, f.runtime.settings.audioMethod)
        assertEquals(CallAccess.DIALER, f.runtime.settings.callAccess)
        assertEquals("", f.runtime.settings.inboundTarget)
        assertEquals(AudioMethod.LEGACY, f.runtime.pendingSettingsFlow.value?.audioMethod)
        assertEquals(CallAccess.AUTO, f.runtime.pendingSettingsFlow.value?.callAccess)
        assertEquals("queued-target", f.runtime.pendingSettingsFlow.value?.inboundTarget)
        verify(f.endpoints.constructed().single(), never()).stop(anyLong(), anyBoolean())

        `when`(f.runtime.cellular.hasCalls).thenReturn(false)
        f.callsChanged(CallSnapshot())
        f.drain()
        assertFalse(f.runtime.health.value.settingsPending)
        assertNull(f.runtime.pendingSettingsFlow.value)
        assertEquals(AudioMethod.LEGACY, f.runtime.settings.audioMethod)
        assertEquals(CallAccess.AUTO, f.runtime.settings.callAccess)
        assertEquals("queued-target", f.runtime.settings.inboundTarget)
    }

    @Test fun editAfterCallEndsConsumesOlderQueueBeforeCallCallback(): Unit = Fixture(enabled = true).use { f ->
        f.connect()
        `when`(f.runtime.cellular.hasCalls).thenReturn(true)
        f.runtime.applySettings { it.copy(audioMethod = AudioMethod.LEGACY) }
        f.drain()

        // The UI can submit another edit before the queued onCallsChanged callback is processed.
        `when`(f.runtime.cellular.hasCalls).thenReturn(false)
        f.runtime.applySettings { it.copy(callAccess = CallAccess.AUTO) }
        f.drain()
        assertNull(f.runtime.pendingSettingsFlow.value)
        assertEquals(AudioMethod.LEGACY, f.runtime.settings.audioMethod)
        assertEquals(CallAccess.AUTO, f.runtime.settings.callAccess)
        f.callsChanged(CallSnapshot())
        f.drain()
        assertFalse(f.runtime.health.value.settingsPending)
        assertEquals(AudioMethod.LEGACY, f.runtime.settings.audioMethod)
        assertEquals(CallAccess.AUTO, f.runtime.settings.callAccess)
    }

    @Test fun laterImmediatePreferencesAreNotRevertedByQueuedSettings(): Unit = Fixture(enabled = true).use { f ->
        f.connect()
        `when`(f.runtime.cellular.hasCalls).thenReturn(true)
        f.runtime.applySettings { it.copy(inboundTarget = "queued-target") }
        f.runtime.setMuteEarpiece(false)
        f.runtime.setSipDebugLog(true)
        f.runtime.setKeepDefaultDialer(true)
        f.drain()
        assertFalse(f.runtime.settings.muteEarpiece)
        assertTrue(f.runtime.settings.sipDebugLog)
        assertTrue(f.runtime.settings.keepDefaultDialer)
        assertEquals("", f.runtime.settings.inboundTarget)

        `when`(f.runtime.cellular.hasCalls).thenReturn(false)
        f.callsChanged(CallSnapshot())
        f.drain()
        assertEquals("queued-target", f.runtime.settings.inboundTarget)
        assertFalse(f.runtime.settings.muteEarpiece)
        assertTrue(f.runtime.settings.sipDebugLog)
        assertTrue(f.runtime.settings.keepDefaultDialer)
    }

    @Test fun queuedSettingsDoNotUndoSwitchOffAtCallEnd(): Unit = Fixture(enabled = true, holder = "gateway").use { f ->
        f.connect()
        f.runtime.setKeepDefaultDialer(true)
        f.drain()
        `when`(f.runtime.cellular.hasCalls).thenReturn(true)
        f.runtime.applySettings { it.copy(inboundTarget = "queued-target") }
        f.runtime.setTakeover(false)
        f.drain()
        assertTrue(f.runtime.health.value.takeoverOffPending)

        `when`(f.runtime.cellular.hasCalls).thenReturn(false)
        f.callsChanged(CallSnapshot())
        f.drain()
        assertFalse(f.runtime.settings.takeover)
        assertFalse(f.runtime.health.value.takeover)
        assertEquals("queued-target", f.runtime.settings.inboundTarget)
        assertEquals(1, f.endpoints.constructed().size)
    }

    @Test fun sipStaysUnregisteredUntilCellularReady(): Unit = Fixture(enabled = true, holdSip = true).use { f ->
        f.connect()
        // The fixture's helper never reports call access or audio, so the cellular side is not ready.
        assertTrue(f.endpoints.constructed().isEmpty())
        assertTrue(f.bridges.constructed().isEmpty())
        val sip = f.runtime.health.value.sip
        assertFalse(sip.ready)
        assertTrue(sip.detail, sip.detail.startsWith("held unregistered: "))
        assertFalse(f.runtime.health.value.cellularReady)
    }

    @Test fun disablingHoldRegistersOnlyAfterCallsEnd(): Unit = Fixture(enabled = true, holdSip = true).use { f ->
        f.connect()
        `when`(f.runtime.cellular.hasCalls).thenReturn(true)
        f.runtime.setHoldSipWhenNotReady(false)
        f.drain()
        assertFalse(f.runtime.settings.holdSipWhenNotReady)
        assertTrue(f.endpoints.constructed().isEmpty())

        `when`(f.runtime.cellular.hasCalls).thenReturn(false)
        f.callsChanged(CallSnapshot())
        f.drain()
        verify(f.endpoints.constructed().single()).start()
        assertEquals(1, f.bridges.constructed().size)

        // Enabling it again while still not ready unregisters at once: there was no ready period.
        f.runtime.setHoldSipWhenNotReady(true)
        f.drain()
        verify(f.endpoints.constructed().single()).stop(anyLong(), eq(false))
        assertEquals(1, f.endpoints.constructed().size)
        assertTrue(f.runtime.health.value.sip.detail.startsWith("held unregistered: "))
    }

    @Test fun healthCheckFailureClosesAdmissionAndRecoversWithoutRestartingSip(): Unit =
        Fixture(enabled = true, holder = "gateway", holdSip = true, healthy = true).use { f ->
            f.connect()
            f.sipStatus(EndpointStatus(true, "registered"))
            assertTrue(f.runtime.health.value.cellularReady)
            assertNull(f.dialPolicy.check("12345"))
            `when`(f.runtime.cellular.isEmergencyNumber("112")).thenReturn(true)
            assertEquals(HangupCause.FORBIDDEN, f.dialPolicy.check("112"))

            `when`(f.helper.health(anyString())).thenThrow(IllegalStateException("helper failed"))
            f.checkHealth()
            val failed = f.runtime.health.value
            assertNull(failed.privileged)
            assertFalse(failed.cellularReady)
            assertTrue(failed.privilegedDetail.contains("helper failed"))
            assertTrue(f.runtime.settings.takeover)
            assertTrue(failed.sip.ready)
            assertEquals(HangupCause.UNAVAILABLE, f.dialPolicy.check("12345"))
            verify(f.endpoints.constructed().single(), never()).stop(anyLong(), anyBoolean())

            doReturn(f.helperHealth).`when`(f.helper).health(anyString())
            f.checkHealth()
            assertTrue(f.runtime.health.value.cellularReady)
            assertNull(f.dialPolicy.check("12345"))
            assertEquals(1, f.endpoints.constructed().size)
        }

    private class Fixture(enabled: Boolean = false, var holder: String? = "phone", rejectLaunch: Boolean = false,
        holdSip: Boolean = false, healthy: Boolean = false) : AutoCloseable {
        private val scopes = mutableListOf<AutoCloseable>()
        private val workerScopes = mutableListOf<AutoCloseable>()
        lateinit var connector: ShizukuConnector
            private set
        lateinit var endpoints: MockedConstruction<PjsipEndpoint>
            private set
        lateinit var builtin: MockedConstruction<SipUserAgent>
            private set
        lateinit var bridges: MockedConstruction<CallBridge>
            private set
        lateinit var callsChanged: (CallSnapshot) -> Unit
            private set
        private var listener: ShizukuConnector.Listener? = null
        private var helperState = ShizukuConnector.State.STOPPED
        var sipListener: EndpointStatusListener? = null
            private set
        lateinit var dialPolicy: DialPolicy
            private set
        val context = mock(Context::class.java)
        val network = mock(ConnectivityManager::class.java)
        val helper = mock(IPrivilegedService::class.java)
        val helperHealth = mock(Bundle::class.java)
        val runtime: GatewayRuntime
        private val worker: ScheduledThreadPoolExecutor
        var serviceToken = Any()

        init {
            val telecom = mock(TelecomManager::class.java)
            val role = mock(RoleManager::class.java)
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.packageName).thenReturn("gateway")
            val pm = mock(PackageManager::class.java)
            `when`(context.packageManager).thenReturn(pm)
            val appInfo = ApplicationInfo().apply { this.enabled = true }
            `when`(pm.getActivityInfo(any(ComponentName::class.java), anyInt())).thenReturn(ActivityInfo().apply {
                this.enabled = true; applicationInfo = appInfo
            })
            `when`(pm.getServiceInfo(any(ComponentName::class.java), anyInt())).thenReturn(ServiceInfo().apply {
                this.enabled = true; applicationInfo = appInfo; permission = Manifest.permission.BIND_INCALL_SERVICE
            })
            `when`(context.getSystemService(TelecomManager::class.java)).thenReturn(telecom)
            `when`(context.getSystemService(RoleManager::class.java)).thenReturn(role)
            `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(network)
            `when`(telecom.defaultDialerPackage).thenAnswer { holder }
            `when`(role.isRoleAvailable(RoleManager.ROLE_DIALER)).thenReturn(true)
            if (rejectLaunch) doThrow(IllegalStateException("background launch denied")).`when`(context)
                .startForegroundService(any(Intent::class.java))
            `when`(helper.dialerState()).thenAnswer { result(DialerRoleResult.STATE) }
            `when`(helper.becomeDefaultDialer(anyString())).thenAnswer {
                holder = "gateway"
                result(DialerRoleResult.SUCCESS)
            }
            `when`(helper.restoreDefaultDialer(anyString())).thenAnswer {
                holder = it.getArgument<String>(0).ifEmpty { "phone" }
                result(DialerRoleResult.SUCCESS)
            }
            for (key in HealthKeys.BOOLEAN_KEYS) `when`(helperHealth.getBoolean(key)).thenReturn(healthy)
            `when`(helper.setupCallAccess(anyString(), anyBoolean(), anyInt())).thenReturn(helperHealth)
            scopes += mockConstruction(SettingsStore::class.java) { store, _ ->
                `when`(store.load()).thenReturn(GatewaySettings(takeover = enabled, callAccess = CallAccess.DIALER,
                    server = "pbx.invalid", username = "test", password = "test", holdSipWhenNotReady = holdSip))
            }
            scopes += mockConstruction(DialerHandoffStore::class.java) { store, _ ->
                `when`(store.save(any(PreviousDialer::class.java) ?: PreviousDialer(0, ""))).thenReturn(true)
            }
            scopes += mockConstruction(CellularEndpoint::class.java) { endpoint, _ ->
                doAnswer { callsChanged = it.getArgument(0); null }.`when`(endpoint).onCallsChanged = any()
                doReturn(mock(SimSlots::class.java)).`when`(endpoint).sims
            }
            scopes += mockConstruction(ShizukuConnector::class.java) { mock, _ ->
                connector = mock
                `when`(mock.state).thenAnswer { helperState }
                `when`(mock.service).thenAnswer { if (helperState == ShizukuConnector.State.CONNECTED) helper else null }
                `when`(mock.detail).thenReturn("")
                doAnswer { listener = it.getArgument(0); null }.`when`(mock).addListener(
                    any(ShizukuConnector.Listener::class.java) ?: ShizukuConnector.Listener { _, _ -> })
                doAnswer { transition(ShizukuConnector.State.BINDING); null }.`when`(mock).start()
                doAnswer { transition(ShizukuConnector.State.STOPPED); null }.`when`(mock).stop()
            }
            val constructor = GatewayRuntime::class.java.getDeclaredConstructor(Context::class.java)
            constructor.isAccessible = true
            runtime = constructor.newInstance(context)
            val field = GatewayRuntime::class.java.getDeclaredField("worker")
            field.isAccessible = true
            worker = field.get(runtime) as ScheduledThreadPoolExecutor
            // Mockito scopes belong to this thread; idle timeout is tested separately.
            worker.setKeepAliveTime(1, TimeUnit.DAYS)
            worker.submit {
                workerScopes += mockStatic(Log::class.java)
                workerScopes += mockStatic(Process::class.java)
                workerScopes += mockStatic(SystemClock::class.java)
                workerScopes += mockConstruction(ComponentName::class.java)
                workerScopes += mockConstruction(Intent::class.java)
                workerScopes += mockConstruction(ConnectivityManager.NetworkCallback::class.java)
                endpoints = mockConstruction(PjsipEndpoint::class.java) { endpoint, construction ->
                    `when`(endpoint.config).thenReturn(construction.arguments()[0] as PjsipConfig)
                    `when`(endpoint.status).thenReturn(EndpointStatus(false, "test endpoint"))
                    doAnswer { sipListener = it.getArgument(0); null }.`when`(endpoint).addStatusListener(
                        any(EndpointStatusListener::class.java) ?: EndpointStatusListener { _, _ -> })
                }.also { workerScopes += it }
                builtin = mockConstruction(SipUserAgent::class.java) { endpoint, construction ->
                    `when`(endpoint.config).thenReturn(construction.arguments()[0] as SipConfig)
                    `when`(endpoint.status).thenReturn(EndpointStatus(false, "test endpoint"))
                    `when`(endpoint.stop(anyLong())).thenReturn(CompletableFuture.completedFuture(Unit))
                }.also { workerScopes += it }
                bridges = mockConstruction(CallBridge::class.java) { _, construction ->
                    dialPolicy = construction.arguments()[5] as DialPolicy
                }.also { workerScopes += it }
            }.get(3, TimeUnit.SECONDS)
            runtime.start()
            drain()
        }

        private fun result(status: String): Bundle = mock(Bundle::class.java).also { bundle ->
            `when`(bundle.getString("status")).thenReturn(status)
            `when`(bundle.getString("holder")).thenReturn(holder)
            `when`(bundle.containsKey("user_id")).thenReturn(true)
            `when`(bundle.containsKey("in_call")).thenReturn(true)
        }
        private fun transition(state: ShizukuConnector.State) {
            helperState = state
            listener?.onChanged(state, connector.service)
        }
        fun promote() {
            runtime.onServiceCreated(serviceToken)
            runtime.onForegroundStarted(serviceToken)
            drain()
        }
        fun connect() {
            promote()
            transition(ShizukuConnector.State.CONNECTED)
            drain()
        }
        fun sipStatus(status: EndpointStatus) {
            val endpoint = endpoints.constructed().last()
            `when`(endpoint.status).thenReturn(status)
            sipListener!!.onStatusChanged(endpoint, status)
            drain()
        }
        fun checkHealth() {
            worker.submit {
                GatewayRuntime::class.java.getDeclaredMethod("periodicCheck").apply { isAccessible = true }.invoke(runtime)
            }.get(3, TimeUnit.SECONDS)
            drain()
        }
        fun awaitNativeShutdown() {
            drain()
            startupExecutor().submit {}.get(3, TimeUnit.SECONDS)
            drain()
        }
        fun drain() {
            do {
                val pending = worker.submit<Boolean> {
                    worker.queue.any { it !is ScheduledFuture<*> || it.getDelay(TimeUnit.NANOSECONDS) <= 0 }
                }.get(3, TimeUnit.SECONDS)
            } while (pending)
        }
        override fun close() {
            runtime.onForegroundStopped(serviceToken)
            drain()
            worker.submit { workerScopes.asReversed().forEach { it.close() } }.get(3, TimeUnit.SECONDS)
            worker.shutdownNow()
            scopes.asReversed().forEach { it.close() }
        }
    }

    companion object {
        private fun startupExecutor() = PjsipEndpoint::class.java.getDeclaredField("startup")
            .apply { isAccessible = true }.get(null) as ThreadPoolExecutor
    }
}
