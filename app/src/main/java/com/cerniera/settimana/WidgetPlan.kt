package com.cerniera.settimana

import com.cerniera.kit.Grammar
import kotlin.math.max
import kotlin.math.min

/**
 * Where everything in the week widget goes, in dp (25 Nov 2026; pure, tested on the JVM).
 *
 * The widget's hero is the week's balance: the seven days sharing one band, each day's share set
 * by its weight (Membrane), so a heavy Thursday shows before any word is read. The same plan serves
 * every size the launcher gives, cover or main screen, upright or turned, growing or shrinking:
 * nothing is drawn from fixed positions, only from this plan, and the plan is a continuous function
 * of the widget's width and height composed from the family's grammar (Kit Grammar):
 *
 * - TILE (short side under 90 dp): the seven days alone, edge to edge, side by side or stacked.
 * - STRIP (twice as wide as tall): the label (with what's next on its line), the days across the
 *   width, the foot. A large strip opens into the diary's two pages.
 * - ROW, and small squares: the days standing at the left, stacked Monday to Sunday (or as the
 *   two pages once large); the label, what's next and the foot beside them.
 * - COLUMN, and large squares: the label, the days across the width (side by side, stacked, or as
 *   two pages, one above the other, once large), what's next beneath them, the foot.
 *
 * Each day's own box then shows as much as its size allows (see [Detail]): a bar of busy time; its
 * initial; its name and date; then its events and knots, line by line.
 */
object WidgetPlan {
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w get() = r - l
        val h get() = b - t
        val cx get() = (l + r) / 2f
        val cy get() = (t + b) / 2f
        fun within(o: Box, e: Float = 0.05f) = l >= o.l - e && t >= o.t - e && r <= o.r + e && b <= o.b + e
        fun overlaps(o: Box, e: Float = 0.05f) = l < o.r - e && o.l < r - e && t < o.b - e && o.t < b - e
    }

    enum class Hero { COLUMNS, ROWS, PAGES_SIDE, PAGES_STACKED }

    /**
     * [label]: the band the label sits in (its capitals). [words]: the room for what's next, or
     * null where there is none (tiles, strips, and widgets too small to give it room). [inline]: the
     * strip's way, what's next set on the label's own line. [foot]: the progress line's band.
     * [cells]: one box per day, Monday first. [binding]: the champagne hairline between the pages.
     */
    class Layout(
        val w: Float, val h: Float,
        val shape: Grammar.Shape, val k: Float, val pad: Float,
        val label: Box?, val words: Box?, val inline: Boolean, val foot: Box?,
        val hero: Box, val arrangement: Hero, val cells: List<Box>, val binding: Box?,
        /** One level of detail for all seven days, so the week reads as one design (see [Detail]). */
        val detail: Detail,
    ) {
        val all: Box get() = Box(0f, 0f, w, h)
    }

    /** Jost's capitals stand about 0.72 of the type's size (the figure Nodo's label uses). */
    const val CAP = 0.72f

    /** A small square takes the row's composition, a large one the column's (rule 1). */
    const val SQUARE_SPLIT = 200f

    fun layout(w: Float, h: Float, weights: List<Float>): Layout {
        val shape = Grammar.shape(w, h)
        val k = Grammar.scale(w, h)
        val pad = Grammar.inset(w, h, k)
        val wts = if (weights.size == 7) weights else List(7) { 1f }
        return when (shape) {
            Grammar.Shape.TILE -> tile(w, h, shape, k, pad, wts)
            Grammar.Shape.STRIP -> strip(w, h, shape, k, pad, wts)
            Grammar.Shape.ROW -> row(w, h, shape, k, pad, wts)
            Grammar.Shape.COLUMN -> column(w, h, shape, k, pad, wts)
            Grammar.Shape.SQUARE -> if (min(w, h) < SQUARE_SPLIT * k) row(w, h, shape, k, pad, wts) else column(w, h, shape, k, pad, wts)
        }
    }

    private fun labelBox(k: Float, pad: Float, l: Float, r: Float) = Box(l, pad, r, pad + Grammar.LABEL * k * CAP)
    private fun footBox(h: Float, k: Float, pad: Float, l: Float, r: Float) = Box(l, h - pad - Grammar.LINE * k, r, h - pad)

    private fun tile(w: Float, h: Float, shape: Grammar.Shape, k: Float, pad: Float, wts: List<Float>): Layout {
        val hero = Box(pad, pad, w - pad, h - pad)
        val arr = if (hero.w >= hero.h) Hero.COLUMNS else Hero.ROWS
        val (cells, bind, lvl) = fit(hero, arr, wts, k)
        return Layout(w, h, shape, k, pad, null, null, false, null, hero, arr, cells, bind, lvl)
    }

    private fun strip(w: Float, h: Float, shape: Grammar.Shape, k: Float, pad: Float, wts: List<Float>): Layout {
        val g = Grammar.GAP * k
        var label: Box? = labelBox(k, pad, pad, w - pad)
        val foot = footBox(h, k, pad, pad, w - pad)
        var hero = Box(pad, label!!.b + g, w - pad, foot.t - g)
        // Too short for a label above the days: the days take the room, the foot stays
        if (hero.h < 16f * k) { label = null; hero = Box(pad, pad, w - pad, foot.t - g) }
        val arr = if (hero.w >= 360f * k && hero.h >= 120f * k) Hero.PAGES_SIDE else Hero.COLUMNS
        val (cells, bind, lvl) = fit(hero, arr, wts, k)
        return Layout(w, h, shape, k, pad, label, null, label != null, foot, hero, arr, cells, bind, lvl)
    }

    private fun row(w: Float, h: Float, shape: Grammar.Shape, k: Float, pad: Float, wts: List<Float>): Layout {
        val g = Grammar.GAP * k
        val innerW = w - 2 * pad; val innerH = h - 2 * pad
        val heroW = (innerH * 0.95f).coerceIn(innerW * 0.34f, innerW * 0.52f)
        val colL = pad + heroW + 1.6f * g
        // Too narrow for words beside the days: the strip's composition instead
        if (w - pad - colL < 84f * k) return strip(w, h, shape, k, pad, wts)
        val hero = Box(pad, pad, pad + heroW, h - pad)
        val label = labelBox(k, pad, colL, w - pad)
        val foot = footBox(h, k, pad, colL, w - pad)
        val words = Box(colL, label.b + g, w - pad, foot.t - g)
        val arr = if (heroW >= 170f * k && innerH >= 170f * k) Hero.PAGES_SIDE else Hero.ROWS
        val (cells, bind, lvl) = fit(hero, arr, wts, k)
        return Layout(w, h, shape, k, pad, label, words.takeIf { it.h >= 24f * k }, false, foot, hero, arr, cells, bind, lvl)
    }

    private fun column(w: Float, h: Float, shape: Grammar.Shape, k: Float, pad: Float, wts: List<Float>): Layout {
        val g = Grammar.GAP * k
        val label = labelBox(k, pad, pad, w - pad)
        val foot = footBox(h, k, pad, pad, w - pad)
        val top = label.b + g; val bottom = foot.t - g
        val avail = bottom - top
        // What's next takes a third, within reason; the days take the rest (rule 7: the hero first)
        val wordsH = (avail * 0.34f).coerceIn(38f * k, 96f * k)
        val words: Box?
        val hero: Box
        if (avail - wordsH - g < 44f * k) { words = null; hero = Box(pad, top, w - pad, bottom) }
        else { hero = Box(pad, top, w - pad, bottom - wordsH - g); words = Box(pad, hero.b + g, w - pad, bottom) }
        val arr = when {
            hero.w >= 200f * k && hero.h >= 150f * k -> Hero.PAGES_STACKED
            hero.w >= hero.h -> Hero.COLUMNS
            else -> Hero.ROWS
        }
        val (cells, bind, lvl) = fit(hero, arr, wts, k)
        return Layout(w, h, shape, k, pad, label, words, false, foot, hero, arr, cells, bind, lvl)
    }

    /**
     * The days' boxes at the richest [Detail] every weekday can reach: each day's share keeps a
     * floor just large enough for that level (so a quiet Tuesday still shows its name when Thursday
     * does), and the rest is shared by weight. As in a paper diary, Saturday and Sunday may take
     * the smaller boxes: when the weekdays list their events, the weekend may show its names alone.
     * Falls back level by level to the bar.
     */
    fun fit(hero: Box, arr: Hero, wts: List<Float>, k: Float): Triple<List<Box>, Box?, Detail> {
        for (level in Detail.values().reversed()) {
            val (cells, bind) = cells(hero, arr, wts, k, level) ?: continue
            if (cells.withIndex().all { (i, b) -> detail(b, k) >= wanted(level, i) }) return Triple(cells, bind, level)
        }
        val (cells, bind) = cells(hero, arr, wts, k, Detail.BAR)!!
        return Triple(cells, bind, Detail.BAR)
    }

    /** The level day [i] must reach when the week shows [level]: the weekend one less below the list. */
    fun wanted(level: Detail, i: Int) = if (i >= 5 && level == Detail.LIST) Detail.NAME else level

    /** The level day [i] is drawn at in [plan]: the plan's own, or the weekend's smaller box. */
    fun shown(plan: Layout, i: Int, b: Box): Detail = minOf(plan.detail, maxOf(wanted(plan.detail, i), detail(b, plan.k)))

    /** The smallest length along its line that gives a day [level], its other side being [cross]; null if none up to [most]. */
    fun need(level: Detail, cross: Float, alongWidth: Boolean, k: Float, most: Float): Float? {
        if (level == Detail.BAR) return 0f
        var f = 1f
        while (f <= most + 0.01f) {
            if (detail(if (alongWidth) Box(0f, 0f, f, cross) else Box(0f, 0f, cross, f), k) >= level) return f
            f += 0.5f
        }
        return null
    }

    /** [Week.shares] with a floor of its own for each day. Sums to [total] whenever the floors fit. */
    fun sharesWith(weights: List<Float>, total: Float, floors: List<Float>): List<Float> {
        val fixed = BooleanArray(weights.size)
        while (true) {
            val freeW = weights.filterIndexed { i, _ -> !fixed[i] }.sum()
            val freeT = total - floors.filterIndexed { i, _ -> fixed[i] }.sum()
            val out = weights.mapIndexed { i, w -> if (fixed[i]) floors[i] else if (freeW > 0f) freeT * w / freeW else 0f }
            val under = out.indices.filter { !fixed[it] && out[it] < floors[it] }
            if (under.isEmpty()) return out
            under.forEach { fixed[it] = true }
        }
    }

    /** The seven days' boxes in [hero], Monday first, and the binding between pages; null if [level] can't fit every day. */
    fun cells(hero: Box, arr: Hero, wts: List<Float>, k: Float, level: Detail = Detail.BAR): Pair<List<Box>, Box?>? {
        val weekend = wanted(level, 5)
        // Each item: its weight, the level it needs, and its other side. [alongWidth]: whether the line runs across
        class Item(val w: Float, val level: Detail, val cross: Float)
        fun split(len: Float, items: List<Item>, gapWanted: Float, alongWidth: Boolean): Pair<List<Float>, Float>? {
            val n = items.size
            val gap = min(gapWanted, len / n * 0.25f).coerceAtLeast(0f)
            val total = max(0f, len - gap * (n - 1))
            val floors = items.map { max(total / n * 0.55f, need(it.level, it.cross, alongWidth, k, total) ?: return null) }
            if (floors.sum() > total + 0.01f) return null
            return sharesWith(items.map { it.w }, total, floors) to gap
        }
        fun along(start: Float, sizes: List<Float>, gap: Float): List<Pair<Float, Float>> {
            var x = start
            return sizes.map { s -> val a = x; x += s + gap; a to a + s }
        }
        return when (arr) {
            Hero.COLUMNS -> {
                val (s, gap) = split(hero.w, wts.map { Item(it, level, hero.h) }, 4f * k, true) ?: return null
                along(hero.l, s, gap).map { (a, b) -> Box(a, hero.t, b, hero.b) } to null
            }
            Hero.ROWS -> {
                val (s, gap) = split(hero.h, wts.map { Item(it, level, hero.w) }, 4f * k, false) ?: return null
                along(hero.t, s, gap).map { (a, b) -> Box(hero.l, a, hero.r, b) } to null
            }
            Hero.PAGES_SIDE -> {
                // The open diary: Monday to Wednesday down the left page; Thursday, Friday and the
                // weekend (Saturday and Sunday side by side) down the right, as the app lays it out
                val bind = min(16f * k, hero.w * 0.08f)
                val mid = hero.cx
                val left = Box(hero.l, hero.t, mid - bind / 2, hero.b)
                val right = Box(mid + bind / 2, hero.t, hero.r, hero.b)
                val (ls, lg) = split(left.h, (0..2).map { Item(wts[it], level, left.w) }, 6f * k, false) ?: return null
                // The weekend's block holds two days side by side: it needs the height of the narrower one
                val (rs, rg) = split(right.h, listOf(Item(wts[3], level, right.w), Item(wts[4], level, right.w),
                    Item(wts[5] + wts[6], weekend, (right.w - 6f * k) / 2f)), 6f * k, false) ?: return null
                val lc = along(left.t, ls, lg).map { (a, b) -> Box(left.l, a, left.r, b) }
                val rr = along(right.t, rs, rg)
                val end = rr[2]
                val (ws, wg) = split(right.w, (5..6).map { Item(wts[it], weekend, end.second - end.first) }, 6f * k, true) ?: return null
                val wk = along(right.l, ws, wg).map { (a, b) -> Box(a, end.first, b, end.second) }
                (lc + listOf(Box(right.l, rr[0].first, right.r, rr[0].second), Box(right.l, rr[1].first, right.r, rr[1].second)) + wk) to
                    Box(mid - 0.5f, hero.t, mid + 0.5f, hero.b)
            }
            Hero.PAGES_STACKED -> {
                // Turned: Monday to Wednesday across the top page, Thursday to Sunday across the bottom
                val bind = min(16f * k, hero.h * 0.08f)
                val mid = hero.cy
                val top = Box(hero.l, hero.t, hero.r, mid - bind / 2)
                val bot = Box(hero.l, mid + bind / 2, hero.r, hero.b)
                val (ts, tg) = split(top.w, (0..2).map { Item(wts[it], level, top.h) }, 6f * k, true) ?: return null
                val (bs, bg) = split(bot.w, (3..6).map { Item(wts[it], if (it >= 5) weekend else level, bot.h) }, 6f * k, true) ?: return null
                (along(top.l, ts, tg).map { (a, b) -> Box(a, top.t, b, top.b) } +
                    along(bot.l, bs, bg).map { (a, b) -> Box(a, bot.t, b, bot.b) }) to Box(hero.l, mid - 0.5f, hero.r, mid + 0.5f)
            }
        }
    }

    /** How much a day's box can show, from its size alone (so growing a widget adds detail, never empty room). */
    enum class Detail { BAR, INITIAL, NAME, LIST }

    /** Whether a day's box reads across (a row) or down (a column). */
    fun across(b: Box) = b.w > b.h * 1.6f

    fun detail(b: Box, k: Float): Detail {
        val ip = inner(b, k)
        val iw = b.w - 2 * ip; val ih = b.h - 2 * ip
        return if (across(b)) when {
            ih >= 11f * k && iw >= 110f * k -> Detail.LIST
            ih >= 9f * k && iw >= 70f * k -> Detail.NAME
            ih >= 8f * k && iw >= 34f * k -> Detail.INITIAL
            else -> Detail.BAR
        } else when {
            // A wide day sets its date beside its name and needs less height than a narrow one
            (iw >= 60f * k && ih >= 42f * k) || (iw >= 48f * k && ih >= 66f * k) -> Detail.LIST
            iw >= 24f * k && ih >= 40f * k -> Detail.NAME
            iw >= 8f * k && ih >= 26f * k -> Detail.INITIAL
            else -> Detail.BAR
        }
    }

    /** A day's own inner margin: a little room, never more than its box can spare. */
    fun inner(b: Box, k: Float) = min(4f * k, min(b.w, b.h) * 0.12f)
}
