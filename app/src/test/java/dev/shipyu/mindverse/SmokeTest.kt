package dev.shipyu.mindverse

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun appConstants() {
        assertEquals("dev.shipyu.mindverse", BuildConfig.APPLICATION_ID)
    }
}
