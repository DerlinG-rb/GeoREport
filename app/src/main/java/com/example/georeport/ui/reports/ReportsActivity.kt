package com.example.georeport.ui.reports

import android.os.Bundle
import androidx.core.content.ContextCompat
import com.example.georeport.R
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.georeport.data.*
import com.example.georeport.domain.*
import com.example.georeport.ui.common.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import java.util.Date
import java.text.DateFormat

class ReportsActivity : AppCompatActivity() {
    private val service by lazy { ReportService(this) }
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var body: LinearLayout
    private var selectedId: String? = null
    private var stateFilter = "Todos"
    private var categoryFilter = "Todas"
    private var newest = true
    private var job: Job? = null
    private var busy = false
    private val admin get() = intent.getBooleanExtra("admin", false)
    private val requiredRole get() = if(admin) Role.ADMINISTRADOR else Role.CIUDADANO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedId = savedInstanceState?.getString("report") ?: intent.getStringExtra("report")
        stateFilter = savedInstanceState?.getString("state") ?: "Todos"
        categoryFilter = savedInstanceState?.getString("category") ?: "Todas"
        newest = savedInstanceState?.getBoolean("newest", true) ?: true
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)); setBackgroundColor(ContextCompat.getColor(this@ReportsActivity, R.color.bg_light)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(ContextCompat.getColor(this@ReportsActivity, R.color.bg_light)); addView(content) }
        setContentView(scroll); fitScreenContent(scroll)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("report", selectedId); outState.putString("state", stateFilter)
        outState.putString("category", categoryFilter); outState.putBoolean("newest", newest)
    }
    override fun onResume() { super.onResume(); if(SessionNavigation.require(this, requiredRole)) refresh() }
    private fun text(parent: LinearLayout, value: String, size: Float = 16f) = TextView(this).apply {
        text = value; applyGeoText(size); parent.addView(this)
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit) = MaterialButton(this).apply {
        text = title; applyGeoButton(); parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }); setOnClickListener { if(!busy) action() }
    }
    private fun scaffold() {
        content.removeAllViews()
        text(content, if(admin) "Administración de reportes" else "Mi historial", 24f)
        button(content, if(selectedId == null) "Volver" else "Volver al listado") {
            if(selectedId == null) finish() else { selectedId = null; refresh() }
        }
        button(content, "Ver incidencias cercanas en RA") { startActivity(android.content.Intent(this, com.example.georeport.ui.ar.NearbyActivity::class.java)) }
        button(content, "Cerrar sesión") { SessionNavigation.logout(this) }
        button(content, "Actualizar / Reintentar") { refresh() }
        status = text(content, "Cargando…")
        status.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
    }
    private fun refresh() {
        if(busy || !SessionNavigation.require(this, requiredRole)) return
        job?.cancel(); scaffold()
        job = lifecycleScope.launch {
            try {
                val id = selectedId
                if(id == null) {
                    val rows = withContext(Dispatchers.IO) { service.list() }
                    renderList(rows)
                } else {
                    val result = withContext(Dispatchers.IO) { Triple(service.detail(id), service.events(id), if(admin) service.attempts(id) else emptyList()) }
                    renderDetail(result.first, result.second, result.third)
                }
                status.text = "Información actualizada"
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { status.text = "${e.message}. Pulsa Actualizar / Reintentar." }
        }
    }
    private fun select(label: String, values: List<String>, current: String, change: (String) -> Unit) {
        text(body, label)
        val spinner = Spinner(this).apply {
            contentDescription = label
            minimumHeight = dp(48)
            adapter = ArrayAdapter(this@ReportsActivity, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(values.indexOf(current).coerceAtLeast(0)); body.addView(this)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if(values[position] != current) change(values[position])
            }
        }
    }
    private fun renderList(rows: List<Tracking>) {
        select("Filtrar por estado", listOf("Todos") + ReportState.entries.map { it.name }, stateFilter) { stateFilter = it; refresh() }
        if(admin) select("Filtrar por categoría", listOf("Todas") + rows.map { it.report.category }.distinct().sorted(), categoryFilter) { categoryFilter = it; refresh() }
        button(body, if(newest) "Fecha: más recientes primero" else "Fecha: más antiguos primero") { newest = !newest; refresh() }
        val filtered = rows.filter { (stateFilter == "Todos" || it.state.name == stateFilter) && (!admin || categoryFilter == "Todas" || it.report.category == categoryFilter) }
            .sortedBy { if(newest) -it.report.createdAt else it.report.createdAt }
        if(filtered.isEmpty()) text(body, "No hay reportes para estos filtros.")
        filtered.forEach { row ->
            photo(row.report, 130)
            button(body, "${row.report.category}\n${date(row.report.createdAt)} · ${row.state}\n${row.report.location}") { selectedId = row.report.id; refresh() }
        }
    }
    private suspend fun renderDetail(row: Tracking, events: List<ReportEvent>, attempts: List<String>) {
        val report = row.report
        text(body, "${report.category} · ${row.state}", 20f)
        photo(report, 340)
        text(body, "Fotografía original conservada; vista previa ajustada.")
        text(body, "${report.description}\nUbicación del incidente: ${report.location}\nCoordenadas: ${report.latitude ?: "sin GPS"}, ${report.longitude ?: "sin GPS"}\n${date(report.createdAt)}\nReferencia: ${report.id}")
        text(body, if(report.locationConfirmed) "Ubicación seleccionada o confirmada por el ciudadano." else "Ubicación aproximada: GPS del teléfono o registro histórico sin confirmación del punto del incidente.")
        if(admin) {
            text(body, "Remitente: ${report.userEmail}")
            val visibility = withContext(Dispatchers.IO) { service.visibility(report.id) }
            text(body, "Consulta compartida en RA: ${if(visibility.enabled) "autorizada" else "desactivada"}")
            if(row.state in listOf(ReportState.APROBADO, ReportState.CANALIZADO)) {
                button(body, if(visibility.enabled) "Editar descripción pública" else "Autorizar consulta compartida en RA") { share(report.id, visibility.description) }
            }
            if(visibility.enabled) button(body, "Retirar autorización de RA") { perform({ service.setSharedVisibility(report.id, false) }) }
        }
        row.institution?.let { text(body, "Institución: $it\nComprobante: ${row.receipt}\nRecepción: ${row.receivedAt}") }
        if(admin) when(row.state) {
            ReportState.ENVIADO -> button(body, "Iniciar revisión") { update(report.id, ReportState.EN_VALIDACION) }
            ReportState.EN_VALIDACION -> {
                button(body, "Aprobar reporte") { update(report.id, ReportState.APROBADO) }
                button(body, "Rechazar con motivo") { reject(report.id) }
            }
            ReportState.APROBADO -> button(body, "Enviar a institución simulada") { credentials(row) }
            ReportState.CANALIZADO -> button(body, "Marcar como resuelto") { update(report.id, ReportState.RESUELTO) }
            else -> Unit
        }
        text(body, "Seguimiento", 20f)
        events.forEach { text(body, "${it.from} → ${it.to}\n${date(it.at)} · ${if(admin) it.actor else if(it.from == "—") "Tú" else "Administración"}\n${it.note}") }
        if(attempts.isNotEmpty()) { text(body, "Diagnóstico de envíos", 20f); attempts.forEach { text(body, it) } }
    }
    private fun share(id: String, current: String) {
        val input = EditText(this).apply {
            hint = "Descripción pública sin datos personales"; contentDescription = hint
            minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(1000)); setText(current)
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Autorizar consulta compartida")
            .setMessage("Redacta un resumen público sin nombres, correos, teléfonos ni otros datos personales. Se compartirán categoría, resumen, fecha, estado y coordenadas con las cuentas de esta instalación. La descripción privada y las fotos no se comparten.")
            .setView(input).setNegativeButton("Cancelar", null).setPositiveButton("Autorizar", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            if(input.text.isNullOrBlank()) input.error = "Escribe el resumen público"
            else { perform({ service.setSharedVisibility(id, true, input.text.toString()) }); dialog.dismiss() }
        } }
        dialog.show()
    }
    private fun photo(report: IncidentReport, height: Int) {
        val image = ImageView(this).apply {
            contentDescription = "Fotografía de ${report.category}"; scaleType = ImageView.ScaleType.FIT_CENTER
            body.addView(this, LinearLayout.LayoutParams(-1, (height * resources.displayMetrics.density).toInt()))
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { val storage = PhotoStorage(this@ReportsActivity); storage.resolve(report.photoPath)?.let { storage.preview(it) } }.getOrNull() }
            if(bitmap != null) image.setImageBitmap(bitmap) else image.contentDescription = "Fotografía no disponible"
        }
    }
    private fun perform(action: () -> Unit, done: () -> Unit = { refresh() }) {
        if(busy) return
        busy = true; status.text = "Procesando…"
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { action() }; busy = false; done() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { busy = false; status.text = "${e.message}. Puedes reintentar la acción." }
            finally { busy = false }
        }
    }
    private fun update(id: String, next: ReportState, note: String = "") = perform({ service.transition(id, next, note) })
    private fun reject(id: String) {
        val input = EditText(this).apply { hint = "Motivo obligatorio"; minLines = 2; contentDescription = hint }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Confirmar rechazo").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Rechazar", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            if(input.text.isNullOrBlank()) input.error = "Indica el motivo" else { update(id, ReportState.RECHAZADO, input.text.toString()); dialog.dismiss() }
        } }; dialog.show()
    }
    private fun credentials(row: Tracking) {
        val input = EditText(this).apply { hint = "Clave de integración de demostración"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        MaterialAlertDialogBuilder(this).setTitle("API simulada").setMessage("La clave solo se utiliza en esta operación.")
            .setView(input).setNegativeButton("Cancelar", null).setPositiveButton("Consultar instituciones") { _, _ ->
                val client = InstitutionClient(this, input.text.toString()); input.text.clear()
                var institutions = emptyList<Institution>()
                perform({ institutions = service.institutions(client).filter { row.report.category in it.categories } }) {
                    status.text = "Selecciona una institución compatible"
                    if(institutions.isEmpty()) { status.text = "No hay instituciones compatibles"; return@perform }
                    MaterialAlertDialogBuilder(this).setTitle("Instituciones de DEMOSTRACIÓN")
                        .setItems(institutions.map { it.name }.toTypedArray()) { _, which ->
                            val institution = institutions[which]
                            MaterialAlertDialogBuilder(this).setTitle("Confirmar envío")
                                .setMessage("Se compartirá descripción, coordenadas y una copia de la fotografía con ${institution.name}. No se enviarán nombre ni contacto.")
                                .setNegativeButton("Cancelar", null).setPositiveButton("Enviar") { _, _ -> perform({ service.send(row.report.id, institution, client) }) }.show()
                        }.setNegativeButton("Cancelar", null).show()
                }
            }.show()
    }
    private fun date(value: Long) = DateFormat.getDateTimeInstance().format(Date(value))
}
