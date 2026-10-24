package dev.shipyu.mindverse

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    /** Guards the identity constant from spec R4-7 against accidental renames. */
    @Test
    fun applicationId() {
        assertEquals("dev.shipyu.mindverse", BuildConfig.APPLICATION_ID)
    }
}
