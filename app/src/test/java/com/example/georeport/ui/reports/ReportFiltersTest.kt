package com.example.georeport.ui.reports

import com.example.georeport.data.IncidentReport
import com.example.georeport.data.Tracking
import com.example.georeport.domain.ReportState
import org.junit.Assert.*
import org.junit.Test

class ReportFiltersTest {
    private fun row(id: String, category: String, time: Long, state: ReportState = ReportState.ENVIADO) = Tracking(
        IncidentReport(123, "owner@example.com", category, "Árbol caído", "Avenida Central", null, null, null, time, id),
        state, null, null, null
    )
    private val rows = listOf(row("first", "Vías", 10), row("second", "Ambiente", 30, ReportState.RESUELTO), row("third", "Vías", 20))

    @Test fun searchIgnoresAccentsCaseAndExtraSpaces() {
        assertEquals(3, ReportFilters(query = "  ARBOL   central ").apply(rows).size)
        assertEquals(2, ReportFilters(query = "vias").apply(rows).size)
    }
    @Test fun filtersCombineAndDoNotMutateInput() {
        assertEquals(listOf("third", "first"), ReportFilters(query = "caido", state = "ENVIADO", category = "Vías").apply(rows).map { it.report.id })
        assertTrue(ReportFilters(state = "RESUELTO", category = "Vías").apply(rows).isEmpty())
        assertEquals(listOf("first", "second", "third"), rows.map { it.report.id })
    }
    @Test fun sortsDatesInBothDirections() {
        assertEquals(listOf(30L, 20L, 10L), ReportFilters().apply(rows).map { it.report.createdAt })
        assertEquals(listOf(10L, 20L, 30L), ReportFilters(newest = false).apply(rows).map { it.report.createdAt })
    }
    @Test fun searchReferenceAndTicketAndEmptyResults() {
        assertEquals("second", ReportFilters(query = "second").apply(rows).single().report.id)
        assertEquals(3, ReportFilters(query = "123").apply(rows).size)
        assertTrue(ReportFilters(query = "inexistente").apply(rows).isEmpty())
        assertTrue(ReportFilters().apply(emptyList()).isEmpty())
        assertEquals(rows.size, ReportFilters().apply(rows).size)
    }
}
