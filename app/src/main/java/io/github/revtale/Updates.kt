package io.github.revtale

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer build published as a GitHub release (tag "v0.8.0", one .apk asset). */
data class UpdateInfo(val version: String, val notes: String, val apiAssetUrl: String, val sizeBytes: Long, val page: String)

/** Version compare, pure Kotlin, unit-tested: "v0.8.0" vs "0.7.0". */
object Versions {
    fun parts(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V")
        .split('.', '-').map { it.takeWhile(Char::isDigit) }.takeWhile { it.isNotEmpty() }.map { it.toInt() }

    fun isNewer(tag: String, current: String): Boolean {
        val a = parts(tag); val b = parts(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/**
 * App updates from the public GitHub releases of [REPO], no account or token needed. Releases are signed with the
 * same key, so Android accepts them as an update.
 */
object Updater {
    const val REPO = "rohit-adak/revtale"
    private const val ACTION_INSTALLED = "io.github.revtale.UPDATE_STATUS"
    private const val SEP = '\u0001'

    /** Keeps the last result for Settings ("Checked 11 Oct 21:40 · up to date" or the error) and the found update. */
    fun remember(ctx: Context, r: Result<UpdateInfo?>) {
        val time = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val u = r.getOrNull()
        BleService.prefs(ctx).edit()
            .putLong("updateCheckedAt", System.currentTimeMillis())
            .putString("updateStatus", "Checked $time · " + (r.exceptionOrNull()?.message ?: u?.let { "${it.version} available" } ?: "up to date"))
            .putString("updateFound", u?.let { listOf(it.version, it.apiAssetUrl, it.sizeBytes.toString(), it.page, it.notes).joinToString(SEP.toString()) })
            .apply()
    }

    /** The update the background check found, if any. */
    fun found(ctx: Context): UpdateInfo? = BleService.prefs(ctx).getString("updateFound", null)?.split(SEP)
        ?.takeIf { it.size >= 5 }?.let { UpdateInfo(it[0], it[4], it[1], it[2].toLongOrNull() ?: 0, it[3]) }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    /** The latest release if it's newer than [current]; throws with a readable message on failure. Off the main thread. */
    fun check(current: String): UpdateInfo? {
        val c = open("https://api.github.com/repos/$REPO/releases/latest", "application/vnd.github+json")
        try {
            when (c.responseCode) {
                200 -> {}
                404 -> error("No release yet.")
                403, 429 -> error("GitHub is busy (${c.responseCode}). Try again later.")
                else -> error("GitHub answered ${c.responseCode}.")
            }
            val j = org.json.JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name")
            if (!Versions.isNewer(tag, current)) return null
            val assets = j.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: error("Release $tag has no .apk")
            return UpdateInfo(tag.removePrefix("v"), j.optString("body").trim(), apk.getString("url"), apk.optLong("size"), j.optString("html_url"))
        } finally { c.disconnect() }
    }

    /** Downloads the APK into the cache, reporting 0..1 progress. */
    fun download(ctx: Context, u: UpdateInfo, progress: (Float) -> Unit): File {
        // the API answers with a redirect to a signed storage URL
        var c = open(u.apiAssetUrl, "application/octet-stream").apply { instanceFollowRedirects = false }
        if (c.responseCode in 300..399) {
            val to = c.getHeaderField("Location"); c.disconnect()
            c = (URL(to).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 30_000 }
        }
        if (c.responseCode != 200) error("Download failed (${c.responseCode}).")
        val out = File(ctx.cacheDir, "update.apk")
        val total = c.contentLengthLong.takeIf { it > 0 } ?: u.sizeBytes
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                }
            }
        }
        c.disconnect()
        return out
    }

    /** Hands the APK to Android's installer; Android asks the rider to confirm (and, the first time, to allow it). */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { input -> s.openWrite("app.apk", 0, apk.length()).use { input.copyTo(it); s.fsync(it) } }
            val status = PendingIntent.getBroadcast(ctx, id, Intent(ACTION_INSTALLED).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(status.intentSender)
        }
    }

    /** Shows Android's confirm screen when the installer asks for it. Register while the app is open. */
    class StatusReceiver(private val onMessage: (String) -> Unit) : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirm?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PackageInstaller.STATUS_SUCCESS -> onMessage("Updated.")
                else -> onMessage("Update not installed: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"))
            }
        }
    }

    val statusFilter get() = android.content.IntentFilter(ACTION_INSTALLED)

    const val NOTIF_UPDATE = 44
    private const val CHANNEL_UPDATES = "revtale_updates"

    /**
     * Background check (the 6-hourly job, and the bike service): a notification once per new version, with a tap
     * that opens the app's update dialog. Blocking; call off the main thread.
     */
    fun checkAndNotify(ctx: Context): Result<UpdateInfo?> {
        val current = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"
        val result = runCatching { check(current) }
        remember(ctx, result)
        val u = result.getOrNull() ?: return result
        val prefs = BleService.prefs(ctx)
        if (prefs.getString("updateNotified", null) == u.version) return result
        prefs.edit().putString("updateNotified", u.version).apply()
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL_UPDATES, "App updates", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(NOTIF_UPDATE, android.app.Notification.Builder(ctx, CHANNEL_UPDATES)
            .setContentTitle("RevTale ${u.version} is available")
            .setContentText(u.notes.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Tap to update (you have $current).")
            .setStyle(android.app.Notification.BigTextStyle().bigText((u.notes + "\n\nYou have $current. Tap to update.").trim()))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(Open.pending(ctx, Open.SETTINGS))
            .build())
        return result
    }
}

/** Checks for a new release every 6 hours even when the app and the bike service aren't running. */
class UpdateJob : android.app.job.JobService() {
    companion object {
        private const val ID = 4401

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(android.app.job.JobScheduler::class.java)
            if (js.getPendingJob(ID) != null) return
            js.schedule(android.app.job.JobInfo.Builder(ID, android.content.ComponentName(ctx, UpdateJob::class.java))
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6 * 3600_000L)
                .setPersisted(true)
                .build())
        }
    }

    override fun onStartJob(params: android.app.job.JobParameters): Boolean {
        Thread { runCatching { Updater.checkAndNotify(this) }; jobFinished(params, false) }.start()
        return true
    }

    override fun onStopJob(params: android.app.job.JobParameters) = true
}
