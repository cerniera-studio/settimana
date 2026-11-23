package com.cerniera.settimana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class WeekTest {
    private val zone = ZoneId.of("Europe/London")
    private val mon = LocalDate.of(2026, 11, 23)
    private fun at(day: Int, h: Int, m: Int = 0) = mon.plusDays(day.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun mondayStartsTheWeek() {
        assertEquals(mon, Week.monday(LocalDate.of(2026, 11, 29)))   // a Sunday
        assertEquals(mon, Week.monday(mon))
        assertEquals("23 – 29 Nov 2026", Week.title(mon))
        assertEquals("30 Nov – 6 Dec 2026", Week.title(LocalDate.of(2026, 11, 30)))
        assertEquals("29 Dec 2025 – 4 Jan 2026", Week.title(LocalDate.of(2025, 12, 29)))
        assertEquals("Next week", Week.relative(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 25)))
    }

    @Test fun sharesFollowWeightAndKeepAFloor() {
        val s = Week.shares(listOf(1f, 6f, 1f), 800f, 120f)
        assertEquals(800f, s.sum(), 0.5f)
        assertTrue(s.all { it >= 120f - 0.01f })
        assertTrue(s[1] > s[0] * 3)
        // Floors that can't fit: equal shares
        assertEquals(listOf(100f, 100f, 100f), Week.shares(listOf(1f, 5f, 1f), 300f, 120f))
    }

    @Test fun eventsAndKnotsLandOnTheirDays() {
        val late = Week.Event(1, "Late call", at(1, 23), at(2, 1), false, 0, "Work")
        val allDay = Week.Event(2, "Car service", mon.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            mon.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, 0, "Personal")
        val knots = listOf(Week.Knot(1, "Overdue", at(0, 9), 1, false, true), Week.Knot(2, "Friday", at(4, 12), 2, false, false))
        val days = Week.days(mon, listOf(late, allDay), knots, today = mon.plusDays(2), zone = zone)
        assertEquals(listOf("Late call"), days[1].events.map { it.title })
        assertEquals(listOf("Late call", "Car service"), days[2].events.map { it.title })   // spills past midnight; all-day on Wednesday only
        assertTrue(days[3].events.isEmpty())
        assertEquals(listOf("Overdue"), days[2].knots.map { it.text })                       // overdue gathers on today
        assertTrue(days[0].knots.isEmpty())
        assertEquals(listOf("Friday"), days[4].knots.map { it.text })
        assertTrue(days[2].weight > days[3].weight)
    }

    @Test fun busyMinutesMergeOverlaps() {
        val e = listOf(Week.Event(1, "a", at(0, 9), at(0, 10), false, 0, ""), Week.Event(2, "b", at(0, 9, 30), at(0, 11), false, 0, ""),
            Week.Event(3, "c", at(0, 6), at(0, 7, 30), false, 0, ""))
        assertEquals(150, Week.busy(Week.Day(mon, e, emptyList()), zone))   // 09:00–11:00 plus 07:00–07:30
    }
}
