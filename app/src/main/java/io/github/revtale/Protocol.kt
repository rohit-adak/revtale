package io.github.revtale

/**
 * The bike <-> phone frame format, kept free of Android code so it can be unit-tested and reused.
 *
 * Every frame is 20 bytes: [0] start (5A = data/control, 5B = info/text), [1] data id, [2..18] payload, [19] = FF.
 * Payload bytes 2..18 are XOR-ed with a one-byte key that differs between bike families. Frames from the bike carry
 * a checksum in byte 18: 255 - (sum of plain bytes 0..17 mod 256).
 *
 * The key is never hard-coded per user: [KeyDetector] finds it from the first frames the bike sends.
 */
object Protocol {
    const val FRAME_LEN = 20
    /** Key used for the phone's first handshake before the bike's own frames confirm its key.
     *  The bike streams only zero frames until it receives this handshake. */
    const val HANDSHAKE_KEY = 0xEB
    const val START_DATA = 0x5A
    const val START_INFO = 0x5B

    /** Keys seen so far; tried first, but detection works for any key. */
    val KNOWN_KEYS = listOf(0xEB, 0xEA)

    /** XOR bytes 2..18 with [key]; header and trailing FF stay plain. The same call encodes and decodes. */
    fun xor(frame: ByteArray, key: Int): ByteArray =
        frame.copyOf().also { for (i in 2 until it.size - 1) it[i] = (it[i].toInt() xor key).toByte() }

    fun isFrame(raw: ByteArray) =
        raw.size == FRAME_LEN && ((raw[0].toInt() and 0xFF) == START_DATA || (raw[0].toInt() and 0xFF) == START_INFO)

    /** True when a decoded (plain) frame's checksum is valid. */
    fun checksumOk(plain: ByteArray): Boolean {
        if (plain.size != FRAME_LEN) return false
        val sum = (0 until 18).sumOf { plain[it].toInt() and 0xFF }
        return (sum % 256) + (plain[18].toInt() and 0xFF) == 255
    }

    /** Decode a raw frame from the bike; null if it is not a frame or the checksum fails with this key. */
    fun decode(raw: ByteArray, key: Int): ByteArray? {
        if (!isFrame(raw)) return null
        val d = xor(raw, key)
        return if (checksumOk(d)) d else null
    }

    /** Read an unsigned big-endian number at a 1-based byte position (the numbering the official field map uses). */
    fun u(plain: ByteArray, start1: Int, len: Int = 1): Int {
        var v = 0
        for (i in 0 until len) v = (v shl 8) or (plain[start1 - 1 + i].toInt() and 0xFF)
        return v
    }

    // ---- frames the phone sends (display / info only) --------------------------------------------------------

    /** Characters the cluster's text line can draw (font scan on a real cluster). Everything else draws nothing. */
    const val CLUSTER_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!\"/:;<=>?@[\\]^_` "

    /** Uppercase and replace anything the cluster can't draw: '.' and ',' become ':' / ' ', the rest a space. */
    fun clusterText(text: String): String =
        text.uppercase().map { c ->
            when {
                c in CLUSTER_GLYPHS -> c
                c == '.' -> ':'
                else -> ' '
            }
        }.joinToString("").trim()

    private fun ascii(text: String, max: Int): ByteArray =
        clusterText(text).take(max).toByteArray(Charsets.US_ASCII)

    /** Set byte 18 to the checksum over plain bytes 0..17 (used by navigation frames). */
    fun withChecksum(plain: ByteArray): ByteArray = plain.copyOf().also {
        val sum = (0 until 18).sumOf { i -> it[i].toInt() and 0xFF }
        it[18] = (255 - sum % 256).toByte()
    }

    /** 5B <id> <text, max 17 ASCII> <zero pad> FF */
    fun textFrame(id: Int, text: String): ByteArray {
        val out = ByteArray(FRAME_LEN)
        out[0] = START_INFO.toByte(); out[1] = id.toByte()
        ascii(text, 17).copyInto(out, 2)
        out[19] = 0xFF.toByte()
        return out
    }

    const val ID_RIDER_NAME = 0x52
    const val ID_CUSTOM_LINE = 0x4C
    const val ID_MOBILE_DATA = 0x4A
    const val ID_CALLER = 0x43

    fun riderNameFrame(name: String) = textFrame(ID_RIDER_NAME, name)

    /** 5B 4C 00 <feedbackId> <text, max 15> <pad> FF — custom line with a feedback/icon id. */
    fun feedbackFrame(feedbackId: Int, text: String): ByteArray {
        val out = ByteArray(FRAME_LEN)
        out[0] = START_INFO.toByte(); out[1] = ID_CUSTOM_LINE.toByte(); out[2] = 0; out[3] = feedbackId.toByte()
        ascii(text, 15).copyInto(out, 4)
        out[19] = 0xFF.toByte()
        return out
    }

    /** Phone status the cluster shows: signal 0-4, battery level 0-5, 12 h clock and date.
     *  [smsStatus] 1 = built-in "SMS SENT SUCCESS", 2 = "SMS NOT SENT"; [unreadSms] = envelope counter. */
    data class PhoneStatus(
        val signal: Int, val batteryLevel: Int, val overspeedKmh: Int = 120,
        val hour12: Int, val minute: Int, val second: Int, val pm: Boolean,
        val day: Int, val month: Int, val year2: Int,
        val smsStatus: Int = 0, val unreadSms: Int = 0
    )

    /** 5B 4A — sent every 2 s; keeps the link alive and drives the clock / phone icons on the cluster. */
    fun mobileDataFrame(s: PhoneStatus): ByteArray {
        val out = ByteArray(FRAME_LEN)
        out[0] = START_INFO.toByte(); out[1] = ID_MOBILE_DATA.toByte()
        out[2] = (s.signal.coerceIn(0, 4) * 16 + s.batteryLevel.coerceIn(0, 5)).toByte()
        out[3] = s.overspeedKmh.coerceIn(0, 255).toByte()
        out[5] = s.smsStatus.coerceIn(0, 2).toByte()
        out[15] = s.unreadSms.coerceIn(0, 99).toByte()
        out[6] = s.hour12.toByte(); out[7] = s.minute.toByte(); out[8] = s.second.toByte(); out[9] = (if (s.pm) 1 else 0).toByte()
        out[12] = s.day.toByte(); out[13] = s.month.toByte(); out[14] = s.year2.toByte()
        out[19] = 0xFF.toByte()
        return out
    }

    // ---- navigation (sequence captured from the official app and rebuilt byte-exact) ---------------------------

    /** Raw ASCII text frame, case kept — the nav start frames must match exactly. */
    private fun rawTextFrame(id: Int, text: String): ByteArray {
        val out = ByteArray(FRAME_LEN)
        out[0] = START_INFO.toByte(); out[1] = id.toByte()
        text.take(17).toByteArray(Charsets.US_ASCII).copyInto(out, 2)
        out[19] = 0xFF.toByte()
        return out
    }

    /** Sent once to switch the cluster into navigation mode. These two go out PLAIN (not XOR-ed). */
    fun navStartFrames(): List<ByteArray> = listOf(rawTextFrame(ID_CUSTOM_LINE, "Navigation"), rawTextFrame(0x63, "Assist On"))

    /** One navigation update; resend about every 400 ms. Distances in metres, [pictogram] = cluster arrow code. */
    data class NavStep(
        val distToTurnM: Int, val minutesLeft: Int, val distLeftM: Int, val pictogram: Int,
        val navStatus: Int = 1, val unit: Int = 0, val alert: Int = 0, val prefix: Int = 0
    )

    /** 5A 4E: dist(2) | time left(2) | dist left(3) | pictogram | strings=1 | status | unit | alert | prefix | 0 0 0 | ck | FF */
    fun navFrame(s: NavStep): ByteArray {
        val out = ByteArray(FRAME_LEN)
        out[0] = START_DATA.toByte(); out[1] = 0x4E
        val d = s.distToTurnM.coerceIn(0, 0xFFFF); out[2] = (d shr 8).toByte(); out[3] = d.toByte()
        val t = s.minutesLeft.coerceIn(0, 0xFFFF); out[4] = (t shr 8).toByte(); out[5] = t.toByte()
        val l = s.distLeftM.coerceIn(0, 0xFFFFFF); out[6] = (l shr 16).toByte(); out[7] = (l shr 8).toByte(); out[8] = l.toByte()
        out[9] = s.pictogram.toByte(); out[10] = 1; out[11] = s.navStatus.toByte()
        out[12] = s.unit.toByte(); out[13] = s.alert.toByte(); out[14] = s.prefix.toByte()
        out[19] = 0xFF.toByte()
        return withChecksum(out)
    }

    /** 5B 4F: maneuver text (≤16 chars) with checksum; sent alongside [navFrame]. */
    fun maneuverFrame(text: String): ByteArray {
        val out = ByteArray(FRAME_LEN)
        out[0] = START_INFO.toByte(); out[1] = 0x4F
        ascii(text, 16).copyInto(out, 2)
        out[19] = 0xFF.toByte()
        return withChecksum(out)
    }

    /**
     * Gear from 5A11 byte 6: the low nibble (0 = neutral, 1–6); the high nibble carries flags (seen 0x10, 0x20).
     * A calibrated bike ([gearMap], raw nibble → gear) wins; [mapped] = false for values that are already gears.
     */
    fun gear(byte6: Int, mapped: Boolean = true): Int {
        val n = byte6 and 0x0F
        if (mapped) gearMap[n]?.let { return it }
        return if (n in 0..6) n else 0
    }

    /** Set from the rider's calibration; empty = the RTR 160 4V (nibble = gear). */
    @Volatile var gearMap: Map<Int, Int> = emptyMap()

    fun batteryLevel(percent: Int) = when (percent) { in 0..9 -> 0; in 10..19 -> 1; in 20..39 -> 2; in 40..59 -> 3; in 60..79 -> 4; else -> 5 }
}

/**
 * Finds the bike's XOR key from incoming frames: a key is accepted once it gives a valid checksum on
 * [needed] different frames in a row and no other key does. Works for any bike family without configuration.
 */
class KeyDetector(private val needed: Int = 3, private val preferred: Int? = null) {
    private var candidates: Set<Int> = (0..255).toSet()
    private val seen = HashSet<String>()
    private var preferredHits = 0
    var key: Int? = null
        private set

    /** Feed a raw frame; returns the key once known. */
    fun offer(raw: ByteArray): Int? {
        key?.let { return it }
        if (!Protocol.isFrame(raw)) return null
        if (!seen.add(raw.joinToString("") { "%02x".format(it) })) return null // identical frames prove nothing new
        // A key remembered for this bike only needs confirming, not searching for.
        if (preferred != null && Protocol.checksumOk(Protocol.xor(raw, preferred)) && ++preferredHits >= 2) {
            key = preferred
            return key
        }
        val passing = candidates.filter { Protocol.checksumOk(Protocol.xor(raw, it)) }.toSet()
        if (passing.isEmpty()) { candidates = (0..255).toSet(); seen.clear(); return null } // noise: start over
        candidates = passing
        if (candidates.size == 1 && seen.size >= needed) key = candidates.first()
        return key
    }
}
