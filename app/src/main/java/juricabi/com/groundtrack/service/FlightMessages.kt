package juricabi.com.groundtrack.service

/**
 * One row of a flight's messages — what the autopilot said, as MAVLink's
 * STATUSTEXT and the links that carry it say it, at its severity (0 emergency
 * … 7 debug). A message said again soon after is folded into its row: [count]
 * times, between [firstAtMs] and [lastAtMs] — wall-clock times, null where the
 * flight kept no clock. [firstIndex] is where in a recording it was first said,
 * -1 live.
 */
class Message(
    val text: String,
    val severity: Int,
    val count: Int,
    val firstAtMs: Long?,
    val lastAtMs: Long?,
    val firstIndex: Int
)

/**
 * A flight's messages, newest last. A repeat of one of the last few rows moves
 * that row to the end, one count on, instead of adding a row: an autopilot that
 * is not ready to arm says every check it fails in a burst, every thirty
 * seconds — A B C A B C — and neighbours alone never matched. Further back than
 * a burst, a message said again is news again and gets a row of its own.
 */
class MessageRows {
    private val rows = ArrayList<Message>()

    fun add(text: String, severity: Int, atMs: Long?, index: Int = -1) {
        val from = maxOf(0, rows.size - BURST)
        val at = (rows.size - 1 downTo from).firstOrNull {
            rows[it].text == text && rows[it].severity == severity
        }
        if (at == null) {
            rows.add(Message(text, severity, 1, atMs, atMs, index))
        } else {
            val was = rows.removeAt(at)
            rows.add(Message(text, severity, was.count + 1, was.firstAtMs, atMs, was.firstIndex))
        }
    }

    fun rows(): List<Message> = ArrayList(rows)

    val size get() = rows.size

    fun clear() = rows.clear()

    private companion object {
        // a burst of failed checks, with room to spare
        const val BURST = 8
    }
}

/**
 * The live flight's messages, where any screen can reach them: kept by the
 * process, as [juricabi.com.groundtrack.gl.LiveFlightPath] keeps its track, and
 * fed by [DataService] as each message arrives — before any screen hears it,
 * so a screen that asks on hearing it finds it there, and while no screen is
 * bound at all. A replay's messages are the recording's, never these.
 */
object FlightMessages {
    private val rows = MessageRows()

    @Synchronized
    fun add(text: String, severity: Int, atMs: Long) = rows.add(text, severity, atMs)

    @Synchronized
    fun rows(): List<Message> = rows.rows()

    @Synchronized
    fun clear() = rows.clear()
}
