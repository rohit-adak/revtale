package io.github.revtale

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads the ongoing turn-by-turn notification of Google Maps (or another supported navigation app) and mirrors
 * it on the cluster. Needs "Notification access" granted to RevTale in Android settings. Nothing is sent anywhere
 * except to the rider's own bike.
 */
class NavListener : NotificationListenerService() {

    companion object {
        val NAV_APPS = setOf("com.google.android.apps.maps", "com.google.android.apps.mapslite")
        /** SMS / RCS apps: replies to the rider's text-back are read from their notifications too. */
        val MESSAGE_APPS = setOf("com.samsung.android.messaging", "com.google.android.apps.messaging")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in MESSAGE_APPS) { onMessage(sbn); return }
        if (sbn.packageName !in NAV_APPS || !sbn.isOngoing) return
        if (!BleService.prefs(this).getBoolean("mapsToCluster", true)) return
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val info = NavParser.parse(title, text, sub) ?: return
        val big = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        BleService.instance?.onNavInfo(info, "title=$title | text=$text | sub=$sub | big=$big")
    }

    private fun onMessage(sbn: StatusBarNotification) {
        val e = sbn.notification.extras
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val sender = (e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: e.getCharSequence(Notification.EXTRA_TITLE))?.toString() ?: return
        // MessagingStyle: the newest message; otherwise the plain text
        @Suppress("DEPRECATION")
        val last = e.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? android.os.Bundle
        val body = (last?.getCharSequence("text") ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return
        BleService.instance?.onMessageNotification(sender, body)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in NAV_APPS && sbn.isOngoing) BleService.instance?.onNavEnded()
    }
}
