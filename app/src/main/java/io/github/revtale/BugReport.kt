package io.github.revtale

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Report a problem": a zip with what went wrong (the rider's words, the last crash, the newest session logs and
 * phone details), handed to the share sheet so the rider picks who gets it (WhatsApp, mail…). Nothing is uploaded
 * by the app itself.
 */
object BugReport {
    private fun crashFile(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    /** Keeps the stack trace of a crash for the next report, then lets Android show its usual crash dialog. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile(app).writeText("${java.util.Date()} · thread ${t.name}\n${version(app)}\n\n${e.stackTraceToString()}")
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun hasCrash(ctx: Context) = crashFile(ctx).exists()

    private fun version(ctx: Context) = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "RevTale ${it.versionName} (${it.longVersionCode})" }
    }.getOrDefault("RevTale ?")

    /**
     * Builds the zip (blocking) and returns the share intent. [logDays] adds every session log from the last that many
     * days (1 = since midnight today, 0 = none; they may contain routes), newest first, up to [LOG_CAP] bytes in all.
     */
    fun build(ctx: Context, what: String, logDays: Int): Intent {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val zip = File(dir, "revtale-report.zip")
        val prefs = BleService.prefs(ctx)
        val about = buildString {
            appendLine("What happened: ${what.ifBlank { "(not described)" }}")
            appendLine()
            appendLine(version(ctx))
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Bike chosen: ${prefs.getString("bike", null) != null} · auto-connect ${prefs.getBoolean("autoConnect", true)} · " +
                "no-notification mode ${prefs.getBoolean("presenceMode", false)}")
            val s = BleService.instance
            appendLine("Service: ${if (s == null) "off" else "${s.gattState}, link ${s.linkOn}, key ${s.linkKey?.let { "%02X".format(it) } ?: "-"}"}")
            appendLine("Notification access: ${androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)}")
            appendLine("Update status: ${prefs.getString("updateStatus", "-")}")
            s?.tail(60)?.takeIf { it.isNotEmpty() }?.let { appendLine(); appendLine("Recent events:"); it.forEach(::appendLine) }
        }
        ZipOutputStream(zip.outputStream()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("report.txt", about.toByteArray())
            crashFile(ctx).takeIf { it.exists() }?.let { put("crash.txt", it.readBytes()) }
            if (logDays > 0) {
                val since = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    add(java.util.Calendar.DAY_OF_YEAR, -(logDays - 1))
                }.timeInMillis
                var total = 0L
                File(ctx.getExternalFilesDir(null), "logs").listFiles { f -> f.extension == "jsonl" && f.lastModified() >= since }
                    ?.sortedByDescending { it.lastModified() }?.forEach { f ->
                        if (total + f.length() <= LOG_CAP) { total += f.length(); put("logs/${f.name}", f.readBytes()) }
                    }
            }
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", zip)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "RevTale problem report")
            putExtra(Intent.EXTRA_TEXT, "RevTale problem: ${what.take(200)}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Send the report")
    }

    /** Raw size limit for the logs in one report (jsonl zips to roughly a tenth). */
    const val LOG_CAP = 80L * 1024 * 1024

    /** After a report went out, the crash is old news. */
    fun clearCrash(ctx: Context) { crashFile(ctx).delete() }
}
