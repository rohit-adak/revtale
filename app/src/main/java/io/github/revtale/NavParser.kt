package io.github.revtale

import kotlin.math.roundToInt

/** Turn types we can recognise in a navigation app's notification text. */
enum class Maneuver {
    STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN_LEFT, UTURN_RIGHT,
    KEEP_LEFT, KEEP_RIGHT, ROUNDABOUT, EXIT_LEFT, EXIT_RIGHT, MERGE, DESTINATION, UNKNOWN
}

/** What a navigation notification says right now. Distances in metres; null when the text did not contain it. */
data class NavInfo(
    val maneuver: Maneuver, val distToTurnM: Int?, val minutesLeft: Int?, val distLeftM: Int?, val instruction: String,
    /** Road to turn onto ("Turn right onto MG Road" → "MG Road"), null when the text names none. */
    val road: String? = null
)

/**
 * Reads Google Maps (and similar) turn-by-turn notifications: title / text / sub-text such as
 * "250 m", "Turn left onto MG Road", "12 min · 4.3 km · 10:45 ETA". Language: English. Pure Kotlin, unit-tested.
 */
object NavParser {

    private val distRe = Regex("""(\d+(?:[.,]\d+)?)\s*(km|m|mi|ft)\b""", RegexOption.IGNORE_CASE)
    private val hourMinRe = Regex("""(\d+)\s*(?:hr|h|hour|hours)\s*(?:(\d+)\s*min)?""", RegexOption.IGNORE_CASE)
    private val minRe = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
    /** Google Maps often only says "Arrive 4:02 pm" in the sub-text, with no minutes or distance left. */
    private val arriveRe = Regex("""(\d{1,2}):(\d{2})\s*([ap])\.?\s*m""", RegexOption.IGNORE_CASE)
    private val roadRe = Regex("""\b(?:onto|on to|towards|toward|into|on)\s+(.+)$""", RegexOption.IGNORE_CASE)

    private val sideRe = Regex("""(the\s+)?(left|right)(\s+side)?""", RegexOption.IGNORE_CASE)

    /** The road named in an instruction, e.g. "Turn right onto MG Road" → "MG Road". */
    fun roadOf(instruction: String): String? =
        roadRe.find(instruction)?.groupValues?.get(1)?.trim()?.trimEnd('.')
            ?.takeIf { it.isNotBlank() && distRe.find(it) == null && !sideRe.matches(it) }

    fun toMetres(value: String, unit: String): Int {
        val v = value.replace(',', '.').toDouble()
        return when (unit.lowercase()) {
            "km" -> v * 1000
            "mi" -> v * 1609.34
            "ft" -> v * 0.3048
            else -> v
        }.roundToInt()
    }

    fun maneuverOf(text: String): Maneuver {
        val t = text.lowercase()
        return when {
            "destination" in t || "arrive" in t || "arrived" in t -> Maneuver.DESTINATION
            "u-turn" in t || "u turn" in t || "make a u" in t -> if ("right" in t) Maneuver.UTURN_RIGHT else Maneuver.UTURN_LEFT
            "roundabout" in t || "rotary" in t -> Maneuver.ROUNDABOUT
            "exit" in t || "ramp" in t -> if ("left" in t) Maneuver.EXIT_LEFT else Maneuver.EXIT_RIGHT
            "merge" in t -> Maneuver.MERGE
            "keep left" in t -> Maneuver.KEEP_LEFT
            "keep right" in t -> Maneuver.KEEP_RIGHT
            "slight left" in t -> Maneuver.SLIGHT_LEFT
            "slight right" in t -> Maneuver.SLIGHT_RIGHT
            "sharp left" in t -> Maneuver.SHARP_LEFT
            "sharp right" in t -> Maneuver.SHARP_RIGHT
            "left" in t -> Maneuver.LEFT
            "right" in t -> Maneuver.RIGHT
            "continue" in t || "straight" in t || "head " in t -> Maneuver.STRAIGHT
            else -> Maneuver.UNKNOWN
        }
    }

    /** [title], [text], [subText] as found in the notification extras (any may be null). */
    fun parse(title: String?, text: String?, subText: String?, now: java.time.LocalTime = java.time.LocalTime.now()): NavInfo? {
        val parts = listOfNotNull(title, text).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        // the distance to the next turn is in the title/text; the sub-text holds the trip totals
        val distToTurn = parts.firstNotNullOfOrNull { p -> distRe.find(p)?.let { toMetres(it.groupValues[1], it.groupValues[2]) } }
        val instruction = parts.firstOrNull { maneuverOf(it) != Maneuver.UNKNOWN } ?: parts.last()
        val maneuver = maneuverOf(instruction)
        var minutes: Int? = null
        var distLeft: Int? = null
        if (subText != null) {
            hourMinRe.find(subText)?.let { minutes = it.groupValues[1].toInt() * 60 + (it.groupValues[2].toIntOrNull() ?: 0) }
                ?: minRe.find(subText)?.let { minutes = it.groupValues[1].toInt() }
            distRe.find(subText)?.let { distLeft = toMetres(it.groupValues[1], it.groupValues[2]) }
            if (minutes == null) minutes = minutesUntil(subText, now)
        }
        if (maneuver == Maneuver.UNKNOWN && distToTurn == null) return null
        return NavInfo(maneuver, distToTurn, minutes, distLeft, instruction.trim(), roadOf(instruction))
    }

    /** Minutes from [now] until an "Arrive 4:02 pm" time (past midnight counts as tomorrow); null without one. */
    fun minutesUntil(text: String, now: java.time.LocalTime): Int? {
        val m = arriveRe.find(text) ?: return null
        val h = m.groupValues[1].toInt() % 12 + if (m.groupValues[3].equals("p", true)) 12 else 0
        val arrive = h * 60 + m.groupValues[2].toInt()
        return ((arrive - (now.hour * 60 + now.minute)) + 1440) % 1440
    }

    private val leadingDistRe = Regex("""^\s*\d+(?:[.,]\d+)?\s*(?:km|m|mi|ft)\b\s*[·•\-–:]?\s*""", RegexOption.IGNORE_CASE)

    /**
     * The help line next to the arrow (cluster custom line, ≤ 17 chars): "^ SOUTH" for "Head south", else the road
     * ("MG ROAD"), else Maps' words without the distance ("LAKE VIEW", "TURN RIGHT"). The cluster's own
     * distance field shows 10–99 m as "0X m", so in that range the line starts with the real distance ("70M ...").
     */
    fun helpLine(info: NavInfo, distM: Int, scroll: Boolean = false): String {
        Pictograms.heading(info.instruction)?.let { return "^ " + Compass.words(it - Pictograms.HEADING_NORTH) }
        if (info.maneuver == Maneuver.DESTINATION) {
            val t = info.instruction.lowercase()
            val side = when { "left" in t -> " ON LEFT"; "right" in t -> " ON RIGHT"; else -> "" }
            return if ("arrived" in t || distM in 1..15) "YOU HAVE ARRIVED" else ((if (distM in 10..99) "${distM}M " else "") + "ARRIVING$side").take(17)
        }
        val prefix = if (distM in 10..99) "${distM}M " else ""
        val target = target(info)
        // the arrow already shows the turn, so the line names where it goes; a keep also says which side,
        // because the keep arrows are easy to miss at a glance ("KEEP RT FLYOVER")
        val side = when (info.maneuver) { Maneuver.KEEP_RIGHT -> "KEEP RT "; Maneuver.KEEP_LEFT -> "KEEP LT "; else -> "" }
        val candidates = if (target.isBlank()) listOf(prefix + shortText(info.maneuver))
            else listOf(prefix + side + target, prefix + target, target)
        if (scroll) {
            // the dash scrolls long text, so nothing gets cut: Maps' whole sentence when it names no road
            // ("KEEP RIGHT TO TAKE THE FLYOVER"), else the whole road name
            val words = Protocol.clusterText(info.road ?: info.instruction.replace(leadingDistRe, "").substringBefore(','))
                .replace(Regex(" +"), " ").trim()
            val full = (prefix + words).trim()
            if (full.length > LINE) return full
        }
        candidates.firstOrNull { it.trim().length <= LINE }?.let { return it.trim() }
        // too long: shorten common words, then cut at a word, never mid-word ("KEEP RIGHT TO TAK" on 13 Oct)
        // whichever keeps more whole words; plain words win a tie
        val plain = fit(prefix + target, LINE)
        val short = fit(prefix + shorten(target), LINE)
        return if (short.split(' ').size > plain.split(' ').size) short else plain
    }

    private const val LINE = 17
    private val takeRe = Regex("""\bto\s+(?:take|stay on|continue on|go on|use|get on)\s+(?:the\s+)?(.+)$""", RegexOption.IGNORE_CASE)
    private val leadRe = Regex("""^(?:(?:make\s+a\s+)?u-?turn|(?:turn|keep|slight|sharp|bear|take the|exit|merge)(?:\s+(?:left|right))?|continue(?:\s+straight)?|go straight)\b[\s,]*""", RegexOption.IGNORE_CASE)
    private val ABBREV = linkedMapOf("ROAD" to "RD", "STREET" to "ST", "MARG" to "MG", "HIGHWAY" to "HWY", "EXPRESSWAY" to "EXPWY",
        "JUNCTION" to "JN", "STATION" to "STN", "MARKET" to "MKT", "NAGAR" to "NGR", "CROSS" to "X", "MAIN" to "MN",
        "NORTH" to "N", "SOUTH" to "S", "EAST" to "E", "WEST" to "W", "FLYOVER" to "FLYOVR", "BRIDGE" to "BRDG",
        "SERVICE" to "SVC", "TEMPLE" to "TMPL", "MANDIR" to "MNDR", "HOSPITAL" to "HOSP", "SCHOOL" to "SCH")

    /** Where the step goes: the road, else "…to take the flyover" → FLYOVER, else Maps' words minus the turn itself. */
    internal fun target(info: NavInfo): String {
        val raw = info.road ?: takeRe.find(info.instruction)?.groupValues?.get(1)
            ?: info.instruction.replace(leadingDistRe, "").replace(leadRe, "")
        // a place with its address ("Hanuman Mandir, Bengaluru East"): the name is enough
        return Protocol.clusterText(raw.substringBefore(',').trim().trimEnd('.')).replace(Regex(" +"), " ").trim()
    }

    internal fun shorten(text: String): String =
        text.split(' ').joinToString(" ") { w -> ABBREV[w] ?: w }

    /** Up to [max] characters, cut after a whole word (a lone over-long word is cut hard). */
    internal fun fit(text: String, max: Int): String {
        val t = text.trim()
        if (t.length <= max) return t
        val cut = t.take(max + 1).lastIndexOf(' ')
        return (if (cut > 0) t.take(cut) else t.take(max)).trim()
    }

    /** Short text for the cluster's maneuver line (≤ 16 drawable characters). */
    fun shortText(m: Maneuver): String = when (m) {
        Maneuver.STRAIGHT -> "GO STRAIGHT"
        Maneuver.SLIGHT_LEFT -> "SLIGHT LEFT"
        Maneuver.LEFT -> "TURN LEFT"
        Maneuver.SHARP_LEFT -> "SHARP LEFT"
        Maneuver.SLIGHT_RIGHT -> "SLIGHT RIGHT"
        Maneuver.RIGHT -> "TURN RIGHT"
        Maneuver.SHARP_RIGHT -> "SHARP RIGHT"
        Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT -> "U TURN"
        Maneuver.KEEP_LEFT -> "KEEP LEFT"
        Maneuver.KEEP_RIGHT -> "KEEP RIGHT"
        Maneuver.ROUNDABOUT -> "ROUNDABOUT"
        Maneuver.EXIT_LEFT -> "EXIT LEFT"
        Maneuver.EXIT_RIGHT -> "EXIT RIGHT"
        Maneuver.MERGE -> "MERGE"
        Maneuver.DESTINATION -> "ARRIVING"
        Maneuver.UNKNOWN -> ""
    }
}

/**
 * Maneuver → the cluster's arrow (pictogram) code, read off a filmed "Test directions" run on the real cluster
 * (Apache RTR 160 4V, 11 Oct 2026). Other codes seen: 11/12 T-junction ←┤ ├→, 13/14 end of road ←T T→,
 * 17/18 ramp ↙ ↘, 19/20 bear ↖ ↗ from a stem, 21 crossroads, 22–25 curved, 34/35 lane icons, 36 ferry, 40 no entry,
 * 50–57 compass (N, NE … NW), 70/71 loops, 78 café, 79 fast food, 80 restaurant, 81 fuel, 82–88 winding/loops.
 * 26–33, 38/39, 42–49, 58–64, 72–76 and 89–127 draw nothing. Arrow-test overrides still win.
 * No roundabout arrow is known: 37, once taken for one, draws a house on the dash (rider, 14 Oct 2026). Roundabouts go
 * as text until the rider picks an arrow in calibration.
 */
object Pictograms {
    val CONFIRMED = mapOf(
        Maneuver.LEFT to 0, Maneuver.SHARP_LEFT to 1, Maneuver.SLIGHT_LEFT to 2,
        Maneuver.RIGHT to 3, Maneuver.SHARP_RIGHT to 4, Maneuver.SLIGHT_RIGHT to 5,
        Maneuver.UTURN_LEFT to 6, Maneuver.STRAIGHT to 7, Maneuver.DESTINATION to 8,
        Maneuver.EXIT_RIGHT to 9, Maneuver.EXIT_LEFT to 10, Maneuver.KEEP_LEFT to 15, Maneuver.KEEP_RIGHT to 16,
        Maneuver.UTURN_RIGHT to 41, Maneuver.MERGE to 77)

    /** Compass arrows: 50 = north, then clockwise every 45° to 57 = north-west. */
    const val HEADING_NORTH = 50

    private val headingRe = Regex("""\bhead\s+(north|south|east|west)(?:[\s-]*(east|west))?""", RegexOption.IGNORE_CASE)

    /** "Head south" → its compass arrow (54); null when the instruction is not a heading. */
    fun heading(instruction: String): Int? {
        val m = headingRe.find(instruction) ?: return null
        val name = (m.groupValues[1] + " " + m.groupValues[2]).trim().uppercase()
        val sector = listOf("NORTH", "NORTH EAST", "EAST", "SOUTH EAST", "SOUTH", "SOUTH WEST", "WEST", "NORTH WEST").indexOf(name)
        return if (sector < 0) null else HEADING_NORTH + sector
    }

    fun code(m: Maneuver, overrides: Map<Maneuver, Int>): Int? = overrides[m] ?: CONFIRMED[m]

    private val OTHER = mapOf(
        11 to "T-junction left", 12 to "T-junction right", 13 to "End of road left", 14 to "End of road right",
        17 to "Ramp sharp left", 18 to "Ramp sharp right", 19 to "Bear left", 20 to "Bear right", 21 to "Crossroads",
        22 to "Curve left", 23 to "Curve left", 24 to "Curve right", 25 to "Curve right", 34 to "Lane left", 35 to "Lane right",
        36 to "Ferry", 37 to "House", 40 to "No entry", 70 to "Loop right", 71 to "Loop", 78 to "Cafe", 79 to "Fast food", 80 to "Restaurant",
        81 to "Fuel", 82 to "Zigzag", 83 to "Bear right", 84 to "Winding right", 85 to "Winding road", 86 to "Loop left",
        87 to "Loop left", 88 to "Loop back")

    /** What the real cluster draws for [code] ("Head south", "Fuel" …); null = it draws nothing. */
    fun label(code: Int): String? = when (code) {
        in HEADING_NORTH..HEADING_NORTH + 7 -> "Head " + Compass.words(code - HEADING_NORTH).lowercase()
        else -> CONFIRMED.entries.firstOrNull { it.value == code }?.key?.name?.lowercase()?.replace('_', ' ')
            ?.replaceFirstChar { it.uppercase() } ?: OTHER[code]
    }
}

/** Heading from the phone's GPS course → 8-point compass words the cluster can draw. Pure Kotlin, unit-tested. */
object Compass {
    private val WORDS = listOf("NORTH", "NORTH EAST", "EAST", "SOUTH EAST", "SOUTH", "SOUTH WEST", "WEST", "NORTH WEST")
    private val SHORT = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun sector(bearing: Float): Int = (((bearing % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8
    fun words(sector: Int) = WORDS[sector]
    fun short(sector: Int) = SHORT[sector]

    /** The sector to show: only leave [current] once the bearing is clearly (> 8°) inside a new one, so it doesn't flicker. */
    fun stable(current: Int, bearing: Float): Int {
        val next = sector(bearing)
        if (current < 0 || next == current) return next
        val offset = Math.abs(((bearing - next * 45f) % 360 + 540) % 360 - 180)
        return if (offset <= 22.5f - 8f) next else current
    }
}
