package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.EndpointStatus
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SipRegistrationTest {
    @Test fun explicitRefreshPreservesReadinessThroughAuthentication() {
        for (challenge in listOf(401, 407)) Registrar().use { r ->
            r.startRegistered()

            r.ua.refreshRegistration()
            val refresh = r.receiveRegistration()
            r.assertAlwaysReady()
            r.reply(refresh, challenge) {
                headers.set(if (challenge == 401) "WWW-Authenticate" else "Proxy-Authenticate",
                    "Digest realm=\"test\", nonce=\"renewal\", algorithm=MD5, qop=\"auth\"")
            }
            val authenticated = r.receiveRegistration(refresh.request.cseq!!.seq)
            assertNotNull(authenticated.request.headers[if (challenge == 401) "Authorization" else "Proxy-Authorization"])
            // Leave the successful response pending for another round trip.
            r.roundTrip()
            r.assertAlwaysReady()
            r.acceptRegistration(authenticated, 60)

            r.assertAlwaysReady()
        }
    }

    @Test fun scheduledRefreshPreservesReadinessAndReplacesOldExpiry() = Registrar().use { r ->
        r.startRegistered(expires = 10)
        val refresh = r.receiveRegistration()
        assertTrue("Scheduled renewal must retain readiness", r.ua.status.ready)
        r.assertAlwaysReady()
        r.acceptRegistration(refresh, 60)
        // The original 10 s binding would have expired during this interval.
        r.after(2000)
        r.assertAlwaysReady()
    }

    @Test fun pendingRefreshExpiresBeforeTransactionTimeoutAndLateSuccessRecovers() = Registrar().use { r ->
        r.startRegistered(expires = 1)
        val refresh = r.receiveRegistration()
        assertTrue(r.awaitStatus { it.detail == "refreshing registration" }.ready)
        // A UDP transaction can wait 32 s; that must not prolong a 1 s binding.
        r.awaitStatus { !it.ready && it.detail == "registration expired" }
        assertFalse(r.ua.status.ready)
        r.acceptRegistration(refresh, 60)
        assertTrue(r.ua.status.ready)
    }

    @Test fun failedRefreshWithdrawsReadinessAndExplicitRefreshRecovers() = Registrar().use { r ->
        r.startRegistered()
        r.ua.refreshRegistration()
        r.reply(r.receiveRegistration(), 403)
        r.awaitStatus { !it.ready && it.detail.startsWith("registration failed: 403") }
        assertFalse(r.ua.status.ready)
        r.ua.refreshRegistration()
        r.acceptRegistration(r.receiveRegistration(), 60)
        assertTrue(r.ua.status.ready)
    }

    @Test fun obsoleteFailureCannotUndoNewerSuccess() = Registrar().use { r ->
        r.startRegistered()
        r.ua.refreshRegistration()
        val obsolete = r.receiveRegistration()
        r.ua.refreshRegistration()
        val current = r.receiveRegistration(obsolete.request.cseq!!.seq)
        r.acceptRegistration(current, 60)
        r.reply(obsolete, 403)
        r.roundTrip()
        r.assertAlwaysReady()
    }

    @Test fun obsoleteSuccessCannotUndoNewerFailure() = Registrar().use { r ->
        r.startRegistered()
        r.ua.refreshRegistration()
        val obsolete = r.receiveRegistration()
        r.ua.refreshRegistration()
        val current = r.receiveRegistration(obsolete.request.cseq!!.seq)
        r.reply(current, 403)
        val failed = r.awaitStatus { !it.ready && it.detail.startsWith("registration failed: 403") }
        r.reply(obsolete, 200) { headers.set("Expires", "60") }
        r.roundTrip()
        assertEquals(failed, r.ua.status)
    }

    /** Exercises the production UA over loopback UDP, with no PBX or Android audio dependency. */
    private class Registrar : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        private val updates = LinkedBlockingQueue<EndpointStatus>()
        val statuses = CopyOnWriteArrayList<EndpointStatus>()
        val ua = SipUserAgent(SipConfig(
            SipAccount("127.0.0.1", socket.localPort, "test", "unused"),
            localPort = 0,
        ), object : SipLog {
            override fun info(msg: String) {}
            override fun warn(msg: String, t: Throwable?) {}
        })
        private var destination: SocketAddress? = null
        private var barrierId = 0

        init {
            ua.addStatusListener { _, status -> statuses += status; updates.offer(status) }
        }

        fun startRegistered(expires: Int = 60) {
            ua.start()
            val registration = receiveRegistration()
            assertFalse("Initial registration must remain unavailable", ua.status.ready)
            acceptRegistration(registration, expires)
            assertTrue(statuses.any { !it.ready && it.detail == "registering" })
            statuses.clear()
        }

        fun acceptRegistration(received: Received, expires: Int) {
            reply(received, 200) { headers.set("Expires", expires.toString()) }
            awaitStatus { it.ready && it.detail == "registered ($expires s)" }
        }

        fun receiveRegistration(afterCseq: Long = 0): Received {
            val received = receive { it is SipRequest && it.method == "REGISTER" && it.cseq!!.seq > afterCseq }
            return Received(received.first as SipRequest, received.second)
        }

        private fun receive(predicate: (SipMessage) -> Boolean): Pair<SipMessage, SocketAddress> {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (true) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                check(remainingMs > 0) { "Timed out waiting for SIP message" }
                socket.soTimeout = remainingMs.toInt()
                val packet = DatagramPacket(ByteArray(8192), 8192)
                socket.receive(packet)
                destination = packet.socketAddress
                val message = SipParser.parse(packet.data, packet.length) ?: continue
                if (predicate(message)) return message to packet.socketAddress
            }
        }

        fun reply(received: Received, code: Int, configure: SipResponse.() -> Unit = {}) {
            send(ua.buildResponse(received.request, code).apply(configure), received.source)
        }

        private fun send(message: SipMessage, to: SocketAddress) {
            val bytes = message.encode()
            socket.send(DatagramPacket(bytes, bytes.size, to))
        }

        /** An OPTIONS reply confirms the UA processed preceding datagrams from this socket. */
        fun roundTrip() {
            val id = "barrier-${++barrierId}"
            val headers = SipHeaders().apply {
                add("Via", "SIP/2.0/UDP 127.0.0.1:${socket.localPort};branch=z9hG4bK$id")
                add("From", "<sip:peer@127.0.0.1>;tag=peer")
                add("To", "<${ua.aor}>")
                add("Call-ID", id)
                add("CSeq", "1 OPTIONS")
            }
            send(SipRequest("OPTIONS", ua.aor, headers), destination!!)
            receive { it is SipResponse && it.callId == id && it.code == 200 }
        }

        fun awaitStatus(predicate: (EndpointStatus) -> Boolean): EndpointStatus {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (true) {
                val next = updates.poll(maxOf(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS)
                    ?: error("Timed out waiting for registration status; last=${ua.status}")
                if (predicate(next)) return next
            }
        }

        fun assertAlwaysReady() {
            assertTrue("Registration should remain ready: ${ua.status}", ua.status.ready)
            assertTrue("Readiness was withdrawn: $statuses", statuses.all { it.ready })
        }

        fun after(delayMs: Long) {
            val done = CountDownLatch(1)
            ua.schedule(delayMs) { done.countDown() }
            assertTrue(done.await(delayMs + 2000, TimeUnit.MILLISECONDS))
        }

        override fun close() {
            try { ua.stop(timeoutMs = 20).get(2, TimeUnit.SECONDS) }
            finally { socket.close() }
        }
    }

    private data class Received(val request: SipRequest, val source: SocketAddress)
}
