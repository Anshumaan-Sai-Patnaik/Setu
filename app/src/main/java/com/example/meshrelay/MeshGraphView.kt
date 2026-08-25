package com.example.meshrelay

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * THE LINK GRAPH. This phone in the middle, everything it knows about the mesh around it.
 *
 * WHAT IS REAL HERE, AND WHAT IS NOT - this distinction is the whole reason the view is
 * allowed to exist, and it should be said out loud on stage before anyone asks:
 *
 *   REAL: which phones are linked right now; which phones this one has only *heard of*;
 *         which pairs of phones have carried a message between them; and every travelling
 *         dot, each of which is an actual payload leaving or arriving on this radio.
 *
 *   NOT REAL: where anything is on screen. This is a diagram of who can reach whom, not a
 *         map of who is standing where. The MAP tab is the one that means geography. A
 *         line on the footer says so, permanently, because a judge who assumes otherwise
 *         and works it out for themselves has caught us rather than been told.
 *
 * The second-hop knowledge is the interesting part and it is free. Every message carries
 * its `path` - the phones it travelled through - so a path of A > B > C is direct evidence
 * that A and B were linked, and that B and C were. A phone that has never met C can draw C,
 * correctly, from a message that came through it. That is what makes this more than a
 * prettier version of the banner radar: on three phones with one link cut, the phone at the
 * far end shows up here as a node it can see but cannot reach.
 *
 * Layout is a two-ring relaxation rather than a free force simulation: directly linked
 * phones sit on the inner ring, phones only heard of on the outer one, and the nodes slide
 * *around* their ring to get out of each other's way. A free simulation looks better in a
 * screenshot and can collapse into a knot or fling a node off the edge; this one cannot do
 * either, and the demo is tomorrow.
 */
class MeshGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** One phone, as the Activity knows it. */
    data class Peer(
        val id: String,
        /** Device model where we know it - we only know that for phones we have linked to. */
        val label: String,
        /** Linked right now, as opposed to merely known about. */
        val linked: Boolean
    )

    /** Tapped node id, or null for a tap on empty space. */
    var onNodeTap: ((String?) -> Unit)? = null

    // -----------------------------------------------------------------------
    // What is on screen
    // -----------------------------------------------------------------------

    private class Body(val id: String) {
        var label = ""
        var linked = false
        var angle = 0f          // radians, position around its ring
        var spin = 0f           // angular velocity
        var radius = 0f         // current distance from centre
        var bornAt = 0L
        var goneAt = 0L         // 0 while it is still known about
        var placed = false
        var phase = 0f          // its own drift offset, so nothing moves in lockstep
        var x = 0f              // last drawn position, kept for hit testing
        var y = 0f
    }

    /** A real payload crossing a real link. Started by the Activity, never by a timer. */
    private class Spark(
        val from: String,
        val to: String,
        val at: Long,
        val colour: Int
    )

    private var youId = ""
    private val bodies = LinkedHashMap<String, Body>()

    /** Unordered pairs, "a|b" with the lower id first. Observed, not assumed. */
    private val edges = mutableSetOf<String>()

    private val sparks = mutableListOf<Spark>()

    private val dp = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 9.5f * dp
        isFakeBoldText = true
    }
    private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * dp
    }

    private var lastFrame = 0L

    // -----------------------------------------------------------------------
    // Input from the Activity
    // -----------------------------------------------------------------------

    /**
     * @param peers everything this phone knows about, linked or merely heard of
     * @param observed pairs of node ids known to have carried a message between them
     */
    fun show(you: String, peers: List<Peer>, observed: Set<String>) {
        youId = you
        val now = SystemClock.uptimeMillis()

        for (p in peers) {
            val b = bodies.getOrPut(p.id) {
                Body(p.id).also {
                    it.bornAt = now
                    // Seeded from the id, so a phone lands in the same place every time
                    // rather than jumping somewhere new whenever the list is rebuilt.
                    val h = p.id.hashCode()
                    it.angle = ((h and 0xFFFF) / 65535f) * (2f * PI.toFloat())
                    it.phase = ((h shr 16 and 0xFF) / 255f) * (2f * PI.toFloat())
                }
            }
            // A node that dropped and came back is new again: it should arrive, not
            // silently resume at full size as though it had never left.
            if (b.goneAt != 0L) {
                b.goneAt = 0L
                b.bornAt = now
                b.placed = false
            }
            b.label = p.label
            b.linked = p.linked
        }

        // Anything no longer known starts fading. Removed only once it has finished,
        // so a link dropping is something you watch happen.
        val present = peers.map { it.id }.toSet()
        for (b in bodies.values) if (b.id !in present && b.goneAt == 0L) b.goneAt = now
        bodies.entries.removeAll { (_, b) -> b.goneAt != 0L && now - b.goneAt > FADE_MS }

        edges.clear()
        edges += observed

        invalidate()
    }

    /**
     * A message just crossed this link. Called from the send and receive paths, so a dot
     * on screen means a payload on the radio - there is no traffic animation that runs
     * when the mesh is quiet.
     */
    fun spark(from: String, to: String, colour: Int) {
        if (from.isEmpty() || to.isEmpty()) return
        sparks += Spark(from, to, SystemClock.uptimeMillis(), colour)
        // A burst of forwards must not turn into a hundred dots to draw per frame.
        if (sparks.size > 40) sparks.subList(0, sparks.size - 40).clear()
        invalidate()
    }

    fun clear() {
        bodies.clear()
        edges.clear()
        sparks.clear()
        invalidate()
    }

    // -----------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------

    private fun ringFor(b: Body, span: Float) = if (b.linked) span * 0.27f else span * 0.44f

    /**
     * One relaxation step. Nodes only ever move around their own ring and towards their
     * own radius, which is what makes this incapable of tangling.
     */
    private fun relax(dt: Float, span: Float) {
        val live = bodies.values.toList()
        if (live.isEmpty()) return

        for (b in live) {
            val want = ringFor(b, span)
            if (!b.placed) {
                // Arrive from the centre outwards: a new phone appears to come *from*
                // this one's radio rather than materialising at the edge.
                b.radius = want * 0.35f
                b.placed = true
            }
            b.radius += (want - b.radius) * min(1f, dt * 4.5f)
        }

        // Spread: anything sitting on top of anything else pushes it around the ring.
        for (i in live.indices) {
            val a = live[i]
            for (j in i + 1 until live.size) {
                val c = live[j]
                var gap = a.angle - c.angle
                while (gap > PI) gap -= 2f * PI.toFloat()
                while (gap < -PI) gap += 2f * PI.toFloat()

                // Only crowded if they are also at a similar distance out. Two phones on
                // different rings may share an angle quite happily.
                val radialGap = abs(a.radius - c.radius)
                if (radialGap > span * 0.12f) continue

                val crowding = MIN_GAP - abs(gap)
                if (crowding <= 0f) continue
                val push = crowding * SPREAD * dt * (if (gap >= 0f) 1f else -1f)
                a.spin += push
                c.spin -= push
            }
        }

        // Observed links pull their two ends towards each other, so phones that actually
        // talk end up drawn near each other and the picture means something.
        for (e in edges) {
            val ends = e.split("|")
            if (ends.size != 2) continue
            val a = bodies[ends[0]] ?: continue
            val c = bodies[ends[1]] ?: continue
            var gap = a.angle - c.angle
            while (gap > PI) gap -= 2f * PI.toFloat()
            while (gap < -PI) gap += 2f * PI.toFloat()
            val pull = gap * SPRING * dt
            a.spin -= pull
            c.spin += pull
        }

        for (b in live) {
            b.spin *= DAMPING
            b.spin = b.spin.coerceIn(-2.2f, 2.2f)
            b.angle += b.spin * dt
        }
    }

    // -----------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.05f)
        lastFrame = now

        val footer = 20f * dp
        val cx = width / 2f
        val cy = (height - footer) / 2f
        val span = min(width.toFloat(), height - footer)

        drawBackdrop(canvas, cx, cy, span)
        relax(dt, span)

        // Where everything is this frame. Computed once: the edges, the sparks and the
        // hit test all have to agree, and recomputing a drifting position three times
        // would let them disagree by a pixel or two.
        val at = HashMap<String, FloatArray>(bodies.size + 1)
        at[youId] = floatArrayOf(cx, cy)

        for (b in bodies.values) {
            // The drift. Small, slow, and out of phase per node: the picture should look
            // like something living rather than a diagram someone printed.
            val wobbleA = 0.045f * sin((now / 2600.0) + b.phase).toFloat()
            val wobbleR = 1f + 0.035f * sin((now / 3100.0) + b.phase * 1.7f).toFloat()
            val a = b.angle + wobbleA
            val r = b.radius * wobbleR
            b.x = cx + r * cos(a)
            b.y = cy + r * sin(a)
            at[b.id] = floatArrayOf(b.x, b.y)
        }

        drawEdges(canvas, now, at)
        drawSparks(canvas, now, at)
        drawPeers(canvas, now)
        drawYou(canvas, cx, cy, now)
        drawFooter(canvas, footer)

        if (isShown) postInvalidateOnAnimation()
    }

    /** A faint ring for each hop distance, so "one hop away" is a place, not a guess. */
    private fun drawBackdrop(canvas: Canvas, cx: Float, cy: Float, span: Float) {
        ring.strokeWidth = 1f * dp
        ring.pathEffect = DASH
        ring.color = Palette.tint(Palette.TEAL, 26)
        canvas.drawCircle(cx, cy, span * 0.27f, ring)
        ring.color = Palette.tint(Palette.SLATE, 30)
        canvas.drawCircle(cx, cy, span * 0.44f, ring)
        ring.pathEffect = null
    }

    private fun drawEdges(canvas: Canvas, now: Long, at: Map<String, FloatArray>) {
        // Direct links first: this phone to everything it is actually connected to.
        for (b in bodies.values) {
            if (!b.linked) continue
            val life = life(b, now)
            val you = at[youId] ?: continue

            // A live link breathes. Only the live ones: a pulse on screen means a radio
            // link that exists at this instant, so the ones that stop pulsing are the
            // ones a judge has just walked out of range with.
            val breath = 0.5f + 0.5f * sin((now / 1150.0) + b.phase).toFloat()
            line.pathEffect = null
            line.strokeWidth = (1.6f + 0.5f * breath) * dp
            line.color = Palette.tint(Palette.TEAL, ((90 + 70 * breath) * life).toInt())
            canvas.drawLine(you[0], you[1], b.x, b.y, line)
        }

        // Links between two other phones, learned from the paths messages arrived by.
        // Dashed and dim: this phone did not see these happen, it inferred them, and the
        // drawing should not claim more than that.
        line.pathEffect = DASH
        line.strokeWidth = 1.2f * dp
        for (e in edges) {
            val ends = e.split("|")
            if (ends.size != 2) continue
            if (youId in ends) continue                       // already drawn, solid
            val a = at[ends[0]] ?: continue
            val c = at[ends[1]] ?: continue
            val fade = min(life(bodies[ends[0]], now), life(bodies[ends[1]], now))
            line.color = Palette.tint(Palette.SLATE, (110 * fade).toInt())
            canvas.drawLine(a[0], a[1], c[0], c[1], line)
        }
        line.pathEffect = null
    }

    /** One travelling dot per payload. Every one of these was a real send or receive. */
    private fun drawSparks(canvas: Canvas, now: Long, at: Map<String, FloatArray>) {
        sparks.removeAll { now - it.at > SPARK_MS }
        for (s in sparks) {
            val a = at[s.from] ?: continue
            val b = at[s.to] ?: continue
            val t = ((now - s.at) / SPARK_MS.toFloat()).coerceIn(0f, 1f)
            val x = a[0] + (b[0] - a[0]) * t
            val y = a[1] + (b[1] - a[1]) * t
            // Brightest in the middle of the crossing, so it reads as a thing in transit
            // rather than something being switched on and off at the two ends.
            val strength = sin(t * PI).toFloat()
            fill.color = Palette.tint(s.colour, (255 * strength).toInt())
            canvas.drawCircle(x, y, 3.4f * dp, fill)
            fill.color = Palette.tint(s.colour, (70 * strength).toInt())
            canvas.drawCircle(x, y, 7f * dp, fill)
        }
    }

    private fun drawPeers(canvas: Canvas, now: Long) {
        for (b in bodies.values) {
            val life = life(b, now)
            val grown = grown(b, now)
            val colour = if (b.linked) Palette.TEAL else Palette.SLATE
            val r = (if (b.linked) 8.5f else 6.5f) * dp * grown

            if (b.linked) {
                // Solid: this phone can hand a message straight to it, right now.
                fill.color = Palette.tint(colour, (46 * life).toInt())
                canvas.drawCircle(b.x, b.y, r * 2.1f, fill)
                fill.color = Palette.tint(colour, (255 * life).toInt())
                canvas.drawCircle(b.x, b.y, r, fill)
            } else {
                // Hollow: known to exist, not reachable from here. The difference between
                // the two shapes is the whole point of the outer ring.
                ring.strokeWidth = 1.8f * dp
                ring.color = Palette.tint(colour, (200 * life).toInt())
                canvas.drawCircle(b.x, b.y, r, ring)
                fill.color = Palette.tint(Palette.GROUND, (200 * life).toInt())
                canvas.drawCircle(b.x, b.y, r - 1.5f * dp, fill)
            }

            label.color = Palette.tint(
                if (b.linked) Palette.TEXT else Palette.TEXT_DIM, (255 * life).toInt()
            )
            canvas.drawText(short(b), b.x, b.y + r + 12f * dp, label)
        }
    }

    private fun drawYou(canvas: Canvas, cx: Float, cy: Float, now: Long) {
        val breath = 0.5f + 0.5f * sin(now / 1000.0).toFloat()
        fill.color = Palette.tint(Palette.TEAL, (30 + 22 * breath).toInt())
        canvas.drawCircle(cx, cy, 20f * dp + 2f * dp * breath, fill)
        ring.strokeWidth = 2f * dp
        ring.color = Palette.TEAL
        canvas.drawCircle(cx, cy, 13f * dp, ring)
        fill.color = Palette.TEAL
        canvas.drawCircle(cx, cy, 6f * dp, fill)

        label.color = Palette.TEAL
        canvas.drawText("THIS PHONE", cx, cy + 30f * dp, label)
    }

    private fun drawFooter(canvas: Canvas, footer: Float) {
        note.color = Palette.TEXT_DIM
        val text = if (bodies.isEmpty())
            "No other phones yet - this one is the whole network so far"
        else
            "Tap a node for its messages · positions are not geographic"
        canvas.drawText(text, width / 2f, height - footer / 3f, note)
    }

    /** 0 while fading out, 1 when fully present. */
    private fun life(b: Body?, now: Long): Float {
        if (b == null) return 0f
        if (b.goneAt == 0L) return 1f
        return (1f - (now - b.goneAt) / FADE_MS.toFloat()).coerceIn(0f, 1f)
    }

    /** Arrival: overshoot a touch and settle, so a new phone has some weight to it. */
    private fun grown(b: Body, now: Long): Float {
        val t = ((now - b.bornAt) / 420f).coerceIn(0f, 1f)
        return if (t < 0.7f) (t / 0.7f) * 1.15f else 1.15f - 0.15f * ((t - 0.7f) / 0.3f)
    }

    private fun short(b: Body): String =
        if (b.label.isNotEmpty()) b.label.take(12) else b.id.take(6)

    // -----------------------------------------------------------------------
    // Touch
    // -----------------------------------------------------------------------

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                // A drag is not a tap. The nodes drift, so a finger that slid across the
                // screen should not open whichever node happened to be under it.
                if (hypot(event.x - downX, event.y - downY) > 16f * dp) return true
                onNodeTap?.invoke(nodeAt(event.x, event.y))
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Generous: a 6dp dot is not a touch target, and on stage a missed tap looks like a
     * frozen app. Nearest wins, so overlapping nodes still resolve to one of them.
     */
    private fun nodeAt(x: Float, y: Float): String? {
        var best: String? = null
        var bestDist = 30f * dp
        for (b in bodies.values) {
            if (b.goneAt != 0L) continue
            val d = hypot(x - b.x, y - b.y)
            if (d < bestDist) {
                bestDist = d
                best = b.id
            }
        }
        return best
    }

    private companion object {
        const val FADE_MS = 700L
        const val SPARK_MS = 780L
        const val MIN_GAP = 0.85f       // radians two nodes try to keep between them
        const val SPREAD = 7.0f
        const val SPRING = 1.3f
        const val DAMPING = 0.86f
        val DASH = DashPathEffect(floatArrayOf(6f, 7f), 0f)
    }
}
