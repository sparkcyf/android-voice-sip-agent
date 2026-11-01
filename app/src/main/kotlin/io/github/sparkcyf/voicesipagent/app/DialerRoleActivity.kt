package io.github.sparkcyf.voicesipagent.app

import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import rikka.shizuku.Shizuku

/** Role acquisition is confined to explicit UI actions. */
abstract class DialerRoleActivity : ComponentActivity() {
    protected val roleMessage get() = GatewayRuntime.get(this).dialerMessage

    override fun onCreate(savedInstanceState: Bundle?) {
        // System bar icons follow the day/night theme; the manifest theme alone keeps them light.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }
    private fun message(value: String) = GatewayRuntime.get(this).showDialerMessage(value)
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val held = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
        message(when {
            held -> "This app is now the default dialer."
            result.resultCode == RESULT_OK -> "Android did not select this app as default dialer. Use system default-app settings."
            else -> "Default dialer selection was cancelled or declined."
        })
        GatewayRuntime.get(this).refreshCallAccess()
    }

    protected fun setTakeoverFromUi(on: Boolean) = afterUnlock {
        val rt = GatewayRuntime.get(this)
        val role = getSystemService(RoleManager::class.java)
        val held = role.isRoleHeld(RoleManager.ROLE_DIALER)
        // Off stops our helper, but an authorized Shizuku server can restart it.
        val helperAvailable = rt.shizuku.service != null || runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val needsSystemRequest = on && !held && !helperAvailable &&
            CallAccess.effective(rt.settings.callAccess, Build.VERSION.SDK_INT, held) == CallAccess.DIALER
        rt.setTakeover(on)
        if (needsSystemRequest) requestDialer()
    }

    protected fun requestDialer() {
        afterUnlock {
            val role = getSystemService(RoleManager::class.java)
            when {
                !role.isRoleAvailable(RoleManager.ROLE_DIALER) -> message("Default dialer selection is unavailable on this device.")
                role.isRoleHeld(RoleManager.ROLE_DIALER) -> GatewayRuntime.get(this).refreshCallAccess()
                GatewayRuntime.get(this).settings.takeover && GatewayRuntime.get(this).shizuku.service != null ->
                    GatewayRuntime.get(this).changeDefaultDialer(true)
                else -> {
                    val rt = GatewayRuntime.get(this)
                    rt.cancelDialerChange()
                    if (rt.rememberDialerForSystemRequest()) runCatching { roleRequest.launch(role.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }
                        .onFailure { message("Could not open default dialer selection. Use system default-app settings.") }
                }
            }
        }
    }

    protected fun switchDialer() = afterUnlock {
        val rt = GatewayRuntime.get(this)
        if (rt.settings.takeover && rt.shizuku.service != null) rt.changeDefaultDialer(false) else chooseDialerInSettings()
    }

    protected fun cancelDialerChange() = afterUnlock { GatewayRuntime.get(this).cancelDialerChange() }

    protected fun chooseDialerInSettings() = afterUnlock {
        GatewayRuntime.get(this).cancelDialerChange()
        val opened = runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }.isSuccess
        if (!opened && runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }.isFailure) {
            message("Open system Settings, then Default apps, and select your usual phone app.")
        }
    }

    protected fun openConfiguration() = afterUnlock {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun afterUnlock(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) { action(); return }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
            override fun onDismissCancelled() { message("Unlock the phone to continue.") }
            override fun onDismissError() { message("Unlock the phone to continue.") }
        })
    }

    override fun onResume() {
        super.onResume()
        GatewayRuntime.get(this).refreshCallAccess()
    }
}
