package io.github.revtale

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.IntentSender

/**
 * "No notification while waiting": the bike becomes a companion device. Android itself watches for it at low power
 * and wakes RevTale when it shows up (ignition on), so no service — and no notification — has to run in between.
 * While a bike is connected the normal ride notification shows, as Android requires.
 */
object BikePresence {
    fun on(ctx: Context) = BleService.prefs(ctx).getBoolean("presenceMode", false)

    private fun cdm(ctx: Context) = ctx.getSystemService(CompanionDeviceManager::class.java)

    /** Is the active bike already a companion device? */
    fun associated(ctx: Context, addr: String): Boolean = runCatching {
        cdm(ctx).myAssociations.any { it.deviceMacAddress?.toString().equals(addr, true) }
    }.getOrDefault(false)

    /** Asks Android to link the bike (one system dialog), then starts watching for it. */
    fun enable(activity: Activity, addr: String, done: (String?) -> Unit) {
        val m = cdm(activity)
        if (associated(activity, addr)) { start(activity, addr); done(null); return }
        val filter = BluetoothLeDeviceFilter.Builder().setScanFilter(ScanFilter.Builder().setDeviceAddress(addr).build()).build()
        val req = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        m.associate(req, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(sender: IntentSender) {
                runCatching { @Suppress("DEPRECATION") activity.startIntentSenderForResult(sender, 77, null, 0, 0, 0) }
                    .onFailure { done("Couldn't open the pairing screen: ${it.message}") }
            }
            override fun onAssociationCreated(info: AssociationInfo) { start(activity, addr); done(null) }
            override fun onFailure(error: CharSequence?) { done(error?.toString() ?: "Android didn't link the bike.") }
        })
    }

    private fun start(ctx: Context, addr: String) {
        runCatching { @Suppress("DEPRECATION") cdm(ctx).startObservingDevicePresence(addr) }
        BleService.prefs(ctx).edit().putBoolean("presenceMode", true).apply()
    }

    /** Back to the always-waiting service (the old way). */
    fun disable(ctx: Context) {
        BleService.prefs(ctx).edit().putBoolean("presenceMode", false).apply()
        BleService.prefs(ctx).getString("bike", null)?.let { a -> runCatching { @Suppress("DEPRECATION") cdm(ctx).stopObservingDevicePresence(a) } }
    }
}

/** Android calls this when the linked bike appears (ignition on) or disappears. */
class BikePresenceService : CompanionDeviceService() {
    override fun onDeviceAppeared(info: AssociationInfo) {
        if (!BikePresence.on(this)) return
        val addr = info.deviceMacAddress?.toString() ?: return
        if (!addr.equals(BleService.prefs(this).getString("bike", null), true)) return
        BleService.startAuto(this, force = true)
    }

    override fun onDeviceDisappeared(info: AssociationInfo) {
        if (BikePresence.on(this)) BleService.instance?.presenceGone()
    }
}
