package io.github.revtale

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A bike the rider chose themselves (owner lock: the app only ever talks to bikes on this list, one at a time). */
data class SavedBike(val addr: String, val name: String)

/**
 * Several bikes on one phone (yours and a second one, or a friend borrowing the phone). The active one stays in the
 * old "bike" / "bikeName" prefs, so everything else keeps working unchanged. Encoding is pure (unit-tested).
 */
object Bikes {
    /** The three letters the dashes put in front of their Bluetooth name (then a serial number). */
    private val NAME_PREFIX = String(charArrayOf('T', 'V', 'S'))

    /** A nearby device named like a connected dash. Only for lists and logs; the saved bike is always the one used. */
    fun looksLikeBike(name: String?) = name?.startsWith(NAME_PREFIX, ignoreCase = true) == true

    fun encode(list: List<SavedBike>): String =
        JSONArray().apply { list.forEach { put(JSONObject().put("addr", it.addr).put("name", it.name)) } }.toString()

    fun decode(s: String?): List<SavedBike> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map { SavedBike(it.getString("addr"), it.optString("name", it.getString("addr"))) }
    }.getOrDefault(emptyList())

    /** All saved bikes; the active one is always included (older installs only had that one). */
    fun all(ctx: Context): List<SavedBike> {
        val p = BleService.prefs(ctx)
        val list = decode(p.getString("bikes", null))
        val active = p.getString("bike", null) ?: return list
        return if (list.any { it.addr == active }) list else list + SavedBike(active, p.getString("bikeName", null) ?: active)
    }

    fun add(ctx: Context, b: SavedBike) {
        val list = all(ctx).filter { it.addr != b.addr } + b
        BleService.prefs(ctx).edit().putString("bikes", encode(list)).apply()
    }

    fun remove(ctx: Context, addr: String) {
        BleService.prefs(ctx).edit().putString("bikes", encode(all(ctx).filter { it.addr != addr })).apply()
    }
}
