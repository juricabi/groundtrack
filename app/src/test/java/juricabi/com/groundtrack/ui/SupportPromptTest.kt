package juricabi.com.groundtrack.ui

import juricabi.com.groundtrack.ui.SupportPrompt.Companion.NEVER
import juricabi.com.groundtrack.ui.SupportPrompt.Companion.afterShowing
import juricabi.com.groundtrack.ui.SupportPrompt.Companion.dueAfterReplay
import juricabi.com.groundtrack.ui.SupportPrompt.Companion.dueOnLogList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Ko-fi card's whole life as plain assertions: when it first asks, what
 * "Not now" postpones it to, and that the second showing is the last.
 */
class SupportPromptTest {

    @Test
    fun theLogListAsksFromTheTenthFlight() {
        assertFalse(dueOnLogList(null, 9))
        assertTrue(dueOnLogList(null, 10))
    }

    @Test
    fun aFinishedReplayAsksOnlyBeforeTheCardHasBeenShown() {
        assertTrue(dueAfterReplay(null))
        assertFalse("a Not now waits for flights, not replays", dueAfterReplay(13))
        assertFalse(dueAfterReplay(NEVER))
    }

    @Test
    fun notNowWaitsTenMoreFlightsFromWhereItWasSaid() {
        // shown after a replay with three flights in the list
        val askAt = afterShowing(null, 3)
        assertFalse(dueOnLogList(askAt, 12))
        assertTrue(dueOnLogList(askAt, 13))
    }

    @Test
    fun theSecondShowingIsTheLast() {
        assertEquals(NEVER, afterShowing(20, 20))
        assertFalse(dueOnLogList(NEVER, 100000))
    }
}
