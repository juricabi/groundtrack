package juricabi.com.groundtrack.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import juricabi.com.groundtrack.R
import juricabi.com.groundtrack.manager.PreferenceManager

/**
 * The Ko-fi card, asked at most twice in the app's life: first when the log
 * list holds ten flights or a replay has been watched to its end, whichever
 * comes first, and after a "Not now" once more, ten flights later.
 *
 * Only the log list and the end of a replay raise it, and neither while a
 * flight is on: a link that dropped and is still being retried leaves the
 * log list one tap away, which is what [inFlight] is asked for.
 */
class SupportPrompt(
    private val context: Context,
    private val prefs: PreferenceManager,
    private val showDialog: (AlertDialog) -> Unit,
    /** A link up, or a dropped one still being retried. */
    private val inFlight: () -> Boolean
) {

    companion object {
        /** Flights before the first ask, and between it and the last. */
        const val FLIGHTS = 10
        const val NEVER = Int.MAX_VALUE

        // The rules, apart from the card so they can be tested without one.
        // askAt is the stored answer: null until the card has been shown.
        fun dueOnLogList(askAt: Int?, flights: Int) = flights >= (askAt ?: FLIGHTS)

        fun dueAfterReplay(askAt: Int?) = askAt == null

        fun afterShowing(askAt: Int?, flights: Int) =
            if (askAt == null) flights + FLIGHTS else NEVER

        /** The one way to Ko-fi, from the card and from Settings alike. */
        fun openKofi(context: Context) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.kofi_url)))
                )
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "No browser found", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The log list has opened on this many flights. */
    fun onLogList(flights: Int) {
        if (dueOnLogList(prefs.getSupportAskAt(), flights)) ask(flights)
    }

    /** A replay has played to its end; [flights] is what the log list holds. */
    fun onReplayFinished(flights: Int) {
        if (dueAfterReplay(prefs.getSupportAskAt())) ask(flights)
    }

    private fun ask(flights: Int) {
        if (inFlight()) return
        // "Not now" is written as the card goes up, so a card backed out of,
        // or lost with a rotation, still counts as one of the two.
        prefs.setSupportAskAt(afterShowing(prefs.getSupportAskAt(), flights))
        showDialog(
            AlertDialog.Builder(context)
                .setTitle(R.string.support_card_title)
                .setMessage(R.string.support_card_text)
                .setPositiveButton(R.string.support_kofi) { _, _ ->
                    prefs.setSupportAskAt(NEVER)
                    openKofi(context)
                }
                .setNegativeButton(R.string.support_not_now, null)
                .setNeutralButton(R.string.support_never) { _, _ ->
                    prefs.setSupportAskAt(NEVER)
                }
                .create()
        )
    }
}
