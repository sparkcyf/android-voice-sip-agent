package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import io.github.sparkcyf.voicesipagent.gateway.BusyPolicy
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import io.github.sparkcyf.voicesipagent.pjsua2.SrtpPolicy
import io.github.sparkcyf.voicesipagent.pjsua2.TlsCertificates
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.telephony.DefaultSim
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The settings file: versioned JSON with `sip`, `routing` and `device` sections (README "Configuration file").
 * Export writes every setting, the SIP password in plain text. Import overlays only the keys present and is
 * validated as a whole before anything changes. The takeover switch is never part of the file.
 * Error messages name the key and the expected form, never the value.
 */
object ConfigFile {
    const val FORMAT = "voice-sip-agent-config"
    const val VERSION = 1
    const val DEFAULT_NAME = "voice-sip-agent-config.json"
    private const val MAX_BYTES = 64 * 1024

    class Invalid(message: String) : IllegalArgumentException(message)

    /** The values an imported file sets; every other setting keeps its current value. */
    class Import internal constructor(private val edits: List<(GatewaySettings) -> GatewaySettings>, val keys: List<String>) {
        fun applyTo(current: GatewaySettings): GatewaySettings = edits.fold(current) { s, edit -> edit(s) }
    }

    fun export(s: GatewaySettings, exportedBy: String? = null): String {
        val root = JSONObject().put("format", FORMAT).put("version", VERSION)
        if (exportedBy != null) root.put("exportedBy", exportedBy)
        for ((section, fields) in SECTIONS) {
            root.put(section, JSONObject().also { obj -> fields.forEach { obj.put(it.key, it.export(s)) } })
        }
        return root.toString(2) + "\n"
    }

    /** The `exportedBy` value: app name and version, or null when the version is unavailable. */
    fun exportedBy(context: Context): String? = runCatching {
        "Voice SIP Agent " + context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    /** UTF-8 text of at most 64 KiB; a leading byte order mark is dropped. */
    fun read(input: InputStream): String {
        val buffer = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < buffer.size) {
            val n = input.read(buffer, size, buffer.size - size)
            if (n < 0) break
            size += n
        }
        if (size > MAX_BYTES) throw Invalid("file is larger than ${MAX_BYTES / 1024} KiB")
        return String(buffer, 0, size, Charsets.UTF_8).removePrefix("﻿")
    }

    fun parse(text: String): Import {
        val tokener = JSONTokener(text)
        val value = try {
            tokener.nextValue().also { if (tokener.nextClean() != 0.toChar()) throw Invalid("unexpected text after the JSON object") }
        } catch (e: JSONException) {
            // The message quotes the whole input, password included; keep only the position.
            val at = Regex("at character (\\d+)").find(e.message.orEmpty())?.groupValues?.get(1)
            throw Invalid("not valid JSON" + (at?.let { " (near character $it)" } ?: ""))
        }
        val root = value as? JSONObject ?: throw Invalid("not a JSON object")
        unknownKeys(root, setOf("format", "version", "exportedBy") + SECTIONS.keys, "")
        if (root.opt("format") != FORMAT) throw Invalid("format must be \"$FORMAT\"")
        when (val version = root.opt("version")) {
            VERSION -> {}
            is Int -> if (version > VERSION) throw Invalid("version $version is newer than this app supports ($VERSION); update the app")
                else throw Invalid("version must be $VERSION")
            else -> throw Invalid("version must be $VERSION")
        }
        if (root.has("exportedBy") && root.opt("exportedBy") !is String) throw Invalid("exportedBy must be a string")
        val edits = mutableListOf<(GatewaySettings) -> GatewaySettings>()
        val keys = mutableListOf<String>()
        for ((section, fields) in SECTIONS) {
            if (!root.has(section)) continue
            val obj = root.opt(section) as? JSONObject ?: throw Invalid("$section must be an object")
            unknownKeys(obj, fields.map { it.key }.toSet(), "$section.")
            for (field in fields) {
                if (!obj.has(field.key)) continue
                edits += field.parse(obj.opt(field.key), "$section.${field.key}")
                keys += "$section.${field.key}"
            }
        }
        return Import(edits, keys)
    }

    /** One line per setting that differs, as `section.key: old → new`; the password shows only as changed. */
    fun changes(old: GatewaySettings, new: GatewaySettings): List<String> = SECTIONS.flatMap { (section, fields) ->
        fields.filter { it.get(old) != it.get(new) }.map {
            "$section.${it.key}: " + if (it.secret) "changed" else "${it.show(old)} → ${it.show(new)}"
        }
    }

    private fun unknownKeys(obj: JSONObject, allowed: Set<String>, prefix: String) {
        val unknown = obj.keys().asSequence().filter { it !in allowed }.toList()
        if (unknown.isNotEmpty()) throw Invalid("unknown key ${unknown.joinToString { "$prefix$it" }}")
    }

    private class Field<T>(
        val key: String,
        val get: (GatewaySettings) -> T,
        val set: GatewaySettings.(T) -> GatewaySettings,
        val expected: String,
        val toJson: (T) -> Any = { it as Any },
        /** null when the JSON value is not acceptable. */
        val fromJson: (Any?) -> T?,
        val secret: Boolean = false,
        /** How [changes] shows a value; defaults to its JSON form. */
        val display: ((T) -> String)? = null,
    ) {
        fun export(s: GatewaySettings) = toJson(get(s))

        fun parse(value: Any?, path: String): (GatewaySettings) -> GatewaySettings {
            val v = fromJson(value) ?: throw Invalid("$path must be $expected")
            return { it.set(v) }
        }

        fun show(s: GatewaySettings): String = display?.invoke(get(s)) ?: when (val v = export(s)) {
            is String -> JSONObject.quote(v)
            else -> v.toString()
        }
    }

    private fun string(key: String, get: (GatewaySettings) -> String, set: GatewaySettings.(String) -> GatewaySettings,
        secret: Boolean = false) = Field(key, get, set, "a string", fromJson = { it as? String }, secret = secret)

    private fun bool(key: String, get: (GatewaySettings) -> Boolean, set: GatewaySettings.(Boolean) -> GatewaySettings) =
        Field(key, get, set, "true or false", fromJson = { it as? Boolean })

    private fun port(key: String, get: (GatewaySettings) -> Int, set: GatewaySettings.(Int) -> GatewaySettings) =
        Field(key, get, set, "an integer from 1 to 65535", fromJson = { (it as? Int)?.takeIf { p -> p in 1..65535 } })

    private fun <T> choice(key: String, get: (GatewaySettings) -> T, set: GatewaySettings.(T) -> GatewaySettings,
        names: Map<T, String>) = Field(key, get, set, names.values.joinToString(" | ", "one of "),
        toJson = { names[it] ?: it.toString() }, fromJson = { v -> names.entries.firstOrNull { it.value == v }?.key })

    private fun ids(vararg ids: String) = ids.associateWith { it }

    private fun intList(value: Any?): List<Int>? {
        val array = value as? JSONArray ?: return null
        return List(array.length()) { (array.opt(it) as? Int)?.takeIf { slot -> slot >= 0 } ?: return null }
    }

    private val SECTIONS: Map<String, List<Field<*>>> = linkedMapOf(
        "sip" to listOf(
            choice("stack", { it.sipStack }, { copy(sipStack = it) }, SipStack.entries.associateWith { it.id }),
            string("server", { it.server }, { copy(server = it) }),
            port("port", { it.port }, { copy(port = it) }),
            choice("transport", { it.sipTransport }, { copy(sipTransport = it) }, ids(*SipTransport.entries.map { it.id }.toTypedArray())),
            choice("srtp", { it.srtp }, { copy(srtp = it) }, ids(*SrtpPolicy.entries.map { it.id }.toTypedArray())),
            bool("tlsVerify", { it.tlsVerify }, { copy(tlsVerify = it) }),
            Field("tlsCa", { it.tlsCa }, { copy(tlsCa = it) },
                "PEM certificates as a string, or \"\" for the Android system CAs",
                fromJson = { value ->
                    (value as? String)?.let { if (it.isBlank()) "" else runCatching { TlsCertificates.normalize(it) }.getOrNull() }
                },
                display = TlsCertificates::label),
            string("domain", { it.domain }, { copy(domain = it) }),
            string("user", { it.username }, { copy(username = it) }),
            string("password", { it.password }, { copy(password = it) }, secret = true),
            port("localPort", { it.localSipPort }, { copy(localSipPort = it) }),
            bool("holdWhenNotReady", { it.holdSipWhenNotReady }, { copy(holdSipWhenNotReady = it) }),
            bool("debugLog", { it.sipDebugLog }, { copy(sipDebugLog = it) }),
        ),
        "routing" to listOf(
            string("target", { it.inboundTarget }, { copy(inboundTarget = it) }),
            Field("prefixes", { it.allowedPrefixes }, { copy(allowedPrefixes = it) },
                "an array of non-empty strings without commas",
                toJson = { JSONArray(it.split(',').map(String::trim).filter(String::isNotEmpty)) },
                fromJson = { value ->
                    (value as? JSONArray)?.let { array ->
                        List(array.length()) { i ->
                            (array.opt(i) as? String)?.trim()?.takeIf { it.isNotEmpty() && ',' !in it } ?: return@let null
                        }.joinToString(",")
                    }
                }),
            choice("busyPolicy", { it.busyPolicy }, { copy(busyPolicy = it) }, BusyPolicy.entries.associateWith { it.name.lowercase() }),
            Field("defaultSim", { it.defaultSimSlot }, { copy(defaultSimSlot = it) },
                "\"system\", \"refuse\" or a SIM slot number from 0",
                toJson = { if (it >= 0) it else DefaultSim.id(it) },
                fromJson = { value ->
                    when (value) {
                        is String -> DefaultSim.of(value)?.takeIf { it < 0 }
                        is Int -> value.takeIf { it >= 0 }
                        else -> null
                    }
                }),
            Field("inboundExcludedSlots", { it.inboundExcludedSlots }, { copy(inboundExcludedSlots = it) },
                "an array of SIM slot numbers from 0",
                toJson = { JSONArray(it.sorted()) }, fromJson = { intList(it)?.toSet() }),
        ),
        "device" to listOf(
            choice("audioMethod", { it.audioMethod }, { copy(audioMethod = it) },
                mapOf(AudioMethod.AUTO to "auto", AudioMethod.OFFICIAL to "redirection", AudioMethod.LEGACY to "direct")),
            choice("callAccess", { it.callAccess }, { copy(callAccess = it) },
                mapOf(CallAccess.AUTO to "auto", CallAccess.COMPANION to "companion", CallAccess.DIALER to "dialer")),
            bool("muteEarpiece", { it.muteEarpiece }, { copy(muteEarpiece = it) }),
            bool("keepDefaultDialer", { it.keepDefaultDialer }, { copy(keepDefaultDialer = it) }),
        ),
    )
}
