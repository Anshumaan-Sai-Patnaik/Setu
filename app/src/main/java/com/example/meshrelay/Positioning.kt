package com.example.meshrelay

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a report came from, as coordinates.
 *
 * Chosen over a list of named venue zones after arguing it out on 21 Aug. Named zones
 * fail for three reasons: not every place has a "Gate 4"; a stadium has either too few
 * zones to be useful or too many to scroll through; and above all, **a person in trouble
 * often does not know where they are** - which is frequently why they are in trouble.
 * Coordinates need no venue setup and no local knowledge.
 *
 * GPS needs no internet. The phone only listens to satellites - no SIM, no data. What it
 * loses offline is the assisted first fix, so a cold start can take minutes instead of
 * seconds, and roofs block it. Hence the rule in MainActivity: a message never waits for
 * a fix.
 *
 * Attaching a position is OPT-IN and off by default. This network copies messages onto
 * strangers' phones and holds them for hours, which is the wrong place for anyone's exact
 * whereabouts unless they chose it.
 *
 * No Android imports here - this file ports to the simulator with the rest of the rules.
 */
data class Position(val lat: Double, val lon: Double) {

    /** Five decimals, about a metre. Locale-independent on purpose. */
    fun encode(): String = trim(lat) + "," + trim(lon)

    companion object {
        private fun trim(v: Double) = ((v * 100_000).roundToLong() / 100_000.0).toString()

        fun decode(s: String): Position? {
            val p = s.split(",")
            if (p.size != 2) return null
            val lat = p[0].toDoubleOrNull() ?: return null
            val lon = p[1].toDoubleOrNull() ?: return null
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
            return Position(lat, lon)
        }

        /** Straight-line distance in metres. */
        fun metresBetween(a: Position, b: Position): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val la1 = Math.toRadians(a.lat)
            val la2 = Math.toRadians(b.lat)
            val h = sin(dLat / 2).pow(2) + cos(la1) * cos(la2) * sin(dLon / 2).pow(2)
            return 2 * r * asin(min(1.0, sqrt(h)))
        }
    }
}

/** Human-readable distance, for someone reading a report under pressure. */
fun describeDistance(metres: Double): String = when {
    metres < 1000 -> metres.roundToLong().toString() + " m away"
    else -> ((metres / 100).roundToLong() / 10.0).toString() + " km away"
}
