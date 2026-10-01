package app.veil.android.admin

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import app.veil.android.VeilLog
import app.veil.android.remote.RemoteStore
import app.veil.android.remote.RemoteSync
import app.veil.android.ui.MainActivity

/** VEIL's device-admin component. Becomes Device Owner through QR provisioning or adb. */
class VeilDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        VeilLog.i("Device admin enabled; device owner=${DeviceOwner.isOwner(context)}")
        DeviceOwner.applyBaseline(context)
    }

    /** Pre-Android 10 provisioning path: the extras arrive here. */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Provisioning.finish(context, adminExtras(intent))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "VEIL is managed by an admin. Disabling it notifies them."
}

/** Shared by both provisioning paths. */
object Provisioning {
    const val EXTRA_SERVER = "veil_server"
    const val EXTRA_PAIR_CODE = "veil_pair_code"

    fun finish(context: Context, extras: PersistableBundle?) {
        val server = extras?.getString(EXTRA_SERVER).orEmpty()
        val code = extras?.getString(EXTRA_PAIR_CODE).orEmpty()
        VeilLog.i("Provisioning complete; pairing extras present=${server.isNotEmpty() && code.isNotEmpty()}")
        if (server.isNotEmpty() && code.isNotEmpty()) {
            val r = RemoteStore.get(context)
            r.pendingPairServer = server
            r.pendingPairCode = code
        }
        DeviceOwner.applyBaseline(context)
        RemoteSync.start(context)
    }
}

internal fun adminExtras(intent: Intent?): PersistableBundle? {
    intent ?: return null
    return if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
    }
}

/**
 * Android 10+ QR provisioning asks which mode to set up. VEIL only supports a
 * fully managed device (Device Owner), never a work profile.
 */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
        adminExtras(intent)?.let { result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, it) }
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        /** DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE (API 29). */
        const val PROVISIONING_MODE_FULLY_MANAGED_DEVICE = 1
    }
}

/** Android 10+: called once VEIL is Device Owner, before setup finishes. Apply lockdown and queue pairing. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Provisioning.finish(this, adminExtras(intent))
        setResult(RESULT_OK)
        finish()
    }
}
