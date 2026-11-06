package com.example.georeport.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class NearbyGeometryTest {
    private fun upright(heading: Double): FloatArray {
        val a=Math.toRadians(heading)
        return floatArrayOf(cos(a).toFloat(),0f,-sin(a).toFloat(), -sin(a).toFloat(),0f,-cos(a).toFloat(), 0f,1f,0f)
    }
    @Test fun headingWrapAndMovementAcrossNorthAreContinuous() {
        val left=GeoProjection.project(0.0,0.0,upright(359.0),0)!!
        val right=GeoProjection.project(0.0,0.0,upright(1.0),0)!!
        assertTrue(left.first>0); assertTrue(right.first<0)
        assertEquals(-left.first,right.first,0.00001)
        assertTrue(abs(left.first-right.first)<0.04)
        assertEquals(0.0,GeoProjection.project(359.0,0.0,upright(359.0),0)!!.first,0.00001)
        assertNull(GeoProjection.project(180.0,0.0,upright(0.0),0))
    }
    @Test fun boundsIncludeDatelineAndPolesAndExcludeDistantCoordinates() {
        val b=GeoProjection.bounds(0.0,179.9999,500)
        assertTrue(b.west>b.east)
        assertEquals(-180.0,GeoProjection.bounds(89.9999,0.0,1000).west,0.0)
        assertEquals(180.0,GeoProjection.bounds(89.9999,0.0,1000).east,0.0)
        assertTrue(GeoProjection.relative(0.0,179.9999,0.0,-179.9999).distance<100)
        assertTrue(GeoProjection.relative(0.0,0.0,0.0,0.01).distance>1000)
        assertFalse(GeoProjection.validCoordinates(Double.NaN,0.0))
        assertFalse(GeoProjection.validCoordinates(0.0,Double.POSITIVE_INFINITY))
        assertFalse(GeoProjection.validCoordinates(91.0,0.0))
    }
    @Test fun declinationRotationAndTiltAffectProjection() {
        assertEquals(0.0,GeoProjection.project(10.0,10.0,upright(0.0),0)!!.first,0.00001)
        val normal=GeoProjection.project(20.0,0.0,upright(0.0),0)!!
        val rotated=GeoProjection.project(20.0,0.0,upright(0.0),1)!!
        assertEquals(normal.first,rotated.second,0.00001)
        val tilt=Math.toRadians(30.0)
        val tilted=floatArrayOf(1f,0f,0f, 0f,sin(tilt).toFloat(),-cos(tilt).toFloat(), 0f,cos(tilt).toFloat(),sin(tilt).toFloat())
        assertTrue(abs(GeoProjection.project(0.0,0.0,tilted,0)!!.second)>0.5)
        assertNull(GeoProjection.project(0.0,0.0,floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f),0))
    }
}
