package io.github.revtale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeTestTest {
    @Test fun everyStepNamesItselfOnTheDash() {
        val steps = CodeTest.steps()
        assertEquals(128 + 8 + 4 + 8 + 64, steps.size)
        assertTrue(steps.all { Protocol.clusterText(it.label) == it.label && it.label.length <= 16 })
        val arrow12 = CodeTest.navStep(steps[12])!!
        assertEquals(12, arrow12.pictogram); assertEquals(12, arrow12.distToTurnM)
        assertEquals(null, CodeTest.navStep(steps.last()))       // feedback ids go on the custom line
    }
}
