package io.github.revtale

/**
 * Incoming calls on the cluster. Pure Kotlin, unit-tested; [BleService] wires it to the phone (call state,
 * contacts, declining, SMS) and to the bike (caller slot 5B43, custom line, the i switch).
 */
object CallerText {
    /** [text] goes to the cluster; [dropped] is what the cluster can't draw (emoji, other scripts), for the log. */
    data class Shown(val text: String, val dropped: String, val usedNumber: Boolean)

    /** The contact name as far as the cluster can draw it; the number only when nothing of the name is left. */
    fun of(name: String?, number: String?): Shown {
        val raw = name?.trim().orEmpty()
        // a dot would become ':' on the cluster ("DR: RAHUL"); in a name it's just a separator
        val clean = Protocol.clusterText(raw.replace('.', ' ')).replace(Regex(" +"), " ")
        val dropped = raw.filter { !it.isWhitespace() && it != '.' && it.uppercaseChar() !in Protocol.CLUSTER_GLYPHS }
        if (clean.any { it.isLetterOrDigit() }) return Shown(clean.take(17), dropped, false)
        // '+' and '-' aren't drawable either: digits only (a +91 number is 12 digits, fits the 17-char slot)
        val digits = number.orEmpty().filter { it.isDigit() }
        return Shown(digits.ifEmpty { "UNKNOWN" }.take(17), dropped, true)
    }
}

/**
 * Texts from someone the rider just texted back ("I'm riding…"): shown on the cluster's 17-character line as
 * "MOM: ON MY WAY SEE YOU" in word-wrapped pages, until the rider presses i. Pure Kotlin, unit-tested.
 */
/**
 * Text longer than the line scrolls through it like an LED sign: one character per step, the start held a moment,
 * then round again with a gap. [frames] are the 17-character windows in order.
 */
object Marquee {
    const val WIDTH = 17
    const val STEP_MS = 300L
    const val HOLD_MS = 1500L

    fun frames(text: String, width: Int = WIDTH, gap: Int = 4): List<String> {
        val t = text.trim()
        if (t.length <= width) return listOf(t)
        val loop = t + " ".repeat(gap)
        val twice = loop + loop
        return loop.indices.map { twice.substring(it, it + width) }
    }
}

object SmsPager {
    const val WIDTH = 17

    /** Same person? Compares the last 10 digits (+91 98765 43210 = 9876543210). */
    fun sameNumber(a: String?, b: String?): Boolean {
        val x = a.orEmpty().filter(Char::isDigit).takeLast(10); val y = b.orEmpty().filter(Char::isDigit).takeLast(10)
        return x.length >= 6 && x == y
    }

    /** "<who>: msg1 msg2" in cluster characters. */
    fun text(who: String, messages: List<String>): String =
        // a full stop would be drawn as ':' (the font has no '.'), so in a message it's just a gap
        (Protocol.clusterText(who) + ": " + Protocol.clusterText(messages.joinToString(" ").replace('.', ' ')))
            .replace(Regex(" +"), " ").trim()

    /** Word-wrapped pages of at most [width] characters; words longer than a page are cut. */
    fun pages(text: String, width: Int = WIDTH): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (w in text.split(' ').filter { it.isNotEmpty() }.flatMap { it.chunked(width) }) {
            line = when {
                line.isEmpty() -> w
                line.length + 1 + w.length <= width -> "$line $w"
                else -> { out += line; w }
            }
        }
        if (line.isNotEmpty()) out += line
        return out
    }
}

/** The i (Info) switch from 5A10 b12 (= 4 while held): one DOWN per press, LONG once it's held [longMs]. */
class InfoButton(private val longMs: Long = 1500) {
    enum class Event { DOWN, LONG }

    /** Counts presses, so a long press can be told apart from the press that declined the call. */
    var pressId = 0; private set
    private var downAt = -1L
    private var longSent = false

    fun feed(held: Boolean, now: Long): Event? {
        if (!held) { downAt = -1; return null }
        if (downAt < 0) { downAt = now; longSent = false; pressId++; return Event.DOWN }
        if (!longSent && now - downAt >= longMs) { longSent = true; return Event.LONG }
        return null
    }
}

/**
 * Ringing → (i pressed: decline) → "hold i to text back" for [promptMs] → (i held: SMS the caller).
 * A call that stops ringing without being answered (missed, or declined on the phone) gets the same offer.
 */
class CallFlow(private val promptMs: Long = 30_000, private val releaseWaitMs: Long = 10_000) {
    sealed class Action {
        object Decline : Action()
        data class OfferSms(val number: String) : Action()
        data class SendSms(val number: String) : Action()
        object Clear : Action()
    }

    @Volatile var ringingNumber: String? = null; private set
    @Volatile var promptNumber: String? = null; private set
    private var promptUntil = 0L
    private var declinePress = -1
    /**
     * Declined with i: the cluster keeps reporting that press (b12 = 2) for a few seconds, so the offer's clock only
     * starts once the switch is let go (or after [releaseWaitMs]). Real rides, 11–12 Oct 2026: the 12 s offer had
     * often run out before the rider held i again, and that hold opened the assistant instead.
     */
    @Volatile var awaitingRelease = false; private set
    private var releaseBy = 0L
    private var ringing = false

    val isRinging get() = ringing

    fun ring(number: String?) { ringing = true; ringingNumber = number; promptNumber = null }

    /** The call was picked up: nothing to offer. */
    fun answered() = reset()

    /** The phone stopped ringing without an answer. */
    fun ended(now: Long): Action? {
        if (!ringing) return null
        ringing = false
        return offer(now)
    }

    fun button(e: InfoButton.Event, pressId: Int, now: Long): Action? = when {
        e == InfoButton.Event.DOWN && ringing -> {
            declinePress = pressId
            ringing = false
            Action.Decline
        }
        // a hold that began before the offer ran out still counts (its LONG lands [LATE_HOLD_MS] later)
        e == InfoButton.Event.LONG && promptNumber != null && !awaitingRelease && now < promptUntil + LATE_HOLD_MS &&
            pressId != declinePress -> {
            val n = promptNumber!!
            reset()
            Action.SendSms(n)
        }
        else -> null
    }

    /** Called after declining (from [button]) to start the offer. */
    fun declined(now: Long): Action {
        val a = offer(now) ?: return Action.Clear
        awaitingRelease = true; releaseBy = now + releaseWaitMs
        return a
    }

    /** The i switch was let go after declining: the offer starts now (the line changes to "hold i"). */
    fun released(now: Long): Action? {
        if (!awaitingRelease) return null
        awaitingRelease = false
        promptUntil = now + promptMs
        return promptNumber?.let { Action.OfferSms(it) }
    }

    /** Starts the offer if the release never showed, and ends it once its time is up. */
    fun tick(now: Long): Action? = when {
        awaitingRelease && now >= releaseBy -> released(now)
        promptNumber != null && !awaitingRelease && now >= promptUntil + LATE_HOLD_MS -> { reset(); Action.Clear }
        else -> null
    }

    private fun offer(now: Long): Action? {
        val n = ringingNumber?.takeIf { it.any(Char::isDigit) } ?: run { reset(); return Action.Clear }
        promptNumber = n; promptUntil = now + promptMs
        return Action.OfferSms(n)
    }

    private fun reset() { ringing = false; promptNumber = null; ringingNumber = null; declinePress = -1; awaitingRelease = false }

    companion object { const val LATE_HOLD_MS = 2000L }
}
