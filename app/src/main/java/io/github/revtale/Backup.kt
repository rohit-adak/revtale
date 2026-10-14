package io.github.revtale

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup of everything the rider would miss after a reinstall: ride files, the fuel log, saved places and settings.
 * One zip the rider saves wherever they like (Drive, Downloads). Never includes tokens, keys or session logs.
 */
object Backup {
    /** Settings worth carrying over (no tokens / API keys, no per-install state). */
    val SETTINGS = listOf("name", "bike", "bikeName", "bikes", "autoConnect", "autoLink", "recordRides", "mapsToCluster",
        "compassOnCluster", "callsToCluster", "assistantOnHold", "callBackSms", "theme", "fuelPrice", "places",
        "rideSummaryOnCluster", "etaOnCluster", "weatherOnCluster", "parkedAlert", "serviceKm")

    /** Only these names are ever read back, so a crafted zip can't write anywhere else (no "../"). */
    private val RIDE = Regex("rides/ride-[0-9]{8}-[0-9]{6}\\.csv")
    fun allowed(name: String) = name == "fuel.csv" || name == "settings.json" || RIDE.matches(name)

    fun write(out: OutputStream, files: List<Pair<String, File>>, settings: String) {
        ZipOutputStream(out).use { z ->
            files.forEach { (name, f) -> z.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            z.putNextEntry(ZipEntry("settings.json")); z.write(settings.toByteArray()); z.closeEntry()
        }
    }

    private const val MAX = 20 * 1024 * 1024

    /** Allowed entries only, each at most 20 MB. */
    fun read(input: InputStream): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && allowed(e.name)) {
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (buf.size() <= MAX) { val n = z.read(chunk); if (n < 0) break; buf.write(chunk, 0, n) }
                    if (buf.size() <= MAX) out[e.name] = buf.toByteArray()
                }
                z.closeEntry()
            }
        }
        return out
    }

    /** Fuel log lines from both sides, one per fill-up time, oldest first. */
    fun mergeFuel(mine: List<String>, theirs: List<String>): List<String> =
        (mine + theirs).mapNotNull { l -> FillUp.parse(l)?.let { it.tMs to l } }.toMap().toSortedMap().values.toList()
}
