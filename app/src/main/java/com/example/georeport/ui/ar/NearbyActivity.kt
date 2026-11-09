package com.example.georeport.ui.ar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.*
import android.hardware.camera2.*
import android.location.*
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.georeport.R
import com.example.georeport.data.*
import com.example.georeport.domain.GeoProjection
import com.example.georeport.ui.common.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date
import kotlin.math.*

class NearbyActivity : AppCompatActivity(), SensorEventListener, LocationListener {
    private val service by lazy { ReportService(this) }
    private lateinit var sensors: SensorManager
    private lateinit var locations: LocationManager
    private lateinit var texture: TextureView
    private lateinit var overlay: Markers
    private lateinit var status: TextView
    private lateinit var dataStatus: TextView
    private lateinit var list: LinearLayout
    private var active = false
    private var permissionRequested = false
    private var radius = 500
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    private var previewReady = false
    private var generation = 0
    private var position: Location? = null
    private var lastQueryPosition: Location? = null
    private var queriedAt = 0L
    private var updatedLabel: String? = null
    private var queryJob: Job? = null
    private var queryGeneration = 0
    private var ticker: Job? = null
    private var rows = emptyList<NearbyIncident>()
    private var quaternion: FloatArray? = null
    private var reliable = false
    private var sensorAvailable = false
    private var orientationAt = 0L
    private var cameraMessage = "Cámara pendiente"
    private var locationMessage: String? = null
    private var focalX = 1.0
    private var focalY = 1.0
    private var approximateFov = true
    private var lastDisplayRotation = -1
    private var sensorOrientation = 90
    private var previewWidth = 640
    private var previewHeight = 480
    private val matrix = FloatArray(9)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(active) startResources()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        radius = savedInstanceState?.getInt("radius", 500)?.takeIf { it in listOf(100,500,1000) } ?: 500
        permissionRequested = savedInstanceState?.getBoolean("permissions", false) ?: false
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        locations = getSystemService(LOCATION_SERVICE) as LocationManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@NearbyActivity, R.color.bg_light))
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        fun label(value: String) = TextView(this).apply { text = value; applyGeoText(); root.addView(this) }
        fun button(value: String, action: () -> Unit) = MaterialButton(this).apply {
            applyGeoSecondaryButton(); text = value; root.addView(this, LinearLayout.LayoutParams(-1,-2))
            setOnClickListener { action() }
        }
        root.addView(MaterialToolbar(this).apply {
            title = "Cerca de ti"
            subtitle = "Incidencias en realidad aumentada"
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "Volver"
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2))
        label("Apunta hacia tu entorno para consultar incidencias aprobadas. Toca un marcador para ver su descripción, fecha y estado.")
        label("La cámara no detecta problemas: muestra reportes registrados. Dirección y distancia aproximadas.")
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this); overlay = Markers()
        frame.addView(texture, FrameLayout.LayoutParams(-1,-1)); frame.addView(overlay, FrameLayout.LayoutParams(-1,-1))
        root.addView(frame, LinearLayout.LayoutParams(-1, dp(if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 240 else 320)))
        status = label("Buscando ubicación…")
        dataStatus = label("Esperando una ubicación válida para consultar")
        dataStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        label("Radio de búsqueda")
        val distances = listOf(100,500,1000)
        val selector = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        distances.forEach { meters ->
            selector.addView(Chip(this).apply {
                id = View.generateViewId(); text = "$meters m"
                contentDescription = "Buscar en un radio de $meters metros"
                isCheckable = true; setEnsureMinTouchTargetSize(true)
                isChecked = radius == meters
                setOnCheckedChangeListener { _, checked ->
                    if (checked && radius != meters) { radius = meters; clearResults(); refresh() }
                }
            })
        }
        root.addView(selector)
        button("Actualizar / Reintentar") { startResources(); refresh() }
        button("Conceder permisos") { requestPermissions() }
        button("Ajustes de ubicación") { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        button("Ajustes de permisos de la aplicación") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }
        button("Volver") { finish() }
        label("Se muestran hasta 20 incidencias autorizadas de esta instalación, ordenadas por cercanía. No se consultan otros dispositivos.")
        label("Lista de incidencias cercanas · mismos datos que los marcadores").applyGeoText(20f)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(-1,-2))
        val scroll = ScrollView(this).apply { setBackgroundColor(ContextCompat.getColor(this@NearbyActivity, R.color.bg_light)); addView(root) }
        setContentView(scroll); fitScreenContent(scroll)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { if(active) openCamera() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { transformPreview() }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { closeCamera(); return true }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.putInt("radius", radius); outState.putBoolean("permissions", permissionRequested)
    }
    override fun onResume() {
        super.onResume()
        if(!SessionNavigation.require(this)) return
        active = true
        startResources()
        if(!permissionRequested) { permissionRequested = true; requestPermissions() }
        ticker = lifecycleScope.launch {
            while(isActive) {
                updateStatus()
                if(goodPosition() == null) {
                    if(rows.isNotEmpty() || queryJob?.isActive == true) clearResults()
                    lastQueryPosition = null
                    dataStatus.text = "Esperando una ubicación válida para consultar"
                } else if(queryJob?.isActive != true && SystemClock.elapsedRealtime() - queriedAt >= 30_000L) refresh()
                delay(1000)
            }
        }
    }
    override fun onPause() {
        active = false; ticker?.cancel(); queryGeneration++; queryJob?.cancel()
        sensors.unregisterListener(this); locations.removeUpdates(this); closeCamera()
        position = null; lastQueryPosition = null; quaternion = null; orientationAt = 0L; reliable = false
        clearResults()
        super.onPause()
    }
    private fun requestPermissions() {
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    @SuppressLint("MissingPermission")
    private fun startResources() {
        if(!active) return
        sensors.unregisterListener(this); locations.removeUpdates(this)
        reliable = false; orientationAt = 0L; quaternion = null
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensorAvailable = sensor != null && sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        locationMessage = null
        if(granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { locations.isProviderEnabled(it) }
            if(providers.isEmpty()) { position = null; locationMessage = "Ubicación desactivada. Actívala en Ajustes de ubicación." }
            providers.forEach { provider ->
                runCatching {
                    locations.requestLocationUpdates(provider, 1500L, 2f, this)
                    locations.getLastKnownLocation(provider)?.let { onLocationChanged(it) }
                }.onFailure { locationMessage = "No se pudo obtener la ubicación. Pulsa Actualizar / Reintentar." }
            }
        } else { position = null; locationMessage = "Permiso de ubicación denegado. Concédelo para buscar incidencias reales cercanas." }
        if(granted(Manifest.permission.CAMERA)) { if(texture.isAvailable) openCamera() }
        else { closeCamera(); cameraMessage = "Sin permiso de cámara. La lista sigue disponible con ubicación válida." }
        updateStatus()
    }
    override fun onLocationChanged(location: Location) {
        if(!active || !GeoProjection.validCoordinates(location.latitude, location.longitude)) return
        val previous = position
        if(previous == null || location.elapsedRealtimeNanos >= previous.elapsedRealtimeNanos) position = Location(location)
        locationMessage = null
        val here = goodPosition()
        if(here != null) {
            val anchor = lastQueryPosition
            if(anchor == null || here.distanceTo(anchor) >= 25f) refresh()
            else showList()
        } else { clearResults(); lastQueryPosition = null }
        updateStatus()
    }
    override fun onProviderDisabled(provider: String) {
        position = null; lastQueryPosition = null; clearResults(); startResources()
    }
    override fun onProviderEnabled(provider: String) { startResources() }
    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    private fun goodPosition(): Location? = position?.takeIf {
        it.hasAccuracy() && it.accuracy.isFinite() && it.accuracy in 0f..50f &&
            (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) in 0..60_000_000_000L
    }
    private fun updateStatus() {
        val here = goodPosition()
        status.text = listOfNotNull(
            locationMessage ?: when {
                position == null -> "Buscando ubicación real…"
                SystemClock.elapsedRealtimeNanos() - position!!.elapsedRealtimeNanos !in 0..60_000_000_000L -> "Ubicación antigua. Esperando una lectura de menos de 60 segundos."
                here == null -> "Precisión insuficiente. Se necesita un error estimado de 50 m o menos; intenta al aire libre."
                else -> "Precisión GPS aproximada: ±${here.accuracy.toInt()} m"
            },
            when {
                !sensorAvailable -> "Sin sensor de orientación compatible: utiliza la lista."
                !reliable || SystemClock.elapsedRealtimeNanos() - orientationAt !in 0..2_000_000_000L -> "Orientación no disponible o imprecisa: aleja imanes y calibra el teléfono. Lista disponible."
                else -> "Gira el teléfono para explorar."
            },
            if(!previewReady) cameraMessage else if(approximateFov) "Cámara activa · campo de visión aproximado (sin calibración disponible)." else "Cámara activa · proyección aproximada según la óptica disponible."
        ).joinToString("\n")
        overlay.invalidate()
    }
    private fun clearResults() {
        queryGeneration++; queryJob?.cancel(); rows = emptyList(); updatedLabel = null
        if(::list.isInitialized) list.removeAllViews()
        if(::overlay.isInitialized) overlay.invalidate()
    }
    private fun refresh() {
        if(!active) return
        val here = goodPosition() ?: run { clearResults(); dataStatus.text = "Esperando una ubicación válida para consultar"; return }
        clearResults()
        val version = ++queryGeneration
        val requestedRadius = radius
        lastQueryPosition = Location(here)
        dataStatus.text = "Consultando reportes reales…"
        queryJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { service.nearby(here.latitude, here.longitude, requestedRadius) }
                if(!active || version != queryGeneration || goodPosition() == null) return@launch
                rows = result; queriedAt = SystemClock.elapsedRealtime()
                updatedLabel = DateFormat.getTimeInstance().format(Date())
                dataStatus.text = if(result.isEmpty()) "No hay incidencias disponibles cerca de tu ubicación"
                    else "${result.size} incidencias · consulta local actualizada: ${DateFormat.getTimeInstance().format(Date())}"
                showList(); overlay.invalidate()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(version != queryGeneration) return@launch
                rows = emptyList(); updatedLabel = null; list.removeAllViews(); overlay.invalidate()
                queriedAt = SystemClock.elapsedRealtime()
                dataStatus.text = "Error al consultar incidencias: ${e.message ?: "almacenamiento no disponible"}. Pulsa Actualizar / Reintentar."
            }
        }
    }
    private fun visibleRows(): List<NearbyIncident> {
        val here = goodPosition() ?: return emptyList()
        return rows.map { NearbyIncident(it.incident, GeoProjection.relative(here.latitude,here.longitude,it.incident.latitude,it.incident.longitude)) }
            .filter { it.position.distance <= radius }.sortedBy { it.position.distance }.take(20)
    }
    private fun distance(meters: Double) = if(meters >= 1000) String.format(java.util.Locale.getDefault(), "Aprox. %.1f km", meters/1000) else "Aprox. ${meters.roundToInt()} m"
    private fun state(value: String) = if(value == "APROBADO") "Aprobado" else "Canalizado"
    private fun showList() {
        list.removeAllViews()
        val visible = visibleRows()
        updatedLabel?.let { at ->
            dataStatus.text = if(visible.isEmpty()) "No hay incidencias disponibles cerca de tu ubicación"
                else "${visible.size} incidencias · consulta local actualizada: $at"
        }
        visible.forEach { row ->
            list.addView(MaterialButton(this).apply {
                applyGeoSecondaryButton(); layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) }
                text = "${row.incident.category} · ${distance(row.position.distance)} · ${state(row.incident.state)}"
                setIconResource(R.drawable.ic_location)
                setOnClickListener { summary(row.incident.id) }
            })
        }
    }
    private fun summary(id: String) {
        lifecycleScope.launch {
            try {
                val incident = withContext(Dispatchers.IO) { service.sharedDetail(id) }
                if(!active) return@launch
                val here = goodPosition() ?: run { dataStatus.text = "Ubicación no disponible; actualiza antes de abrir el detalle"; return@launch }
                val d = GeoProjection.relative(here.latitude,here.longitude,incident.latitude,incident.longitude).distance
                if(d > radius) { refresh(); return@launch }
                val dialog = MaterialAlertDialogBuilder(this@NearbyActivity).setTitle(incident.category)
                    .setMessage("${incident.description}\n\nFecha: ${DateFormat.getDateTimeInstance().format(Date(incident.createdAt))}\nEstado actual: ${state(incident.state)} · ${distance(d)}\n\n" +
                        if(incident.locationConfirmed) "Punto confirmado en el mapa; dirección y distancia aproximadas." else "Ubicación aproximada: GPS del teléfono o registro histórico sin confirmación del punto del incidente.")
                    .setPositiveButton("Cerrar", null)
                dialog.show()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { clearResults(); dataStatus.text = "No se puede abrir la incidencia: ${e.message}. Pulsa Actualizar / Reintentar." }
        }
    }
    override fun onSensorChanged(event: SensorEvent) {
        if(!active) return
        if(lastDisplayRotation != rotation()) { lastDisplayRotation = rotation(); transformPreview() }
        reliable = event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        orientationAt = event.timestamp
        if(!reliable) { overlay.invalidate(); return }
        val next = FloatArray(4)
        SensorManager.getQuaternionFromVector(next, event.values)
        val last = quaternion
        if(last == null) quaternion = next else {
            val sign = if(last.indices.sumOf { (last[it]*next[it]).toDouble() } < 0) -1 else 1
            for(i in last.indices) last[i] = last[i] * .82f + next[i] * sign * .18f
            val norm = sqrt(last.sumOf { (it*it).toDouble() }).toFloat()
            if(norm <= 0 || !norm.isFinite()) { reliable = false; return }
            for(i in last.indices) last[i] /= norm
        }
        val q = quaternion!!
        SensorManager.getRotationMatrixFromVector(matrix, floatArrayOf(q[1],q[2],q[3],q[0]))
        overlay.invalidate() // No database or network access on sensor events.
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM }
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if(!active || opening || camera != null || !granted(Manifest.permission.CAMERA)) return
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: error("No hay cámara trasera")
            val characteristics = manager.getCameraCharacteristics(id)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(SurfaceTexture::class.java)
                .filter { it.width <= 1920 }.minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: error("Vista previa no compatible")
            previewWidth = size.width; previewHeight = size.height
            val sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            approximateFov = sensor == null || focal == null || focal <= 0 || sensor.width <= 0 || sensor.height <= 0
            if(!approximateFov) {
                // Center crop from sensor aspect to preview stream aspect, then to viewport in onDraw.
                val aspect = previewWidth.toDouble()/previewHeight
                val cropWidth = min(sensor!!.width.toDouble(), sensor.height * aspect)
                val cropHeight = min(sensor.height.toDouble(), sensor.width / aspect)
                focalX = 2 * focal!! / cropWidth; focalY = 2 * focal / cropHeight
            } else {
                // Explicit approximation: 60° horizontal FOV in native buffer coordinates.
                focalX = 1 / tan(Math.toRadians(30.0)); focalY = focalX * previewWidth / previewHeight
            }
            texture.surfaceTexture!!.setDefaultBufferSize(size.width, size.height); transformPreview()
            opening = true; cameraMessage = "Abriendo cámara…"
            val requestGeneration = generation
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if(!active || requestGeneration != generation || !texture.isAvailable) { device.close(); return }
                    opening = false; camera = device
                    val surface = Surface(texture.surfaceTexture)
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                            if(Build.VERSION.SDK_INT >= 31 && characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true) {
                                set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
                            }
                        }
                        @Suppress("DEPRECATION")
                        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(value: CameraCaptureSession) {
                                if(!active || camera != device || requestGeneration != generation) { value.close(); surface.release(); return }
                                session = value
                                try {
                                    value.setRepeatingRequest(request.build(), null, Handler(mainLooper))
                                    previewReady = true; updateStatus()
                                } catch(e: Exception) { closeCamera(); cameraMessage = "No se pudo iniciar la vista previa. Usa la lista o reintenta."; updateStatus() }
                            }
                            override fun onConfigureFailed(value: CameraCaptureSession) {
                                surface.release()
                                if(requestGeneration == generation) { closeCamera(); cameraMessage = "Configuración de cámara no compatible. Lista disponible."; updateStatus() }
                            }
                            override fun onClosed(value: CameraCaptureSession) { surface.release() }
                        }, Handler(mainLooper))
                    } catch(e: Exception) { surface.release(); closeCamera(); cameraMessage = "Error al preparar la cámara. Usa la lista o reintenta."; updateStatus() }
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if(requestGeneration == generation) { closeCamera(); cameraMessage = "Cámara desconectada. Lista disponible."; updateStatus() }
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if(requestGeneration == generation) { closeCamera(); cameraMessage = "Error de cámara. Usa la lista o reintenta."; updateStatus() }
                }
            }, Handler(mainLooper))
        } catch(e: Exception) { opening = false; cameraMessage = "Cámara no disponible: ${e.message}. Lista disponible."; updateStatus() }
    }
    @Suppress("DEPRECATION")
    private fun rotation() = windowManager.defaultDisplay.rotation
    private fun transformPreview() {
        if(texture.width == 0 || texture.height == 0) return
        val degrees = (sensorOrientation - rotation()*90 + 360)%360
        val swapped = degrees == 90 || degrees == 270
        val bufferW = if(swapped) previewHeight.toFloat() else previewWidth.toFloat()
        val bufferH = if(swapped) previewWidth.toFloat() else previewHeight.toFloat()
        val scale = max(texture.width / bufferW, texture.height / bufferH)
        val transform = Matrix()
        transform.setScale(previewWidth.toFloat()/texture.width, previewHeight.toFloat()/texture.height, texture.width/2f, texture.height/2f)
        transform.postRotate(degrees.toFloat(), texture.width/2f, texture.height/2f)
        transform.postScale(scale,scale,texture.width/2f,texture.height/2f)
        texture.setTransform(transform)
    }
    private fun closeCamera() {
        generation++; session?.close(); session = null; camera?.close(); camera = null; opening = false; previewReady = false
        if(::overlay.isInitialized) overlay.invalidate()
    }
    inner class Markers : View(this@NearbyActivity) {
        private val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val targets = mutableListOf<Pair<RectF, String>>()
        private val icon = ContextCompat.getDrawable(this@NearbyActivity, R.drawable.ic_location)?.mutate()?.apply { setTint(Color.WHITE) }
        init { contentDescription = "Marcadores direccionales aproximados. Todos los incidentes se pueden abrir con los botones de la lista." }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas); targets.clear()
            val here = goodPosition() ?: return
            if(!active || !previewReady || !reliable || !sensorAvailable || SystemClock.elapsedRealtimeNanos() - orientationAt !in 0..2_000_000_000L) return
            // No incident altitude exists: horizontal direction only, not an object/depth anchor.
            val declination = GeomagneticField(here.latitude.toFloat(),here.longitude.toFloat(),if(here.hasAltitude()) here.altitude.toFloat() else 0f,System.currentTimeMillis()).declination.toDouble()
            val density = resources.displayMetrics.density
            @Suppress("DEPRECATION")
            val scaledDensity = resources.displayMetrics.scaledDensity
            paint.textSize = 14 * scaledDensity
            val line = paint.fontMetrics.let { it.descent - it.ascent } + 4*density
            val boxHeight = max(72*density, 3*line + 16*density)
            val half = min(150*density, width/2f)
            val swapped = ((sensorOrientation - rotation()*90 + 360)%360) % 180 != 0
            val bufferW = if(swapped) previewHeight.toDouble() else previewWidth.toDouble()
            val bufferH = if(swapped) previewWidth.toDouble() else previewHeight.toDouble()
            val scale = max(width/bufferW, height/bufferH)
            val fx = (if(swapped) focalY else focalX) * bufferW * scale / width
            val fy = (if(swapped) focalX else focalY) * bufferH * scale / height
            visibleRows().forEach { row ->
                if(row.position.distance < 1) return@forEach // Bearing is undefined at the same coordinate; list remains usable.
                val screen = GeoProjection.project(row.position.bearing,declination,this@NearbyActivity.matrix,rotation()) ?: return@forEach
                val x = (width/2.0 * (1 + screen.first*fx)).toFloat()
                val y = (height/2.0 * (1 + screen.second*fy)).toFloat()
                if(!x.isFinite() || !y.isFinite() || x !in 0f..width.toFloat() || y !in 0f..height.toFloat()) return@forEach
                val cx = x.coerceIn(half,width-half)
                val box = RectF(cx-half,y-boxHeight/2,cx+half,y+boxHeight/2)
                // Do not invent vertical positions to avoid overlaps: nearer markers have priority.
                if(box.top < 0 || box.bottom > height || targets.any { RectF.intersects(it.first,box) }) return@forEach
                paint.color = ContextCompat.getColor(this@NearbyActivity, R.color.primary_dark)
                canvas.drawRoundRect(box,dp(12).toFloat(),dp(12).toFloat(),paint)
                icon?.setBounds((box.left+8*density).toInt(),(box.top+8*density).toInt(),(box.left+28*density).toInt(),(box.top+28*density).toInt())
                icon?.draw(canvas)
                paint.color = Color.WHITE
                val left = box.left + 34*density
                val available = (box.right - left - 8*density).coerceAtLeast(1f)
                val lines = listOf(row.incident.category, distance(row.position.distance), state(row.incident.state))
                lines.forEachIndexed { index, value ->
                    canvas.drawText(TextUtils.ellipsize(value,paint,available,TextUtils.TruncateAt.END).toString(),left,box.top+8*density-paint.fontMetrics.ascent+index*line,paint)
                }
                targets.add(box to row.incident.id)
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if(event.action == MotionEvent.ACTION_DOWN) return targets.any { it.first.contains(event.x,event.y) }
            if(event.action == MotionEvent.ACTION_UP) {
                targets.firstOrNull { it.first.contains(event.x,event.y) }?.let { summary(it.second); performClick(); return true }
            }
            return super.onTouchEvent(event)
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
