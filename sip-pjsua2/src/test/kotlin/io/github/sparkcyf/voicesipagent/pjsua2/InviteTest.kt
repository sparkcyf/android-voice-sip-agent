package io.github.sparkcyf.voicesipagent.pjsua2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteTest {
    private fun invite(requestUser: String, vararg headers: String) =
        (listOf("INVITE sip:$requestUser@203.0.113.5:5062;transport=tls SIP/2.0") + headers + listOf("", "v=0"))
            .joinToString("\r\n")

    @Test
    fun targetPrefersDialHeader() {
        val msg = invite("android-gw", "From: <sip:302@pbx>;tag=1", "X-VSA-Dial: 5551234567")
        assertEquals("5551234567", Invite.target(msg))
    }

    @Test
    fun targetFallsBackToRequestUri() {
        assertEquals("5551234567", Invite.target(invite("5551234567", "From: <sip:302@pbx>;tag=1")))
    }

    @Test
    fun callerFromPaiThenFrom() {
        val withPai = invite("x", "From: \"Desk\" <sip:302@pbx>;tag=1", "P-Asserted-Identity: \"Alice\" <sip:+15550001111@pbx>")
        assertEquals("+15550001111", Invite.caller(withPai).number)
        assertEquals("Alice", Invite.caller(withPai).displayName)
        val compact = invite("x", "f: <sip:302@pbx>;tag=1")
        assertEquals("302", Invite.caller(compact).number)
        assertNull(Invite.caller(compact).displayName)
    }

    @Test
    fun simSlotHeader() {
        assertNull(Invite.line(invite("x", "From: <sip:302@pbx>;tag=1")))
        assertEquals(1, Invite.line(invite("x", "x-vsa-simslot: 1")))
        assertEquals(0, Invite.line(invite("x", "X-VSA-SIMSLOT:0")))
        assertEquals(Invite.INVALID_LINE, Invite.line(invite("x", "X-VSA-SIMSLOT: sim2")))
        assertEquals(Invite.INVALID_LINE, Invite.line(invite("x", "X-VSA-SIMSLOT: -1")))
    }

    @Test
    fun headersStopAtBody() {
        val msg = invite("5551234567") + "\r\nX-VSA-Dial: 999"
        assertEquals("5551234567", Invite.target(msg))
    }
}
