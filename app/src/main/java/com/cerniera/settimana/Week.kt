package com.cerniera.settimana

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The week as a paper diary keeps it (pure, tested on the JVM). Seven days, Monday first; each
 * day's events from the calendar and knots from Nodo; and how much of the page each day takes.
 */
object Week {
    /** One calendar instance: [start] and [end] are epoch ms; an all-day event's are midnight UTC, as the provider gives them. */
    data class Event(
        val id: Long,
        val title: String,
        val start: Long,
        val end: Long,
        val allDay: Boolean,
        val colour: Int,
        val calendar: String,
        val location: String? = null,
    )

    /** A knot from Nodo, read-only here. */
    data class Knot(val id: Int, val text: String, val due: Long, val weight: Int, val done: Boolean, val late: Boolean)

    data class Day(val date: LocalDate, val events: List<Event>, val knots: List<Knot>) {
        val timed get() = events.filter { !it.allDay }.sortedBy { it.start }
        val allDay get() = events.filter { it.allDay }
        /**
         * How much the day weighs on the page (Membrane): a full Thursday takes height from an empty
         * Saturday. One for the day itself, one per timed event, a half per all-day event or knot.
         */
        val weight get() = 1f + timed.size + 0.5f * (allDay.size + knots.size)
    }

    /** The Monday that starts the week holding [d]. */
    fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun dates(monday: LocalDate) = (0L until 7L).map { monday.plusDays(it) }

    /**
     * Shares of [total] for days of [weights], each at least [floor] (so an empty day keeps room for
     * its name and one line). Shares sum to [total] whenever the floors fit.
     */
    fun shares(weights: List<Float>, total: Float, floor: Float): List<Float> {
        if (weights.isEmpty()) return emptyList()
        if (floor * weights.size >= total) return weights.map { total / weights.size }
        val fixed = BooleanArray(weights.size)
        var out: List<Float>
        while (true) {
            val freeW = weights.filterIndexed { i, _ -> !fixed[i] }.sum()
            val freeT = total - floor * fixed.count { it }
            out = weights.mapIndexed { i, w -> if (fixed[i]) floor else freeT * w / freeW }
            val under = out.indices.filter { !fixed[it] && out[it] < floor }
            if (under.isEmpty()) return out
            under.forEach { fixed[it] = true }
        }
    }

    /** Which days sit on which page of the spread: Monday to Wednesday; Thursday, Friday, and the weekend sharing a block. */
    val LEFT = listOf(0, 1, 2)
    val RIGHT = listOf(3, 4, 5)   // 5 is the weekend block: Saturday and Sunday side by side

    /** The events of [all] that touch [date] in [zone]: an event spanning days appears on each. */
    fun on(all: List<Event>, date: LocalDate, zone: java.time.ZoneId): List<Event> {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // All-day events are stored in UTC midnights
        val utcStart = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val utcEnd = date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        return all.filter { e -> if (e.allDay) e.start < utcEnd && e.end > utcStart else e.start < dayEnd && (e.end > dayStart || e.end == e.start && e.start >= dayStart) }
    }

    /** Knots due on [date]; overdue open knots gather on today (Nodo's rule: nothing slips into the past undone). */
    fun knotsOn(all: List<Knot>, date: LocalDate, today: LocalDate, zone: java.time.ZoneId): List<Knot> = all.filter { k ->
        val d = java.time.Instant.ofEpochMilli(k.due).atZone(zone).toLocalDate()
        if (k.late && !k.done) date == today else d == date
    }.sortedBy { it.due }

    fun days(monday: LocalDate, events: List<Event>, knots: List<Knot>, today: LocalDate, zone: java.time.ZoneId) =
        dates(monday).map { d -> Day(d, on(events, d, zone), knotsOn(knots, d, today, zone)) }

    /** "23 – 29 Nov 2026", or across months "30 Nov – 6 Dec 2026". */
    fun title(monday: LocalDate): String {
        val sun = monday.plusDays(6)
        fun m(d: LocalDate) = d.month.name.take(3).lowercase().replaceFirstChar(Char::titlecase)
        return if (monday.month == sun.month) "${monday.dayOfMonth} – ${sun.dayOfMonth} ${m(sun)} ${sun.year}"
        else if (monday.year == sun.year) "${monday.dayOfMonth} ${m(monday)} – ${sun.dayOfMonth} ${m(sun)} ${sun.year}"
        else "${monday.dayOfMonth} ${m(monday)} ${monday.year} – ${sun.dayOfMonth} ${m(sun)} ${sun.year}"
    }

    /** "This week", "Next week", "Last week", or the dates. */
    fun relative(monday: LocalDate, today: LocalDate): String = when (java.time.temporal.ChronoUnit.WEEKS.between(monday(today), monday)) {
        0L -> "This week"; 1L -> "Next week"; -1L -> "Last week"; else -> "Week of ${monday.dayOfMonth} ${monday.month.name.take(3).lowercase().replaceFirstChar(Char::titlecase)}"
    }

    /** 09:30, or "All day". */
    fun time(e: Event, zone: java.time.ZoneId): String = if (e.allDay) "All day" else
        java.time.Instant.ofEpochMilli(e.start).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

    /** Busy minutes between 07:00 and 22:00, for the day's density bar. */
    fun busy(day: Day, zone: java.time.ZoneId): Int {
        val from = day.date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        val to = day.date.atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val spans = day.timed.map { maxOf(it.start, from) to minOf(it.end, to) }.filter { it.second > it.first }.sortedBy { it.first }
        var total = 0L; var curS = -1L; var curE = -1L
        spans.forEach { (s, e) -> if (s > curE) { if (curE > curS) total += curE - curS; curS = s; curE = e } else curE = maxOf(curE, e) }
        if (curE > curS) total += curE - curS
        return (total / 60_000).toInt()
    }
}
