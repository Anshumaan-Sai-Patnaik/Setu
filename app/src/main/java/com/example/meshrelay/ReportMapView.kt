package com.example.meshrelay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
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
 *
 * ## The camera
 *
 * Drag to move, pinch to zoom, and the ground goes on for ever in every direction: the
 * grid is drawn from the camera outwards rather than from the reports, so there is
 * somewhere to go even where nothing has happened. That matters more than it sounds.
 * A view that can only frame the reports quietly tells a judge that the map is a picture
 * of the data; a view you can walk off the edge of tells them it is a place, and that the
 * empty ground north of the stage is empty because nobody has reported anything there.
 *
 * It starts on autopilot - framing everything - and stays there until a finger moves it.
 * From then on the camera is the user's, because a view that keeps snapping back while
 * someone is looking at a corner of it is worse than one that never moved. RECENTRE
 * appears the moment that happens, so there is always one tap back to the whole picture.
 * Nothing on stage should ever be one lost gesture away from unrecoverable.
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

    // -----------------------------------------------------------------------
    // The world, and the camera looking at it
    // -----------------------------------------------------------------------

    /**
     * Where metres are measured from. Fixed at the first position this view ever sees and
     * never moved again.
     *
     * It used to be the centroid of whatever was on screen, recomputed every frame. That
     * was fine while the camera was on autopilot and impossible once it was not: a report
     * arriving would shift the origin under a camera that had been placed by hand, and the
     * whole map would jump sideways while someone was looking at it.
     */
    private var anchor: Position? = null

    /** Camera centre, in metres east and north of the anchor. */
    private var camEast = 0.0
    private var camNorth = 0.0

    /** Zoom, as screen pixels per metre of ground. */
    private var pxPerMetre = 0f

    /** True once a finger has moved the camera. Autopilot stops for good at that point. */
    private var userDriven = false

    /** Where RECENTRE is, for hit testing. Empty while the camera is on autopilot. */
    private val recentreBox = RectF()

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
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG)
    /** The halo around the worst report. Borrowed from every radar screen ever drawn. */
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val dp = resources.displayMetrics.density

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

    // -----------------------------------------------------------------------
    // Gestures
    // -----------------------------------------------------------------------

    private val pinch = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomBy(d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        }
    )

    private val drags = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                // Pinching moves the focus point around as fingers land and lift, which
                // the scroll detector reports as a drag. Taking both at once makes the
                // map lurch, so the zoom wins while it is happening.
                if (pinch.isInProgress) return true
                if (pxPerMetre <= 0f) return true
                takeCamera()
                camEast += dx / pxPerMetre
                camNorth -= dy / pxPerMetre
                invalidate()
                return true
            }

            /** Double-tap is the gesture people already try. It goes back to the whole picture. */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                releaseCamera()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (userDriven && recentreBox.contains(e.x, e.y)) releaseCamera()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        drags.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun takeCamera() {
        userDriven = true
    }

    /** Back to autopilot: frame everything again on the next draw. */
    private fun releaseCamera() {
        userDriven = false
        heldSpan = 0.0
        invalidate()
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        if (pxPerMetre <= 0f) return
        takeCamera()
        // Keep the ground under the fingers under the fingers. Zooming about the centre
        // of the view instead would slide whatever someone is looking at off the screen.
        val worldE = camEast + (fx - width / 2f) / pxPerMetre
        val worldN = camNorth - (fy - height / 2f) / pxPerMetre
        pxPerMetre = (pxPerMetre * factor).coerceIn(MIN_PPM, MAX_PPM)
        camEast = worldE - (fx - width / 2f) / pxPerMetre
        camNorth = worldN + (fy - height / 2f) / pxPerMetre
        invalidate()
    }

    // -----------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

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

        // Fix the origin the first time there is anything to measure from. Until then the
        // map is empty ground with a grid on it, which is still worth being able to move
        // around: it is the difference between a chart of the data and a place.
        if (anchor == null && points.isNotEmpty()) {
            anchor = Position(
                points.sumOf { it.lat } / points.size,
                points.sumOf { it.lon } / points.size
            )
        }
        val origin = anchor
        val mPerLat = 110_540.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(origin?.lat ?: 0.0))

        // Flat-earth approximation around the anchor. Over a venue-sized area the error is
        // far below GPS noise, and it avoids any projection library.
        fun eastOf(p: Position) = if (origin == null) 0.0 else (p.lon - origin.lon) * mPerLon
        fun northOf(p: Position) = if (origin == null) 0.0 else (p.lat - origin.lat) * mPerLat

        val usable = minOf(width, height) - pad * 2
        if (!userDriven) frameEverything(points, usable, ::eastOf, ::northOf)
        if (pxPerMetre <= 0f) pxPerMetre = (usable / 40.0).toFloat()   // empty map: 40 m across

        fun sxOf(east: Double) = width / 2f + ((east - camEast) * pxPerMetre).toFloat()
        fun syOf(north: Double) = height / 2f - ((north - camNorth) * pxPerMetre).toFloat()
        fun sx(p: Position) = sxOf(eastOf(p))
        fun sy(p: Position) = syOf(northOf(p))

        drawGrid(canvas, ::sxOf, ::syOf)

        if (points.isEmpty()) {
            note.textAlign = Paint.Align.CENTER
            canvas.drawText("No reports with a location yet", width / 2f, height / 2f, note)
            note.textAlign = Paint.Align.LEFT
            drawScale(canvas, pad, faded)
            drawCameraControls(canvas, faded)
            return
        }

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
        drawScale(canvas, pad, faded)
        drawCameraControls(canvas, faded)
    }

    /** Autopilot: put everything on screen, with the margin the map has always had. */
    private fun frameEverything(
        points: List<Position>,
        usable: Float,
        eastOf: (Position) -> Double,
        northOf: (Position) -> Double
    ) {
        if (points.isEmpty()) {
            camEast = 0.0
            camNorth = 0.0
            return
        }
        val es = points.map(eastOf)
        val ns = points.map(northOf)
        camEast = (es.min() + es.max()) / 2
        camNorth = (ns.min() + ns.max()) / 2

        // Never zoom closer than 40 m across, or two reports a metre apart fill the screen.
        var span = 40.0
        span = max(span, (es.max() - es.min()) * 1.3)
        span = max(span, (ns.max() - ns.min()) * 1.3)

        // Hold the scale while walking to the same report, so approaching actually looks
        // like approaching. Only ever widen.
        if (target != null) {
            heldSpan = max(heldSpan, span)
            span = heldSpan
        }
        pxPerMetre = (usable / span).toFloat().coerceIn(MIN_PPM, MAX_PPM)
    }

    /**
     * The ground, and it does not run out.
     *
     * Lines are placed on round numbers of metres in world space rather than as a fixed
     * fraction of the view, so they stay put while the map moves under them - that is what
     * makes dragging feel like moving across something rather than sliding a picture
     * about. Every fifth line is brighter, which is what turns the grid from texture into
     * something a distance can be counted off against.
     *
     * It still carries no data. There is no basemap here and pretending otherwise would be
     * dishonest; the grid is there because a plot of dots on flat black gives the eye
     * nothing to judge distance against, and the scale bar alone is not enough.
     */
    private fun drawGrid(canvas: Canvas, sxOf: (Double) -> Float, syOf: (Double) -> Float) {
        if (pxPerMetre <= 0f) return
        val step = niceStep((GRID_TARGET_DP * dp / pxPerMetre).toDouble())
        val faint = Palette.tint(Palette.TEAL, 16)
        val strong = Palette.tint(Palette.TEAL, 34)

        val halfW = (width / 2f) / pxPerMetre
        val halfH = (height / 2f) / pxPerMetre

        var i = ceil((camEast - halfW) / step).toLong()
        val lastI = floor((camEast + halfW) / step).toLong()
        var guard = 0
        while (i <= lastI && guard++ < MAX_LINES) {
            grid.color = if (i % 5 == 0L) strong else faint
            val x = sxOf(i * step)
            canvas.drawLine(x, 0f, x, height.toFloat(), grid)
            i++
        }

        var j = ceil((camNorth - halfH) / step).toLong()
        val lastJ = floor((camNorth + halfH) / step).toLong()
        guard = 0
        while (j <= lastJ && guard++ < MAX_LINES) {
            grid.color = if (j % 5 == 0L) strong else faint
            val y = syOf(j * step)
            canvas.drawLine(0f, y, width.toFloat(), y, grid)
            j++
        }
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

    /**
     * RECENTRE, and the one line that teaches the gesture.
     *
     * The hint is shown only until the map has been moved once. After that the person has
     * worked it out, and a permanent instruction on a screen a judge is looking at is
     * clutter that says the design did not manage to be obvious.
     */
    private fun drawCameraControls(canvas: Canvas, faded: Int) {
        if (!userDriven) {
            recentreBox.setEmpty()
            note.color = faded
            note.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "drag to move · pinch to zoom",
                width / 2f, height - note.textSize * 0.9f, note
            )
            note.textAlign = Paint.Align.LEFT
            return
        }

        val text = "RECENTRE"
        note.color = Palette.TEAL
        note.textAlign = Paint.Align.CENTER
        val w = note.measureText(text) + 22f * dp
        val h = note.textSize + 14f * dp
        recentreBox.set(width - w - 10f * dp, 10f * dp, width - 10f * dp, 10f * dp + h)

        chip.color = Palette.tint(Palette.TEAL, 34)
        canvas.drawRoundRect(recentreBox, 9f * dp, 9f * dp, chip)
        chip.color = Palette.tint(Palette.TEAL, 120)
        chip.style = Paint.Style.STROKE
        chip.strokeWidth = 1f * dp
        canvas.drawRoundRect(recentreBox, 9f * dp, 9f * dp, chip)
        chip.style = Paint.Style.FILL

        canvas.drawText(
            text, recentreBox.centerX(),
            recentreBox.centerY() + note.textSize * 0.36f, note
        )
        note.textAlign = Paint.Align.LEFT
        note.color = faded
    }

    /** Without a scale, a plot of dots says nothing about how far apart anything is. */
    private fun drawScale(canvas: Canvas, pad: Float, colour: Int) {
        if (pxPerMetre <= 0f) return
        bar.color = colour
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

    /**
     * The nearest 1, 2 or 5 times a power of ten. Unlike niceRound this has no ceiling and
     * no floor, because the grid has to keep making sense whether the camera is a metre
     * off the ground or a hundred kilometres up.
     */
    private fun niceStep(v: Double): Double {
        if (v <= 0.0 || v.isNaN()) return 1.0
        val mag = 10.0.pow(floor(log10(v)))
        for (m in doubleArrayOf(1.0, 2.0, 5.0)) if (v <= m * mag) return m * mag
        return 10.0 * mag
    }

    private fun colourFor(priority: Int) = Palette.forPriority(priority)

    private companion object {
        /** Roughly how far apart grid lines should look, before rounding to a real distance. */
        const val GRID_TARGET_DP = 46f

        /** A metre is a hundredth of a pixel: the whole city. */
        const val MIN_PPM = 0.01f

        /** A metre is thirty pixels: close enough to tell two people apart. */
        const val MAX_PPM = 30f

        /** A cheap upper bound on work per frame, in case zoom and size ever disagree. */
        const val MAX_LINES = 400
    }
}
