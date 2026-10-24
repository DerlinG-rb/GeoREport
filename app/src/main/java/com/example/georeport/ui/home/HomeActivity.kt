package com.example.georeport.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.georeport.R
import com.example.georeport.databinding.ActivityHomeBinding
import com.example.georeport.ui.auth.LoginActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.random.Random

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    // Evidencia fotográfica
    private var selectedImageUri: Uri? = null
    private var tempCameraUri: Uri? = null

    // OpenStreetMap y GPS
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var incidentMarker: Marker? = null
    private var selectedIncidentPoint: GeoPoint? = null
    private var lastUserGeoPoint: GeoPoint? = null
    private var geocodeJob: Job? = null

    // Launcher para selección de archivo de imagen (Galería o Explorador)
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            displaySelectedPhoto(it)
        }
    }

    // Launcher para tomar fotografía con cámara
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && tempCameraUri != null) {
            displaySelectedPhoto(tempCameraUri!!)
        }
    }

    // Launcher para permisos de cámara
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(
                this,
                getString(R.string.msg_camera_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Launcher para permisos de ubicación GPS
    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            enableUserLocationOverlay()
            fetchRealGpsLocation(centerMap = true)
        } else {
            Toast.makeText(
                this,
                getString(R.string.gps_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val incidentCategories by lazy {
        listOf(
            getString(R.string.category_pothole),
            getString(R.string.category_lighting),
            getString(R.string.category_garbage),
            getString(R.string.category_water_leak),
            getString(R.string.category_fallen_tree),
            getString(R.string.category_traffic_sign),
            getString(R.string.category_electrical_hazard),
            getString(R.string.category_other)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configuración de OpenStreetMap antes de inflar vistas
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("${packageName}_osm", Context.MODE_PRIVATE)
        )

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restaurar estado en caso de recreación
        if (savedInstanceState != null) {
            tempCameraUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                savedInstanceState.getParcelable("TEMP_CAMERA_URI", Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                savedInstanceState.getParcelable("TEMP_CAMERA_URI")
            }

            selectedImageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                savedInstanceState.getParcelable("SELECTED_IMAGE_URI", Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                savedInstanceState.getParcelable("SELECTED_IMAGE_URI")
            }
            selectedImageUri?.let { displaySelectedPhoto(it) }
        }

        setupUserHeader()
        setupCategoriesDropdown()
        setupOpenStreetMap()
        setupLocationClient()
        setupListeners()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tempCameraUri?.let { outState.putParcelable("TEMP_CAMERA_URI", it) }
        selectedImageUri?.let { outState.putParcelable("SELECTED_IMAGE_URI", it) }
    }

    private fun setupUserHeader() {
        val userEmail = intent.getStringExtra("USER_EMAIL")
        if (!userEmail.isNullOrBlank()) {
            binding.tvUserEmailDisplay.text = userEmail
        }
    }

    private fun setupCategoriesDropdown() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            incidentCategories
        )
        binding.actvIncidentCategory.setAdapter(adapter)

        binding.actvIncidentCategory.setOnItemClickListener { _, _, position, _ ->
            syncSelectedChip(incidentCategories[position])
        }
    }

    private fun setupLocationClient() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOpenStreetMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.5)

            // Centro inicial por defecto antes de recibir GPS
            val defaultPoint = GeoPoint(-0.180653, -78.467838)
            controller.setCenter(defaultPoint)

            // Evitar que el NestedScrollView intercepte los gestos al interactuar con el mapa
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        binding.homeScrollView.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        binding.homeScrollView.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
        }

        // Receptor de eventos de toque para seleccionar ubicación del incidente en el mapa
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                updateIncidentLocation(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                updateIncidentLocation(p)
                return true
            }
        }
        val mapEventsOverlay = MapEventsOverlay(mapEventsReceiver)
        binding.mapView.overlays.add(0, mapEventsOverlay)

        // Marcador visual para el punto de la incidencia con Bitmap seguro para osmdroid
        incidentMarker = Marker(binding.mapView).apply {
            title = getString(R.string.map_incident_marker_title)
            snippet = getString(R.string.map_incident_marker_snippet)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = getMarkerBitmapDrawable(R.drawable.ic_incident_pin)
            isDraggable = true
            setVisible(false) // Se hace visible cuando se fije un punto
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker?) {}
                override fun onMarkerDragStart(marker: Marker?) {}
                override fun onMarkerDragEnd(marker: Marker?) {
                    marker?.position?.let { updateIncidentLocation(it) }
                }
            })
        }
        binding.mapView.overlays.add(incidentMarker)

        // Inicializar GPS si los permisos ya están concedidos
        checkLocationPermissionAndInit()
    }

    private fun getMarkerBitmapDrawable(drawableId: Int): BitmapDrawable {
        val drawable = ContextCompat.getDrawable(this, drawableId)
        if (drawable is BitmapDrawable) {
            return drawable
        }
        val width = if (drawable != null && drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 48
        val height = if (drawable != null && drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 48
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable?.setBounds(0, 0, canvas.width, canvas.height)
        drawable?.draw(canvas)
        return BitmapDrawable(resources, bitmap)
    }

    private fun checkLocationPermissionAndInit() {
        if (hasLocationPermission()) {
            enableUserLocationOverlay()
            fetchRealGpsLocation(centerMap = true)
        } else {
            requestLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    private fun enableUserLocationOverlay() {
        if (!hasLocationPermission()) return
        try {
            if (myLocationOverlay == null) {
                val provider = GpsMyLocationProvider(applicationContext)
                myLocationOverlay = MyLocationNewOverlay(provider, binding.mapView).apply {
                    enableMyLocation()
                }
                binding.mapView.overlays.add(myLocationOverlay)
            } else {
                myLocationOverlay?.enableMyLocation()
            }
            binding.mapView.invalidate()
        } catch (e: Exception) {
            // Manejado
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchRealGpsLocation(centerMap: Boolean, updateIncidentPoint: Boolean = true) {
        if (!hasLocationPermission()) {
            requestLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        binding.tvCoordinates.text = getString(R.string.gps_status_searching)

        val cancellationTokenSource = CancellationTokenSource()
        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    lastUserGeoPoint = geoPoint
                    if (updateIncidentPoint || selectedIncidentPoint == null) {
                        updateIncidentLocation(geoPoint)
                    } else if (selectedIncidentPoint != null) {
                        binding.tvCoordinates.text = String.format(
                            Locale.US,
                            "Lat: %.5f, Lon: %.5f",
                            selectedIncidentPoint!!.latitude,
                            selectedIncidentPoint!!.longitude
                        )
                    }
                    if (centerMap) {
                        binding.mapView.controller.animateTo(geoPoint, 17.5, 1000L)
                    }
                    Toast.makeText(
                        this,
                        getString(R.string.gps_status_acquired),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    fallbackToLastLocation(centerMap, updateIncidentPoint)
                }
            }.addOnFailureListener {
                fallbackToLastLocation(centerMap, updateIncidentPoint)
            }
        } catch (e: SecurityException) {
            fallbackToLastLocation(centerMap, updateIncidentPoint)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackToLastLocation(centerMap: Boolean, updateIncidentPoint: Boolean = true) {
        if (!hasLocationPermission()) return
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    val geoPoint = GeoPoint(loc.latitude, loc.longitude)
                    lastUserGeoPoint = geoPoint
                    if (updateIncidentPoint || selectedIncidentPoint == null) {
                        updateIncidentLocation(geoPoint)
                    }
                    if (centerMap) {
                        binding.mapView.controller.animateTo(geoPoint, 17.0, 800L)
                    }
                } else {
                    onLocationUnavailable()
                }
            }.addOnFailureListener {
                onLocationUnavailable()
            }
        } catch (e: SecurityException) {
            onLocationUnavailable()
        }
    }

    private fun onLocationUnavailable() {
        checkGpsProviderEnabled()
        if (selectedIncidentPoint != null) {
            binding.tvCoordinates.text = String.format(
                Locale.US,
                "Lat: %.5f, Lon: %.5f",
                selectedIncidentPoint!!.latitude,
                selectedIncidentPoint!!.longitude
            )
        } else {
            binding.tvCoordinates.text = getString(R.string.coordinates_default)
        }
    }

    private fun checkGpsProviderEnabled() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!isGpsEnabled) {
            Toast.makeText(this, getString(R.string.gps_disabled_prompt), Toast.LENGTH_LONG).show()
        }
    }

    private fun updateIncidentLocation(point: GeoPoint) {
        selectedIncidentPoint = point
        incidentMarker?.position = point
        incidentMarker?.setVisible(true)
        try {
            incidentMarker?.showInfoWindow()
        } catch (e: Exception) {
            // Ignorado
        }
        binding.mapView.invalidate()

        binding.tvCoordinates.text = String.format(
            Locale.US,
            "Lat: %.5f, Lon: %.5f",
            point.latitude,
            point.longitude
        )

        // Geocodificación inversa en segundo plano
        resolveAddressFromCoordinates(point)
    }

    private fun resolveAddressFromCoordinates(point: GeoPoint) {
        geocodeJob?.cancel()
        geocodeJob = lifecycleScope.launch(Dispatchers.IO) {
            val geocoder = Geocoder(this@HomeActivity, Locale.getDefault())
            val addresses = getAddressesFromPoint(geocoder, point.latitude, point.longitude)
            val resolvedAddress = formatAddress(addresses, point)

            withContext(Dispatchers.Main) {
                binding.etLocation.setText(resolvedAddress)
            }
        }
    }

    private suspend fun getAddressesFromPoint(
        geocoder: Geocoder,
        latitude: Double,
        longitude: Double
    ): List<Address>? {
        if (!Geocoder.isPresent()) return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                try {
                    geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) {
                                continuation.resume(addresses)
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    })
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun formatAddress(addresses: List<Address>?, point: GeoPoint): String {
        if (addresses.isNullOrEmpty()) {
            return String.format(Locale.US, "Lat: %.5f, Lon: %.5f", point.latitude, point.longitude)
        }
        val addr = addresses[0]
        val thoroughfare = addr.thoroughfare
        val subThoroughfare = addr.subThoroughfare
        val locality = addr.locality ?: addr.subAdminArea ?: addr.adminArea
        return when {
            thoroughfare != null && subThoroughfare != null && locality != null ->
                "$thoroughfare $subThoroughfare, $locality"
            thoroughfare != null && locality != null ->
                "$thoroughfare, $locality"
            thoroughfare != null ->
                thoroughfare
            addr.getAddressLine(0) != null ->
                addr.getAddressLine(0)
            else ->
                String.format(Locale.US, "Ubicación (%.5f, %.5f)", point.latitude, point.longitude)
        }
    }

    private fun setupListeners() {
        // Acciones de evidencia fotográfica
        binding.btnTakePhoto.setOnClickListener {
            checkCameraPermissionAndLaunch()
        }

        binding.btnUploadFile.setOnClickListener {
            openFilePicker()
        }

        binding.btnChangePhoto.setOnClickListener {
            showPhotoSourceDialog()
        }

        binding.btnRemovePhoto.setOnClickListener {
            clearPhoto()
        }

        // Chips rápidos de tipo de incidencia
        binding.chipPothole.setOnClickListener { selectCategory(getString(R.string.category_pothole)) }
        binding.chipLighting.setOnClickListener { selectCategory(getString(R.string.category_lighting)) }
        binding.chipGarbage.setOnClickListener { selectCategory(getString(R.string.category_garbage)) }
        binding.chipWater.setOnClickListener { selectCategory(getString(R.string.category_water_leak)) }
        binding.chipTree.setOnClickListener { selectCategory(getString(R.string.category_fallen_tree)) }
        binding.chipTraffic.setOnClickListener { selectCategory(getString(R.string.category_traffic_sign)) }
        binding.chipOther.setOnClickListener { selectCategory(getString(R.string.category_other)) }

        // Acciones de ubicación GPS en OpenStreetMap
        binding.btnDetectLocation.setOnClickListener {
            fetchRealGpsLocation(centerMap = true, updateIncidentPoint = true)
        }

        binding.fabMyLocation.setOnClickListener {
            fetchRealGpsLocation(centerMap = true, updateIncidentPoint = false)
        }

        // Envío del reporte ciudadano
        binding.btnSubmitReport.setOnClickListener {
            validateAndSubmitReport()
        }

        // Cierre de sesión
        binding.btnLogout.setOnClickListener {
            showLogoutDialog()
        }
    }

    private fun openFilePicker() {
        pickImageLauncher.launch("image/*")
    }

    private fun checkCameraPermissionAndLaunch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            val imagesFolder = File(cacheDir, "camera_photos")
            if (!imagesFolder.exists()) {
                imagesFolder.mkdirs()
            }
            val imageFile = File(imagesFolder, "incident_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                imageFile
            )
            tempCameraUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.msg_camera_error) + ": ${e.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showPhotoSourceDialog() {
        val options = arrayOf(
            getString(R.string.btn_take_photo),
            getString(R.string.btn_upload_file)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.photo_prompt_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkCameraPermissionAndLaunch()
                    1 -> openFilePicker()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun displaySelectedPhoto(uri: Uri) {
        selectedImageUri = uri
        binding.ivIncidentPhoto.setImageURI(uri)
        binding.layoutNoPhoto.visibility = View.GONE
        binding.layoutPhotoPreview.visibility = View.VISIBLE
    }

    private fun clearPhoto() {
        selectedImageUri = null
        tempCameraUri = null
        binding.ivIncidentPhoto.setImageDrawable(null)
        binding.layoutPhotoPreview.visibility = View.GONE
        binding.layoutNoPhoto.visibility = View.VISIBLE
    }

    private fun selectCategory(categoryName: String) {
        binding.actvIncidentCategory.setText(categoryName, false)
        syncSelectedChip(categoryName)
    }

    private fun syncSelectedChip(categoryName: String) {
        when (categoryName) {
            getString(R.string.category_pothole) -> binding.chipPothole.isChecked = true
            getString(R.string.category_lighting) -> binding.chipLighting.isChecked = true
            getString(R.string.category_garbage) -> binding.chipGarbage.isChecked = true
            getString(R.string.category_water_leak) -> binding.chipWater.isChecked = true
            getString(R.string.category_fallen_tree) -> binding.chipTree.isChecked = true
            getString(R.string.category_traffic_sign) -> binding.chipTraffic.isChecked = true
            getString(R.string.category_other) -> binding.chipOther.isChecked = true
            else -> binding.chipGroupCategories.clearCheck()
        }
    }

    private fun validateAndSubmitReport() {
        // 1. Validación de fotografía
        if (selectedImageUri == null) {
            Toast.makeText(this, getString(R.string.msg_photo_required), Toast.LENGTH_LONG).show()
            binding.homeScrollView.smoothScrollTo(0, binding.cardPhotoEvidence.top)
            return
        }

        // 2. Validación de categoría
        val selectedCategory = binding.actvIncidentCategory.text?.toString()?.trim().orEmpty()
        if (selectedCategory.isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_category_required), Toast.LENGTH_LONG).show()
            binding.tilIncidentCategory.error = getString(R.string.msg_category_required)
            binding.homeScrollView.smoothScrollTo(0, binding.cardIncidentCategory.top)
            return
        } else {
            binding.tilIncidentCategory.error = null
        }

        val description = binding.etDescription.text?.toString()?.trim().orEmpty()
        val coordinatesText = selectedIncidentPoint?.let {
            String.format(Locale.US, " (Lat: %.5f, Lon: %.5f)", it.latitude, it.longitude)
        } ?: ""

        val location = binding.etLocation.text?.toString()?.trim().orEmpty().ifEmpty {
            "Ubicación fijada en el mapa"
        } + coordinatesText

        val ticketNumber = Random.nextInt(10000, 99999)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val dateString = dateFormat.format(Date())

        val message = getString(R.string.msg_report_success_desc, ticketNumber) +
                "\n\n• Categoría: $selectedCategory" +
                "\n• Ubicación: $location" +
                "\n• Fecha: $dateString" +
                (if (description.isNotEmpty()) "\n• Detalle: $description" else "")

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.msg_report_success_title))
            .setMessage(message)
            .setIcon(R.drawable.ic_check_circle)
            .setPositiveButton(getString(R.string.dialog_btn_new_report)) { dialog, _ ->
                resetForm()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.dialog_btn_ok)) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun resetForm() {
        clearPhoto()
        binding.actvIncidentCategory.setText("", false)
        binding.chipGroupCategories.clearCheck()
        binding.tilIncidentCategory.error = null
        binding.etDescription.text?.clear()
        binding.etLocation.text?.clear()
        selectedIncidentPoint = null
        incidentMarker?.setVisible(false) // Ocultar marcador de forma segura
        binding.tvCoordinates.text = getString(R.string.coordinates_default)
        binding.mapView.invalidate()
        binding.homeScrollView.smoothScrollTo(0, 0)
    }

    private fun showLogoutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.btn_logout))
            .setMessage("¿Estás seguro de que deseas salir?")
            .setPositiveButton("Cerrar Sesión") { _, _ ->
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (hasLocationPermission()) {
            myLocationOverlay?.enableMyLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        myLocationOverlay?.disableMyLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.mapView.onDetach()
    }
}
