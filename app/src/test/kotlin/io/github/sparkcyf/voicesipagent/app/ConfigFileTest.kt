package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.gateway.BusyPolicy
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.telephony.DefaultSim
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigFileTest {
    private val full = GatewaySettings(
        takeover = false, keepDefaultDialer = true, muteEarpiece = false,
        server = "sip.example.org", port = 5061, sipTransport = "tls", srtp = "mandatory",
        sipDebugLog = true, holdSipWhenNotReady = false, username = "android-gw", password = "test-secret",
        domain = "example.org", localSipPort = 5070, inboundTarget = "302", allowedPrefixes = "+1,00",
        busyPolicy = BusyPolicy.IGNORE, audioMethod = AudioMethod.LEGACY, callAccess = CallAccess.DIALER,
        sipStack = SipStack.BUILTIN, defaultSimSlot = 1, inboundExcludedSlots = setOf(1, 0),
    )

    @Test fun exportRoundTripsEverySettingExceptTakeover() {
        val text = ConfigFile.export(full, "Voice SIP Agent 0.4.0")
        val json = JSONObject(text)
        assertEquals("voice-sip-agent-config", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertEquals("test-secret", json.getJSONObject("sip").getString("password"))
        assertEquals("direct", json.getJSONObject("device").getString("audioMethod"))
        assertEquals("[\"+1\",\"00\"]", json.getJSONObject("routing").getJSONArray("prefixes").toString())
        assertEquals("[0,1]", json.getJSONObject("routing").getJSONArray("inboundExcludedSlots").toString())
        assertFalse(text.contains("takeover"))

        val imported = ConfigFile.parse(text)
        assertEquals(20, imported.keys.size)
        // Takeover is the only setting the file leaves alone.
        assertEquals(full.copy(takeover = true), imported.applyTo(GatewaySettings(takeover = true)))
    }

    @Test fun defaultsExportSymbolicValues() {
        val routing = JSONObject(ConfigFile.export(GatewaySettings())).getJSONObject("routing")
        assertEquals("system", routing.getString("defaultSim"))
        assertEquals(0, routing.getJSONArray("prefixes").length())
        assertEquals("reject", routing.getString("busyPolicy"))
        val refuse = JSONObject(ConfigFile.export(GatewaySettings(defaultSimSlot = DefaultSim.REFUSE)))
        assertEquals("refuse", refuse.getJSONObject("routing").getString("defaultSim"))
    }

    @Test fun importOverlaysOnlyPresentKeys() {
        val imported = ConfigFile.parse("""
            {"format": "voice-sip-agent-config", "version": 1,
             "sip": {"server": "new.example.org", "password": "p"},
             "routing": {"defaultSim": 0, "prefixes": [" 9 ", "8"]},
             "device": {}}
        """)
        assertEquals(listOf("sip.server", "sip.password", "routing.prefixes", "routing.defaultSim"), imported.keys)
        assertEquals(full.copy(server = "new.example.org", password = "p", defaultSimSlot = 0, allowedPrefixes = "9,8"),
            imported.applyTo(full))
        assertEquals(full, ConfigFile.parse("""{"format":"voice-sip-agent-config","version":1}""").applyTo(full))
    }

    @Test fun invalidFilesAreRejectedWithoutEchoingValues() {
        val header = """"format":"voice-sip-agent-config","version":1"""
        val cases = mapOf(
            """{$header, "sip": {"password": "test-secret"""" to "not valid JSON",
            """[1]""" to "not a JSON object",
            """{$header} "test-secret"""" to "unexpected text",
            """{"version":1}""" to "format must be",
            """{"format":"test-secret","version":1}""" to "format must be",
            """{"format":"voice-sip-agent-config"}""" to "version must be 1",
            """{"format":"voice-sip-agent-config","version":"1"}""" to "version must be 1",
            """{"format":"voice-sip-agent-config","version":2}""" to "newer than this app supports",
            """{$header, "takeover": true}""" to "unknown key takeover",
            """{$header, "sip": {"passwrod": "test-secret"}}""" to "unknown key sip.passwrod",
            """{$header, "sip": "test-secret"}""" to "sip must be an object",
            """{$header, "sip": {"port": 0}}""" to "sip.port must be",
            """{$header, "sip": {"port": "5060"}}""" to "sip.port must be",
            """{$header, "sip": {"port": 5060.5}}""" to "sip.port must be",
            """{$header, "sip": {"transport": "test-secret"}}""" to "sip.transport must be one of udp | tcp | tls",
            """{$header, "sip": {"password": null}}""" to "sip.password must be a string",
            """{$header, "sip": {"holdWhenNotReady": "false"}}""" to "sip.holdWhenNotReady must be true or false",
            """{$header, "routing": {"prefixes": "1,2"}}""" to "routing.prefixes must be",
            """{$header, "routing": {"prefixes": ["1,2"]}}""" to "routing.prefixes must be",
            """{$header, "routing": {"prefixes": [""]}}""" to "routing.prefixes must be",
            """{$header, "routing": {"defaultSim": "0"}}""" to "routing.defaultSim must be",
            """{$header, "routing": {"defaultSim": -1}}""" to "routing.defaultSim must be",
            """{$header, "routing": {"inboundExcludedSlots": [0, -1]}}""" to "routing.inboundExcludedSlots must be",
            """{$header, "device": {"audioMethod": 2}}""" to "device.audioMethod must be one of auto | redirection | direct",
        )
        for ((text, expected) in cases) {
            val error = assertThrows(text, ConfigFile.Invalid::class.java) { ConfigFile.parse(text) }
            assertTrue("${error.message} for $text", error.message!!.contains(expected))
            assertFalse(error.message!!.contains("test-secret"))
        }
    }

    @Test fun readLimitsSizeAndDropsByteOrderMark() {
        assertEquals("{}", ConfigFile.read(ByteArrayInputStream("﻿{}".toByteArray())))
        assertThrows(ConfigFile.Invalid::class.java) { ConfigFile.read(ByteArrayInputStream(ByteArray(64 * 1024 + 1))) }
        assertEquals(64 * 1024, ConfigFile.read(ByteArrayInputStream(ByteArray(64 * 1024) { ' '.code.toByte() })).length)
    }

    @Test fun changesListDifferencesAndHideThePassword() {
        val new = full.copy(server = "b", password = "other-secret", inboundExcludedSlots = emptySet(), takeover = true)
        assertEquals(listOf("sip.server: \"sip.example.org\" → \"b\"", "sip.password: changed",
            "routing.inboundExcludedSlots: [0,1] → []"), ConfigFile.changes(full, new))
        assertEquals(emptyList<String>(), ConfigFile.changes(full, full.copy(takeover = true)))
    }
}
