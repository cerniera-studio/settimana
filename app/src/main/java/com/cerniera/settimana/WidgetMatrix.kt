package com.cerniera.settimana

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.cerniera.kit.Finish
import com.cerniera.kit.Ground
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Test only (debug builds, `--ez matrix true`): the week widget drawn by its own painter at the
 * family's 13 matrix sizes, in its other states, as portrait and landscape pairs, and across a sweep
 * of sizes from the smallest resize to the whole main screen, saved to files/ for the emulator walk
 * to attach to the build's release.
 */
object WidgetMatrix {
    val MATRIX = listOf("192x164", "255x164", "255x267", "255x361", "128x267", "177x226", "177x300", "177x480",
        "385x226", "385x480", "560x300", "300x120", "110x110")

    /** A fixed fictitious week, seen on its Thursday at noon, so every build's pictures compare. */
    fun demo(allowed: Boolean = true, empty: Boolean = false): WeekFace.Snap {
        val zone = ZoneId.systemDefault()
        val monday = Week.monday(LocalDate.now(zone))
        val today = monday.plusDays(3)
        val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val (events, knots0) = if (empty || !allowed) emptyList<Week.Event>() to emptyList<Week.Knot>() else Sources.demo(monday, zone)
        // The demo's knots, judged against the fixed noon: the one on Wednesday is overdue
        val knots = knots0.map { it.copy(late = it.due < now) }
        return WeekFace.Snap(monday, Week.days(monday, events, knots, today, zone), today, now, zone, allowed)
    }

    fun draw(c: Context, w: Float, h: Float, d: Float, snap: WeekFace.Snap, finish: Finish = Finish.VETRO): Bitmap =
        WeekWidget.picture(c, Math.round(w * d), Math.round(h * d), d, finish, 24f, Ground.flat(0xFF1B211E.toInt()), snap).first

    fun save(c: Context) {
        val d = 2.625f
        fun out(b: Bitmap, n: String) = File(c.filesDir, n).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val week = demo()
        // The picker's preview: the family's 960 px canvas at 2.625 px per dp, in Carbonio
        val pv = draw(c, 255f, 164f, d, week, Finish.CARBONIO)
        val canvas = Bitmap.createBitmap(960, 960, Bitmap.Config.ARGB_8888)
        Canvas(canvas).drawBitmap(pv, ((960 - pv.width) / 2).toFloat(), ((960 - pv.height) / 2).toFloat(), null)
        out(canvas, "preview_settimana.png")
        for (sz in MATRIX) { val (w, h) = sz.split("x").map { it.toFloat() }; out(draw(c, w, h, d, week), "matrix-$sz.png") }
        // The other states
        out(draw(c, 255f, 164f, d, demo(allowed = false)), "state-no-calendar.png")
        out(draw(c, 255f, 164f, d, demo(empty = true)), "state-empty-week.png")
        out(draw(c, 385f, 226f, d, week, Finish.CARBONIO), "state-carbonio.png")
        // Portrait and landscape pairs, as a launcher reports them for one widget on each screen
        sheet(c, listOf(listOf(192f to 220f, 255f to 164f), listOf(255f to 300f, 400f to 190f), listOf(420f to 440f, 640f to 300f)), 2f, week, "pairs.png")
        // Growing and shrinking: one widget resized across the whole range, both ways
        val ws = listOf(70f, 110f, 160f, 220f, 300f, 400f, 560f)
        val hs = listOf(40f, 90f, 140f, 200f, 280f, 400f)
        sheet(c, hs.map { h -> ws.map { w -> w to h } }, 1.5f, week, "sweep.png")
    }

    /** Pictures laid out in rows at their true relative sizes, on a dark sheet. */
    private fun sheet(c: Context, rows: List<List<Pair<Float, Float>>>, d: Float, snap: WeekFace.Snap, name: String) {
        val gap = 16f * d
        val width = rows.maxOf { r -> r.sumOf { (it.first * d + gap).toDouble() } }.toFloat() + gap
        val height = rows.sumOf { r -> (r.maxOf { it.second } * d + gap).toDouble() }.toFloat() + gap
        val b = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.drawColor(0xFF0B0D0E.toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        var y = gap
        for (r in rows) {
            var x = gap
            for ((w, h) in r) { cv.drawBitmap(draw(c, w, h, d, snap), x, y, p); x += w * d + gap }
            y += r.maxOf { it.second } * d + gap
        }
        File(c.filesDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
