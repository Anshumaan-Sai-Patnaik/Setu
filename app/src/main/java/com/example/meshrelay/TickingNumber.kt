package com.example.meshrelay

import android.os.Handler
import android.os.Looper
import android.widget.TextView

/**
 * A counter that counts, instead of teleporting.
 *
 * The three tiles are the decision layer's only visible output, and a rule the judges
 * cannot see does not exist (Plan.md 17.2). A number that silently swaps from 3 to 15
 * between two glances is a rule nobody watched happen; the same number climbing
 * 3-4-5-6... drags the eye to it and makes the size of the jump felt rather than read.
 *
 * Where this actually earns its place is the eviction beat: the store is squeezed to 10,
 * twelve INFO messages go in, and "carrying" has to be *seen* climbing and then stopping
 * dead at 10 while "evicted" starts moving. On "phones linked" it is a smaller flourish -
 * three phones only ever counts to two - but it is the same helper and it costs nothing.
 *
 * The whole roll is capped at about a third of a second. This is punctuation between real
 * events and it must never be the reason someone is looking at a stale number: a big jump
 * strides several at a time rather than taking longer.
 */
class TickingNumber(private val view: TextView) {

    private val handler = Handler(Looper.getMainLooper())

    /** What is on screen right now. Null until the first paint. */
    private var showing: Int? = null
    private var target = 0
    private var stride = 1
    private var stepMs = 30L

    private val tick = object : Runnable {
        override fun run() {
            val at = showing ?: return
            val next =
                if (at < target) minOf(target, at + stride)
                else maxOf(target, at - stride)
            showing = next
            view.text = next.toString()
            if (next != target) handler.postDelayed(this, stepMs)
        }
    }

    fun set(value: Int, colour: Int) {
        view.setTextColor(colour)

        val from = showing
        if (from == value) return

        // First paint is not a change. Counting up from zero at launch would read as the
        // app still starting, on a screen whose whole claim is that it is already working.
        if (from == null) {
            showing = value
            view.text = value.toString()
            return
        }

        target = value
        handler.removeCallbacks(tick)

        // Budget the whole roll, then decide the step from it - never the other way round.
        val distance = kotlin.math.abs(value - from)
        val steps = minOf(distance, MAX_STEPS)
        stride = (distance + steps - 1) / steps          // ceiling, so it lands exactly
        stepMs = (ROLL_MS / steps).coerceIn(24L, 90L)

        // One kick at the start. The roll carries the rest of the attention, so the
        // scale settles back while the digits are still moving.
        view.animate().cancel()
        view.scaleX = 1.18f
        view.scaleY = 1.18f
        view.animate().scaleX(1f).scaleY(1f).setDuration(260).start()

        handler.post(tick)
    }

    /** Called when the view goes away, so a half-finished roll cannot outlive it. */
    fun stop() = handler.removeCallbacks(tick)

    private companion object {
        const val ROLL_MS = 340L
        const val MAX_STEPS = 14
    }
}
