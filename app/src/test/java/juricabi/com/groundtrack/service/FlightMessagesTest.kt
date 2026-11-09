package juricabi.com.groundtrack.service

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A flight's messages, folded the way an autopilot repeats itself: the same
 * burst of failed pre-arm checks every thirty seconds, which neighbours alone
 * never matched.
 */
class FlightMessagesTest {

    private fun MessageRows.texts() = rows().map { "${it.text}×${it.count}" }

    @Test
    fun aRepeatedBurstFoldsIntoItsRows() {
        val rows = MessageRows()
        rows.add("PreArm: Throttle below failsafe", 2, 1_000)
        rows.add("PreArm: GPS not healthy", 2, 1_100)
        rows.add("PreArm: Compass not calibrated", 2, 1_200)
        rows.add("PreArm: Throttle below failsafe", 2, 31_000)
        rows.add("PreArm: GPS not healthy", 2, 31_100)
        rows.add("PreArm: Compass not calibrated", 2, 31_200)
        assertEquals(
            listOf("PreArm: Throttle below failsafe×2", "PreArm: GPS not healthy×2", "PreArm: Compass not calibrated×2"),
            rows.texts()
        )
        // when it began, and when it was last said
        assertEquals(1_000L, rows.rows()[0].firstAtMs)
        assertEquals(31_000L, rows.rows()[0].lastAtMs)
    }

    @Test
    fun aRepeatMovesItsRowToTheNewest() {
        val rows = MessageRows()
        rows.add("EKF3 IMU0 is using GPS", 6, 1)
        rows.add("Armed", 6, 2)
        rows.add("EKF3 IMU0 is using GPS", 6, 3)
        assertEquals(listOf("Armed×1", "EKF3 IMU0 is using GPS×2"), rows.texts())
    }

    @Test
    fun saidAgainLongAfterIsNewsAgain() {
        val rows = MessageRows()
        rows.add("Battery 1 low", 4, 0)
        for (k in 1..8) rows.add("Mission: $k WP", 6, k.toLong())
        rows.add("Battery 1 low", 4, 100)
        assertEquals(10, rows.size)
        assertEquals("Battery 1 low×1", rows.texts().last())
    }

    @Test
    fun theSameWordsAtAnotherSeverityAreAnotherMessage() {
        val rows = MessageRows()
        rows.add("Vibration compensation ON", 6, 0)
        rows.add("Vibration compensation ON", 4, 1)
        assertEquals(2, rows.size)
    }

    @Test
    fun aFoldedRowKeepsWhereItWasFirstSaid() {
        val rows = MessageRows()
        rows.add("GPS Glitch", 2, null, index = 120)
        rows.add("GPS Glitch", 2, null, index = 480)
        assertEquals(120, rows.rows().single().firstIndex)
    }

    @Before
    fun forget() = FlightMessages.clear()

    @Test
    fun theLiveFlightsMessagesAreKeptUntilItIsForgotten() {
        FlightMessages.add("Armed", 6, 10)
        FlightMessages.add("Armed", 6, 20)
        assertEquals(1, FlightMessages.rows().size)
        assertEquals(2, FlightMessages.rows().single().count)
        FlightMessages.clear()
        assertEquals(0, FlightMessages.rows().size)
    }
}
