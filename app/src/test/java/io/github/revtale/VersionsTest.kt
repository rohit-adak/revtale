package io.github.revtale

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun newerTagsOnly() {
        assertTrue(Versions.isNewer("v0.8.0", "0.7.0"))
        assertTrue(Versions.isNewer("v0.7.10", "0.7.9"))
        assertTrue(Versions.isNewer("1.0", "0.9.9"))
        assertFalse(Versions.isNewer("v0.7.0", "0.7.0"))
        assertFalse(Versions.isNewer("v0.6.5", "0.7.0"))
        assertFalse(Versions.isNewer("nightly", "0.7.0"))
        assertTrue(Versions.isNewer("v0.8.0-beta", "0.7.0"))
    }
}
