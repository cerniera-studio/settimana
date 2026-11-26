package com.cerniera.settimana

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import com.cerniera.kit.Kit
import com.cerniera.kit.px
import com.cerniera.kit.withAlpha
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The week to view, drawn as one sheet (Membrane): seven days share the page, a full day taking
 * height (or width) from an empty one. Four arrangements:
 * - [Mode.SPREAD_V], main screen upright: Monday to Wednesday on the left page, Thursday, Friday
 *   and the weekend (Saturday and Sunday side by side) on the right; nothing under the crease.
 * - [Mode.SPREAD_H], main screen turned: Monday to Wednesday across the top page, Thursday to
 *   Sunday across the bottom one.
 * - [Mode.COLUMN], cover upright: the days down one scrolling page, with the seven days as tabs in
 *   the hinge-side edge.
 * - [Mode.ROW], cover turned: three days side by side, scrolling sideways.
 *
 * Concentrato: drag the page to turn the week and the words fall away, leaving each day's events
 * as bars of busy time, so a week reads as density. They return when it settles. Every shape
 * turns by touch, up, down, left or right (see Touch).
 * Giulietta: no buttons on the page. Tap an event or a knot; hold a day's open space for a new event.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class WeekView(ctx: Context) : View(ctx) {
    enum class Mode { SPREAD_V, SPREAD_H, COLUMN, ROW }

    var days: List<Week.Day> = emptyList()
        set(v) {
            val newWeek = v.firstOrNull()?.date != field.firstOrNull()?.date
            field = v; relayout()
            if (newWeek && landEnd) { landEnd = false; scroll = maxScroll(); invalidate() }
        }
    var today: LocalDate = LocalDate.now()
    var zone: ZoneId = ZoneId.systemDefault()
    var mode = Mode.COLUMN; set(v) { field = v; relayout() }
    /** The crease's band in px (spreads only): nothing is drawn there. */
    var gutter = 0
    /** Where the crease runs, in this view's own px (x when upright, y when turned); -1 for the middle. */
    var crease = -1f

    var onEvent: (Week.Event) -> Unit = {}
    var onKnot: (Week.Knot) -> Unit = {}
    var onNewEvent: (LocalDate) -> Unit = {}
    /** "+n more": the whole day in a sheet. */
    var onDayAll: (Week.Day) -> Unit = {}
    /** -1 for the week before, +1 for the week after. */
    var onTurn: (Int) -> Unit = {}

    private class Block(val day: Int, val r: RectF)
    private var blocks: List<Block> = emptyList()
    private var contentLen = 0f
    private val scroller = OverScroller(ctx)
    private var scroll = 0f
    /** Concentrato, 0 at rest to 1 mid-drag. */
    private var calm = 0f
    private val hits = mutableListOf<Pair<RectF, Any>>()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private fun dp(v: Number) = context.px(v).toFloat()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = relayout()

    /** Days' places: shares of the page by weight, each at least room for its name and a line. */
    private fun relayout() {
        if (width == 0 || height == 0 || days.size < 7) { blocks = emptyList(); invalidate(); return }
        val pad = dp(12); val gap = dp(8); val floor = dp(78)
        val w = width.toFloat(); val h = height.toFloat()
        val out = mutableListOf<Block>()
        fun weights(ix: List<Int>) = ix.map { days[it].weight }
        when (mode) {
            Mode.SPREAD_V -> {
                val cx = if (crease > 0) crease else w / 2f
                listOf(Triple(listOf(0, 1, 2), pad, cx - gutter / 2f), Triple(listOf(3, 4, 5), cx + gutter / 2f, w - pad)).forEach { (ix, x0, x1) ->
                    val ws = ix.map { if (it == 5) days[5].weight + days[6].weight else days[it].weight }
                    var y = pad
                    Week.shares(ws, h - 2 * pad - gap * (ix.size - 1), floor).forEachIndexed { k, sh ->
                        val r = RectF(x0, y, x1, y + sh)
                        if (ix[k] == 5) {
                            val mid = (r.left + r.right) / 2f
                            out += Block(5, RectF(r.left, r.top, mid - gap / 2, r.bottom)); out += Block(6, RectF(mid + gap / 2, r.top, r.right, r.bottom))
                        } else out += Block(ix[k], r)
                        y += sh + gap
                    }
                }
                contentLen = h
            }
            Mode.SPREAD_H -> {
                val cy = if (crease > 0) crease else h / 2f
                listOf(Triple(listOf(0, 1, 2), pad, cy - gutter / 2f), Triple(listOf(3, 4, 5, 6), cy + gutter / 2f, h - pad)).forEach { (ix, y0, y1) ->
                    var x = pad
                    Week.shares(weights(ix), w - 2 * pad - gap * (ix.size - 1), dp(110)).forEachIndexed { k, sh ->
                        out += Block(ix[k], RectF(x, y0, x + sh, y1)); x += sh + gap
                    }
                }
                contentLen = h
            }
            Mode.COLUMN -> {
                val left = dp(44) // the tabs in the hinge-side edge
                var y = pad
                days.forEachIndexed { i, d ->
                    val need = dp(64) + dp(26) * (d.timed.size + d.allDay.size + d.knots.size).coerceAtLeast(1)
                    out += Block(i, RectF(left, y, w - pad, y + max(floor, need))); y += max(floor, need) + gap
                }
                contentLen = y + pad
            }
            Mode.ROW -> {
                val each = (w - 2 * pad - 2 * gap) / 3f
                var x = pad
                days.indices.forEach { i -> out += Block(i, RectF(x, pad, x + each, h - pad)); x += each + gap }
                contentLen = x + pad - gap
            }
        }
        blocks = out
        scroll = scroll.coerceIn(0f, maxScroll())
        invalidate()
    }

    /** The days' boxes in this view's px, for the four-shape check. */
    fun report(): String = blocks.joinToString(", ", "[", "]") { "[${it.day}, ${it.r.left.toInt()}, ${it.r.top.toInt()}, ${it.r.right.toInt()}, ${it.r.bottom.toInt()}]" }

    private fun maxScroll() = when (mode) {
        Mode.COLUMN -> max(0f, contentLen - height)
        Mode.ROW -> max(0f, contentLen - width)
        else -> 0f
    }

    /** Scrolls the single-page shapes to [day] (0 = Monday), for the tabs and for opening on today. */
    fun show(day: Int, animate: Boolean = true) {
        val b = blocks.firstOrNull { it.day == day } ?: return
        val target = (if (mode == Mode.ROW) b.r.left - dp(12) else b.r.top - dp(12)).coerceIn(0f, maxScroll())
        if (!animate) { scroll = target; invalidate(); return }
        ValueAnimator.ofFloat(scroll, target).apply {
            duration = Kit.GLIDE; interpolator = Kit.ease
            addUpdateListener { scroll = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ---- Touch ----------------------------------------------------------------------------------
    //
    // Turning the week (26 Nov 2026: on the cover only the arrows turned it). Every shape now turns
    // by touch, up, down, left or right:
    // - across the way the page scrolls (sideways on the upright cover and the spreads, up and down
    //   on the turned cover), the page follows the finger and a firm swipe or a fling turns the week;
    // - along the way it scrolls, pulling on past Sunday turns to the week after, and past Monday to
    //   the week before. Only when the gesture starts at that end, so a long scroll to Sunday never
    //   turns the week by accident; the page gives with resistance and a caption names the week.
    // The first few dp of a gesture settle its axis, so a slightly diagonal scroll stays a scroll.

    private enum class Axis { X, Y }
    /** The gesture's axis, settled on its first move; null until then. */
    private var axis: Axis? = null
    /** The page's offset while it is dragged or pulled to turn the week. */
    private var dragX = 0f
    private var dragY = 0f
    private var atStart = false
    private var atEnd = false
    private var turning = false
    /** Past the point of turning, for one tick of feedback as it is crossed. */
    private var armed = false
    /** After turning back along the scroll, the new week opens on its Sunday rather than its Monday. */
    private var landEnd = false

    /** The axis the page scrolls along, or null for the spreads, which don't scroll. */
    private fun scrollAxis() = when (mode) { Mode.COLUMN -> Axis.Y; Mode.ROW -> Axis.X; else -> null }
    private fun offset(a: Axis) = if (a == Axis.X) dragX else dragY
    private fun setOffset(a: Axis, v: Float) { if (a == Axis.X) dragX = v else dragY = v }
    private fun span(a: Axis) = (if (a == Axis.X) width else height).toFloat()
    /** How far to drag before the week turns: shorter for an edge pull, which already resists. */
    private fun threshold(a: Axis) = if (a == scrollAxis()) dp(84) else span(a) * 0.2f

    private fun easeCalm() {
        val a = axis ?: return
        calm = (abs(offset(a)) / (span(a) * 0.18f)).coerceIn(0f, 1f)
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            axis = null; armed = false
            atStart = scroll <= 0.5f; atEnd = scroll >= maxScroll() - 0.5f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (mode == Mode.COLUMN && e.x < dp(40)) { tabAt(e.y)?.let { show(it); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }; return true }
            val (x, y) = content(e.x, e.y)
            hits.lastOrNull { it.first.contains(x, y) }?.second?.let { t ->
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                when (t) { is Week.Event -> onEvent(t); is Week.Knot -> onKnot(t); is Week.Day -> onDayAll(t) }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val (x, y) = content(e.x, e.y)
            if (hits.any { it.first.contains(x, y) }) return
            blocks.firstOrNull { it.r.contains(x, y) }?.let { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onNewEvent(days[it.day].date) }
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (turning) return true
            val a = axis ?: run {
                val tx = abs(e2.x - (e1?.x ?: e2.x)); val ty = abs(e2.y - (e1?.y ?: e2.y))
                (if (tx > ty) Axis.X else Axis.Y).also { axis = it }
            }
            val d = if (a == Axis.X) dx else dy
            val off = offset(a)
            if (a == scrollAxis()) {
                // Along the scroll: scroll, unless pulling on past an end the gesture began at
                val pullOn = off != 0f || (d > 0 && atEnd && scroll >= maxScroll() - 0.5f) || (d < 0 && atStart && scroll <= 0.5f)
                if (pullOn) {
                    var n = off - d * 0.5f
                    if ((off < 0 && n > 0) || (off > 0 && n < 0)) n = 0f // back to rest; the page doesn't pull the other way
                    setOffset(a, n)
                } else scroll = (scroll + d).coerceIn(0f, maxScroll())
            } else {
                // Across the scroll (or anywhere on the spreads): the page follows the finger
                setOffset(a, off - d)
            }
            if (offset(a) != 0f) parent?.requestDisallowInterceptTouchEvent(true)
            val past = abs(offset(a)) > threshold(a)
            if (past != armed) { armed = past; if (past) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            easeCalm()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (turning) return true
            val a = axis ?: return true
            val v = if (a == Axis.X) vx else vy
            val other = if (a == Axis.X) vy else vx
            if (a != scrollAxis()) {
                // A firm swipe across turns the week, however short the drag
                if (abs(v) > dp(900) && abs(v) > abs(other)) { turn(if (v < 0) 1 else -1, a); return true }
                return true
            }
            if (offset(a) != 0f) return true // pulling past an end: lifting decides
            when (mode) {
                Mode.COLUMN -> scroller.fling(0, scroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
                Mode.ROW -> scroller.fling(scroll.toInt(), 0, -vx.toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                else -> {}
            }
            postInvalidateOnAnimation()
            return true
        }
    })

    private fun content(x: Float, y: Float) = when (mode) {
        Mode.COLUMN -> x - dragX to y + scroll - dragY
        Mode.ROW -> x + scroll - dragX to y - dragY
        else -> x - dragX to y - dragY
    }

    private fun tabAt(y: Float): Int? {
        val step = (height - dp(24)) / 7f
        return ((y - dp(12)) / step).toInt().takeIf { it in 0..6 }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = gestures.onTouchEvent(e)
        if (!turning && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) {
            val a = axis
            android.util.Log.i("Settimana", "lift ${e.actionMasked} mode=$mode axis=$a dx=$dragX dy=$dragY scroll=$scroll w=$width h=$height")
            if (a != null && offset(a) != 0f) {
                val off = offset(a)
                if (e.actionMasked == MotionEvent.ACTION_UP && abs(off) > threshold(a)) turn(if (off < 0) 1 else -1, a) else settle(a)
            }
        }
        return r || super.onTouchEvent(e)
    }

    /** Turns to the week before (-1) or after (+1), sliding the page out along [a]. */
    private fun turn(dir: Int, a: Axis) {
        android.util.Log.i("Settimana", "view turn $dir along $a from ${offset(a)}")
        turning = true
        val out = if (dir > 0) -span(a) else span(a)
        ValueAnimator.ofFloat(offset(a), out).apply {
            duration = Kit.SETTLE; interpolator = Kit.ease
            addUpdateListener { setOffset(a, it.animatedValue as Float); calm = 1f; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: android.animation.Animator) {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    // Pulled back past Monday, the week before opens on its Sunday; otherwise on its Monday
                    landEnd = dir < 0 && a == scrollAxis()
                    scroll = if (landEnd) maxScroll() else 0f
                    turning = false
                    onTurn(dir)
                    setOffset(a, -out * 0.35f); settle(a)
                }
            })
            start()
        }
    }

    private fun settle(a: Axis) {
        ValueAnimator.ofFloat(offset(a), 0f).apply {
            duration = Kit.GLIDE; interpolator = Kit.ease
            addUpdateListener { setOffset(a, it.animatedValue as Float); calm = (abs(offset(a)) / (span(a) * 0.18f)).coerceIn(0f, 1f); invalidate() }
            start()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = (if (mode == Mode.ROW) scroller.currX else scroller.currY).toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    // ---- Drawing --------------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        hits.clear()
        if (blocks.isEmpty()) return
        c.save()
        when (mode) {
            Mode.COLUMN -> c.translate(dragX, dragY - scroll)
            Mode.ROW -> c.translate(dragX - scroll, dragY)
            else -> c.translate(dragX, dragY)
        }
        blocks.forEach { drawDay(c, days[it.day], it.r) }
        c.restore()
        drawTurnCaption(c)
        if (mode == Mode.COLUMN) drawTabs(c)
        // The crease, as the diary's binding: a hairline down (or across) the middle
        if (gutter > 0) {
            p.style = Paint.Style.FILL; p.color = withAlpha(Kit.CHAMPAGNE, 0.35f)
            val cx = if (crease > 0) crease else if (mode == Mode.SPREAD_V) width / 2f else height / 2f
            if (mode == Mode.SPREAD_V) c.drawRect(cx - dp(0.5f), dp(18), cx + dp(0.5f), height - dp(18), p)
            else if (mode == Mode.SPREAD_H) c.drawRect(dp(18), cx - dp(0.5f), width - dp(18), cx + dp(0.5f), p)
        }
    }

    /** While the page is dragged or pulled, the week it will turn to, in the space it uncovers. */
    private fun drawTurnCaption(c: Canvas) {
        val a = axis ?: return
        val off = offset(a)
        if (turning || abs(off) < dp(8) || days.isEmpty()) return
        val dir = if (off < 0) 1 else -1
        val ready = abs(off) > threshold(a)
        val alpha = (abs(off) / threshold(a)).coerceIn(0f, 1f)
        val label = (if (dir > 0) "Next week" else "Week before") + " · " + Week.title(days[0].date.plusWeeks(dir.toLong()))
        tp.typeface = Kit.sans(context, 600); tp.textSize = dp(11); tp.letterSpacing = 0.14f
        tp.color = withAlpha(if (ready) Kit.CHAMPAGNE else Kit.MUTED, alpha)
        if (a == Axis.Y) {
            // In the band uncovered above or below the page
            tp.textAlign = Paint.Align.CENTER
            val y = if (off < 0) height + off / 2f + dp(4) else off / 2f + dp(4)
            c.drawText(label.uppercase(), width / 2f, y, tp)
        } else {
            // Turned on its side in the strip uncovered at the left or right
            tp.textAlign = Paint.Align.CENTER
            val x = if (off < 0) width + off / 2f else off / 2f
            c.save(); c.rotate(if (off < 0) 90f else -90f, x, height / 2f)
            c.drawText(label.uppercase(), x, height / 2f + dp(4), tp)
            c.restore()
        }
        tp.letterSpacing = 0f; tp.textAlign = Paint.Align.LEFT
    }

    private fun drawTabs(c: Canvas) {
        val step = (height - dp(24)) / 7f
        val visible = blocks.filter { it.r.bottom - scroll > 0 && it.r.top - scroll < height }.map { it.day }
        tp.typeface = Kit.sans(context, 600); tp.textSize = dp(11); tp.textAlign = Paint.Align.CENTER
        days.forEachIndexed { i, d ->
            val cy = dp(12) + step * (i + 0.5f)
            val on = i in visible
            val isToday = d.date == today
            if (on) { p.style = Paint.Style.FILL; p.color = withAlpha(Kit.CHAMPAGNE, if (isToday) 0.9f else 0.18f); c.drawRoundRect(RectF(dp(6), cy - dp(18), dp(34), cy + dp(18)), dp(14), dp(14), p) }
            tp.color = when { on && isToday -> Kit.GROUND; isToday -> Kit.CHAMPAGNE; on -> Kit.INK; else -> Kit.MUTED }
            c.drawText(d.date.dayOfWeek.name.take(2).lowercase().replaceFirstChar(Char::titlecase), dp(20), cy - dp(1), tp)
            tp.textSize = dp(10); c.drawText("${d.date.dayOfMonth}", dp(20), cy + dp(12), tp); tp.textSize = dp(11)
            if (d.knots.any { it.late && !it.done }) { p.color = Kit.ROSSO; c.drawCircle(dp(32), cy - dp(14), dp(2.5f), p) }
        }
    }

    private fun drawDay(c: Canvas, d: Week.Day, r: RectF) {
        val isToday = d.date == today
        val words = 1f - calm
        // The day's ground
        p.style = Paint.Style.FILL; p.color = Kit.SURFACE
        c.drawRoundRect(r, dp(18), dp(18), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1); p.color = if (isToday) withAlpha(Kit.CHAMPAGNE, 0.8f) else Kit.HAIRLINE
        c.drawRoundRect(r, dp(18), dp(18), p)
        c.save(); c.clipRect(r)

        val inX = r.left + dp(14); var y = r.top + dp(14)
        // The head: the day's name small, its date in the display face
        tp.typeface = Kit.sans(context, 600); tp.textSize = dp(10.5f); tp.letterSpacing = 0.16f; tp.textAlign = Paint.Align.LEFT
        tp.color = if (isToday) Kit.CHAMPAGNE else Kit.MUTED
        val name = (if (isToday) "TODAY · " else "") + d.date.dayOfWeek.name.take(3)
        c.drawText(name, inX, y + dp(10), tp)
        tp.letterSpacing = 0f; tp.typeface = Kit.display(context); tp.textSize = dp(26); tp.color = if (isToday) Kit.CHAMPAGNE else Kit.INK
        tp.textAlign = Paint.Align.RIGHT
        c.drawText("${d.date.dayOfMonth}", r.right - dp(14), y + dp(22), tp)
        tp.textAlign = Paint.Align.LEFT
        y += dp(20)
        // How full the day is, 07:00 to 22:00, as a hairline that fills
        val busy = Week.busy(d, zone) / 900f
        p.style = Paint.Style.FILL; p.color = withAlpha(Kit.INK, 0.08f)
        val barW = (r.width() - dp(28)) * 0.55f
        c.drawRoundRect(RectF(inX, y, inX + barW, y + dp(2)), dp(1), dp(1), p)
        p.color = withAlpha(Kit.CHAMPAGNE, 0.85f)
        c.drawRoundRect(RectF(inX, y, inX + barW * busy.coerceIn(0f, 1f), y + dp(2)), dp(1), dp(1), p)
        y += dp(12)

        // Margin for knots when the day is wide enough; otherwise knots follow the events
        val wide = r.width() > dp(250)
        val marginW = if (wide && d.knots.isNotEmpty()) min(dp(150), r.width() * 0.36f) else 0f
        val textRight = r.right - dp(14) - marginW - (if (marginW > 0) dp(10) else 0f)

        // Concentrato: the day as bars of busy time across the block
        if (calm > 0.01f) {
            val top = y + dp(4); val bh = dp(10)
            val from = d.date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli(); val span = 15 * 3_600_000f
            p.color = withAlpha(Kit.CHAMPAGNE, 0.75f * calm)
            d.timed.forEach { e ->
                val a = ((e.start - from) / span).coerceIn(0f, 1f); val b = ((e.end - from) / span).coerceIn(0f, 1f)
                if (b > a) c.drawRoundRect(RectF(inX + (r.width() - dp(28)) * a, top, inX + (r.width() - dp(28)) * max(b, a + 0.01f), top + bh), dp(3), dp(3), p)
            }
        }

        val line = dp(26)
        val events = d.allDay + d.timed
        val knotsInline = if (marginW > 0) emptyList() else d.knots
        val rows = events.size + knotsInline.size
        val room = ((r.bottom - dp(10) - y) / line).toInt()
        val shown = if (rows > room) (room - 1).coerceAtLeast(0) else rows
        var k = 0
        tp.typeface = Kit.sans(context, 400)
        events.forEach { e ->
            if (k >= shown) return@forEach
            val ry = y + k * line
            val hit = RectF(r.left, ry, textRight, ry + line)
            if (e.allDay) {
                p.color = withAlpha(e.colour.takeIf { it != 0 } ?: Kit.CHAMPAGNE, 0.22f * words + 0.05f)
                c.drawRoundRect(RectF(inX - dp(4), ry + dp(3), textRight, ry + line - dp(3)), dp(8), dp(8), p)
                tp.color = withAlpha(Kit.INK, words); tp.textSize = dp(13)
                c.drawText(ellipsize(e.title, textRight - inX - dp(4)), inX + dp(4), ry + dp(17.5f), tp)
            } else {
                p.color = withAlpha(e.colour.takeIf { it != 0 } ?: Kit.CHAMPAGNE, words)
                c.drawCircle(inX + dp(3), ry + dp(13), dp(3), p)
                tp.color = withAlpha(Kit.MUTED, words); tp.textSize = dp(12); tp.typeface = Kit.sans(context, 500)
                val t = Week.time(e, zone)
                c.drawText(t, inX + dp(12), ry + dp(17), tp)
                val tw = tp.measureText("00:00") + dp(20)
                tp.color = withAlpha(Kit.INK, words); tp.textSize = dp(13.5f); tp.typeface = Kit.sans(context, 400)
                c.drawText(ellipsize(e.title, textRight - inX - tw), inX + tw, ry + dp(17), tp)
            }
            hits += hit to e
            k++
        }
        knotsInline.forEach { kn -> if (k < shown) { drawKnot(c, kn, inX, y + k * line, textRight, words); k++ } }
        if (rows > shown) {
            tp.color = withAlpha(Kit.CHAMPAGNE, words); tp.textSize = dp(12); tp.typeface = Kit.sans(context, 500)
            c.drawText("+${rows - shown} more", inX + dp(12), y + shown * line + dp(17), tp)
            hits += RectF(r.left, y + shown * line, r.right, r.bottom) to d
        }
        if (events.isEmpty() && knotsInline.isEmpty() && words > 0.05f) {
            tp.color = withAlpha(Kit.MUTED, 0.6f * words); tp.textSize = dp(12.5f)
            c.drawText("Free", inX, y + dp(17), tp)
        }

        // The margin: Nodo's knots for the day, as in a diary's margin notes
        if (marginW > 0) {
            val mx = r.right - dp(14) - marginW
            p.color = Kit.HAIRLINE; c.drawRect(mx - dp(6), y, mx - dp(5), r.bottom - dp(12), p)
            d.knots.forEachIndexed { i, kn -> if (y + (i + 1) * line < r.bottom - dp(6)) drawKnot(c, kn, mx, y + i * line, r.right - dp(14), words) }
        }
        c.restore()
    }

    private fun drawKnot(c: Canvas, k: Week.Knot, x: Float, y: Float, right: Float, words: Float) {
        val colour = when { k.done -> Kit.MUTED; k.late -> Kit.ROSSO; else -> Kit.CHAMPAGNE }
        p.style = Paint.Style.FILL; p.color = colour
        val rad = dp(2.6f + k.weight * 0.7f)
        c.drawCircle(x + dp(4), y + dp(13), rad, p)
        if (k.weight >= 3) { p.style = Paint.Style.STROKE; p.strokeWidth = dp(1); c.drawCircle(x + dp(4), y + dp(13), rad + dp(2.5f), p); p.style = Paint.Style.FILL }
        tp.typeface = Kit.sans(context, 400); tp.textSize = dp(12.5f); tp.color = withAlpha(if (k.late && !k.done) Kit.ROSSO else Kit.INK, words * if (k.done) 0.5f else 1f)
        c.drawText(ellipsize(k.text, right - x - dp(14)), x + dp(14), y + dp(17), tp)
        hits += RectF(x - dp(4), y, right, y + dp(26)) to k
    }

    private fun ellipsize(s: String, w: Float) = if (w <= 0) "" else TextUtils.ellipsize(s, tp, w, TextUtils.TruncateAt.END).toString()
}
