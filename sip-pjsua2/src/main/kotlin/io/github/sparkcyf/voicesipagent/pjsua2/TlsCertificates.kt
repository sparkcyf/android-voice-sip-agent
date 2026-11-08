package io.github.sparkcyf.voicesipagent.pjsua2

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Certificates the user trusts for SIP over TLS instead of the Android system CAs: a private CA, or a
 * self-signed server certificate (which then acts as a pin). Stored as a canonical PEM bundle that holds
 * certificates only; anything else in an imported file, such as a private key, is dropped.
 */
object TlsCertificates {
    const val MAX_BYTES = 64 * 1024
    const val MAX_CERTIFICATES = 16

    private val PEM_BLOCK = Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]*?)-----END CERTIFICATE-----")
    private val CN = Regex("(?:^|,)CN=((?:\\\\.|[^,\\\\])+)")

    /** The message names the problem, never the file content. */
    class Invalid(message: String) : IllegalArgumentException(message)

    /** PEM with one or more certificates, or one DER certificate, as a canonical PEM bundle. */
    fun normalize(data: ByteArray): String {
        if (data.size > MAX_BYTES) throw Invalid("certificate file is larger than ${MAX_BYTES / 1024} KiB")
        // Base64 and PEM armour are ASCII; Latin-1 keeps DER bytes intact for the check below.
        val text = String(data, Charsets.ISO_8859_1)
        val ders = if ("-----BEGIN" in text) {
            PEM_BLOCK.findAll(text).map {
                try {
                    Base64.getMimeDecoder().decode(it.groupValues[1])
                } catch (_: IllegalArgumentException) {
                    throw Invalid("a CERTIFICATE block is not valid Base64")
                }
            }.toList().ifEmpty { throw Invalid("no CERTIFICATE block found") }
        } else {
            listOf(data)
        }
        if (ders.size > MAX_CERTIFICATES) throw Invalid("more than $MAX_CERTIFICATES certificates")
        return ders.joinToString("") { pem(parse(it)) }
    }

    fun normalize(text: String): String = normalize(text.toByteArray(Charsets.UTF_8))

    /** Reads a certificate file, refusing more than [MAX_BYTES] without reading it all. */
    fun read(input: InputStream): ByteArray {
        val buffer = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < buffer.size) {
            val n = input.read(buffer, size, buffer.size - size)
            if (n < 0) break
            size += n
        }
        if (size > MAX_BYTES) throw Invalid("certificate file is larger than ${MAX_BYTES / 1024} KiB")
        return buffer.copyOf(size)
    }

    /** The certificates of a bundle made by [normalize]. */
    fun certificates(pem: String): List<X509Certificate> = PEM_BLOCK.findAll(pem).map {
        parse(Base64.getMimeDecoder().decode(it.groupValues[1]))
    }.toList()

    fun pem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" + PEM_ENCODER.encodeToString(cert.encoded) + "\n-----END CERTIFICATE-----\n"

    /** Subject CN (or the whole subject), e.g. for "2 certificates (CN=a, CN=b)". */
    fun name(cert: X509Certificate): String {
        val subject = cert.subjectX500Principal.getName("RFC2253")
        return CN.find(subject)?.let { "CN=" + it.groupValues[1] } ?: subject.ifEmpty { "(no subject)" }
    }

    /** Uppercase colon-separated SHA-256 of the DER encoding, as browsers and openssl show it. */
    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** One line per certificate: name, expiry and fingerprint. */
    fun describe(cert: X509Certificate, now: Date = Date()): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val expiry = if (now.after(cert.notAfter)) "EXPIRED ${day.format(cert.notAfter)}" else "until ${day.format(cert.notAfter)}"
        return "${name(cert)}, $expiry, SHA-256 ${sha256(cert)}"
    }

    /** A short label: "system CAs" when empty, else the count and names. */
    fun label(pem: String?): String {
        if (pem.isNullOrEmpty()) return "system CAs"
        val certs = runCatching { certificates(pem) }.getOrNull() ?: return "unreadable certificates"
        val count = if (certs.size == 1) "1 certificate" else "${certs.size} certificates"
        return "$count (${certs.joinToString { name(it) }})"
    }

    private fun parse(der: ByteArray): X509Certificate = try {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    } catch (_: Exception) {
        // CertificateException, or a provider's runtime exception on malformed input.
        throw Invalid("not an X.509 certificate (PEM or DER)")
    }

    private val PEM_ENCODER = Base64.getMimeEncoder(64, "\n".toByteArray())
}
