package io.github.revtale

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One JSON object per line (JSONL). Every event carries wall-clock ms, ISO time and a monotonic clock. */
class SessionLog(private val ctx: Context) {
    private val exec = Executors.newSingleThreadExecutor()
    private var writer: BufferedWriter? = null
    var file: File? = null
        private set
    val count = AtomicLong()

    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneId.systemDefault())
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    fun open(name: String): File {
        val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val f = File(dir, "$name.jsonl")
        file = f
        writer = BufferedWriter(FileWriter(f, true))
        count.set(0)
        return f
    }

    /** Writes the event and returns a short human-readable line for the on-screen tail. */
    fun log(src: String, ev: String, vararg kv: Pair<String, Any?>): String {
        val ms = System.currentTimeMillis()
        val j = JSONObject()
        j.put("ms", ms)
        j.put("t", iso.format(Instant.ofEpochMilli(ms)))
        j.put("mono_ms", SystemClock.elapsedRealtime())
        j.put("src", src)
        j.put("ev", ev)
        for ((k, v) in kv) {
            when (v) {
                null -> j.put(k, JSONObject.NULL)
                is Number, is Boolean -> j.put(k, v)
                else -> j.put(k, v.toString())
            }
        }
        count.incrementAndGet()
        val text = j.toString()
        exec.execute {
            try {
                writer?.let { it.write(text); it.newLine(); it.flush() }
            } catch (_: Exception) {
            }
        }
        val detail = kv.joinToString(" ") { "${it.first}=${it.second}" }
        return "${clock.format(Instant.ofEpochMilli(ms))} $src/$ev $detail"
    }

    fun close() {
        exec.execute {
            try { writer?.close() } catch (_: Exception) {}
            writer = null
        }
    }

    /** Copies the session file into Downloads/RevTale so it is easy to find and pull. Blocking. */
    fun saveToDownloads(target: File? = file): Uri? {
        val f = target ?: return null
        exec.submit {}.get() // wait for queued writes + close
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.nameWithoutExtension + ".txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RevTale")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        return uri
    }
}
