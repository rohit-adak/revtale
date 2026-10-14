package io.github.revtale

/**
 * "Test directions": plays every display code we can send, one every [STEP_MS], so the rider can film the cluster
 * once and every code can be read off the video. Each step also writes its number where the cluster shows it
 * (the text above the arrow and the distance), so the video identifies itself. Display frames only, parked only.
 */
object CodeTest {
    const val STEP_MS = 2000L

    enum class Kind { ARROW, PREFIX, UNIT, ALERT, FEEDBACK, DIST }

    /** One step: [label] goes on the cluster (≤ 16 chars) and in the log; [value] is the code under test. */
    data class Step(val kind: Kind, val value: Int, val label: String, val prefix: Int = 1, val unit: Int = 0, val minutes: Int = 0)

    /**
     * Distance test (≈ 1 min): the cluster shows 10–99 m as "0X m". Tries 70 m under every prefix and unit, then a
     * spread of distances, straight arrow throughout. The ETA on the dash is now + step number, so a video says which
     * step is which.
     */
    fun distanceSteps(): List<Step> {
        val s = (0..7).map { Step(Kind.DIST, 70, "D70 P$it", prefix = it) } +
            (1..3).map { Step(Kind.DIST, 70, "D70 U$it", unit = it) } +
            listOf(5, 9, 10, 15, 25, 50, 65, 75, 99, 100, 150).map { Step(Kind.DIST, it, "D$it") }
        return s.mapIndexed { i, st -> st.copy(minutes = i + 1) }
    }

    fun steps(): List<Step> =
        (0..127).map { Step(Kind.ARROW, it, "ARROW $it") } +
        (0..7).map { Step(Kind.PREFIX, it, "PREFIX $it") } +
        (0..3).map { Step(Kind.UNIT, it, "UNIT $it") } +
        (0..7).map { Step(Kind.ALERT, it, "ALERT $it") } +
        (0..63).map { Step(Kind.FEEDBACK, it, "FB $it") }

    /** The navigation frame for a nav step: distance = the code (so "12 M" on the dash means code 12). */
    fun navStep(s: Step): Protocol.NavStep? = when (s.kind) {
        Kind.ARROW -> Protocol.NavStep(distToTurnM = s.value, minutesLeft = s.value, distLeftM = s.value * 100, pictogram = s.value, prefix = 1)
        Kind.PREFIX -> Protocol.NavStep(100 + s.value, 5, 1500, pictogram = 3, prefix = s.value)
        Kind.UNIT -> Protocol.NavStep(100 + s.value, 5, 1500, pictogram = 3, unit = s.value, prefix = 1)
        Kind.ALERT -> Protocol.NavStep(100 + s.value, 5, 1500, pictogram = 3, alert = s.value, prefix = 1)
        Kind.FEEDBACK -> null
        Kind.DIST -> Protocol.NavStep(s.value, s.minutes, 1500, pictogram = 7, unit = s.unit, prefix = s.prefix)
    }

    fun durationS() = steps().size * STEP_MS / 1000
}
