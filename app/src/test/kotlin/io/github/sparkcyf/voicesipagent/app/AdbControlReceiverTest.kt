package io.github.sparkcyf.voicesipagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class AdbControlReceiverTest {
    @Test fun provisionCapturesValidatedValuesAndMergesWithLatestSettings(): Unit = Fixture().use { f ->
        var update: ((GatewaySettings) -> GatewaySettings)? = null
        doAnswer { update = it.getArgument(0); null }.`when`(f.runtime).applySettings(any() ?: { it })
        val args = mutableMapOf<String, Any>("op" to "provision", "server" to "new.invalid",
            "password" to "test-secret", "target" to "", "transport" to "TLS", "srtp" to "MANDATORY",
            "port" to 5061, "audio_method" to 2, "call_access" to 1, "hold_sip" to false, "tls_verify" to false)
        f.receive(args)
        verify(f.pending, never()).finish()

        // Later Intent changes must not bypass validation or replace the captured edit.
        args["audio_method"] = 99
        args["password"] = "changed-after-dispatch"
        val current = GatewaySettings(username = "latest-user", domain = "latest.invalid",
            inboundTarget = "old-target", allowedPrefixes = "123", sipDebugLog = true)
        assertEquals(current.copy(server = "new.invalid", password = "test-secret", inboundTarget = "",
            sipTransport = "tls", srtp = "mandatory", port = 5061, audioMethod = 2, callAccess = 1,
            holdSipWhenNotReady = false, tlsVerify = false), update!!(current))

        f.drain()
        verify(f.pending).setResult(1, "ok: settings snapshot", null)
        verify(f.pending).finish()
        assertFalse(AppLog.tail.value.last().contains("test-secret"))
    }

    @Test fun invalidCommandsRejectBeforeRuntimeMutationWithoutEchoingValues(): Unit = Fixture().use { f ->
        val invalidProvisionArgs = listOf(
            mapOf("port" to 0), mapOf("port" to 65536), mapOf("port" to "5060"),
            mapOf("transport" to "test-secret"), mapOf("srtp" to "test-secret"),
            mapOf("audio_method" to -1), mapOf("audio_method" to 3),
            mapOf("call_access" to -1), mapOf("call_access" to 3),
            mapOf("password" to 123),
            mapOf("hold_sip" to "false"), mapOf("passwrod" to "test-secret"),
            mapOf("tls_verify" to "false"), mapOf("tls_ca" to 1), mapOf("tls_ca" to "test-secret"),
        )
        val invalid = invalidProvisionArgs.map {
            mapOf("op" to "provision", "password" to "test-secret") + it
        } + listOf(emptyMap(), mapOf("op" to "test-secret"), mapOf("op" to 123),
            mapOf("op" to "sip-stack"), mapOf("op" to "sip-stack", "stack" to "test-secret"),
            mapOf("op" to "takeover", "on" to "false"), mapOf("op" to "setup", "on" to true))
        for (args in invalid) {
            clearInvocations(f.pending)
            f.receive(args)
            val data = org.mockito.ArgumentCaptor.forClass(String::class.java)
            verify(f.pending).setResult(eq(2), data.capture(), isNull())
            assertTrue(data.value.startsWith("error: "))
            assertFalse(data.value.contains("test-secret"))
            assertFalse(AppLog.tail.value.last().contains("test-secret"))
            verify(f.pending).finish()
            verifyNoInteractions(f.runtime)
        }
    }

    @Test fun validTogglesKeepFalseAndDefaultToTrueOnlyWhenOmitted(): Unit = Fixture().use { f ->
        f.receive(mapOf("op" to "takeover", "on" to false))
        verify(f.runtime).setTakeover(false)
        f.drain()
        // A snapshot can report not-ready even when submission is acknowledged.
        verify(f.pending).setResult(1, "ok: ready=false", null)
        clearInvocations(f.pending)

        f.receive(mapOf("op" to "takeover"))
        verify(f.runtime).setTakeover(true)
        f.drain()
        verify(f.pending).setResult(1, "ok: ready=false", null)
    }

    @Test fun statusReturnsSummaryAndKeepsFullReportInLogs(): Unit = Fixture().use { f ->
        f.receive(mapOf("op" to "status"))
        verify(f.pending, never()).finish()
        f.drain()
        verify(f.pending).setResult(1, "ok: ready=false", null)
        assertTrue(AppLog.tail.value.any { it.endsWith("status: test full report") })
        verify(f.pending).finish()
    }

    @Test fun firstReplyWinsWhetherWorkerOrTimeoutFinishesFirst() {
        for (timeoutFirst in listOf(false, true)) Fixture().use { f ->
            f.receive(mapOf("op" to "setup"))
            if (timeoutFirst) {
                f.timeout.run()
                f.drain()
                verify(f.pending).setResult(eq(3), contains("queued or running"), isNull())
            } else {
                f.drain()
                f.timeout.run()
                verify(f.pending).setResult(1, "ok: ready=false", null)
            }
            verify(f.pending, times(1)).setResult(anyInt(), anyString(), isNull())
            verify(f.pending, times(1)).finish()
        }
    }

    @Test fun submissionAndSchedulingFailuresHaveSafeErrorReplies() {
        for (failScheduling in listOf(false, true)) Fixture().use { f ->
            val error = IllegalStateException("test-secret")
            if (failScheduling) doThrow(error).`when`(f.runtime).afterQueued(any() ?: {})
            else doThrow(error).`when`(f.runtime).runSetup()
            f.receive(mapOf("op" to "setup"))
            verify(f.pending).setResult(eq(2), startsWith("error: could not submit command"), isNull())
            assertFalse(AppLog.tail.value.last().contains("test-secret"))
            f.timeout.run()
            verify(f.pending, times(1)).finish()
        }
    }

    @Test fun summaryFailureReturnsErrorInsteadOfSuccessOrExceptionText(): Unit = Fixture().use { f ->
        doThrow(IllegalStateException("test-secret")).`when`(f.runtime).statusLine()
        f.receive(mapOf("op" to "setup"))
        f.drain()
        verify(f.pending).setResult(eq(2), startsWith("error: could not read command state"), isNull())
        assertFalse(AppLog.tail.value.last().contains("test-secret"))
        verify(f.pending).finish()
    }

    @Test fun exportWritesSavedSettingsWithPasswordButRepliesWithout(): Unit = Fixture().use { f ->
        doReturn(GatewaySettings(server = "sip.example.org", password = "test-secret")).`when`(f.runtime).savedSettings()
        f.receive(mapOf("op" to "export"))
        val file = File(f.external, ConfigFile.DEFAULT_NAME)
        assertFalse(file.exists())
        f.drain()
        val data = org.mockito.ArgumentCaptor.forClass(String::class.java)
        verify(f.pending).setResult(eq(1), data.capture(), isNull())
        assertEquals("ok: exported to ${file.canonicalPath}", data.value)
        val settings = ConfigFile.parse(file.readText()).applyTo(GatewaySettings())
        assertEquals("sip.example.org", settings.server)
        assertEquals("test-secret", settings.password)
    }

    @Test fun importValidatesFileThenMergesWithLatestSettings(): Unit = Fixture().use { f ->
        var update: ((GatewaySettings) -> GatewaySettings)? = null
        doAnswer { update = it.getArgument(0); null }.`when`(f.runtime).applySettings(any() ?: { it })
        val file = File(f.external, "other.json")
        file.writeText("""{"format":"voice-sip-agent-config","version":1,"sip":{"password":"test-secret","port":5061}}""")
        f.receive(mapOf("op" to "import", "path" to file.path))
        // The file is read once, at dispatch.
        file.delete()
        val current = GatewaySettings(username = "latest-user", takeover = false)
        assertEquals(current.copy(password = "test-secret", port = 5061), update!!(current))
        f.drain()
        verify(f.pending).setResult(1, "ok: imported 2 settings; settings snapshot", null)
        assertFalse(AppLog.tail.value.any { it.contains("test-secret") })
    }

    @Test fun provisionTlsCaReadsCertificateFileOrResetsToSystem(): Unit = Fixture().use { f ->
        var update: ((GatewaySettings) -> GatewaySettings)? = null
        doAnswer { update = it.getArgument(0); null }.`when`(f.runtime).applySettings(any() ?: { it })
        val file = File(f.external, "pbx.pem").apply { writeText("pbx\n" + TEST_CERT_PEM) }
        f.receive(mapOf("op" to "provision", "tls_ca" to file.path))
        // Read once, at dispatch.
        file.delete()
        assertEquals(GatewaySettings(tlsCa = TEST_CERT_PEM), update!!(GatewaySettings()))

        f.receive(mapOf("op" to "provision", "tls_ca" to "system"))
        assertEquals(GatewaySettings(), update!!(GatewaySettings(tlsCa = TEST_CERT_PEM)))
    }

    @Test fun badPathsAndFilesAreRejectedBeforeRuntimeMutation(): Unit = Fixture().use { f ->
        val bad = File(f.external, "bad.json").apply { writeText("""{"format":"voice-sip-agent-config","version":1,"sip":{"port":"test-secret"}}""") }
        val private = File(f.data, "shared_prefs/gateway.json")
        val cases = listOf(
            mapOf("op" to "import") to "cannot read",
            mapOf("op" to "import", "path" to bad.path) to "sip.port must be",
            mapOf("op" to "import", "path" to "relative.json") to "path must be absolute",
            mapOf("op" to "import", "path" to private.path) to "outside the app's private storage",
            mapOf("op" to "export", "path" to "${f.external}/../${f.data.name}/x.json") to "outside the app's private storage",
            mapOf("op" to "export", "path" to 1) to "path must use --es",
            mapOf("op" to "export", "file" to "x") to "unexpected argument",
            mapOf("op" to "provision", "tls_ca" to bad.path) to "not an X.509 certificate",
            mapOf("op" to "provision", "tls_ca" to "${f.external}/missing.pem") to "cannot read",
            mapOf("op" to "provision", "tls_ca" to private.path) to "outside the app's private storage",
        )
        for ((args, expected) in cases) {
            clearInvocations(f.pending)
            f.receive(args)
            val data = org.mockito.ArgumentCaptor.forClass(String::class.java)
            verify(f.pending).setResult(eq(2), data.capture(), isNull())
            assertTrue(data.value, data.value.startsWith("error: ") && data.value.contains(expected))
            assertFalse(data.value.contains("test-secret"))
            verifyNoInteractions(f.runtime)
        }
    }

    @Test fun exportWriteFailureIsAnError(): Unit = Fixture().use { f ->
        doReturn(GatewaySettings()).`when`(f.runtime).savedSettings()
        f.receive(mapOf("op" to "export", "path" to "${f.external}/missing/dir/x.json"))
        f.drain()
        verify(f.pending).setResult(eq(2), startsWith("error: cannot write"), isNull())
        verify(f.pending).finish()
    }

    private class Fixture : AutoCloseable {
        private val log = mockStatic(Log::class.java)
        private val looper = mockStatic(Looper::class.java)
        val runtime = mock(GatewayRuntime::class.java)
        val receiver = mock(AdbControlReceiver::class.java, CALLS_REAL_METHODS)
        val pending = mock(BroadcastReceiver.PendingResult::class.java)
        private val context = mock(Context::class.java)
        private val root: File = Files.createTempDirectory("adb-control").toFile()
        val external = File(root, "external").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }
        private val singleton = GatewayRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
        private val previous = singleton.get(null)
        private val queue = ArrayDeque<() -> Unit>()
        lateinit var timeout: Runnable
        private val handlers = mockConstruction(Handler::class.java) { mock, _ ->
            doAnswer { timeout = it.getArgument(0); true }.`when`(mock)
                .postDelayed(any(Runnable::class.java), eq(5_000L))
        }

        init {
            singleton.set(null, runtime)
            doReturn(true).`when`(receiver).isOrderedBroadcast
            doReturn(external).`when`(context).getExternalFilesDir(null)
            doReturn(data).`when`(context).dataDir
            doReturn(pending).`when`(receiver).goAsync()
            doReturn("ready=false").`when`(runtime).statusLine()
            doReturn("settings snapshot").`when`(runtime).settingsLine()
            doReturn("test full report\n").`when`(runtime).statusReport()
            doAnswer { queue.addLast(it.getArgument(0)); null }.`when`(runtime).afterQueued(any() ?: {})
        }

        @Suppress("DEPRECATION")
        fun receive(args: Map<String, Any>) {
            val extras = mock(Bundle::class.java)
            doAnswer { args.keys }.`when`(extras).keySet()
            doAnswer { args[it.getArgument<String>(0)] }.`when`(extras).get(anyString())
            val intent = mock(Intent::class.java)
            doReturn(extras).`when`(intent).extras
            doAnswer { args.containsKey(it.getArgument<String>(0)) }.`when`(intent).hasExtra(anyString())
            receiver.onReceive(context, intent)
        }

        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }

        override fun close() {
            singleton.set(null, previous)
            root.deleteRecursively()
            handlers.close()
            looper.close()
            log.close()
        }
    }
}
