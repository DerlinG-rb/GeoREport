package com.example.georeport.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.example.georeport.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Institution(val id: String, val name: String, val categories: List<String>)
data class Receipt(val id: String, val at: String)

interface InstitutionGateway {
    fun institutions(): List<Institution>
    fun send(report: IncidentReport, institution: String): Receipt
}

/** Credential provided per operation by the development administrator, never persisted in APK. */
class InstitutionClient(private val context: Context, private val token: String) : InstitutionGateway {
    private fun request(path: String, body: JSONObject? = null, key: String? = null): String {
        require(token.isNotBlank()) { "Introduce la clave de integración de demostración" }
        val url = URL(BuildConfig.INSTITUTION_URL.trimEnd('/') + path)
        require(url.protocol == "https" || (BuildConfig.DEBUG && url.protocol == "http")) { "Se requiere HTTPS" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Idempotency-Key", key)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            check(status in 200..299) { "API de demostración: HTTP $status; verifica conexión, clave y campos" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    override fun institutions(): List<Institution> {
        val array = JSONArray(request("/institutions"))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index); val categories = item.getJSONArray("categories")
            Institution(item.getString("id"), item.getString("name"), (0 until categories.length()).map { categories.getString(it) })
        }
    }
    override fun send(report: IncidentReport, institution: String): Receipt {
        require(report.latitude != null && report.longitude != null) { "El reporte no tiene coordenadas confirmadas" }
        require(report.description.isNotBlank()) { "El reporte no tiene descripción" }
        val storage = PhotoStorage(context)
        val photo = checkNotNull(storage.resolve(report.photoPath)) { "Fotografía no disponible" }
        // Export a bounded JPEG without EXIF (no hidden GPS/device metadata). Original stays private.
        val bitmap = storage.preview(photo)
        val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
        bitmap.recycle()
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val payload = JSONObject().apply {
            put("report_id", report.id); put("category", report.category); put("description", report.description)
            put("location", JSONObject().put("latitude", report.latitude).put("longitude", report.longitude))
            put("created_at", date.format(Date(report.createdAt))); put("institution", institution)
            put("photo", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        val response = JSONObject(request("/reports", payload, "${report.id}:$institution"))
        val id = response.getString("receipt_id"); val at = response.getString("received_at")
        require(id.isNotBlank() && at.isNotBlank()) { "Comprobante incompleto" }
        return Receipt(id, at)
    }
}
