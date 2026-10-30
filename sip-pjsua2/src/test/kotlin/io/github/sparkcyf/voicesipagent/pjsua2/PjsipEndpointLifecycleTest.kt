package io.github.sparkcyf.voicesipagent.pjsua2

import io.github.sparkcyf.voicesipagent.voice.AbstractCallLeg
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PjsipEndpointLifecycleTest {
    @Test fun libraryReleaseCompletionRemainsPendingAfterTheStopWaitTimesOut() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val warnings = CopyOnWriteArrayList<String>()
        val worker = PjsuaWorker({}, { Thread.sleep(1) }, {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }, { throw AssertionError(it) })
        val libraryWorker = PjsuaLib::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(PjsuaLib) as PjsuaWorker
        val session = PjsuaWorker::class.java.getDeclaredField("session").apply { isAccessible = true }
        val original = session.get(libraryWorker)
        val oldLog = PjsuaLib.log
        val startup = PjsipEndpoint::class.java.getDeclaredField("startup").apply { isAccessible = true }
            .get(null) as ThreadPoolExecutor
        try {
            worker.start()
            session.set(libraryWorker, session.get(worker))
            PjsuaLib.log = object : PjsipLog {
                override fun info(msg: String) {}
                override fun warn(msg: String, t: Throwable?) { warnings += msg }
            }
            val stopped = PjsipEndpoint.stopLibrary()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // The startup queue resumes after the existing 5 s stop wait, without retrying release.
            startup.submit {}.get(6, TimeUnit.SECONDS)
            assertTrue(warnings.contains("SIP library shutdown failed"))
            assertFalse(stopped.isDone)
            release.countDown()
            stopped.get(3, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            try { worker.stop() } finally {
                session.set(libraryWorker, original)
                PjsuaLib.log = oldLog
            }
        }
    }

    @Test fun timedOutCleanupContinuesAndOneFailedHangupDoesNotSkipTheNextCall() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val warnings = CopyOnWriteArrayList<String>()
        val worker = PjsuaWorker({}, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Thread.sleep(1)
        }, {}, { throw AssertionError(it) })
        // Use the real queue/stop protocol without loading the device-only JNI library.
        val field = PjsuaLib::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val libraryWorker = field.get(PjsuaLib) as PjsuaWorker
        val session = PjsuaWorker::class.java.getDeclaredField("session").apply { isAccessible = true }
        val original = session.get(libraryWorker)
        val endpoint = PjsipEndpoint(PjsipConfig(server = "pbx.invalid", username = "test", password = "test"),
            object : PjsipLog {
                override fun info(msg: String) {}
                override fun warn(msg: String, t: Throwable?) { warnings += msg }
            })
        val first = PjsipCall(endpoint, LegDirection.OUTBOUND, Party("test"), "test", null, "p1")
        val second = PjsipCall(endpoint, LegDirection.OUTBOUND, Party("test"), "test", null, "p2")
        first.addListener(object : CallLeg.Listener {
            override fun onStateChanged(leg: CallLeg, state: LegState) { error("listener failed") }
        })
        endpoint.calls[1] = first
        endpoint.calls[2] = second
        setStatus(endpoint, EndpointStatus(true, "registered"))
        endpoint.addStatusListener { _, _ -> error("status listener failed") }
        try {
            worker.start()
            session.set(libraryWorker, session.get(worker))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            endpoint.stop(timeoutMs = 10, releaseTransport = false)
            assertTrue(warnings.any { it.contains("cleanup wait timed out") })
            assertEquals(LegState.NEW, second.state)
            val cleaned = CountDownLatch(1)
            assertTrue(worker.post { cleaned.countDown() })
            release.countDown()
            assertTrue(cleaned.await(2, TimeUnit.SECONDS))
            assertEquals(LegState.ENDED, first.state)
            assertEquals(LegState.ENDED, second.state)
            assertTrue(warnings.contains("call hangup failed"))
            assertTrue(warnings.contains("SIP stopped status notification failed"))
        } finally {
            release.countDown()
            try { worker.stop() } finally { session.set(libraryWorker, original) }
        }
    }

    @Test fun workerFailureClearsReadinessAndEndsCallsWithoutWaitingForNativeCallbacks() {
        val endpoint = endpoint()
        val statuses = mutableListOf<EndpointStatus>()
        endpoint.addStatusListener { _, status -> statuses += status }
        setStatus(endpoint, EndpointStatus(true, "registered"))
        val leg = PjsipCall(endpoint, LegDirection.OUTBOUND, Party("test"), "test", null, "p1")
        // Exercise a connected local leg without loading the device-only JNI library on the host.
        AbstractCallLeg::class.java.getDeclaredMethod("transition", LegState::class.java, HangupCause::class.java)
            .apply { isAccessible = true }.invoke(leg, LegState.CONNECTED, null)
        endpoint.calls[1] = leg

        endpoint.onWorkerFailure(IllegalStateException("poll failed"))
        assertEquals(EndpointStatus(false, "SIP worker failed: poll failed"), endpoint.status)
        assertEquals(LegState.ENDED, leg.state)
        assertEquals(HangupCause.NETWORK_ERROR, leg.endCause)
        assertTrue(endpoint.calls.isEmpty())

        // Duplicate failure delivery and late registration callbacks cannot revive the endpoint.
        endpoint.onWorkerFailure(IllegalStateException("duplicate"))
        setStatus(endpoint, EndpointStatus(true, "late registration"))
        endpoint.start()
        assertEquals(2, statuses.size)
        assertFalse(endpoint.status.ready)
        assertEquals(LegState.ENDED, endpoint.dial("test", Party("test")).state)
    }

    private fun endpoint() = PjsipEndpoint(PjsipConfig(server = "pbx.invalid", username = "test", password = "test"))

    private fun setStatus(endpoint: PjsipEndpoint, status: EndpointStatus) {
        PjsipEndpoint::class.java.getDeclaredMethod("setStatus", EndpointStatus::class.java)
            .apply { isAccessible = true }.invoke(endpoint, status)
    }

    @Test fun stoppingQueuedStartupSkipsNativeInitialization() {
        val startup = PjsipEndpoint::class.java.getDeclaredField("startup").apply { isAccessible = true }
            .get(null) as ThreadPoolExecutor
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val warnings = CopyOnWriteArrayList<String>()
        val endpoint = PjsipEndpoint(
            PjsipConfig(server = "pbx.invalid", username = "test", password = "test"),
            object : PjsipLog {
                override fun info(msg: String) {}
                override fun warn(msg: String, t: Throwable?) { warnings += msg }
            },
        )
        // Hold the startup queue so cancellation happens before any JNI or DNS work.
        startup.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(PjsuaLib.running)
            endpoint.start()
            assertEquals("registering", endpoint.status.detail)
            endpoint.stop()
            release.countDown()
            startup.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals("stopped", endpoint.status.detail)
            assertFalse(endpoint.status.ready)
            assertFalse(PjsuaLib.running)
            assertTrue(warnings.toString(), warnings.isEmpty())
        } finally {
            release.countDown()
            endpoint.stop(timeoutMs = 0)
            PjsipEndpoint.stopLibrary()
            startup.submit {}.get(3, TimeUnit.SECONDS)
        }
    }
}
