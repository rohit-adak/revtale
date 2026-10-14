package io.github.revtale

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a phone restart or an app update, start waiting for the bike again — the app never has to be opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> BleService.startAuto(ctx)
        }
    }
}
