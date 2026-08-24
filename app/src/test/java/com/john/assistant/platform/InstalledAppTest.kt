package com.john.assistant.platform

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * App-name normalisation decides whether "open you tube" finds YouTube.
 * It is pure string handling, so it is tested directly rather than through a
 * PackageManager fake.
 */
class InstalledAppTest {

    @Test
    fun `lowercases and strips punctuation`() {
        assertEquals("youtube", InstalledApp.normalise("YouTube"))
        assertEquals("google maps", InstalledApp.normalise("Google Maps"))
        assertEquals("at t", InstalledApp.normalise("AT&T"))
        assertEquals("k 9 mail", InstalledApp.normalise("K-9 Mail"))
    }

    @Test
    fun `collapses whitespace`() {
        assertEquals("my app", InstalledApp.normalise("  My   App  "))
    }

    @Test
    fun `keeps digits, which several app names need`() {
        assertEquals("threema 2", InstalledApp.normalise("Threema 2"))
    }
}
