package io.github.sparkcyf.voicesipagent.privileged.shell

import android.content.Context
import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.CallAudioStatus
import io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService
import io.github.sparkcyf.voicesipagent.privileged.DialerRoleResult
import kotlin.system.exitProcess

/**
 * Shizuku UserService, instantiated by Shizuku inside an app_process running as shell.
 * Only the owning app (same uid that bound us) may call in.
 */
class PrivilegedService(context: Context) : IPrivilegedService.Stub() {
    private val appUid = context.applicationInfo.uid
    private val appPackage = context.packageName
    private val shellCtx = ShellEnv.shellContext(context)
    private var tap: CallAudioTap? = null
    private var lastTapStats = "no tap yet"
    private var lastBackend = ""
    private var lastRecoveryDetail = ""
    /** Set by the app from its per-device record; applies from the next tap. */
    @Volatile private var directMuteBlocked = false
    private val audioManager by lazy { ShellEnv.audioManager(shellCtx) }
    private val dialerPlatform by lazy { ShellDialerPlatform(shellCtx, appUid) }
    private val dialerRoles by lazy { DialerRoles(appPackage, dialerPlatform) }

    /** Never held while taking the service or tap monitors (tap close calls back in). */
    private val muteLock = Any()
    private var muteTapOwner: Any? = null
    private val localOutputMute by lazy {
        LocalOutputMute(object : LocalOutputMute.AudioControl {
            override val mode get() = audioManager.mode
            override val voiceCallMuted get() = audioManager.isStreamMute(AudioManager.STREAM_VOICE_CALL)
            @SuppressLint("WrongConstant")
            override fun requestMode(mode: Int) { audioManager.mode = mode }
            override fun requestVoiceCallMute(muted: Boolean) {
                // The helper holds MODIFY_PHONE_STATE. This mutes output without changing the saved
                // volume index; Telecom separately owns microphone mute. Stream mute is global and,
                // unlike a mode request, is not automatically removed on abrupt helper-process death.
                audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL,
                    if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            }
            override fun awaitMode(wanted: Int) = this@PrivilegedService.awaitMode(wanted)
            override fun awaitVoiceCallMute(wanted: Boolean): Boolean {
                val end = SystemClock.elapsedRealtime() + 600
                var observed = voiceCallMuted
                while (observed != wanted && SystemClock.elapsedRealtime() < end) {
                    SystemClock.sleep(50)
                    observed = voiceCallMuted
                }
                return observed
            }
        })
    }

    init {
        Log.i(TAG, "created uid=${Process.myUid()} for $appPackage")
    }

    private fun enforceCaller(pkg: String? = null) {
        val caller = Binder.getCallingUid()
        if (caller != appUid && caller != Process.myUid()) throw SecurityException("caller uid $caller not allowed")
        if (pkg != null && pkg != appPackage) throw SecurityException("package $pkg not allowed")
    }

    override fun destroy() {
        enforceCaller()
        Log.i(TAG, "destroy")
        closeCallAudio()
        exitProcess(0)
    }

    override fun getUid(): Int { enforceCaller(); return Process.myUid() }

    override fun dialerState(): Bundle = dialerOperation {
        DialerRoleResult(DialerRoleResult.STATE, "", dialerPlatform.state())
    }

    override fun becomeDefaultDialer(expectedHolder: String): Bundle = dialerOperation { dialerRoles.acquire(expectedHolder) }

    override fun restoreDefaultDialer(previousHolder: String): Bundle = dialerOperation { dialerRoles.restore(previousHolder) }

    private fun dialerOperation(operation: () -> DialerRoleResult): Bundle {
        enforceCaller()
        return asHelper {
            try { operation().toBundle() } catch (e: Exception) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                DialerRoleResult(DialerRoleResult.FAILED, "Dialer operation failed: ${cause.message}").toBundle()
            }
        }
    }

    override fun setup(packageName: String, takeover: Boolean): Bundle {
        return setupCallAccess(packageName, takeover, CallAccess.AUTO)
    }

    override fun setupCallAccess(packageName: String, takeover: Boolean, callAccess: Int): Bundle {
        enforceCaller(packageName)
        val log = StringBuilder()
        fun step(name: String, vararg cmd: String) {
            val r = ShellEnv.run(*cmd)
            log.append(name).append('=').append(if (r.exit == 0) "ok" else "fail(${r.exit}: ${r.out.trim().take(120)})").append('\n')
        }
        val companion = Build.VERSION.SDK_INT >= 31 && callAccess != CallAccess.DIALER
        // Reuse observations only until setup attempts to change the corresponding setting.
        var ongoingCallsAllowed: Boolean? = if (companion) appOpAllowed(packageName) else null
        if (ongoingCallsAllowed == false) {
            step("appops", "appops", "set", packageName, "MANAGE_ONGOING_CALLS", "allow")
            ongoingCallsAllowed = null
        }
        // The override is lost on every reboot and Telecom restart, so it is re-checked each time. Telecom
        // keeps it as a plain list: adding appends a duplicate and removing drops one entry.
        val entries = if (Build.VERSION.SDK_INT >= 31) companionOverrideCount(packageName) else 0
        var hasCompanionOverride: Boolean? = entries > 0
        if (companion && takeover && entries == 0) {
            step("companion", "cmd", "telecom", "add-or-remove-call-companion-app", packageName, "1")
            hasCompanionOverride = null
        } else if (!takeover || !companion) {
            repeat(entries) { step("companion-remove", "cmd", "telecom", "add-or-remove-call-companion-app", packageName, "0") }
            if (entries > 0) hasCompanionOverride = null
        }
        var dozeAllowed: Boolean? = dozeAllowlisted(packageName)
        if (dozeAllowed == false) {
            step("deviceidle", "cmd", "deviceidle", "whitelist", "+$packageName")
            dozeAllowed = null
        }
        // The runtime passes effective takeover, keeping grants enabled until deferred shutdown ends.
        if (takeover) {
            for (perm in PHONE_RUNTIME_PERMISSIONS) {
                step("grant:${perm.substringAfterLast('.')}", "pm", "grant", packageName, perm)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Dialer call/recovery notifications remain necessary with gateway takeover off.
            val needsNotifications = takeover || asHelper {
                runCatching { dialerPlatform.state().holder == appPackage }
                    .onFailure { log.append("notification-role-check=fail(${it.message})\n") }
                    .getOrDefault(false)
            }
            if (needsNotifications) {
                step("grant:POST_NOTIFICATIONS", "pm", "grant", packageName, "android.permission.POST_NOTIFICATIONS")
            }
        }
        Log.i(TAG, "setup:\n$log")
        return readHealth(packageName, ongoingCallsAllowed, hasCompanionOverride, dozeAllowed)
            .apply { putString("setup_log", log.toString()) }
    }

    override fun health(packageName: String): Bundle {
        enforceCaller(packageName)
        return readHealth(packageName)
    }

    private fun readHealth(
        packageName: String,
        ongoingCallsAllowed: Boolean? = null,
        hasCompanionOverride: Boolean? = null,
        dozeAllowed: Boolean? = null,
    ): Bundle {
        val b = Bundle()
        b.putInt(HealthKeys.UID, Process.myUid())
        b.putBoolean(HealthKeys.MANAGE_ONGOING_CALLS, Build.VERSION.SDK_INT >= 31 && (ongoingCallsAllowed ?: appOpAllowed(packageName)))
        b.putBoolean(HealthKeys.COMPANION_OVERRIDE, Build.VERSION.SDK_INT >= 31 && (hasCompanionOverride ?: companionOverridden(packageName)))
        b.putBoolean(HealthKeys.DOZE_ALLOWLISTED, dozeAllowed ?: dozeAllowlisted(packageName))
        val icp = ShellEnv.isPstnCallAudioInterceptable()
        b.putBoolean(HealthKeys.INTERCEPTABLE, icp == true)
        b.putString(HealthKeys.DETAIL, if (icp == null) "isPstnCallAudioInterceptable unavailable" else "")
        val capabilities = asHelper { AudioCapabilities.inspect(shellCtx) }
        b.putBoolean(HealthKeys.DIRECT_AUDIO, capabilities.direct)
        b.putBoolean(HealthKeys.REDIRECT_AUDIO, capabilities.redirect)
        b.putBoolean(HealthKeys.LOCAL_SUPPRESSION, capabilities.suppression)
        b.putString(HealthKeys.AUDIO_DETAIL, capabilities.detail)
        return b
    }

    @Synchronized
    override fun openCallAudio(method: Int, sampleRate: Int): ParcelFileDescriptor {
        enforceCaller()
        closeCallAudio()
        synchronized(muteLock) {
            localOutputMute.beginCall(if (directMuteBlocked) "it previously stopped direct capture on this device" else null)
        }
        lastRecoveryDetail = ""
        val owner = Any()
        val t = CallAudioTap(shellCtx, method, sampleRate, TapHooks(owner))
        // Register before starting the worker threads so a tap that closes during open cannot later
        // acquire a mute lease. setLocalOutputMuted is serialized with open/close on the service.
        synchronized(muteLock) { muteTapOwner = owner }
        return try {
            asHelper { t.open() }.also { tap = t; lastBackend = t.usedMethod }
        } catch (e: Throwable) {
            lastTapStats = t.stats()
            Log.w(TAG, "openCallAudio failed: $e")
            throw IllegalStateException(e.message ?: e.toString())
        }
    }

    @Synchronized
    override fun closeCallAudio() {
        enforceCaller()
        tap?.let {
            it.close()
            lastTapStats = it.stats()
            lastRecoveryDetail = it.recoveryDetail
        }
        tap = null
        // Also retries a restore that failed in the tap callback, or after an earlier close.
        restoreLocal("tap closed")
    }

    override fun audioStats(): String {
        enforceCaller()
        return tap?.stats() ?: lastTapStats
    }

    @Synchronized
    override fun callAudioStatus(): Bundle {
        enforceCaller()
        return asHelper {
            synchronized(muteLock) {
                CallAudioStatus(
                    active = tap?.isOpen == true,
                    backend = tap?.usedMethod ?: lastBackend,
                    muteRequested = localOutputMute.requested,
                    outputMute = localOutputMute.observedMute(),
                    muteDetail = localOutputMute.lastMuteResult,
                    ownsOutputChange = localOutputMute.ownsOutputChange,
                    restorationPending = localOutputMute.restorationPending,
                    restoreDetail = localOutputMute.lastRestoreResult,
                    voiceCallStreamMuted = runCatching {
                        audioManager.isStreamMute(AudioManager.STREAM_VOICE_CALL)
                    }.getOrNull(),
                    directMuteIncompatible = localOutputMute.directMuteIncompatible,
                    recoveryDetail = tap?.recoveryDetail ?: lastRecoveryDetail,
                ).toBundle()
            }
        }
    }

    @Synchronized
    override fun setLocalOutputMuted(muted: Boolean): String {
        enforceCaller()
        return asHelper { if (muted) muteLocal() else restoreLocal("requested") }
    }

    override fun setDirectOutputMuteBlocked(blocked: Boolean) {
        enforceCaller()
        directMuteBlocked = blocked
    }

    private inline fun <T> asHelper(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return try { block() } finally { Binder.restoreCallingIdentity(token) }
    }

    private fun muteLocal(): String = synchronized(muteLock) {
        val current = tap
        if (current == null || muteTapOwner == null) return "no tap open"
        muteFor(current.usedMethod)
    }

    private fun muteFor(usedMethod: String): String {
        // Redirected taps keep working when mode 5 tears down the handset patches. Direct telephony
        // taps need IN_CALL to remain active, so silence only their voice-call output stream.
        return localOutputMute.mute(Build.VERSION.SDK_INT >= 33 && usedMethod.startsWith("redirection"))
            .also { Log.i(TAG, "local mute: $it") }
    }

    /** Mute coordination for in-place tap recoveries; ignored once [owner]'s tap is no longer current. */
    private inner class TapHooks(private val owner: Any) : CallAudioTap.Hooks {
        override fun withdrawMuteForCaptureFailure(failure: String): Boolean = asHelper {
            synchronized(muteLock) {
                if (muteTapOwner !== owner) return@synchronized false
                val restored = localOutputMute.withdrawForCaptureFailure(failure) ?: return@synchronized false
                Log.w(TAG, "local mute withdrawn after capture failure: $restored")
                true
            }
        }

        override fun beforeBackendSwitch() {
            asHelper { synchronized(muteLock) { if (muteTapOwner === owner) restoreLocal("audio backend switch") } }
        }

        override fun afterBackendSwitch(usedMethod: String) {
            asHelper {
                synchronized(muteLock) {
                    if (muteTapOwner === owner && localOutputMute.requested) muteFor(usedMethod)
                }
            }
        }

        override fun onClosed() = onTapClosed(owner)
    }

    private fun onTapClosed(owner: Any) = asHelper {
        synchronized(muteLock) {
            if (muteTapOwner === owner) {
                muteTapOwner = null
                restoreLocal("tap closed")
            }
        }
    }

    // Tap callbacks may run inside an app's Binder transaction. Always restore as the helper UID.
    private fun restoreLocal(why: String): String = asHelper {
        synchronized(muteLock) {
            localOutputMute.restore(why).also {
                if (it != "not muted") Log.i(TAG, "local mute: $it")
            }
        }
    }

    /** Mode changes are applied asynchronously by AudioService; wait briefly for [want] (-1: just settle). */
    private fun awaitMode(want: Int): Int {
        val end = SystemClock.elapsedRealtime() + 600
        var m = audioManager.mode
        while (m != want && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(50)
            m = audioManager.mode
        }
        return m
    }

    private fun appOpAllowed(pkg: String) =
        ShellEnv.run("appops", "get", pkg, "MANAGE_ONGOING_CALLS").out.contains("allow")

    private fun companionOverridden(pkg: String) = companionOverrideCount(pkg) > 0

    /** How many times [pkg] appears in Telecom's companion override list. */
    private fun companionOverrideCount(pkg: String): Int {
        val out = ShellEnv.run("dumpsys", "telecom", timeoutSec = 15).out
        // Printed as "DefaultCallCompanionApps: (override <pkg>, <pkg>, ...)".
        val line = out.lineSequence().firstOrNull { it.contains("DefaultCallCompanionApps") } ?: return 0
        return Regex("[\\s(,]${Regex.escape(pkg)}(?=[,)\\s])").findAll(line).count()
    }

    private fun dozeAllowlisted(pkg: String) =
        ShellEnv.run("cmd", "deviceidle", "whitelist").out.lineSequence().any { it.trim().endsWith(",$pkg") || it.contains(",$pkg,") }

    companion object {
        private const val TAG = "VsaPrivileged"
        private val PHONE_RUNTIME_PERMISSIONS = buildList {
            add("android.permission.CALL_PHONE")
            add("android.permission.READ_PHONE_STATE")
            // Android 12 adds the getPhoneAccount() permission check used by isCellular().
            if (Build.VERSION.SDK_INT >= 31) add("android.permission.READ_PHONE_NUMBERS")
        }
    }
}
