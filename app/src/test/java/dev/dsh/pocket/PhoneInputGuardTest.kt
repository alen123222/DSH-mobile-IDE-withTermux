package dev.dsh.pocket

import org.junit.Assert.*
import org.junit.Test

class PhoneInputGuardTest {
    @Test fun alternatingTapAndTypeCannotBypassFailureLimit() {
        val guard = PhoneInputGuard()
        guard.inputResult(false)
        assertNotNull(guard.blocked("tap"))
        assertNull(guard.blocked("screenshot"))
        guard.observed()
        assertNull(guard.blocked("tap"))
        guard.inputResult(false)
        guard.observed()
        assertNotNull(guard.blocked("tap"))
        assertNotNull(guard.blocked("type"))
        assertNull(guard.blocked("finished"))
        guard.reset()
        assertNull(guard.blocked("type"))
    }
    @Test fun confirmedInputResetsFailures() {
        val guard = PhoneInputGuard()
        guard.inputResult(false)
        guard.observed()
        guard.inputResult(true)
        assertNotNull(guard.blocked("tap"))
        guard.observed()
        guard.inputResult(false)
        guard.observed()
        assertNull(guard.blocked("tap"))
    }
    @Test fun rejectsMalformedCoordinatesAndKeepsEdgesInsideScreen() {
        for (value in listOf(950.0, -0.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { phoneCoordinate(value, 3040, true) }
        }
        assertEquals(3039f, phoneCoordinate(1.0, 3040, true))
        assertEquals(0f, phoneCoordinate(0.0, 3040, true))
        assertThrows(IllegalArgumentException::class.java) { phoneCoordinate(3040.0, 3040, false) }
    }
}
