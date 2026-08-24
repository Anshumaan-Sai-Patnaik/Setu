package com.example.meshrelay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The command centre: reports plotted where they were actually sent from.
 *
 * No basemap, no tiles, no venue file. Nothing to load, nothing to configure, and it
 * works at any site on earth. What a dispatcher needs is the shape of the trouble: three
 * reports clustered here, a medical call eighty metres over there.
 *
 * A real deployment would put the organiser's own site plan behind this. That is a
 * drawing job, not a protocol job, and saying so is more honest than pretending a
 * hand-drawn festival map is part of the system.
 */
class ReportMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var groups: List<Pair<Position, List<MeshMessage>>> = emptyList()
    private var own: Position? = null

    // --- what this phone is walking towards -------------------------------
    private var target: MeshMessage? = null
    private var targetDistance: Double? = null

    /** Distance when the trend was last updated, so small GPS jitter cannot flip it. */
    private var markDistance: Double? = null
    private var trend: String = ""

    /**
     * The map fits everything on screen, which quietly defeats the thing a person walking
     * actually wants to see: as they get closer the view zoomed in by the same amount, so
     * the two dots never appeared to meet. Holding the scale while heading for the same
     * report lets the gap visibly close.
     */
    private var heldSpan: Double = 0.0

    private val blip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val leash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val note = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headline = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }

    fun show(messages: List<MeshMessage>, ownPosition: Position?) {
        own = ownPosition
        // Reports from the same phone share a position exactly, so grouping on the
        // encoded value collapses them into one marker with a count.
        groups = messages.filter { it.pos != null }
            .groupBy { it.pos!!.encode() }
            .map { (_, list) -> list.first().pos!! to list }

        updateTarget(messages, ownPosition)
        invalidate()
    }

    /** Which report to head for is a decision-layer choice, so it lives in Positioning.kt. */
    private fun updateTarget(messages: List<MeshMessage>, here: Position?) {
        val pick = chooseTarget(messages, here)
        if (pick == null || here == null) {
            target = null
            targetDistance = null
            trend = ""
            heldSpan = 0.0
            return
        }

        val d = Position.metresBetween(here, pick.pos!!)

        if (pick.id != target?.id) {
            // New target: start again rather than carry a stale trend across.
            target = pick
            markDistance = d
            trend = ""
            heldSpan = 0.0
        } else {
            val mark = markDistance
            // Five metres is roughly GPS noise. Below that, saying "getting closer" would
            // be inventing progress out of jitter.
            if (mark != null && abs(d - mark) >= 5.0) {
                trend = if (d < mark) "getting closer" else "getting further"
                markDistance = d
            }
        }
        targetDistance = d
    }

    private fun ink(): Int {
        val v = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, v, true)
        return v.data
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val solid = ink()
        val faded = (solid and 0x00FFFFFF) or 0x99000000.toInt()
        note.color = faded
        label.color = Color.WHITE

        val pad = width * 0.10f
        note.textSize = width * 0.032f
        label.textSize = width * 0.036f
        label.isFakeBoldText = true
        headline.textSize = width * 0.042f

        val points = groups.map { it.first } + listOfNotNull(own)
        if (points.isEmpty()) {
            note.textAlign = Paint.Align.CENTER
            canvas.drawText("No reports with a location yet", width / 2f, height / 2f, note)
            note.textAlign = Paint.Align.LEFT
            return
        }

        // Flat-earth approximation around the centre. Over a venue-sized area the error is
        // far below GPS noise, and it avoids any projection library.
        val lat0 = points.sumOf { it.lat } / points.size
        val lon0 = points.sumOf { it.lon } / points.size
        val mPerLat = 110_540.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(lat0))

        fun eastOf(p: Position) = (p.lon - lon0) * mPerLon
        fun northOf(p: Position) = (p.lat - lat0) * mPerLat

        // Never zoom closer than 40 m across, or two reports a metre apart fill the screen.
        var span = 40.0
        for (p in points) {
            span = max(span, abs(eastOf(p)) * 2.4)
            span = max(span, abs(northOf(p)) * 2.4)
        }
        // Hold the scale while walking to the same report, so approaching actually looks
        // like approaching. Only ever widen.
        if (target != null) {
            heldSpan = max(heldSpan, span)
            span = heldSpan
        }

        val usable = minOf(width, height) - pad * 2
        val scale = (usable / span).toFloat()

        fun sx(p: Position) = width / 2f + (eastOf(p) * scale).toFloat()
        fun sy(p: Position) = height / 2f - (northOf(p) * scale).toFloat()

        // The gap, drawn as a line that visibly shortens as you close it.
        val here = own
        val aim = target?.pos
        if (here != null && aim != null) {
            leash.color = faded
            canvas.drawLine(sx(here), sy(here), sx(aim), sy(aim), leash)
            targetDistance?.let { d ->
                note.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    describeDistance(d),
                    (sx(here) + sx(aim)) / 2f,
                    (sy(here) + sy(aim)) / 2f - note.textSize * 0.4f,
                    note
                )
                note.textAlign = Paint.Align.LEFT
            }
        }

        own?.let {
            ring.color = solid
            canvas.drawCircle(sx(it), sy(it), width * 0.030f, ring)
            note.textAlign = Paint.Align.CENTER
            canvas.drawText("you", sx(it), sy(it) - width * 0.042f, note)
            note.textAlign = Paint.Align.LEFT
        }

        for ((pos, list) in groups) {
            val worst = list.maxOf { it.priority }
            blip.color = colourFor(worst)
            blip.alpha = 225
            val r = width * (0.038f + 0.008f * minOf(list.size - 1, 4))
            canvas.drawCircle(sx(pos), sy(pos), r, blip)
            if (list.size > 1) {
                canvas.drawText(
                    list.size.toString(), sx(pos), sy(pos) + label.textSize * 0.36f, label
                )
            }
        }

        drawHeadline(canvas, solid)
        drawScale(canvas, scale, pad, faded)
    }

    /**
     * The single most useful thing to tell someone walking towards an emergency, stated
     * rather than left as arithmetic between two glances at a number.
     */
    private fun drawHeadline(canvas: Canvas, solid: Int) {
        val t = target ?: return
        val d = targetDistance ?: return
        headline.color = colourFor(t.priority)
        canvas.drawText(
            t.type.tag + "   " + describeDistance(d),
            width * 0.04f, headline.textSize * 1.4f, headline
        )
        if (trend.isNotEmpty()) {
            note.color = solid
            canvas.drawText(trend, width * 0.04f, headline.textSize * 2.6f, note)
            note.color = (solid and 0x00FFFFFF) or 0x99000000.toInt()
        }
    }

    /** Without a scale, a plot of dots says nothing about how far apart anything is. */
    private fun drawScale(canvas: Canvas, pxPerMetre: Float, pad: Float, colour: Int) {
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f; color = colour }
        val metres = niceRound((width * 0.28f / pxPerMetre).toDouble())
        val barPx = (metres * pxPerMetre).toFloat()
        val y = height - pad * 0.55f
        val x0 = pad * 0.6f
        canvas.drawLine(x0, y, x0 + barPx, y, bar)
        canvas.drawLine(x0, y - 10f, x0, y + 10f, bar)
        canvas.drawLine(x0 + barPx, y - 10f, x0 + barPx, y + 10f, bar)
        canvas.drawText(
            if (metres >= 1000) (metres / 1000).roundToLong().toString() + " km"
            else metres.roundToLong().toString() + " m",
            x0, y - 16f, note
        )
    }

    private fun niceRound(v: Double): Double {
        val steps = doubleArrayOf(5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0)
        for (s in steps) if (v <= s) return s
        return 5000.0
    }

    private fun colourFor(priority: Int) = when {
        priority >= 9 -> Color.parseColor("#D32F2F")
        priority >= 8 -> Color.parseColor("#F57C00")
        priority >= 5 -> Color.parseColor("#FBC02D")
        else -> Color.parseColor("#78909C")
    }
}
