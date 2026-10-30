package com.example.georeport.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class IncidentReport(
    val ticket: Int,
    val userEmail: String,
    val category: String,
    val description: String,
    val location: String,
    val latitude: Double?,
    val longitude: Double?,
    val photoPath: String?,
    val createdAt: Long
)

/**
 * Base de datos SQLite local para reportes; las cuentas de acceso se guardan en UserStore.
 * Cada reporte conserva su dirección, latitud y longitud dentro de un JSON.
 * Ejecutar las operaciones de disco en Dispatchers.IO para no bloquear la interfaz.
 */
class ReportStore internal constructor(private val database: Database) {
    constructor(context: Context) : this(database(context.applicationContext))

    // Inserta un reporte completo y su ruta de foto; devuelve false si falla la escritura.
    fun save(report: IncidentReport): Boolean = try {
        database.writableDatabase.insertOrThrow("reports", null, ContentValues().apply {
            put("payload", report.toJson().toString())
            put("photo_path", report.photoPath)
        }) != -1L
    } catch (_: Exception) {
        false
    }

    // Lee los reportes por id y transforma cada JSON en un IncidentReport; use cierra el cursor.
    fun getAll(): List<IncidentReport> =
        database.readableDatabase.rawQuery("SELECT payload FROM reports ORDER BY id", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(JSONObject(cursor.getString(0)).toIncidentReport())
            }
        }

    // Cuenta los reportes sin cargar todo su contenido en memoria.
    fun count(): Int = database.readableDatabase.rawQuery("SELECT COUNT(*) FROM reports", null).use {
        it.moveToFirst()
        it.getInt(0)
    }

    // Comprueba si una foto sigue vinculada a algún reporte; ? recibe la ruta como parámetro.
    fun usesPhoto(path: String): Boolean = database.readableDatabase.rawQuery(
        "SELECT 1 FROM reports WHERE photo_path = ? LIMIT 1", arrayOf(path)
    ).use { it.moveToFirst() }

    // SQLiteOpenHelper crea y abre georeport_reports.db, actualmente con esquema versión 1.
    internal class Database(private val context: Context) :
        SQLiteOpenHelper(context, "georeport_reports.db", null, 1) {
        // Crea la tabla de reportes, un índice para buscar fotos y el registro de migraciones.
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE reports (id INTEGER PRIMARY KEY, payload TEXT NOT NULL, photo_path TEXT)")
            db.execSQL("CREATE INDEX reports_photo ON reports(photo_path)")
            db.execSQL("CREATE TABLE migrations (name TEXT PRIMARY KEY)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

        // Importa una sola vez los reportes de versiones que usaban SharedPreferences.
        // La transacción confirma toda la importación o la revierte si ocurre un error.
        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // A corrupt legacy file fails closed: never overwrite it with an empty collection.
            db.beginTransaction()
            try {
                val migrated = db.rawQuery("SELECT 1 FROM migrations WHERE name = 'preferences'", null)
                    .use { it.moveToFirst() }
                if (!migrated) {
                    val reports = JSONArray(prefs.getString(KEY_REPORTS, null) ?: "[]")
                    for (index in 0 until reports.length()) {
                        val report = reports.getJSONObject(index)
                        db.insertOrThrow("reports", null, ContentValues().apply {
                            put("payload", report.toString())
                            put("photo_path", report.optString(KEY_PHOTO_PATH).takeIf { it.isNotBlank() })
                        })
                    }
                    db.execSQL("INSERT INTO migrations(name) VALUES ('preferences')")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            // Only remove the old copy after the transaction is durable.
            if (prefs.contains(KEY_REPORTS)) prefs.edit().remove(KEY_REPORTS).commit()
        }
    }

    // Serializa los datos del reporte para guardarlos en la columna payload.
    private fun IncidentReport.toJson(): JSONObject = JSONObject().apply {
        put(KEY_TICKET, ticket)
        put(KEY_USER_EMAIL, userEmail)
        put(KEY_CATEGORY, category)
        put(KEY_DESCRIPTION, description)
        put(KEY_LOCATION, location)
        latitude?.let { put(KEY_LATITUDE, it) }
        longitude?.let { put(KEY_LONGITUDE, it) }
        photoPath?.let { put(KEY_PHOTO_PATH, it) }
        put(KEY_CREATED_AT, createdAt)
    }

    // Reconstruye el reporte al leerlo; coordenadas y foto pueden no estar presentes.
    private fun JSONObject.toIncidentReport(): IncidentReport = IncidentReport(
        ticket = optInt(KEY_TICKET),
        userEmail = optString(KEY_USER_EMAIL),
        category = optString(KEY_CATEGORY),
        description = optString(KEY_DESCRIPTION),
        location = optString(KEY_LOCATION),
        latitude = if (has(KEY_LATITUDE)) optDouble(KEY_LATITUDE) else null,
        longitude = if (has(KEY_LONGITUDE)) optDouble(KEY_LONGITUDE) else null,
        photoPath = optString(KEY_PHOTO_PATH).takeIf { it.isNotBlank() },
        createdAt = optLong(KEY_CREATED_AT)
    )

    private companion object {
        // The production constructor passes only applicationContext, never an Activity.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: Database? = null
        fun database(context: Context): Database = instance ?: synchronized(this) {
            instance ?: Database(context).also { instance = it }
        }

        const val PREFS_NAME = "georeport_reports"
        const val KEY_REPORTS = "reports"
        const val KEY_TICKET = "ticket"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_CATEGORY = "category"
        const val KEY_DESCRIPTION = "description"
        const val KEY_LOCATION = "location"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_PHOTO_PATH = "photo_path"
        const val KEY_CREATED_AT = "created_at"
    }
}
