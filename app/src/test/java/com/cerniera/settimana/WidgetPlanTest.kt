package com.cerniera.settimana

import com.cerniera.kit.Grammar
import com.cerniera.settimana.WidgetPlan.Box
import com.cerniera.settimana.WidgetPlan.Detail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The week widget's plan at every size a launcher can give it (25 Nov 2026): from the smallest
 * resize to the whole main screen, upright and turned, growing and shrinking in 6 dp steps. Every
 * day must keep a box inside the widget, no two may overlap, nothing may cover the days, and no
 * band may be left empty (the grammar's rule 7).
 */
class WidgetPlanTest {
    private val weeks = listOf(
        List(7) { 1f },                                      // an empty week
        listOf(3f, 2f, 1.5f, 6f, 2f, 2f, 1f),                // the demo's shape: a heavy Thursday
        listOf(12f, 1f, 1f, 1f, 1f, 1f, 1f),                 // one crowded day
        listOf(1f, 1f, 1f, 1f, 1f, 9f, 9f),                  // a full weekend
    )

    private fun sizes() = sequence { var w = 40f; while (w <= 720f) { var h = 40f; while (h <= 720f) { yield(w to h); h += 6f }; w += 6f } }

    @Test fun everySizeKeepsSevenDaysInside() {
        var n = 0
        for ((w, h) in sizes()) for (wts in weeks) {
            val p = WidgetPlan.layout(w, h, wts)
            val at = "${w}x$h ${p.shape} ${p.arrangement}"
            assertEquals("$at: seven days", 7, p.cells.size)
            p.cells.forEachIndexed { i, b ->
                assertTrue("$at: day $i has a size, $b", b.w >= 0f && b.h >= 0f)
                assertTrue("$at: day $i inside the hero, $b in ${p.hero}", b.within(p.hero))
                assertTrue("$at: day $i inside the widget's inset, $b", b.within(Box(p.pad, p.pad, w - p.pad, h - p.pad)))
                for (j in i + 1 until 7) assertTrue("$at: days $i and $j overlap", !b.overlaps(p.cells[j]))
            }
            listOfNotNull(p.label, p.words, p.foot).forEach { r ->
                assertTrue("$at: $r inside the widget", r.within(p.all))
                assertTrue("$at: $r covers the days", !r.overlaps(p.hero))
            }
            p.words?.let { wb -> p.label?.let { assertTrue("$at: words under the label", !wb.overlaps(it)) }; p.foot?.let { assertTrue("$at: words over the foot", !wb.overlaps(it)) } }
            p.binding?.let { assertTrue("$at: binding inside the hero", it.within(p.hero)) }
            p.cells.forEachIndexed { i, b -> assertTrue("$at: day $i can't show ${p.detail}", WidgetPlan.detail(b, p.k) >= p.detail) }
            n++
        }
        assertTrue(n > 10_000)
    }

    @Test fun theDaysShareTheWholeHero() {
        for ((w, h) in sizes()) {
            val p = WidgetPlan.layout(w, h, weeks[1])
            if (p.arrangement != WidgetPlan.Hero.COLUMNS && p.arrangement != WidgetPlan.Hero.ROWS) continue
            val along = if (p.arrangement == WidgetPlan.Hero.COLUMNS) p.hero.w else p.hero.h
            val used = p.cells.sumOf { (if (p.arrangement == WidgetPlan.Hero.COLUMNS) it.w else it.h).toDouble() }.toFloat()
            val gaps = (if (p.arrangement == WidgetPlan.Hero.COLUMNS) p.cells.last().r - p.cells.first().l else p.cells.last().b - p.cells.first().t) - used
            assertEquals("${w}x$h: the days and their gaps fill the hero", along, used + gaps, 0.1f)
        }
    }

    /** Rule 7: between the label, the days, what's next and the foot, nothing but the grammar's gaps. */
    @Test fun noEmptyBands() {
        for ((w, h) in sizes()) {
            val p = WidgetPlan.layout(w, h, weeks[1])
            if (p.shape == Grammar.Shape.TILE) { assertEquals("${w}x$h tile", p.hero, Box(p.pad, p.pad, w - p.pad, h - p.pad)); continue }
            // The vertical stack in the hero's own column: what isn't a block may only be gaps
            val stack = listOfNotNull(p.label, p.hero, p.words, p.foot).filter { it.l < p.hero.r && it.r > p.hero.l }.sortedBy { it.t }
            var free = stack.first().t - p.pad
            for (i in 1 until stack.size) free += (stack[i].t - stack[i - 1].b).coerceAtLeast(0f)
            free += (h - p.pad) - stack.last().b
            assertTrue("${w}x$h ${p.shape}: $free dp left empty", free <= 3.05f * Grammar.GAP * p.k + 0.1f)
        }
    }

    @Test fun theDaysGetMoreDetailAsTheyGrow() {
        val k = 1f
        var last = Detail.BAR
        for (hh in 10..400 step 3) { val d = WidgetPlan.detail(Box(0f, 0f, 90f, hh.toFloat()), k); assertTrue("down $hh: $d after $last", d >= last || WidgetPlan.across(Box(0f, 0f, 90f, hh.toFloat()))); if (!WidgetPlan.across(Box(0f, 0f, 90f, hh.toFloat()))) last = d }
        last = Detail.BAR
        for (ww in 10..600 step 3) { val b = Box(0f, 0f, ww.toFloat(), 18f); if (!WidgetPlan.across(b)) continue; val d = WidgetPlan.detail(b, k); assertTrue("across $ww: $d after $last", d >= last); last = d }
        assertEquals(Detail.LIST, last)
    }

    @Test fun theFamilyMatrixComposesAsTheGrammarSays() {
        fun at(s: String) = s.split("x").map { it.toFloat() }.let { WidgetPlan.layout(it[0], it[1], weeks[1]) }
        // Every matrix size but the small square has the label
        for (s in WidgetMatrix.MATRIX) if (s != "110x110") assertTrue("$s has its label", at(s).label != null)
        assertEquals(Grammar.Shape.STRIP, at("300x120").shape); assertTrue(at("300x120").inline)
        assertTrue("4x2 says what's next", at("255x164").words != null)
        assertEquals("a large column opens as the diary's pages", WidgetPlan.Hero.PAGES_STACKED, at("385x480").arrangement)
        assertEquals("a large row opens as the diary's pages", WidgetPlan.Hero.PAGES_SIDE, at("560x300").arrangement)
        assertEquals(WidgetPlan.Hero.ROWS, at("177x480").arrangement)
    }

    /** One design across the week: where the days can be named, every day is named, the quietest included. */
    @Test fun everyDayShowsTheSameDetail() {
        fun at(s: String) = s.split("x").map { it.toFloat() }.let { WidgetPlan.layout(it[0], it[1], weeks[2]) }
        assertTrue("4x2 names every day", at("255x164").detail >= Detail.NAME)
        assertTrue("a tall column names every day", at("128x267").detail >= Detail.INITIAL)
        assertTrue("a large widget lists every day's events", at("385x480").detail >= Detail.NAME)
    }

    @Test fun pagesKeepTheAppsSplit() {
        val (cells, bind) = WidgetPlan.cells(Box(0f, 0f, 400f, 300f), WidgetPlan.Hero.PAGES_SIDE, weeks[1], 1f)!!
        val mid = bind!!.cx
        cells.take(3).forEach { assertTrue("Mon to Wed on the left page", it.r <= mid) }
        cells.drop(3).forEach { assertTrue("Thu to Sun on the right page", it.l >= mid) }
        assertEquals("Saturday and Sunday side by side", cells[5].t, cells[6].t, 0.01f)
        assertTrue("Thursday, the heaviest, is the tallest", cells[3].h > cells[4].h)
    }
}
