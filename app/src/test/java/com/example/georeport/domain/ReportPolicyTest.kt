package com.example.georeport.domain

import org.junit.Assert.*
import org.junit.Test

class ReportPolicyTest {
    @Test fun citizensCannotChangeAnyState() {
        for(from in ReportState.entries) for(to in ReportState.entries) {
            assertThrows(IllegalStateException::class.java) { ReportPolicy.transition(Role.CIUDADANO,from,to,"motivo","receipt") }
        }
    }
    @Test fun transitionMatrixIsStrict() {
        val allowed = setOf("ENVIADO:EN_VALIDACION","EN_VALIDACION:APROBADO","EN_VALIDACION:RECHAZADO","APROBADO:CANALIZADO","CANALIZADO:RESUELTO")
        for(from in ReportState.entries) for(to in ReportState.entries) {
            if("$from:$to" in allowed) ReportPolicy.transition(Role.ADMINISTRADOR,from,to,"motivo","receipt")
            else assertThrows(IllegalArgumentException::class.java) { ReportPolicy.transition(Role.ADMINISTRADOR,from,to,"motivo","receipt") }
        }
    }
    @Test fun rejectionAndReceiptRequired() {
        assertThrows(IllegalArgumentException::class.java) { ReportPolicy.transition(Role.ADMINISTRADOR,ReportState.EN_VALIDACION,ReportState.RECHAZADO," ") }
        assertThrows(IllegalArgumentException::class.java) { ReportPolicy.transition(Role.ADMINISTRADOR,ReportState.APROBADO,ReportState.CANALIZADO,"") }
    }
    @Test fun citizenCanReadOnlyOwnedReports() {
        assertTrue(ReportPolicy.canRead(Role.CIUDADANO,"a","a"))
        assertFalse(ReportPolicy.canRead(Role.CIUDADANO,"a","b"))
        assertTrue(ReportPolicy.canRead(Role.ADMINISTRADOR,"a","b"))
    }
    @Test fun distanceAndBearing() {
        assertEquals(0.0, GeoProjection.relative(0.0,0.0,0.0,0.0).distance,0.001)
        val east = GeoProjection.relative(0.0,0.0,0.0,0.001)
        assertEquals(111.19,east.distance,0.1); assertEquals(90.0,east.bearing,0.01)
    }
    @Test fun turningDeviceMovesMarkerAndScreenRotationRemapsAxes() {
        // Upright camera facing north: device X east, Y up, -Z north.
        val north = floatArrayOf(1f,0f,0f, 0f,0f,-1f, 0f,1f,0f)
        val middle = GeoProjection.project(0.0,0.0,north,0)!!
        assertEquals(0.0,middle.first,0.001)
        val eastOfView = GeoProjection.project(20.0,0.0,north,0)!!
        assertTrue(eastOfView.first > 0)
        val rotated = GeoProjection.project(20.0,0.0,north,1)!!
        assertEquals(eastOfView.first,rotated.second,0.001)
        assertNull(GeoProjection.project(180.0,0.0,north,0))
        assertEquals(0.0,GeoProjection.project(10.0,10.0,north,0)!!.first,0.001)
        val eastFacing = floatArrayOf(0f,0f,-1f, -1f,0f,0f, 0f,1f,0f)
        assertNull(GeoProjection.project(0.0,0.0,eastFacing,0))
    }
}
