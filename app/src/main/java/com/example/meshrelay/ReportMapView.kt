package com.example.meshrelay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
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
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    /** The halo around the worst report. Borrowed from every radar screen ever drawn. */
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

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

    /**
     * A faint grid. It carries no data - there is no basemap here and pretending
     * otherwise would be dishonest - but a plot of dots on flat black gives the eye
     * nothing to judge distance against, and the scale bar alone is not enough.
     */
    private fun drawGrid(canvas: Canvas) {
        grid.color = Palette.tint(Palette.TEAL, 20)
        val step = width / 8f
        var x = step
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), grid)
            x += step
        }
        var y = step
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, grid)
            y += step
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)

        val solid = Palette.TEXT
        val faded = Palette.TEXT_DIM
        note.color = faded
        label.color = Palette.GROUND

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
            leash.color = Palette.tint(Palette.TEAL, 130)
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

        // You are teal, because teal in this app is always "the network, and you are
        // part of it". Reports are never teal, so your own dot can never be mistaken
        // for an emergency.
        own?.let {
            halo.color = Palette.tint(Palette.TEAL, 45)
            canvas.drawCircle(sx(it), sy(it), width * 0.055f, halo)
            ring.color = Palette.TEAL
            canvas.drawCircle(sx(it), sy(it), width * 0.030f, ring)
            blip.color = Palette.TEAL
            blip.alpha = 255
            canvas.drawCircle(sx(it), sy(it), width * 0.012f, blip)
            note.textAlign = Paint.Align.CENTER
            note.color = Palette.TEAL
            canvas.drawText("you", sx(it), sy(it) - width * 0.070f, note)
            note.color = faded
            note.textAlign = Paint.Align.LEFT
        }

        for ((pos, list) in groups) {
            val worst = list.maxOf { it.priority }
            val colour = colourFor(worst)
            val r = width * (0.034f + 0.008f * minOf(list.size - 1, 4))
            // Only the genuinely critical get a halo. If everything glows, the glow
            // stops meaning anything - the same reasoning that keeps red reserved.
            if (worst >= 9) {
                halo.color = Palette.tint(colour, 40)
                canvas.drawCircle(sx(pos), sy(pos), r * 2.1f, halo)
                halo.color = Palette.tint(colour, 55)
                canvas.drawCircle(sx(pos), sy(pos), r * 1.5f, halo)
            }
            blip.color = colour
            blip.alpha = 255
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

    private fun colourFor(priority: Int) = Palette.forPriority(priority)
}
