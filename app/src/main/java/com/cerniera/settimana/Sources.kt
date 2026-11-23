package com.cerniera.settimana

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId

/**
 * Where the week comes from. Events: the phone's calendar provider (Google, Samsung and Exchange
 * accounts sync into it), read only. Knots: Nodo's provider, read only, through the family's
 * permission; a tap on a knot opens Nodo at it. Version one writes nothing: a new event is handed
 * to the calendar app, and Google or Samsung keep the sync, invites and repeat rules.
 */
object Sources {
    fun calendarAllowed(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun events(c: Context, monday: LocalDate, zone: ZoneId): List<Week.Event> {
        if (!calendarAllowed(c)) return emptyList()
        // A day either side, so all-day events in UTC and late-night events are not missed
        val from = monday.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = monday.plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val cols = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.VISIBLE, CalendarContract.Instances.STATUS)
        val out = mutableListOf<Week.Event>()
        runCatching {
            c.contentResolver.query(uri, cols, null, null, CalendarContract.Instances.BEGIN)?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getInt(8) == 0) continue                                     // a hidden calendar
                    if (cur.getInt(9) == CalendarContract.Instances.STATUS_CANCELED) continue
                    out += Week.Event(cur.getLong(0), cur.getString(1)?.ifBlank { null } ?: "(No title)", cur.getLong(2), cur.getLong(3),
                        cur.getInt(4) == 1, cur.getInt(5), cur.getString(6) ?: "", cur.getString(7)?.ifBlank { null })
                }
            }
        }
        return out
    }

    // ---- Nodo ---------------------------------------------------------------------------------

    const val NODO = "com.cerniera.nodo"
    private val WEEK: Uri = Uri.parse("content://com.cerniera.nodo.cues/week")

    fun nodoInstalled(c: Context) = runCatching { c.packageManager.getPackageInfo(NODO, 0); true }.getOrDefault(false)

    /**
     * Knots due in the week, and every open overdue knot. Empty when Nodo is missing, too old to
     * answer /week, or the permission wasn't granted (installing Settimana before Nodo leaves it
     * ungranted until Settimana is installed again: Android grants a normal permission only if its
     * owner is already there).
     */
    fun knots(c: Context, monday: LocalDate, zone: ZoneId): List<Week.Knot> {
        val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = WEEK.buildUpon().appendQueryParameter("from", "$from").appendQueryParameter("to", "$to").build()
        val out = mutableListOf<Week.Knot>()
        runCatching {
            c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                val id = cur.getColumnIndex("id"); val text = cur.getColumnIndex("text"); val due = cur.getColumnIndex("due")
                val weight = cur.getColumnIndex("weight"); val done = cur.getColumnIndex("done"); val late = cur.getColumnIndex("late")
                if (id < 0 || text < 0 || due < 0) return@use
                while (cur.moveToNext()) out += Week.Knot(cur.getInt(id), cur.getString(text) ?: "", cur.getLong(due),
                    if (weight >= 0) cur.getInt(weight) else 1, done >= 0 && cur.getInt(done) == 1, late >= 0 && cur.getInt(late) == 1)
            }
        }
        return out
    }

    fun openKnot(c: Context, k: Week.Knot) = runCatching {
        c.startActivity(Intent().setClassName(NODO, "$NODO.MainActivity").putExtra("knot", k.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    // ---- The calendar app ---------------------------------------------------------------------

    fun openEvent(c: Context, e: Week.Event) = runCatching {
        c.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end))
    }.isSuccess

    /** A new event on [date] at nine, written by the calendar app (version one writes nothing itself). */
    fun newEvent(c: Context, date: LocalDate, zone: ZoneId) = runCatching {
        val start = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        c.startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3_600_000))
    }.isSuccess

    // ---- A fictitious week for the emulator walk (debug builds only) -------------------------------

    fun demo(monday: LocalDate, zone: ZoneId): Pair<List<Week.Event>, List<Week.Knot>> {
        fun at(day: Int, h: Int, m: Int = 0) = monday.plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        fun allDay(day: Int) = monday.plusDays(day.toLong()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val champ = 0xFFD9BB86.toInt(); val green = 0xFF7FC79A.toInt(); val rosso = 0xFFD7262E.toInt()
        var id = 1L
        fun ev(day: Int, h: Int, m: Int, mins: Int, t: String, col: Int = champ, loc: String? = null) =
            Week.Event(id++, t, at(day, h, m), at(day, h, m) + mins * 60_000L, false, col, "Work", loc)
        val events = listOf(
            ev(0, 9, 30, 30, "Stand-up"), ev(0, 14, 0, 60, "Interview: AML lead", rosso, "Teams"),
            ev(1, 10, 0, 90, "Client call: onboarding review"),
            ev(3, 8, 30, 60, "Screening walkthrough"), ev(3, 11, 0, 60, "Recruiter catch-up", green), ev(3, 13, 0, 45, "Lunch with Sam", green),
            ev(3, 15, 30, 90, "Section 166 scoping", rosso, "Leeds"), ev(3, 18, 0, 60, "Gym", green),
            ev(4, 9, 0, 60, "Report drafting"),
            ev(5, 17, 30, 120, "Arsenal v Example FC", rosso),
            Week.Event(id++, "Car service", allDay(2), allDay(3), true, champ, "Personal"),
        )
        val now = System.currentTimeMillis()
        val knots = listOf(
            Week.Knot(1, "Call the bank", at(2, 9), 2, false, at(2, 9) < now),
            Week.Knot(2, "Renew parking permit", at(4, 12), 1, false, at(4, 12) < now),
            Week.Knot(3, "Book the MOT", at(6, 10), 3, false, at(6, 10) < now),
        )
        return events to knots
    }
}
