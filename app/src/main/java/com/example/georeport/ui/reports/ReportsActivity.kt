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
    private lateinit var toolbarHost: LinearLayout
    private lateinit var reportScroll: ScrollView
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
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)); setBackgroundColor(ContextCompat.getColor(this@ReportsActivity, R.color.bg_light)) }
        val scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        reportScroll = scroll
        toolbarHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@ReportsActivity, R.color.bg_light))
            addView(toolbarHost, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root); fitScreenContent(root)
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
        reportScroll.scrollTo(0, 0)
        toolbarHost.removeAllViews()
        val toolbar = MaterialToolbar(this).apply {
            title = if (admin) "Administración" else "Mis reportes"
            subtitle = if (selectedId == null) "Consulta y seguimiento" else "Detalle del reporte"
            setBackgroundColor(ContextCompat.getColor(this@ReportsActivity, R.color.surface_card))
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
        toolbarHost.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        status = text(content, "Cargando…")
        status.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; content.addView(this) }
    }
    private fun panel(parent: LinearLayout, color: Int = R.color.surface_card): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = if (color == R.color.surface_card) dp(1) else 0
            strokeColor = ContextCompat.getColor(this@ReportsActivity, R.color.stroke_light)
            setCardBackgroundColor(ContextCompat.getColor(this@ReportsActivity, color))
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        card.addView(inner)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        return inner
    }
    private fun stateBadge(parent: LinearLayout, state: ReportState) {
        val color = when (state) {
            ReportState.RESUELTO -> R.color.success_green
            ReportState.RECHAZADO -> R.color.accent_red
            ReportState.ENVIADO, ReportState.EN_VALIDACION -> R.color.accent_orange
            else -> R.color.primary_dark
        }
        text(parent, state.displayLabel(), 14f).apply {
            setTextColor(ContextCompat.getColor(this@ReportsActivity, color))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@ReportsActivity, R.color.surface_card))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(8) }
        }
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
    private fun select(parent: LinearLayout, label: String, values: List<String>, current: String, change: (String) -> Unit) {
        val field = TextInputLayout(this, null, com.google.android.material.R.attr.textInputStyle).apply {
            hint = label
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        val names = values.map { value -> ReportState.entries.firstOrNull { it.name == value }?.displayLabel() ?: value }
        val dropdown = MaterialAutoCompleteTextView(field.context).apply {
            minimumHeight = dp(56)
            setPaddingRelative(dp(16), dp(16), dp(16), dp(16))
            inputType = InputType.TYPE_NULL
            setSimpleItems(names.toTypedArray())
            setText(names[values.indexOf(current).coerceAtLeast(0)], false)
            setOnItemClickListener { _, _, position, _ -> change(values[position]) }
        }
        field.addView(dropdown, LinearLayout.LayoutParams(-1, -2))
        parent.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
    private fun renderList(rows: List<Tracking>) {
        cachedRows = rows
        val intro = panel(body, R.color.primary_dark)
        text(intro, if (admin) "Bandeja de incidencias" else "Tu comunidad, en seguimiento", 24f)
            .setTextColor(ContextCompat.getColor(this, R.color.white))
        val pending = rows.count { it.state == ReportState.ENVIADO || it.state == ReportState.EN_VALIDACION }
        text(intro, if (admin) "$pending pendientes de revisión · ${rows.size} reportes en total"
            else "Consulta el estado y los avances de tus reportes.", 16f)
            .setTextColor(ContextCompat.getColor(this, R.color.primary_light))
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
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (stateFilter != "Todos" || categoryFilter != "Todas" || !newest) View.VISIBLE else View.GONE
        }
        val toggleFilters = button(body, "", {})
        fun updateFilterToggle() {
            toggleFilters.text = if (filters.visibility == View.VISIBLE) "Ocultar filtros" else "Filtrar y ordenar"
        }
        toggleFilters.applyGeoSecondaryButton()
        toggleFilters.setOnClickListener {
            filters.visibility = if (filters.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateFilterToggle()
        }
        updateFilterToggle()
        body.addView(filters, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        select(filters, "Estado", listOf("Todos") + ReportState.entries.map { it.name }, stateFilter) {
            stateFilter = it; visibleCount = 20; renderResults()
        }
        select(filters, "Categoría", listOf("Todas") + rows.map { it.report.category }.distinct().sorted(), categoryFilter) {
            categoryFilter = it; visibleCount = 20; renderResults()
        }
        select(filters, "Ordenar por", listOf("Más recientes", "Más antiguos"), if (newest) "Más recientes" else "Más antiguos") {
            newest = it == "Más recientes"; visibleCount = 20; renderResults()
        }
        button(filters, "Limpiar filtros") {
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
            stateBadge(details, row.state)
            photo(row.report, 130, details)
            text(details, row.report.location)
            text(details, date(row.report.createdAt), 14f)
            button(details, if (admin) "Gestionar reporte #${row.report.ticket}" else "Ver reporte #${row.report.ticket}") { selectedId = row.report.id; refresh() }.applyGeoSecondaryButton()
        }
        if (filtered.size > visibleCount) button(results, "Mostrar más reportes") { visibleCount += 20; renderResults() }.applyGeoSecondaryButton()
    }
    private suspend fun renderDetail(row: Tracking, events: List<ReportEvent>, attempts: List<String>) {
        val report = row.report
        val hero = panel(body, R.color.primary_dark)
        text(hero, report.category, 24f).setTextColor(ContextCompat.getColor(this, R.color.white))
        stateBadge(hero, row.state)
        text(hero, "Reporte #${report.ticket} · ${date(report.createdAt)}", 14f)
            .setTextColor(ContextCompat.getColor(this, R.color.primary_light))
        if (!admin && row.state in listOf(ReportState.APROBADO, ReportState.CANALIZADO)) {
            text(hero, "Ya puedes consultarlo en Cerca de ti si estás dentro del radio de búsqueda.", 16f)
                .setTextColor(ContextCompat.getColor(this, R.color.white))
            button(hero, "Abrir Cerca de ti") {
                startActivity(android.content.Intent(this, com.example.georeport.ui.ar.NearbyActivity::class.java))
            }.applyGeoSecondaryButton()
        }
        photo(report, 280)
        val description = panel(body)
        text(description, "Descripción", 20f)
        text(description, report.description.ifBlank { "Sin descripción adicional." })
        val location = panel(body, R.color.primary_light)
        text(location, "Ubicación del incidente", 20f).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_location, 0, 0, 0)
            compoundDrawablePadding = dp(8)
        }
        text(location, report.location)
        text(location, if(report.locationConfirmed) "Punto confirmado en el mapa" else "Punto aproximado · sin confirmación en el mapa", 14f)
        button(location, "Ver coordenadas y referencia") {
            MaterialAlertDialogBuilder(this)
                .setTitle("Datos del reporte #${report.ticket}")
                .setMessage("Latitud: ${report.latitude ?: "sin GPS"}\nLongitud: ${report.longitude ?: "sin GPS"}\n\nReferencia: ${report.id}")
                .setPositiveButton("Cerrar", null).show()
        }.applyGeoSecondaryButton()
        if(admin) {
            val management = panel(body)
            text(management, "Gestionar incidencia", 22f)
            text(management, "Remitente: ${report.userEmail}", 14f)
            text(management, "Siguiente acción", 16f)
            when(row.state) {
                ReportState.ENVIADO -> button(management, "Iniciar revisión") { update(report.id, ReportState.EN_VALIDACION) }
                ReportState.EN_VALIDACION -> {
                    button(management, "Aprobar reporte") { update(report.id, ReportState.APROBADO) }
                    button(management, "Rechazar con motivo") { reject(report.id) }.apply {
                        applyGeoSecondaryButton()
                        setTextColor(ContextCompat.getColor(this@ReportsActivity, R.color.accent_red))
                    }
                }
                ReportState.APROBADO -> button(management, "Enviar a institución simulada") { credentials(row) }
                ReportState.CANALIZADO -> button(management, "Marcar como resuelto") { update(report.id, ReportState.RESUELTO) }
                else -> text(management, "Este reporte ya no tiene acciones pendientes.", 14f)
            }
            val visibility = withContext(Dispatchers.IO) { service.visibility(report.id) }
            text(management, "Visibilidad en realidad aumentada", 20f)
            text(management, if(visibility.enabled) "Visible para otras cuentas de esta instalación" else "No compartido", 14f)
            if(row.state in listOf(ReportState.APROBADO, ReportState.CANALIZADO)) {
                button(management, if(visibility.enabled) "Editar resumen público" else "Autorizar publicación en RA") { share(report.id, visibility.description) }.applyGeoSecondaryButton()
            }
            if(visibility.enabled) button(management, "Retirar autorización de RA") { perform({ service.setSharedVisibility(report.id, false) }) }.applyGeoSecondaryButton()
        }
        row.institution?.let {
            val destination = panel(body)
            text(destination, "Institución asignada", 20f)
            text(destination, "$it\nComprobante: ${row.receipt}\nRecepción: ${row.receivedAt}")
        }
        val timeline = panel(body)
        text(timeline, "Seguimiento", 22f)
        if (events.isEmpty()) text(timeline, "Aún no hay movimientos registrados.")
        events.sortedByDescending { it.at }.forEachIndexed { index, event ->
            val entry = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_check_circle)
                imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@ReportsActivity,
                    if (index == 0) R.color.primary_blue else R.color.text_secondary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            entry.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { topMargin = dp(12); marginEnd = dp(12) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            entry.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val label = ReportState.entries.firstOrNull { it.name == event.to }?.displayLabel() ?: event.to
            text(info, label, 18f).setTypeface(null, android.graphics.Typeface.BOLD)
            text(info, "${date(event.at)} · ${if(admin) event.actor else if(event.from == "—") "Tú" else "Administración"}", 14f)
            if (event.note.isNotBlank()) text(info, event.note)
            timeline.addView(entry)
            if (index < events.lastIndex) timeline.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@ReportsActivity, R.color.stroke_light))
            }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(36); topMargin = dp(8); bottomMargin = dp(8) })
        }
        if(attempts.isNotEmpty()) button(body, "Ver diagnóstico de envíos") {
            MaterialAlertDialogBuilder(this).setTitle("Diagnóstico de envíos")
                .setMessage(attempts.joinToString("\n\n")).setPositiveButton("Cerrar", null).show()
        }.applyGeoSecondaryButton()
    }
    private fun share(id: String, current: String) {
        val input = TextInputEditText(this).apply {
            hint = "Resumen público"; contentDescription = hint
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
        val frame = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@ReportsActivity, R.color.primary_dark))
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
        }
        val image = ImageView(this).apply {
            contentDescription = "Ampliar fotografía de ${report.category}"
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        val caption = TextView(this).apply {
            text = "Cargando fotografía…"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@ReportsActivity, R.color.white))
            setBackgroundColor(android.graphics.Color.argb(190, 12, 30, 45))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(caption, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        parent.addView(frame, LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(8); bottomMargin = dp(16) })
        photoJobs += lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { val storage = PhotoStorage(this@ReportsActivity); storage.resolve(report.photoPath)?.let { storage.preview(it) } }.getOrNull() }
            if(bitmap != null) {
                image.setImageBitmap(bitmap)
                caption.text = "Ver fotografía completa"
                frame.isFocusable = true
                frame.contentDescription = image.contentDescription
                image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                frame.setOnClickListener {
                    val full = ImageView(this@ReportsActivity).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                        contentDescription = "Fotografía completa de ${report.category}"
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                    }
                    MaterialAlertDialogBuilder(this@ReportsActivity).setTitle("Evidencia fotográfica")
                        .setView(full).setPositiveButton("Cerrar", null).show()
                }
            } else {
                caption.text = "Fotografía no disponible"
                image.contentDescription = "Fotografía no disponible"
            }
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
            input.minimumHeight = dp(56)
            input.setPaddingRelative(dp(16), dp(16), dp(16), dp(16))
            input.gravity = android.view.Gravity.TOP or android.view.Gravity.START
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
        val input = TextInputEditText(this).apply { hint = "Clave de integración"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
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
