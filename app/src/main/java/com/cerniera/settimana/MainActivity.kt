package com.cerniera.settimana

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.Toast
import com.cerniera.kit.Card
import com.cerniera.kit.Crash
import com.cerniera.kit.Kit
import com.cerniera.kit.Row
import com.cerniera.kit.Sheet
import com.cerniera.kit.doctrineCard
import com.cerniera.kit.endValue
import com.cerniera.kit.pressable
import com.cerniera.kit.primary
import com.cerniera.kit.px
import com.cerniera.kit.text
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Settimana: the calendar as a paper diary's week to view (22 Nov 2026 ideas page; started as its
 * own app on 23 Nov, with Nodo's knots in each day's margin rather than inside Nodo, so Nodo's
 * crease stays "now" and Settimana's crease is the diary's binding).
 *
 * Version one reads the week (calendar events and Nodo's knots), lays it out as a diary in every
 * shape of the phone, opens events in the calendar app and knots in Nodo, and hands new events to
 * the calendar app. Google or Samsung keep the sync, invites and repeat rules.
 */
class MainActivity : Activity() {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private var monday: LocalDate = Week.monday(LocalDate.now())
    private var demo = false
    private var asked = 1
    private var sheet: Sheet? = null
    private var week: WeekView? = null
    private val main = Handler(Looper.getMainLooper())

    private val calendarChanged = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { if (sheet == null) load() }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this, "Settimana")) return
        Kit.follow(this)
        val debug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        demo = debug && intent?.getStringExtra("demo") == "week"
        intent?.getStringExtra("date")?.let { runCatching { monday = Week.monday(LocalDate.parse(it)) } }
        if (!demo && !Sources.calendarAllowed(this)) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), ASK)
        render()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        i.getStringExtra("date")?.let { runCatching { monday = Week.monday(LocalDate.parse(it)); render() } }
    }

    override fun onResume() {
        super.onResume()
        if (Sources.calendarAllowed(this)) runCatching { contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, calendarChanged) }
        // A knot tied, moved or done in Nodo redraws the margins
        runCatching { contentResolver.registerContentObserver(android.net.Uri.parse("content://com.cerniera.nodo.cues/week"), false, calendarChanged) }
        if (sheet == null) load()
    }

    override fun onPause() { super.onPause(); runCatching { contentResolver.unregisterContentObserver(calendarChanged) } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ASK) render()
    }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); Kit.follow(this); render() }

    @Deprecated("Back lowers a sheet first")
    override fun onBackPressed() { sheet?.let { it.dismiss(); sheet = null; return }; @Suppress("DEPRECATION") super.onBackPressed() }

    // ---- The week -------------------------------------------------------------------------------

    /** Reads the week off the main thread, then hands it to the page. */
    private fun load() {
        val m = monday; val z = zone
        Thread {
            val (events, knots) = if (demo) Sources.demo(m, z) else Sources.events(this, m, z) to Sources.knots(this, m, z)
            val days = Week.days(m, events, knots, LocalDate.now(z), z)
            main.post { if (m == monday) week?.let { it.today = LocalDate.now(z); it.zone = z; it.days = days; firstShow(it) } }
        }.start()
    }

    private var shownToday = false
    /** On opening the cover shapes, today comes into view. */
    private fun firstShow(v: WeekView) {
        if (shownToday) return
        shownToday = true
        val i = java.time.temporal.ChronoUnit.DAYS.between(monday, LocalDate.now(zone)).toInt()
        if (i in 0..6 && (v.mode == WeekView.Mode.COLUMN || v.mode == WeekView.Mode.ROW)) v.post { v.show(i, animate = false) }
    }

    private fun turn(dir: Int) { monday = monday.plusWeeks(dir.toLong()); shownToday = false; updateHead(); load() }

    private var headTitle: android.widget.TextView? = null
    private var headSub: android.widget.TextView? = null
    private var todayChip: View? = null

    private fun updateHead() {
        val today = LocalDate.now(zone)
        headTitle?.text = Week.relative(monday, today)
        headSub?.text = Week.title(monday)
        todayChip?.visibility = if (Week.monday(today) == monday) View.GONE else View.VISIBLE
    }

    private fun render() {
        window.statusBarColor = Kit.GROUND; window.navigationBarColor = Kit.GROUND
        shownToday = false
        val cfg = resources.configuration
        val mainScreen = cfg.smallestScreenWidthDp >= 600
        // Upright or turned from the whole window, bars included: the main screen is nearly square,
        // so the space left after the status and navigation bars can't tell (found on the emulator)
        val bounds = windowManager.currentWindowMetrics.bounds
        val turned = bounds.width() > bounds.height()
        val mode = when {
            mainScreen && !turned -> WeekView.Mode.SPREAD_V
            mainScreen -> WeekView.Mode.SPREAD_H
            !turned -> WeekView.Mode.COLUMN
            else -> WeekView.Mode.ROW
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Kit.GROUND)
            setOnApplyWindowInsetsListener { v, i ->
                val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsets.CONSUMED
            }
        }

        // The head: the week's name, its dates, and the way to the weeks either side
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(if (mode == WeekView.Mode.ROW) 6 else 14), px(12), px(4))
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headSub = text("", 11f, Kit.CHAMPAGNE, 500).apply { letterSpacing = 0.2f; isAllCaps = true }
        headTitle = text("", if (mode == WeekView.Mode.ROW) 22f else 30f, Kit.INK, displayFace = true).apply {
            maxLines = 1; isClickable = true; setOnClickListener { settings() }
        }
        words.addView(headSub); words.addView(headTitle)
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        fun arrow(s: String, dir: Int) = text(s, 26f, Kit.INK, 300).apply {
            gravity = Gravity.CENTER; isClickable = true; pressable()
            contentDescription = if (dir < 0) "The week before" else "The week after"
            setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); turn(dir) }
        }
        todayChip = text("Today", 13f, Kit.CHAMPAGNE, 500).apply {
            gravity = Gravity.CENTER; setPadding(px(14), px(8), px(14), px(8)); isClickable = true; pressable()
            background = com.cerniera.kit.rounded(0, px(18).toFloat(), Kit.HAIRLINE, px(1))
            setOnClickListener { monday = Week.monday(LocalDate.now(zone)); shownToday = false; updateHead(); load() }
        }
        head.addView(todayChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
        head.addView(arrow("‹", -1), LinearLayout.LayoutParams(px(48), px(48)))
        head.addView(arrow("›", 1), LinearLayout.LayoutParams(px(48), px(48)))
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        if (!demo && !Sources.calendarAllowed(this)) {
            root.addView(Card(this).apply {
                row(Row(this@MainActivity, "Allow your calendar", "Settimana reads the events on your phone's calendars (Google, Samsung, Exchange) to lay out the week. It changes nothing.",
                    endValue("Allow", Kit.CHAMPAGNE)) {
                    // Asked twice and refused, Android stops showing the prompt: then its settings page
                    if (asked >= 2) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    else { asked++; requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), ASK) }
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(8), px(16), px(4)) })
        }

        val v = WeekView(this).apply {
            this.mode = mode
            gutter = if (mode == WeekView.Mode.SPREAD_V || mode == WeekView.Mode.SPREAD_H) px(32) else 0
            onEvent = { e -> eventSheet(e) }
            onKnot = { k -> if (!Sources.openKnot(this@MainActivity, k)) Toast.makeText(this@MainActivity, "Nodo isn't installed", Toast.LENGTH_SHORT).show() }
            onNewEvent = { d -> if (!Sources.newEvent(this@MainActivity, d, zone)) Toast.makeText(this@MainActivity, "No calendar app to add the event", Toast.LENGTH_SHORT).show() }
            onDayAll = { d -> daySheet(d) }
            onTurn = { dir -> turn(dir) }
        }
        week = v
        root.addView(v, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateHead()
        // The crease sits in the middle of the glass, not of this view (the head takes the top)
        v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            val dm = resources.displayMetrics
            val c = when (mode) {
                WeekView.Mode.SPREAD_V -> dm.widthPixels / 2f - loc[0]
                WeekView.Mode.SPREAD_H -> dm.heightPixels / 2f - loc[1]
                else -> -1f
            }
            if (c != (view as WeekView).crease) { view.crease = c; view.days = view.days }
        }
        record(root, v, mode)
        load()
    }

    // ---- Sheets ---------------------------------------------------------------------------------

    private fun eventSheet(e: Week.Event) {
        sheet = Sheet.show(this, e.title, onClose = { sheet = null }) { sh ->
            val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
            val s = Instant.ofEpochMilli(e.start).atZone(zone); val en = Instant.ofEpochMilli(e.end).atZone(zone)
            val whenText = if (e.allDay) "All day, " + java.time.format.DateTimeFormatter.ofPattern("EEE d MMM").format(Instant.ofEpochMilli(e.start).atZone(java.time.ZoneOffset.UTC))
                else fmt.format(s) + " to " + (if (s.toLocalDate() == en.toLocalDate()) "%02d:%02d".format(en.hour, en.minute) else fmt.format(en))
            sh.body.addView(Card(this).apply {
                row(Row(this@MainActivity, "When", whenText))
                e.location?.let { row(Row(this@MainActivity, "Where", it)) }
                if (e.calendar.isNotBlank()) row(Row(this@MainActivity, "Calendar", e.calendar))
            })
            sh.body.addView(primary("Open in the calendar") { if (!Sources.openEvent(this, e)) Toast.makeText(this, "No calendar app to open it", Toast.LENGTH_SHORT).show(); sh.dismiss() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14); bottomMargin = px(16) })
        }
    }

    private fun daySheet(d: Week.Day) {
        val title = d.date.dayOfWeek.name.lowercase().replaceFirstChar(Char::titlecase) + " ${d.date.dayOfMonth}"
        sheet = Sheet.show(this, title, onClose = { sheet = null }) { sh ->
            sh.body.addView(Card(this).apply {
                (d.allDay + d.timed).forEach { e -> row(Row(this@MainActivity, e.title, Week.time(e, zone) + (e.location?.let { " · $it" } ?: ""), endValue("›")) { sh.dismiss(); eventSheet(e) }) }
                d.knots.forEach { k -> row(Row(this@MainActivity, k.text, "Knot in Nodo" + if (k.late && !k.done) " · overdue" else "", endValue("›", if (k.late && !k.done) Kit.ROSSO else Kit.CHAMPAGNE)) { Sources.openKnot(this@MainActivity, k) }) }
            })
            sh.body.addView(primary("New event this day", filled = false) { sh.dismiss(); Sources.newEvent(this, d.date, zone) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14); bottomMargin = px(16) })
        }
    }

    /** Settings and notes, from a tap on the week's name (Giulietta). */
    private fun settings() {
        sheet = Sheet.show(this, "Settimana", onClose = { sheet = null }) { sh ->
            val nodo = Sources.nodoInstalled(this)
            val knotsOk = nodo && checkSelfPermission("com.cerniera.nodo.permission.NEXT") == android.content.pm.PackageManager.PERMISSION_GRANTED
            sh.body.addView(Card(this).apply {
                row(Row(this@MainActivity, "Calendar", if (Sources.calendarAllowed(this@MainActivity)) "Reading your calendars" else "Not allowed yet",
                    endValue(if (Sources.calendarAllowed(this@MainActivity)) "On" else "Allow", if (Sources.calendarAllowed(this@MainActivity)) Kit.GOOD else Kit.CHAMPAGNE)) {
                    if (!Sources.calendarAllowed(this@MainActivity)) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), ASK)
                })
                row(Row(this@MainActivity, "Nodo's knots in the margins", when {
                    !nodo -> "Install Nodo to see knots beside each day"
                    !knotsOk -> "Nodo arrived after Settimana: install Settimana again so Android lets it read the knots"
                    else -> "Each day's knots in its margin; overdue ones gather on today"
                }, endValue(if (knotsOk) "On" else "Off", if (knotsOk) Kit.GOOD else Kit.MUTED)))
            })
            sh.body.addView(text("Tap an event to see it, or a knot to open it in Nodo. Hold a day's open space for a new event. Drag the spread sideways, or use the arrows, for the weeks either side.",
                13f, Kit.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
            sh.body.addView(doctrineCard(listOf(
                "Membrane" to "The seven days share one sheet: a full Thursday takes height from an empty Saturday.",
                "Concentrato" to "Drag the week sideways and the words fall away, leaving each day's busy time as bars. They return when it settles.",
                "Giulietta" to "No add button and no settings on the page: hold a day's space for a new event; tap the week's name for this sheet.",
                "The fold" to "Open, the crease is the diary's binding: Monday to Wednesday on one page, Thursday to Sunday on the other. Folded, the days run down the cover with tabs in the hinge-side edge.",
            )), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14); bottomMargin = px(16) })
        }
    }

    /** Where the head and the days landed, for the four-shape check (files/layout.json). */
    private fun record(root: View, v: WeekView, mode: WeekView.Mode) {
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (v.height <= 0 || v.days.isEmpty()) return
                val o = IntArray(2).also { v.getLocationInWindow(it) }
                val r = IntArray(2).also { root.getLocationInWindow(it) }
                val json = "{\"mode\": \"$mode\", \"rotation\": ${display?.rotation ?: 0}, \"root\": [${r[0]}, ${r[1]}, ${r[0] + root.width}, ${r[1] + root.height}], " +
                    "\"view\": [${o[0]}, ${o[1]}, ${o[0] + v.width}, ${o[1] + v.height}], \"crease\": ${v.crease}, \"days\": ${v.report()}}"
                runCatching { java.io.File(filesDir, "layout.json").writeText(json) }
            }
        })
    }

    companion object { private const val ASK = 21 }
}
