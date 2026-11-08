package io.github.sparkcyf.voicesipagent.pjsua2

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class TlsCertificatesTest {
    @Test fun pemWithSurroundingTextKeepsOnlyCertificates() {
        val file = "Bag Attributes\n    friendlyName: pbx\n$SERVER\n" +
            "-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg\n-----END PRIVATE KEY-----\n"
        val pem = TlsCertificates.normalize(file)
        assertEquals(SERVER + "\n", pem)
        assertFalse(pem.contains("PRIVATE"))
        // Canonical output normalizes to itself.
        assertEquals(pem, TlsCertificates.normalize(pem))
    }

    @Test fun bundlesAndDerAreAccepted() {
        val bundle = TlsCertificates.normalize("$SERVER\r\n$CA\r\n")
        assertEquals(listOf("CN=pbx.test", "CN=Test Root CA"), TlsCertificates.certificates(bundle).map(TlsCertificates::name))
        assertEquals("2 certificates (CN=pbx.test, CN=Test Root CA)", TlsCertificates.label(bundle))

        val der = Base64.getMimeDecoder().decode(SERVER.lines().filterNot { it.startsWith("-----") }.joinToString(""))
        assertEquals(TlsCertificates.normalize(SERVER), TlsCertificates.normalize(der))
    }

    @Test fun describeShowsFingerprintAndExpiry() {
        val cert = TlsCertificates.certificates(TlsCertificates.normalize(SERVER)).single()
        // openssl x509 -noout -fingerprint -sha256
        assertEquals("DD:14:D5:45:D5:5A:6B:F4:87:1E:5A:1C:18:DB:34:6A:E1:84:68:A0:D9:A0:D6:18:1D:2F:28:3E:10:F4:5A:51",
            TlsCertificates.sha256(cert))
        assertEquals("CN=pbx.test, until 2126-10-15, SHA-256 ${TlsCertificates.sha256(cert)}", TlsCertificates.describe(cert))
        assertTrue(TlsCertificates.describe(cert, Date(cert.notAfter.time + 1)).contains("EXPIRED 2126-10-15"))
        assertEquals("system CAs", TlsCertificates.label(""))
        assertEquals("system CAs", TlsCertificates.label(null))
    }

    @Test fun invalidInputIsRejectedWithoutEchoingIt() {
        val cases = listOf(
            "test-secret".toByteArray(),
            "-----BEGIN PRIVATE KEY-----\ntest-secret\n-----END PRIVATE KEY-----\n".toByteArray(),
            "-----BEGIN CERTIFICATE-----\ntest-secret\n-----END CERTIFICATE-----\n".toByteArray(),
            "-----BEGIN CERTIFICATE-----\n#test-secret#\n-----END CERTIFICATE-----\n".toByteArray(),
            ByteArray(TlsCertificates.MAX_BYTES + 1),
            (1..TlsCertificates.MAX_CERTIFICATES + 1).joinToString("\n") { SERVER }.toByteArray(),
        )
        for (data in cases) {
            val e = assertThrows(TlsCertificates.Invalid::class.java) { TlsCertificates.normalize(data) }
            assertFalse(e.message!!.contains("test-secret"))
        }
    }

    @Test fun readRefusesOversizedFiles() {
        assertEquals(3, TlsCertificates.read(ByteArrayInputStream(ByteArray(3))).size)
        assertEquals(TlsCertificates.MAX_BYTES, TlsCertificates.read(ByteArrayInputStream(ByteArray(TlsCertificates.MAX_BYTES))).size)
        assertThrows(TlsCertificates.Invalid::class.java) {
            TlsCertificates.read(ByteArrayInputStream(ByteArray(TlsCertificates.MAX_BYTES + 1)))
        }
    }

    @Test fun policyLabelNeverIncludesPem() {
        val policy = TlsPolicy(verifyServer = false, caPem = TlsCertificates.normalize(SERVER))
        assertEquals("verify=off ca=1 certificate (CN=pbx.test)", policy.toString())
        assertFalse(PjsipConfig(server = "s", username = "u", password = "test-secret", transport = SipTransport.TLS,
            tls = policy).toString().contains("BEGIN"))
    }

    companion object {
        /** Self-signed, CN=pbx.test, until 2126. */
        val SERVER = """
-----BEGIN CERTIFICATE-----
MIIBfDCCASOgAwIBAgIUMhyjGThRUk4ag2B0vzyBCZHvBogwCgYIKoZIzj0EAwIw
EzERMA8GA1UEAwwIcGJ4LnRlc3QwIBcNMjYxMTA4MTgzMTM1WhgPMjEyNjEwMTUx
ODMxMzVaMBMxETAPBgNVBAMMCHBieC50ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0D
AQcDQgAEKYedsZVM60WGGU4aTlMjiYSKca4EB3xIKzffbxrvS0LUSVUF422/8Gy8
9Hi+KYb/VSAJrwaBkIeCu7XgsVXyhqNTMFEwHQYDVR0OBBYEFOHFKBspwYfS1663
8Za3CY7d8dWwMB8GA1UdIwQYMBaAFOHFKBspwYfS16638Za3CY7d8dWwMA8GA1Ud
EwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIgEWTD4NNB2SK9VgmRI3I4J9BW
mZ+yYRXd0n03YkDs7GYCIFF4BGNkjG5YddQgygXklSp/gTaRnnkI1D54PR9FJ4D1
-----END CERTIFICATE-----
""".trim()

        /** Self-signed, O=Test, CN=Test Root CA, until 2126. */
        val CA = """
-----BEGIN CERTIFICATE-----
MIIBozCCAUmgAwIBAgIUb0l8DWpMRS+xDWiyiZZ8zLY+7E0wCgYIKoZIzj0EAwIw
JjENMAsGA1UECgwEVGVzdDEVMBMGA1UEAwwMVGVzdCBSb290IENBMCAXDTI2MTEw
ODE4MzEzNVoYDzIxMjYxMDE1MTgzMTM1WjAmMQ0wCwYDVQQKDARUZXN0MRUwEwYD
VQQDDAxUZXN0IFJvb3QgQ0EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQdBoXT
see/fm9dZdO0ABze88yVI5JnfYz82LeQ9drd/g1dOqPmaj4C5bWx8jz1lXvqhyov
gQHgYRzZ3Q08/NXWo1MwUTAdBgNVHQ4EFgQUg+EUlSqflV2hrFVBypnkt+cvXOUw
HwYDVR0jBBgwFoAUg+EUlSqflV2hrFVBypnkt+cvXOUwDwYDVR0TAQH/BAUwAwEB
/zAKBggqhkjOPQQDAgNIADBFAiBb/SYjF1nhjcWkdvl3o5ef3d7T5ldUU4ylbeRr
K7m89QIhAMgpV0Es6o7vXBKuGMzSM437xtcF6t5wnWiSKvMYxktm
-----END CERTIFICATE-----
""".trim()
    }
}
