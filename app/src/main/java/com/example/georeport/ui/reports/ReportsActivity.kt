package com.example.georeport.ui.reports

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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
    private var query = ""
    private var cachedRows = emptyList<Tracking>()
    private lateinit var results: LinearLayout
    private lateinit var resultCount: TextView
    private var visibleCount = 20
    private var searchJob: Job? = null
    private val photoJobs = mutableListOf<Job>()
    private var job: Job? = null
    private var busy = false
    private val admin get() = intent.getBooleanExtra("admin", false)
    private val requiredRole get() = if(admin) Role.ADMINISTRADOR else Role.CIUDADANO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedId = savedInstanceState?.getString("report") ?: intent.getStringExtra("report")
        stateFilter = savedInstanceState?.getString("state") ?: "Todos"
        categoryFilter = savedInstanceState?.getString("category") ?: "Todas"
        query = savedInstanceState?.getString("query") ?: ""
        newest = savedInstanceState?.getBoolean("newest", true) ?: true
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(16)); setBackgroundColor(ContextCompat.getColor(this@ReportsActivity, R.color.bg_light)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(ContextCompat.getColor(this@ReportsActivity, R.color.bg_light)); addView(content) }
        setContentView(scroll); fitScreenContent(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) return
                if (selectedId == null) finish() else { selectedId = null; refresh() }
            }
        })
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("report", selectedId); outState.putString("state", stateFilter)
        outState.putString("category", categoryFilter); outState.putBoolean("newest", newest); outState.putString("query", query)
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
        val toolbar = MaterialToolbar(this).apply {
            title = if (admin) "Reportes" else "Mi historial"
            subtitle = if (selectedId == null) "Consulta y seguimiento" else "Detalle del reporte"
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "Volver"
            setNavigationOnClickListener {
                if (busy) return@setNavigationOnClickListener
                if (selectedId == null) finish() else { selectedId = null; refresh() }
            }
            menu.add("Actualizar / Reintentar").setIcon(R.drawable.ic_refresh)
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add("Incidencias cercanas")
            menu.add("Cerrar sesión")
            setOnMenuItemClickListener { item ->
                if (!busy) when (item.title.toString()) {
                    "Actualizar / Reintentar" -> refresh()
                    "Incidencias cercanas" -> startActivity(android.content.Intent(this@ReportsActivity, com.example.georeport.ui.ar.NearbyActivity::class.java))
                    "Cerrar sesión" -> MaterialAlertDialogBuilder(this@ReportsActivity)
                        .setTitle("¿Cerrar sesión?").setMessage("Tus reportes guardados se conservarán.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Cerrar sesión") { _, _ -> SessionNavigation.logout(this@ReportsActivity) }.show()
                }
                true
            }
        }
        content.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        status = text(content, "Cargando…")
        status.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
    }
    private fun refresh() {
        if(busy || !SessionNavigation.require(this, requiredRole)) return
        job?.cancel(); searchJob?.cancel()
        photoJobs.forEach { it.cancel() }; photoJobs.clear()
        scaffold()
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
                status.text = ""; status.visibility = View.GONE
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { status.text = "${e.message}. Pulsa Actualizar / Reintentar." }
        }
    }
    private fun select(label: String, values: List<String>, current: String, change: (String) -> Unit) {
        val field = TextInputLayout(this, null, com.google.android.material.R.attr.textInputStyle).apply {
            hint = label
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        val names = values.map { value -> ReportState.entries.firstOrNull { it.name == value }?.displayLabel() ?: value }
        val dropdown = MaterialAutoCompleteTextView(field.context).apply {
            inputType = InputType.TYPE_NULL
            setSimpleItems(names.toTypedArray())
            setText(names[values.indexOf(current).coerceAtLeast(0)], false)
            setOnItemClickListener { _, _, position, _ -> change(values[position]) }
        }
        field.addView(dropdown, LinearLayout.LayoutParams(-1, -2))
        body.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    private fun renderList(rows: List<Tracking>) {
        cachedRows = rows
        if (categoryFilter != "Todas" && rows.none { it.report.category == categoryFilter }) categoryFilter = "Todas"
        val searchField = TextInputLayout(this, null, com.google.android.material.R.attr.textInputStyle).apply {
            hint = "Buscar reportes"
            helperText = "Descripción, ubicación, categoría o referencia"
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        }
        val search = TextInputEditText(searchField.context).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            setText(query)
        }
        searchField.addView(search, LinearLayout.LayoutParams(-1, -2))
        body.addView(searchField, LinearLayout.LayoutParams(-1, -2))
        select("Estado", listOf("Todos") + ReportState.entries.map { it.name }, stateFilter) {
            stateFilter = it; visibleCount = 20; renderResults()
        }
        select("Categoría", listOf("Todas") + rows.map { it.report.category }.distinct().sorted(), categoryFilter) {
            categoryFilter = it; visibleCount = 20; renderResults()
        }
        select("Ordenar por", listOf("Más recientes", "Más antiguos"), if (newest) "Más recientes" else "Más antiguos") {
            newest = it == "Más recientes"; visibleCount = 20; renderResults()
        }
        button(body, "Limpiar filtros") {
            stateFilter = "Todos"; categoryFilter = "Todas"; newest = true; query = ""; visibleCount = 20
            searchJob?.cancel(); body.removeAllViews(); renderList(cachedRows)
        }.applyGeoSecondaryButton()
        resultCount = text(body, "", 16f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }
        search.doAfterTextChanged {
            query = it?.toString().orEmpty(); visibleCount = 20
            searchJob?.cancel()
            searchJob = lifecycleScope.launch { delay(250); renderResults() }
        }
        renderResults()
    }
    private fun renderResults() {
        photoJobs.forEach { it.cancel() }; photoJobs.clear()
        results.removeAllViews()
        val filtered = ReportFilters(query, stateFilter, categoryFilter, newest).apply(cachedRows)
        resultCount.text = "${filtered.size} de ${cachedRows.size} reportes"
        if (filtered.isEmpty()) {
            text(results, if (cachedRows.isEmpty()) "Aún no hay reportes" else "Sin coincidencias", 20f)
            text(results, if (cachedRows.isEmpty()) "Los reportes enviados aparecerán aquí para consultar su seguimiento." else "Prueba otra búsqueda o limpia los filtros para ver todos los reportes.")
        }
        filtered.take(visibleCount).forEach { row ->
            val card = MaterialCardView(this).apply {
                radius = dp(20).toFloat(); cardElevation = 0f
                strokeWidth = dp(1); strokeColor = ContextCompat.getColor(this@ReportsActivity, R.color.stroke_light)
                setCardBackgroundColor(ContextCompat.getColor(this@ReportsActivity, R.color.surface_card))
            }
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            card.addView(details)
            results.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            text(details, row.report.category, 20f)
            text(details, row.state.displayLabel()).apply {
                setTextColor(ContextCompat.getColor(this@ReportsActivity, R.color.primary_dark))
            }
            photo(row.report, 130, details)
            text(details, row.report.location)
            text(details, date(row.report.createdAt), 14f)
            button(details, "Ver reporte #${row.report.ticket}") { selectedId = row.report.id; refresh() }.applyGeoSecondaryButton()
        }
        if (filtered.size > visibleCount) button(results, "Mostrar más reportes") { visibleCount += 20; renderResults() }.applyGeoSecondaryButton()
    }
    private suspend fun renderDetail(row: Tracking, events: List<ReportEvent>, attempts: List<String>) {
        val report = row.report
        text(body, "${report.category} · ${row.state.displayLabel()}", 20f)
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
        val input = TextInputEditText(this).apply {
            hint = "Descripción pública sin datos personales"; contentDescription = hint
            minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(android.text.InputFilter.LengthFilter(1000)); setText(current)
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Autorizar consulta compartida")
            .setMessage("Redacta un resumen público sin nombres, correos, teléfonos ni otros datos personales. Se compartirán categoría, resumen, fecha, estado y coordenadas con las cuentas de esta instalación. La descripción privada y las fotos no se comparten.")
            .setView(dialogField(input)).setNegativeButton("Cancelar", null).setPositiveButton("Autorizar", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            if(input.text.isNullOrBlank()) input.error = "Escribe el resumen público"
            else { perform({ service.setSharedVisibility(id, true, input.text.toString()) }); dialog.dismiss() }
        } }
        dialog.show()
    }
    private fun photo(report: IncidentReport, height: Int, parent: LinearLayout = body) {
        val image = ImageView(this).apply {
            contentDescription = "Fotografía de ${report.category}"; scaleType = ImageView.ScaleType.FIT_CENTER
            parent.addView(this, LinearLayout.LayoutParams(-1, (height * resources.displayMetrics.density).toInt()))
        }
        photoJobs += lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { val storage = PhotoStorage(this@ReportsActivity); storage.resolve(report.photoPath)?.let { storage.preview(it) } }.getOrNull() }
            if(bitmap != null) image.setImageBitmap(bitmap) else image.contentDescription = "Fotografía no disponible"
        }
    }
    private fun perform(action: () -> Unit, done: () -> Unit = { refresh(); content.showFeedback("Cambios guardados") }) {
        if(busy) return
        busy = true; status.visibility = View.VISIBLE; status.text = "Procesando…"
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { action() }; busy = false; done() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { busy = false; status.text = "${e.message}. Puedes reintentar la acción." }
            finally { busy = false }
        }
    }
    private fun update(id: String, next: ReportState, note: String = "") = perform({ service.transition(id, next, note) })
    private fun dialogField(input: TextInputEditText): LinearLayout {
        val field = TextInputLayout(this, null, com.google.android.material.R.attr.textInputStyle).apply {
            hint = input.hint; input.hint = null
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        return LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(field, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun reject(id: String) {
        val input = TextInputEditText(this).apply { hint = "Motivo obligatorio"; minLines = 2; contentDescription = hint; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Confirmar rechazo").setView(dialogField(input))
            .setNegativeButton("Cancelar", null).setPositiveButton("Rechazar", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            if(input.text.isNullOrBlank()) input.error = "Indica el motivo" else { update(id, ReportState.RECHAZADO, input.text.toString()); dialog.dismiss() }
        } }; dialog.show()
    }
    private fun credentials(row: Tracking) {
        val input = TextInputEditText(this).apply { hint = "Clave de integración de demostración"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        MaterialAlertDialogBuilder(this).setTitle("API simulada").setMessage("La clave solo se utiliza en esta operación.")
            .setView(dialogField(input)).setNegativeButton("Cancelar", null).setPositiveButton("Consultar instituciones") { _, _ ->
                val client = InstitutionClient(this, input.text.toString()); input.text?.clear()
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
