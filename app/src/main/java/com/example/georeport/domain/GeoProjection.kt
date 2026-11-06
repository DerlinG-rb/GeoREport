package com.example.georeport.domain

import kotlin.math.*

object GeoProjection {
    fun validCoordinates(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    data class Bounds(val south: Double, val north: Double, val west: Double, val east: Double)
    fun bounds(lat: Double, lon: Double, radius: Int): Bounds {
        require(validCoordinates(lat, lon) && radius > 0)
        val angular = radius / 6371000.0
        val deltaLat = Math.toDegrees(angular)
        val south = (lat - deltaLat).coerceAtLeast(-90.0)
        val north = (lat + deltaLat).coerceAtMost(90.0)
        if (south == -90.0 || north == 90.0) return Bounds(south, north, -180.0, 180.0)
        val deltaLon = Math.toDegrees(asin((sin(angular) / cos(Math.toRadians(lat))).coerceIn(-1.0, 1.0)))
        fun wrap(value: Double) = (value + 540.0) % 360.0 - 180.0
        return Bounds(south, north, wrap(lon - deltaLon), wrap(lon + deltaLon))
    }
    data class Position(val distance: Double, val bearing: Double)
    fun relative(lat: Double, lon: Double, targetLat: Double, targetLon: Double): Position {
        val a = Math.toRadians(lat); val b = Math.toRadians(targetLat)
        val d = Math.toRadians(targetLon - lon)
        val h = sin((b-a)/2).pow(2) + cos(a)*cos(b)*sin(d/2).pow(2)
        return Position(6371000 * 2 * asin(sqrt(h.coerceIn(0.0,1.0))),
            (Math.toDegrees(atan2(sin(d)*cos(b), cos(a)*sin(b)-sin(a)*cos(b)*cos(d)))+360)%360)
    }
    /** World ENU -> device -> rotated screen. Back camera looks along device -Z. */
    fun project(bearingTrue: Double, declination: Double, matrix: FloatArray, rotation: Int): Pair<Double, Double>? {
        val bearing = Math.toRadians(bearingTrue - declination)
        val east = sin(bearing); val north = cos(bearing)
        val x = matrix[0]*east + matrix[3]*north
        val y = matrix[1]*east + matrix[4]*north
        val z = matrix[2]*east + matrix[5]*north
        if(z >= -0.05) return null
        val (sx, sy) = when(rotation) { 1 -> y to -x; 2 -> -x to -y; 3 -> -y to x; else -> x to y }
        return sx / -z to -sy / -z
    }
}
