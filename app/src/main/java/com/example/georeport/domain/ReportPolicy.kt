package com.example.georeport.domain

enum class Role { CIUDADANO, ADMINISTRADOR }
enum class ReportState { ENVIADO, EN_VALIDACION, APROBADO, RECHAZADO, CANALIZADO, RESUELTO }

object ReportPolicy {
    fun canRead(role: Role, actor: String, owner: String) = role == Role.ADMINISTRADOR || actor == owner
    fun transition(role: Role, from: ReportState, to: ReportState, note: String, receipt: String? = null) {
        check(role == Role.ADMINISTRADOR) { "Operación exclusiva del administrador" }
        val allowed = when (from) {
            ReportState.ENVIADO -> setOf(ReportState.EN_VALIDACION)
            ReportState.EN_VALIDACION -> setOf(ReportState.APROBADO, ReportState.RECHAZADO)
            ReportState.APROBADO -> setOf(ReportState.CANALIZADO)
            ReportState.CANALIZADO -> setOf(ReportState.RESUELTO)
            else -> emptySet()
        }
        require(to in allowed) { "Transición no permitida: $from → $to" }
        require(to != ReportState.RECHAZADO || note.isNotBlank()) { "Indica el motivo del rechazo" }
        require(to != ReportState.CANALIZADO || !receipt.isNullOrBlank()) { "Falta el comprobante de recepción" }
    }
}
