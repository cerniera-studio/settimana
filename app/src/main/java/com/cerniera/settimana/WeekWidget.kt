package com.cerniera.settimana

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.cerniera.kit.Backdrop
import com.cerniera.kit.Family
import com.cerniera.kit.Finish
import com.cerniera.kit.Ground
import com.cerniera.kit.Surface
import java.time.LocalDate
import java.time.ZoneId

/**
 * The week on the home screen (25 Nov 2026). Mechack's three questions, answered before it was
 * built: Fold Widgets' Agenda lists events and its Month lays out dates, so a third list would
 * overlap them; what only Settimana has is the week's balance (each day's share of the week) with
 * Nodo's knots beside the events, so that is the hero. It complements the other two: a tap on a
 * day opens Settimana on that day.
 *
 * Fitted to every size and shape (his rule, 24 Nov 2026: scalable, portrait and landscape, growing
 * and shrinking, no back and forth): the launcher reports a size for each orientation (and, on the
 * Fold, for each screen); each gets its own picture, drawn to its exact size at the density of the
 * screen in use, composed by [WidgetPlan] from the family's grammar. Resizing either way redraws at
 * the new size (onAppWidgetOptionsChanged). Nothing is a scaled copy of another size.
 *
 * Kept current without Settimana open: a content trigger on the calendar provider and on Nodo's
 * /week (JobScheduler), an alarm at the next boundary (an event starting or ending, or midnight),
 * the half-hourly update, and Fold Widgets' LOOK_CHANGED for its finish and the wallpaper.
 */
class WeekWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = async(c)
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = async(c)
    override fun onEnabled(c: Context) { Watch.schedule(c, force = true); async(c) }
    override fun onDisabled(c: Context) { Watch.cancel(c); alarm(c)?.let { c.getSystemService(AlarmManager::class.java)?.cancel(it) } }

    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            LOOK -> { Backdrop.invalidate(); async(c) }
            TICK, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> async(c)
            else -> super.onReceive(c, i)
        }
    }

    private fun async(c: Context) {
        val pending = goAsync()
        Thread { try { refresh(c.applicationContext) } catch (t: Throwable) { android.util.Log.w("Settimana", "widget not drawn", t) } finally { pending.finish() } }.start()
    }

    companion object {
        const val LOOK = "com.cerniera.LOOK_CHANGED"
        const val TICK = "com.cerniera.settimana.WIDGET_TICK"
        const val EXTRA_ID = "widget_id"
        private val DAY_IDS = intArrayOf(R.id.d0, R.id.d1, R.id.d2, R.id.d3, R.id.d4, R.id.d5, R.id.d6)

        fun store(c: Context) = c.getSharedPreferences("settimana_widgets", Context.MODE_PRIVATE)

        private fun ids(c: Context, m: AppWidgetManager): IntArray =
            runCatching { m.getAppWidgetIds(ComponentName(c, WeekWidget::class.java)) }.getOrNull() ?: IntArray(0)

        /** Redraws every week widget, from any thread but the main one. */
        fun refresh(c: Context) {
            val m = AppWidgetManager.getInstance(c) ?: return
            val ids = ids(c, m)
            if (ids.isEmpty()) return
            Watch.schedule(c)
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val snap = snap(c, Week.monday(today), today, zone)
            val d = Backdrop.density(c)
            val screen = Backdrop.screenSize(c)
            val portrait = screen.y >= screen.x
            // Where Fold Widgets' live mode last saw these widgets (26 Nov 2026), then the family look
            Family.seats(c, store(c), ids) { sizes(m.getAppWidgetOptions(it)) }
            val look = look(c)
            ids.forEach { id ->
              // Sent through the Kit's WidgetBudget (27 Nov 2026): within Android's real cap, and a refusal
              // is redrawn smaller rather than leaving the week on its last picture
              com.cerniera.kit.WidgetBudget.send(c, m, id) {
                val budget = com.cerniera.kit.WidgetBudget.bytes(c)
                val shrink = Math.sqrt(com.cerniera.kit.WidgetBudget.scale(c).toDouble()).toFloat()
                val list = sizes(m.getAppWidgetOptions(id))
                // The size in use now first, at full resolution, so it is never the one that gives way
                val now = if (portrait) list.minBy { it.width / it.height } else list.maxBy { it.width / it.height }
                val seat = Backdrop.seat(store(c), id)
                val map = LinkedHashMap<SizeF, RemoteViews>()
                var spent = 0L
                (listOf(now) + list.filter { !same(it, now) }).forEachIndexed { index, s ->
                    val isNow = index == 0
                    var px = Math.round(s.width * d); var py = Math.round(s.height * d)
                    // Measured by a tap at this very size, on this screen: its true pixels
                    if (isNow && seat != null && seat.exact && seat.screenW == screen.x && seat.screenH == screen.y &&
                        Math.abs(seat.repW - s.width) < 1.5f && Math.abs(seat.repH - s.height) < 1.5f) {
                        px = Math.round(seat.width); py = Math.round(seat.height)
                    }
                    val want = px.toLong() * py * 4
                    val f = shrink * (if (!isNow && spent + want > budget) Math.sqrt((budget - spent).coerceAtLeast(budget / 6).toDouble() / want).toFloat() else 1f)
                    val bw = Math.round(px * f).coerceAtLeast(40); val bh = Math.round(py * f).coerceAtLeast(30)
                    spent += bw.toLong() * bh * 4
                    // The picture's own dp, from its pixels (the tap-measured size may differ a little from the report)
                    val dd = d * f
                    // Every picture on the family's ground (27 Nov 2026): if the launcher shows the one drawn
                    // for the other orientation, a solid finish is still the same tone as its neighbours
                    val ground = Family.ground(c, store(c), id)
                    val (bmp, drawn) = picture(c, bw, bh, dd, look.first, look.second, ground, snap)
                    map[s] = views(c, id, index, s, bmp, drawn, bw / dd, bh / dd, snap.monday)
                }
                if (map.size == 1) map.values.first() else RemoteViews(map)
              }
            }
            alarm(c, nextBoundary(snap))
        }

        fun snap(c: Context, monday: LocalDate, today: LocalDate, zone: ZoneId): WeekFace.Snap {
            val allowed = Sources.calendarAllowed(c)
            val events = Sources.events(c, monday, zone)
            val knots = Sources.knots(c, monday, zone)
            return WeekFace.Snap(monday, Week.days(monday, events, knots, today, zone), today, System.currentTimeMillis(), zone, allowed)
        }

        /** One picture [bw] x [bh] px at [d] px per dp, wearing the family's finish over [ground]. */
        fun picture(c: Context, bw: Int, bh: Int, d: Float, finish: Finish, radius: Float, ground: Ground, snap: WeekFace.Snap): Pair<Bitmap, WeekFace.Drawn> {
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b).apply { scale(d, d) }
            val w = bw / d; val h = bh / d
            val s = Surface(finish, ground, w, h, minOf(radius, minOf(w, h) / 2), 1.1f / d)
            s.paint(cv)
            return b to WeekFace.draw(c, cv, s, snap)
        }

        private fun views(c: Context, id: Int, index: Int, size: SizeF, bmp: Bitmap, drawn: WeekFace.Drawn, w: Float, h: Float, monday: LocalDate): RemoteViews =
            RemoteViews(c.packageName, R.layout.week_widget).apply {
                setImageViewBitmap(R.id.picture, bmp)
                setContentDescription(R.id.picture, drawn.spoken)
                // The whole widget: Settimana at this week; its bounds measure the widget (Backdrop.record)
                setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, id * 64 + index * 8 + 7,
                    Intent(c, MainActivity::class.java).putExtra(EXTRA_ID, id).putExtra("rw", size.width).putExtra("rh", size.height)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                // Each day: a tap opens Settimana on that day. Placed in the picture's own dp, scaled to the size reported
                val sx = size.width / w; val sy = size.height / h
                DAY_IDS.forEachIndexed { i, vid ->
                    val b = drawn.cells.getOrNull(i)
                    if (b == null || b.w < 4f || b.h < 4f) { setViewVisibility(vid, View.GONE); return@forEachIndexed }
                    setViewVisibility(vid, View.VISIBLE)
                    setViewLayoutMargin(vid, RemoteViews.MARGIN_LEFT, b.l * sx, TypedValue.COMPLEX_UNIT_DIP)
                    setViewLayoutMargin(vid, RemoteViews.MARGIN_TOP, b.t * sy, TypedValue.COMPLEX_UNIT_DIP)
                    setViewLayoutWidth(vid, b.w * sx, TypedValue.COMPLEX_UNIT_DIP)
                    setViewLayoutHeight(vid, b.h * sy, TypedValue.COMPLEX_UNIT_DIP)
                    val date = monday.plusDays(i.toLong())
                    setContentDescription(vid, date.dayOfWeek.name.lowercase().replaceFirstChar(Char::titlecase) + " ${date.dayOfMonth}")
                    setOnClickPendingIntent(vid, PendingIntent.getActivity(c, id * 64 + index * 8 + i,
                        Intent(c, MainActivity::class.java).putExtra("date", date.toString())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }

        /**
         * Every size the launcher says it shows this widget at, in dp: one per orientation, and on
         * the Fold one per screen (up to four), so turning or folding finds a picture made for it.
         */
        private fun sizes(o: Bundle): List<SizeF> {
            @Suppress("DEPRECATION")
            val listed = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.filter { it.width > 0 && it.height > 0 }.orEmpty()
            val out = ArrayList<SizeF>()
            for (s in listed) if (out.none { same(it, s) }) out += s
            if (out.isNotEmpty()) return out.take(4)
            val pw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250); val ph = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 160)
            val lw = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, pw); val lh = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, ph)
            val p = SizeF(pw.coerceAtLeast(40).toFloat(), ph.coerceAtLeast(40).toFloat()); val l = SizeF(lw.coerceAtLeast(40).toFloat(), lh.coerceAtLeast(40).toFloat())
            return if (same(p, l)) listOf(p) else listOf(p, l)
        }

        private fun same(a: SizeF, b: SizeF) = Math.abs(a.width - b.width) < 1.5f && Math.abs(a.height - b.height) < 1.5f

        /** The tap that opened Settimana from a widget: records the widget's true size, and redraws if it changed. */
        fun measured(c: Context, i: Intent?) {
            i ?: return
            val id = i.getIntExtra(EXTRA_ID, -1).takeIf { it >= 0 } ?: return
            val reported = SizeF(i.getFloatExtra("rw", 0f), i.getFloatExtra("rh", 0f))
            if (runCatching { Backdrop.record(c, store(c), i, id, null, null, true, reported) }.getOrDefault(false))
                Thread { runCatching { refresh(c.applicationContext) } }.start()
        }

        /**
         * Fold Widgets' finish and corners, and the picture behind its glass, through the Kit's Family
         * (26 Nov 2026), the one code every Cerniera widget uses. Until then Fold Widgets never told
         * Settimana when the finish changed (its list of followers stopped at Riserva), so the week
         * kept whatever finish it last drew; and with no place from live mode (its picture's view is
         * now named root, as live mode looks for) its glass read a different ground from its neighbours.
         */
        fun look(c: Context): Pair<Finish, Float> = Family.look(c, store(c)).let { it.finish to it.radius.toFloat() }

        /** When the picture next goes stale: an event this week starting or ending, or midnight. */
        fun nextBoundary(snap: WeekFace.Snap): Long {
            val midnight = snap.today.plusDays(1).atStartOfDay(snap.zone).toInstant().toEpochMilli()
            val soon = snap.days.flatMap { it.timed }.flatMap { listOf(it.start, it.end) }.filter { it > snap.now + 30_000 }.minOrNull()
            return minOf(midnight + 5_000, soon ?: Long.MAX_VALUE).coerceAtLeast(snap.now + 60_000)
        }

        private fun alarm(c: Context): PendingIntent? = PendingIntent.getBroadcast(c, 9001,
            Intent(c, WeekWidget::class.java).setAction(TICK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** An inexact alarm (no special permission): the picture redraws near [at], whenever the phone is next awake. */
        private fun alarm(c: Context, at: Long) {
            val pi = alarm(c) ?: return
            runCatching { c.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, at, pi) }
        }
    }
}

/**
 * Redraws the widgets when the calendar or Nodo's knots change, without Settimana running: a job
 * triggered by the providers' own change notices. Such a job lasts one change, so each run asks for
 * the next; the widget's update (also sent after a restart) asks again if it was ever lost.
 */
class Watch : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            runCatching { WeekWidget.refresh(applicationContext) }
            jobFinished(params, false)
            schedule(applicationContext, force = true)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val JOB = 4801

        fun schedule(c: Context, force: Boolean = false) {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            if (!force && js.getPendingJob(JOB) != null) return
            val job = JobInfo.Builder(JOB, ComponentName(c, Watch::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .addTriggerContentUri(JobInfo.TriggerContentUri(Uri.parse("content://com.cerniera.nodo.cues/week"), 0))
                .setTriggerContentUpdateDelay(2_000)
                .setTriggerContentMaxDelay(15_000)
                .build()
            runCatching { js.schedule(job) }
        }

        fun cancel(c: Context) { runCatching { c.getSystemService(JobScheduler::class.java)?.cancel(JOB) } }
    }
}
