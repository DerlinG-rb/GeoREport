package com.example.georeport.ui.reports

import com.example.georeport.data.Tracking
import com.example.georeport.domain.ReportState
import java.text.Normalizer
import java.util.Locale

/** Presentation-only filtering of the reports already authorized by ReportService. */
internal data class ReportFilters(
    val query: String = "",
    val state: String = "Todos",
    val category: String = "Todas",
    val newest: Boolean = true
) {
    fun apply(rows: List<Tracking>): List<Tracking> {
        val terms = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matching = rows.filter { row ->
            val report = row.report
            val searchable = normalize("${report.category} ${report.description} ${report.location} ${report.ticket} ${report.id} ${row.state.displayLabel()}")
            (state == "Todos" || row.state.name == state) &&
                (category == "Todas" || report.category == category) && terms.all(searchable::contains)
        }
        return if (newest) matching.sortedByDescending { it.report.createdAt }
        else matching.sortedBy { it.report.createdAt }
    }
}

internal fun ReportState.displayLabel(): String = when (this) {
    ReportState.ENVIADO -> "Enviado"
    ReportState.EN_VALIDACION -> "En revisión"
    ReportState.APROBADO -> "Aprobado"
    ReportState.RECHAZADO -> "Rechazado"
    ReportState.CANALIZADO -> "Canalizado"
    ReportState.RESUELTO -> "Resuelto"
}

private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
