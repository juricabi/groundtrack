package juricabi.com.groundtrack.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import juricabi.com.groundtrack.R
import juricabi.com.groundtrack.service.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

/**
 * The way into the flight's messages is there exactly while there is
 * something behind it, and a history left with nothing in it goes.
 */
@RunWith(RobolectricTestRunner::class)
class MessageHistoryTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setTheme(R.style.AppTheme)
            setContentView(TextView(this).also { it.id = R.id.messages_tab })
        }
    }

    private fun message(text: String, severity: Int = 6) = Message(text, severity, 1, null, null, -1)

    @Test
    fun theTabStandsOnlyOverMessagesAndSaysHowMany() {
        val host = Robolectric.buildActivity(Host::class.java).create().get()
        var rows = emptyList<Message>()
        val history = MessageHistory(host, { it.show() }, { rows }, { null })
        val tab = host.findViewById<TextView>(R.id.messages_tab)
        history.refresh()
        assertEquals(View.GONE, tab.visibility)
        rows = listOf(message("Armed"))
        history.refresh()
        assertEquals(View.VISIBLE, tab.visibility)
        assertEquals("1 message", tab.text)
        rows = listOf(message("Armed"), message("Disarmed"))
        history.refresh()
        assertEquals("2 messages", tab.text)
    }

    @Test
    fun aHistoryLeftEmptyCloses() {
        val host = Robolectric.buildActivity(Host::class.java).create().get()
        var rows = listOf(message("PreArm: GPS not healthy", 2))
        var shown: AlertDialog? = null
        val history = MessageHistory(host, { shown = it; it.show() }, { rows }, { null })
        history.refresh()
        host.findViewById<TextView>(R.id.messages_tab).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shown!!.isShowing)
        // the flight was forgotten under it
        rows = emptyList()
        history.refresh()
        assertFalse(shown!!.isShowing)
    }

    @Test
    fun onlyWarningsAndWorseAreNamed() {
        assertEquals(listOf("Emergency", "Alert", "Critical", "Error", "Warning"),
            (0..4).map { MessageHistory.severityWord(it) })
        assertNull(MessageHistory.severityWord(6))
    }
}
