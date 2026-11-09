package juricabi.com.groundtrack.ui

import android.app.Activity
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import juricabi.com.groundtrack.R
import juricabi.com.groundtrack.service.Message

/**
 * Everything the autopilot said over the flight — the boot lines, every
 * pre-arm check it failed, the EKF's and the battery's warnings — where the
 * one-line display above the flight mode shows only the latest, and only for
 * ten seconds.
 *
 * The way in is the small tab at the start of that line, shown while the
 * flight has messages: it is never there with nothing behind it, and only its
 * own words take a touch. The flight's are [rows] — the live flight's, or the
 * replay's up to where it stands — newest first. In a replay a row is a place
 * to go: [jump] takes the replay to where it was first said.
 */
class MessageHistory(
    private val activity: Activity,
    /** The one dialog funnel the activity keeps. */
    private val showDialog: (AlertDialog) -> Unit,
    private val rows: () -> List<Message>,
    /** Where a tapped row takes the replay; null live, where a row is only read. */
    private val jump: () -> ((Message) -> Unit)?
) {
    private val tab: TextView = activity.findViewById(R.id.messages_tab)
    private var dialog: AlertDialog? = null
    private var shown: List<Message> = emptyList()
    private var adapter: BaseAdapter? = null
    private var list: ListView? = null
    private val timeOfDay = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        tab.setOnClickListener { open() }
    }

    /**
     * Brings the tab, and the history if it is open, up to the flight: called
     * when a message arrives, a replay seek ends, the replay's clock is read,
     * and the flight is forgotten.
     */
    fun refresh() {
        val now = rows()
        tab.visibility = if (now.isEmpty()) View.GONE else View.VISIBLE
        tab.text = if (now.size == 1) "1 message" else "${now.size} messages"
        val open = dialog ?: return
        // a history with nothing in it is not left standing
        if (now.isEmpty()) {
            open.dismiss()
            return
        }
        show(now)
    }

    private fun open() {
        val now = rows()
        if (now.isEmpty()) return
        dialog?.dismiss()
        val listView = ListView(activity)
        listView.divider = null
        val rowsAdapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int): Any = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            // read-only live: nothing there to press
            override fun areAllItemsEnabled() = jump() != null
            override fun isEnabled(p: Int) = jump() != null
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View =
                bind((cv ?: row()) as LinearLayout, shown[p])
        }
        listView.adapter = rowsAdapter
        listView.setOnItemClickListener { _, _, p, _ ->
            val go = jump() ?: return@setOnItemClickListener
            val message = shown.getOrNull(p) ?: return@setOnItemClickListener
            dialog?.dismiss()
            go(message)
        }
        adapter = rowsAdapter
        list = listView
        show(now)
        val built = AlertDialog.Builder(activity)
            .setTitle("Messages")
            .setView(listView)
            .setNegativeButton("Close", null)
            .create()
        built.setOnDismissListener {
            if (dialog === built) {
                dialog = null
                adapter = null
                list = null
            }
        }
        built.setOnShowListener {
            val m = activity.resources.displayMetrics
            built.window?.setLayout((m.widthPixels * 0.94).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            // Over a live flight the flight stays readable around it — the
            // model, the tiles, the next message on the line.
            if (jump() == null) built.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Capped, so a long flight's history scrolls rather than covering
            // the screen: rows wrap to any height, so measured, not counted.
            listView.post {
                val cap = (m.heightPixels * 0.6).toInt()
                if (listView.height > cap) {
                    listView.layoutParams = listView.layoutParams?.apply { height = cap }
                }
            }
        }
        dialog = built
        showDialog(built)
    }

    /**
     * New rows go in at the top. A reader scrolled down to an earlier one is
     * kept on it rather than slid away under the new ones; a reader at the
     * top sees them come in.
     */
    private fun show(now: List<Message>) {
        val newestFirst = now.asReversed().toList()
        val listView = list
        val first = listView?.firstVisiblePosition ?: 0
        val offset = listView?.getChildAt(0)?.top ?: 0
        val atTop = first == 0 && offset >= 0
        val grown = newestFirst.size - shown.size
        shown = newestFirst
        adapter?.notifyDataSetChanged()
        if (listView != null && !atTop && grown > 0) listView.setSelectionFromTop(first + grown, offset)
    }

    private fun row(): LinearLayout {
        val d = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            addView(TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) })
            addView(TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
        }
    }

    private fun bind(view: LinearLayout, message: Message): View {
        val meta = view.getChildAt(0) as TextView
        val text = view.getChildAt(1) as TextView
        val parts = ArrayList<String>()
        when {
            message.firstAtMs == null -> {}
            message.count > 1 && message.lastAtMs != null && message.lastAtMs != message.firstAtMs ->
                parts += "${timeOfDay.format(Date(message.firstAtMs))} – ${timeOfDay.format(Date(message.lastAtMs))}"
            else -> parts += timeOfDay.format(Date(message.lastAtMs ?: message.firstAtMs))
        }
        // a word as well as a colour, so the colour is never the only telling
        severityWord(message.severity)?.let { parts += it }
        if (message.count > 1) parts += "×${message.count}"
        meta.text = parts.joinToString(" · ")
        meta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        meta.setTextColor(themeColor(android.R.attr.textColorSecondary))
        text.text = message.text
        text.setTextColor(
            when {
                message.severity <= 3 -> ContextCompat.getColor(activity, R.color.messageError)
                message.severity == 4 -> ContextCompat.getColor(activity, R.color.messageWarning)
                message.severity == 7 -> themeColor(android.R.attr.textColorSecondary)
                else -> themeColor(android.R.attr.textColorPrimary)
            }
        )
        text.setTypeface(null, if (message.severity <= 3) Typeface.BOLD else Typeface.NORMAL)
        return view
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(activity, value.resourceId) else value.data
    }

    companion object {
        /** The word a severity is said with; none below a warning, where the text speaks for itself. */
        fun severityWord(severity: Int): String? = when (severity) {
            0 -> "Emergency"
            1 -> "Alert"
            2 -> "Critical"
            3 -> "Error"
            4 -> "Warning"
            else -> null
        }
    }
}
