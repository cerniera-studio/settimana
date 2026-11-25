package com.cerniera.settimana

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import com.cerniera.kit.Finish
import com.cerniera.kit.Grammar
import com.cerniera.kit.Kit
import com.cerniera.kit.Surface
import com.cerniera.kit.withAlpha
import com.cerniera.settimana.WidgetPlan.Box
import com.cerniera.settimana.WidgetPlan.Detail
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * The week widget's painter (25 Nov 2026): draws one picture at any size in dp, from [WidgetPlan],
 * over the family's finish (the [Surface] the caller has painted). Every mark takes the Surface's
 * proven ink at its own place, so it stays legible over any finish and wallpaper.
 */
object WeekFace {
    /** What the widget shows: the week, today, and the moment it was drawn. */
    class Snap(val monday: LocalDate, val days: List<Week.Day>, val today: LocalDate, val now: Long, val zone: ZoneId, val allowed: Boolean)

    /** What a picture holds besides its pixels: where each day sits (dp), and words for TalkBack. */
    class Drawn(val cells: List<Box>, val spoken: String)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun fade(col: Int, f: Float) = withAlpha(col, ((col ushr 24) and 255) / 255f * f)
    private fun rosso(s: Surface, x: Float, y: Float) = when {
        s.finish == Finish.ROSSO -> 0xFFFFD08A.toInt()
        s.darkInk(x, y) -> 0xFF9E1B22.toInt()
        else -> 0xFFFF6A6F.toInt()
    }

    fun weights(snap: Snap) = if (snap.allowed) snap.days.map { it.weight } else List(7) { 1f }

    fun draw(c: Context, cv: Canvas, s: Surface, snap: Snap): Drawn {
        val plan = WidgetPlan.layout(s.w, s.h, weights(snap))
        val k = plan.k
        val lateKnots = snap.days.flatMap { it.knots }.filter { it.late && !it.done }.distinctBy { it.id }
        val todayDay = snap.days.firstOrNull { it.date == snap.today }
        val todayLeft = todayDay?.timed?.count { it.end > snap.now } ?: 0

        // The label opens it (rule 5): the week's dates; on a strip, what's next shares its line
        plan.label?.let { lb ->
            val lp = Grammar.label(c, k, s.muted(lb.cx, lb.cy))
            val full = "Settimana · ${short(snap.monday)}".uppercase()
            val text = listOf(full, short(snap.monday).uppercase(), "SETTIMANA").firstOrNull { lp.measureText(it) <= lb.w } ?: full
            val shown = Grammar.fit(text, lp, lb.w)
            cv.capTop(shown, lb.l, lb.t, lp)
            val used = lp.measureText(shown) + 16f * k
            // On the right: overdue knots in rosso, else what's left today; on a strip, what's next
            val right: Pair<String, Int>? = when {
                plan.inline -> next(snap)?.let { (t, m) -> "$m · $t" to s.ink(lb.r - 40f, lb.cy) }
                    ?: (if (!snap.allowed) "Allow your calendar" else "Nothing else this week").let { it to s.muted(lb.r - 40f, lb.cy) }
                lateKnots.isNotEmpty() -> "${lateKnots.size} overdue" to rosso(s, lb.r - 30f, lb.cy)
                todayLeft > 0 -> "$todayLeft today" to s.muted(lb.r - 30f, lb.cy)
                else -> null
            }
            if (right != null && lb.w - used > 40f * k) {
                val mp = Grammar.meta(c, k, right.second).apply { textAlign = Paint.Align.RIGHT }
                // Set on the label's capitals line, so the two read as one line
                cv.drawText(Grammar.fit(right.first, mp, lb.w - used), lb.r, lb.b, mp)
            }
        }

        // The pages' binding: a champagne hairline, as in the app
        plan.binding?.let { b ->
            p.shader = null; p.style = Paint.Style.FILL; p.color = withAlpha(Kit.Fixed.CHAMPAGNE, 0.55f)
            cv.drawRect(RectF(b.l, b.t, b.r, b.b), p)
        }

        plan.cells.forEachIndexed { i, b -> cell(c, cv, s, b, snap.days.getOrNull(i), snap, k, plan.detail) }

        // What's next (ROW and COLUMN): the title as large as the room allows, its when beneath
        plan.words?.let { wb -> words(c, cv, s, wb, snap, k, lateKnots.size) }

        // The foot closes it (rule 6): how far through the week we are
        plan.foot?.let { f -> Grammar.line(cv, s, f.l, f.cy, f.w, elapsed(snap), k) }

        return Drawn(plan.cells, spoken(snap, lateKnots.size))
    }

    // ---- A day ---------------------------------------------------------------------------------

    private fun cell(c: Context, cv: Canvas, s: Surface, b: Box, day: Week.Day?, snap: Snap, k: Float, detail: Detail) {
        if (b.w <= 0.5f || b.h <= 0.5f) return
        val date = day?.date ?: return
        val isToday = date == snap.today
        val fadeBy = if (date < snap.today) 0.5f else 1f
        val ink = fade(s.ink(b.cx, b.cy), fadeBy); val muted = fade(s.muted(b.cx, b.cy), fadeBy)
        val acc = s.accent(b.cx, b.cy); val faint = s.faint(b.cx, b.cy)
        val head = if (isToday) acc else muted
        val late = isToday && day.knots.any { it.late && !it.done }
        val busy = if (snap.allowed) (Week.busy(day, snap.zone) / 900f).coerceIn(0f, 1f) else 0f
        val fill = if (isToday) acc else fade(s.ink(b.cx, b.cy), 0.72f * fadeBy)

        // Today: a soft wash of the finish's light, so it is found at a glance at any size
        if (isToday) {
            p.shader = null; p.style = Paint.Style.FILL; p.color = withAlpha(acc, 0.13f)
            val r = min(6f * k, min(b.w, b.h) / 3f)
            cv.drawRoundRect(RectF(b.l, b.t, b.r, b.b), r, r, p)
        }
        val ip = WidgetPlan.inner(b, k)
        val box = Box(b.l + ip, b.t + ip, b.r - ip, b.b - ip)
        val across = WidgetPlan.across(b)
        val dayName = date.dayOfWeek.name.take(3)
        val initial = dayName.take(1)

        fun bar(r: Box, horizontal: Boolean) {
            if (r.w <= 0.5f || r.h <= 0.5f) return
            p.shader = null; p.style = Paint.Style.FILL
            if (horizontal) {
                val th = min(r.h * 0.6f, 8f * k).coerceAtLeast(min(2f, r.h))
                val t = r.cy - th / 2
                p.color = faint; cv.drawRoundRect(RectF(r.l, t, r.r, t + th), th / 2, th / 2, p)
                if (busy > 0f) { p.color = fill; cv.drawRoundRect(RectF(r.l, t, r.l + max(th, r.w * busy), t + th), th / 2, th / 2, p) }
            } else {
                val tw = min(r.w * 0.6f, 8f * k).coerceAtLeast(min(2f, r.w))
                val l = r.cx - tw / 2
                p.color = faint; cv.drawRoundRect(RectF(l, r.t, l + tw, r.b), tw / 2, tw / 2, p)
                if (busy > 0f) { p.color = fill; cv.drawRoundRect(RectF(l, r.b - max(tw, r.h * busy), l + tw, r.b), tw / 2, tw / 2, p) }
            }
        }
        fun dot(x: Float, y: Float, rad: Float, col: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = col; cv.drawCircle(x, y, rad, p) }
        fun labelPaint(col: Int, room: Float): TextPaint = Grammar.label(c, k, col).also { if (Grammar.cap(it) > room) it.textSize *= room / Grammar.cap(it) }

        when (detail) {
            Detail.BAR -> bar(box, across)
            Detail.INITIAL -> if (across) {
                val lp = labelPaint(head, box.h)
                val cap = Grammar.cap(lp)
                cv.drawText(initial, box.l, box.cy + cap / 2, lp)
                val x = box.l + lp.measureText("W") + 6f * k
                if (late) dot(x - 3f * k, box.t + 1.5f * k, 1.6f * k, rosso(s, b.cx, b.cy))
                bar(Box(x, box.t, box.r, box.b), true)
            } else {
                val lp = labelPaint(head, min(Grammar.LABEL * k * 0.72f, box.w)).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0f }
                cv.capTop(initial, box.cx, box.t, lp)
                val y = box.t + Grammar.cap(lp) + 5f * k
                if (late) dot(box.r - 1.5f * k, box.t + 1.5f * k, 1.6f * k, rosso(s, b.cx, b.cy))
                bar(Box(box.l, y, box.r, box.b), false)
            }
            Detail.NAME -> if (across) {
                val lp = labelPaint(head, box.h)
                val name = "$dayName ${date.dayOfMonth}".takeIf { lp.measureText("WED 30") + 70f * k <= box.w } ?: dayName
                val cap = Grammar.cap(lp)
                cv.drawText(name, box.l, box.cy + cap / 2, lp)
                val x = box.l + lp.measureText(if (name == dayName) "WED" else "WED 30") + 8f * k
                if (late) dot(x - 4f * k, box.cy - cap / 2, 1.8f * k, rosso(s, b.cx, b.cy))
                bar(Box(x, box.t, box.r, box.b), true)
            } else {
                val lp = labelPaint(head, box.h)
                val name = if (lp.measureText(dayName) <= box.w) dayName else initial
                cv.capTop(name, box.l, box.t, lp)
                if (late) dot(box.r - 2f * k, box.t + 2f * k, 1.8f * k, rosso(s, b.cx, b.cy))
                var y = box.t + Grammar.cap(lp) + 6f * k
                val fs = min(22f * k, min(box.w * 0.62f, box.h * 0.3f))
                if (fs >= 11f * k) {
                    val fp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.display(c); textSize = fs; color = ink }
                    cv.capTop("${date.dayOfMonth}", box.l, y, fp)
                    y += Grammar.cap(fp) + 7f * k
                }
                bar(Box(box.l, y, box.r, box.b), false)
            }
            Detail.LIST -> list(c, cv, s, box, day, snap, k, across, head, ink, muted, faint, fill, busy, late)
        }
    }

    /** A day with room for its events and knots, line by line (the diary page itself). */
    private fun list(c: Context, cv: Canvas, s: Surface, box: Box, day: Week.Day, snap: Snap, k: Float, across: Boolean,
                     head: Int, ink: Int, muted: Int, faint: Int, fill: Int, busy: Float, late: Boolean) {
        val lp = Grammar.label(c, k, head)
        val name = day.date.dayOfWeek.name.take(3)
        val mp = Grammar.meta(c, k, muted)
        val tp = Grammar.paint(c, Grammar.META * k, ink, 600)
        val lead = Grammar.META * k * 1.38f
        val text: Box
        if (across) {
            // The name and date at the left, set as one label; the day's items after it
            val nameW = lp.measureText("WED 30") + 10f * k
            cv.drawText("$name ${day.date.dayOfMonth}", box.l, box.t + min(box.h, lead) / 2 + Grammar.cap(lp) / 2, lp)
            if (late) dot(cv, box.l + nameW - 6f * k, box.t + min(box.h, lead) / 2 - Grammar.cap(lp) / 2, 1.8f * k, rosso(s, box.cx, box.cy))
            text = Box(box.l + nameW, box.t, box.r, box.b)
        } else {
            cv.capTop(name, box.l, box.t, lp)
            var y = box.t + Grammar.cap(lp) + 6f * k
            val fs = min(22f * k, box.w * 0.4f)
            val fp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.display(c); textSize = fs; color = ink }
            // The date beside the name when the column is wide, beneath it otherwise
            if (box.w >= lp.measureText(name) + fp.measureText("30") + 14f * k && box.h < 150f * k) {
                fp.textAlign = Paint.Align.RIGHT
                cv.drawText("${day.date.dayOfMonth}", box.r, box.t + Grammar.cap(lp) + 1f, fp)
            } else { cv.capTop("${day.date.dayOfMonth}", box.l, y, fp); y += Grammar.cap(fp) + 7f * k }
            if (late) dot(cv, box.l + lp.measureText(name) + 5f * k, box.t + Grammar.cap(lp) / 2, 1.8f * k, rosso(s, box.cx, box.cy))
            // The day's busy time from 07:00 to 22:00, as the hairline under its head in the app
            val th = 2.2f * k
            p.shader = null; p.style = Paint.Style.FILL; p.color = faint
            cv.drawRoundRect(RectF(box.l, y, box.r, y + th), th / 2, th / 2, p)
            if (busy > 0f) { p.color = fill; cv.drawRoundRect(RectF(box.l, y, box.l + max(th, box.w * busy), y + th), th / 2, th / 2, p) }
            text = Box(box.l, y + th + 6f * k, box.r, box.b)
        }
        val all = items(day, snap)
        val lines = (text.h / lead).toInt().coerceAtLeast(if (across) 1 else 0)
        if (lines <= 0) return
        if (!snap.allowed || all.isEmpty()) {
            val y = if (across) text.t + min(text.h, lead) / 2 + Grammar.cap(mp) / 2 else text.t + Grammar.cap(mp)
            cv.drawText(if (!snap.allowed) "—" else "Free", text.l, y, mp); return
        }
        val shown = if (all.size > lines) all.take(lines - 1) else all
        val more = all.size - shown.size
        var y = text.t
        for (it in shown) {
            val base = y + (lead - Grammar.META * k) / 2 + Grammar.cap(tp)
            var x = text.l
            dot(cv, x + 2.2f * k, base - Grammar.cap(tp) / 2, 2.2f * k, it.colour)
            x += 8f * k
            if (it.time != null) { cv.drawText(it.time, x, base, mp); x += mp.measureText(it.time) + 5f * k }
            if (x < text.r) cv.drawText(Grammar.fit(it.title, tp, text.r - x), x, base, tp)
            y += lead
        }
        if (more > 0) cv.drawText("+$more more", text.l, y + (lead - Grammar.META * k) / 2 + Grammar.cap(mp), mp)
    }

    private fun dot(cv: Canvas, x: Float, y: Float, rad: Float, col: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = col; cv.drawCircle(x, y, rad, p) }

    private class Item(val time: String?, val title: String, val colour: Int)

    /** A day's lines: all-day events, timed events by time, then knots (rosso when overdue). */
    private fun items(d: Week.Day, snap: Snap): List<Item> {
        val ev = d.allDay.map { Item(null, it.title, it.colour or 0xFF000000.toInt()) } +
            d.timed.map { Item(Week.time(it, snap.zone), it.title, it.colour or 0xFF000000.toInt()) }
        val kn = d.knots.filter { !it.done }.map { Item(null, it.text, if (it.late) Kit.Fixed.ROSSO else Kit.Fixed.CHAMPAGNE) }
        return ev + kn
    }

    // ---- What's next ----------------------------------------------------------------------------

    /** The next thing this week: the event on now or next, else the next knot. (title, when) */
    fun next(snap: Snap): Pair<String, String>? {
        if (!snap.allowed) return null
        val ev = snap.days.flatMap { it.timed }.distinctBy { it.id to it.start }.filter { it.end > snap.now }.minByOrNull { it.start }
        if (ev != null) {
            val t = Instant.ofEpochMilli(ev.start).atZone(snap.zone)
            val whenText = if (ev.start <= snap.now) "Now · until " + hm(ev.end, snap.zone) else day(t.toLocalDate(), snap.today) + " · " + hm(ev.start, snap.zone)
            return ev.title to (whenText + (ev.location?.let { " · $it" } ?: ""))
        }
        val knot = snap.days.flatMap { it.knots }.filter { !it.done && !it.late && it.due > snap.now }.minByOrNull { it.due } ?: return null
        val d = Instant.ofEpochMilli(knot.due).atZone(snap.zone).toLocalDate()
        return knot.text to "Knot · " + day(d, snap.today) + " · " + hm(knot.due, snap.zone)
    }

    private fun words(c: Context, cv: Canvas, s: Surface, wb: Box, snap: Snap, k: Float, late: Int) {
        val n = next(snap)
        if (n == null) {
            val (title, note) = when {
                !snap.allowed -> "Allow your calendar" to "Tap to open Settimana"
                late > 0 -> "Nothing else this week" to (if (late == 1) "1 knot overdue" else "$late knots overdue")
                else -> "Nothing else this week" to "Free from here"
            }
            Grammar.statement(c, cv, s, title, note.takeIf { wb.h >= 40f * k }, wb.l, wb.b, wb.w, k)
            return
        }
        val mp = Grammar.meta(c, k, s.muted(wb.cx, wb.b - 6f))
        val metaH = Grammar.META * k * 1.3f
        val room = wb.h - metaH - 4f * k
        val title = Grammar.title(c, n.first, s.ink(wb.cx, wb.cy), wb.w, room, k, lines = 3, largest = 28f)
        val end = title.draw(cv, wb.l, wb.t)
        cv.drawText(Grammar.fit(n.second, mp, wb.w), wb.l, min(wb.b, end + 4f * k + Grammar.cap(mp)), mp)
    }

    // ---- Words ------------------------------------------------------------------------------------

    /** "23 – 29 Nov", or across months "30 Nov – 6 Dec". */
    fun short(monday: LocalDate): String {
        val sun = monday.plusDays(6)
        fun m(d: LocalDate) = d.month.name.take(3).lowercase().replaceFirstChar(Char::titlecase)
        return if (monday.month == sun.month) "${monday.dayOfMonth} – ${sun.dayOfMonth} ${m(sun)}" else "${monday.dayOfMonth} ${m(monday)} – ${sun.dayOfMonth} ${m(sun)}"
    }

    fun day(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::titlecase) + " ${d.dayOfMonth}"
    }

    private fun hm(ms: Long, z: ZoneId) = Instant.ofEpochMilli(ms).atZone(z).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

    /** How far through the week (Monday 00:00 to Sunday 24:00) [Snap.now] is. */
    fun elapsed(snap: Snap): Float {
        val a = snap.monday.atStartOfDay(snap.zone).toInstant().toEpochMilli()
        val b = snap.monday.plusDays(7).atStartOfDay(snap.zone).toInstant().toEpochMilli()
        return ((snap.now - a).toFloat() / (b - a)).coerceIn(0f, 1f)
    }

    private fun spoken(snap: Snap, late: Int): String {
        val b = StringBuilder("Settimana, ${short(snap.monday)}.")
        if (!snap.allowed) return b.append(" Allow your calendar: tap to open Settimana.").toString()
        val busiest = snap.days.maxByOrNull { it.weight }
        if (busiest != null && busiest.timed.isNotEmpty()) b.append(" Busiest: ${busiest.date.dayOfWeek.name.lowercase().replaceFirstChar(Char::titlecase)}.")
        next(snap)?.let { (t, w) -> b.append(" Next: $t, $w.") }
        if (late > 0) b.append(" $late overdue.")
        return b.toString()
    }
}

/** Draws [s] with the top of its capitals at [top] (the grammar's way of placing a line). */
private fun Canvas.capTop(s: String, x: Float, top: Float, p: Paint) = drawText(s, x, top + Grammar.cap(p), p)
