package com.example.meshrelay

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * The colours, once, for the code that cannot use `@color/`.
 *
 * The RecyclerView rows, the map and the state banner all colour themselves at
 * runtime from a priority or a connection state, so they cannot name a resource in
 * a layout file. Without this they each kept their own hex strings and drifted:
 * "medical red" was three slightly different reds depending on where you looked.
 *
 * Keep these in step with `res/values/colors.xml`. It is duplication, and the
 * alternative - reading resources from a plain object with no Context - is worse.
 *
 * MeshRules.kt and Positioning.kt must never import this. They are hand-ported to
 * JavaScript for the simulator and stay free of anything Android.
 */
object Palette {

    // Ground and surfaces
    const val GROUND = 0xFF0B1220.toInt()
    const val SURFACE = 0xFF111C2E.toInt()
    const val SURFACE_RAISED = 0xFF16233A.toInt()
    const val BORDER = 0xFF1E2D45.toInt()
    const val BORDER_STRONG = 0xFF2A3D5C.toInt()

    // Type
    const val TEXT = 0xFFE6EDF7.toInt()
    const val TEXT_SECONDARY = 0xFF8FA3BF.toInt()
    const val TEXT_DIM = 0xFF5B6E8C.toInt()

    // Connectivity - the mesh's own voice. Never used for a report, so teal on
    // screen always means "the network is doing something", never "something is wrong".
    const val TEAL = 0xFF14B8A6.toInt()
    const val TEAL_DEEP = 0xFF0D9488.toInt()

    // Status - spent sparingly, means exactly one thing: confirmed.
    const val GREEN = 0xFF22C55E.toInt()

    // Alerts - attention, not emergency.
    const val ORANGE = 0xFFF97316.toInt()
    const val AMBER = 0xFFFBBF24.toInt()

    // Danger - reserved. If everything urgent is red, nothing is.
    const val RED = 0xFFEF4444.toInt()

    const val SLATE = 0xFF64748B.toInt()

    /**
     * Priority to colour. Bands, not per-type, so a type added later inherits its
     * colour from what it costs the crowd rather than from someone's taste.
     */
    fun forPriority(priority: Int): Int = when {
        priority >= 9 -> RED       // hurt or ill, missing, official orders
        priority >= 8 -> ORANGE    // fire, threat
        priority >= 5 -> AMBER     // dangerous crowding
        else -> SLATE              // questions, chatter
    }

    /** The same colour at low opacity, for a fill sitting under its own text. */
    fun tint(colour: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))

    /** A filled pill: tag chips, badges, the receipt strip. */
    fun pill(fill: Int, radiusPx: Float, stroke: Int? = null, strokePx: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fill)
            if (stroke != null && strokePx > 0) setStroke(strokePx, stroke)
        }

    /** A dot. Used for the mesh-state light and for nodes on the map. */
    fun dot(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }
}
