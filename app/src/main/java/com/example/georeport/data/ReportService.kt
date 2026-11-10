package com.example.georeport.data

import android.content.Context
import android.content.ContentValues
import com.example.georeport.domain.*

data class Tracking(val report: IncidentReport, val state: ReportState, val institution: String?, val receipt: String?, val receivedAt: String?)
data class ReportEvent(val from: String, val to: String, val at: Long, val actor: String, val note: String)

/** Local prototype authority. Replace this boundary with server authorization for deployment. */
class ReportService internal constructor(private val users: UserStore, private val store: ReportStore) {
    constructor(context: Context) : this(UserStore(context), ReportStore(context))
    private fun identity() = checkNotNull(users.sessionIdentity()) { "Inicia sesión nuevamente" }
    fun list(): List<Tracking> {
        val (email, role) = identity()
        return store.getAll().filter { ReportPolicy.canRead(role, email, it.userEmail) }.map { tracking(it) }
    }
    fun detail(id: String): Tracking = list().firstOrNull { it.report.id == id }
        ?: error("Reporte no disponible para esta sesión")
    private fun tracking(report: IncidentReport): Tracking = store.database.readableDatabase.rawQuery(
        "SELECT state,institution,receipt,received_at FROM workflow WHERE report_key=?", arrayOf(report.id)
    ).use { c ->
        if (!c.moveToFirst()) Tracking(report, ReportState.ENVIADO, null, null, null)
        else Tracking(report, ReportState.valueOf(c.getString(0)), c.getString(1), c.getString(2), c.getString(3))
    }
    fun create(report: IncidentReport): Boolean {
        val (email, role) = identity()
        check(role == Role.CIUDADANO && report.userEmail == email) { "No autorizado" }
        return store.save(report)
    }
    fun events(id: String): List<ReportEvent> {
        val report = detail(id).report
        return listOf(ReportEvent("—", "ENVIADO", report.createdAt, report.userEmail, "Reporte ciudadano recibido")) +
            store.database.readableDatabase.rawQuery("SELECT old_state,new_state,at,actor,note FROM events WHERE report_key=? ORDER BY id", arrayOf(id)).use { c ->
                buildList { while(c.moveToNext()) add(ReportEvent(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4))) }
            }
    }
    fun transition(id: String, next: ReportState, note: String = "") {
        require(next != ReportState.CANALIZADO) { "Utiliza el envío confirmado por la API" }
        change(id, next, note)
    }
    private fun change(id: String, next: ReportState, note: String, institution: String? = null, receipt: String? = null, receivedAt: String? = null) {
        val db = store.database.writableDatabase
        db.beginTransaction()
        try {
            val (email, role) = identity()
            val old = detail(id)
            ReportPolicy.transition(role, old.state, next, note, receipt)
            check(db.insertWithOnConflict("workflow", null, ContentValues().apply {
                put("report_key", id); put("state", next.name)
                put("institution", institution ?: old.institution); put("receipt", receipt ?: old.receipt)
                put("received_at", receivedAt ?: old.receivedAt)
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "No se pudo guardar el estado" }
            db.insertOrThrow("events", null, ContentValues().apply {
                put("report_key", id); put("old_state", old.state.name); put("new_state", next.name)
                put("at", System.currentTimeMillis()); put("actor", email); put("note", note.trim())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun institutions(client: InstitutionGateway): List<Institution> {
        check(identity().second == Role.ADMINISTRADOR) { "No autorizado" }
        return client.institutions()
    }
    fun send(id: String, institution: Institution, client: InstitutionGateway) = synchronized(sendLock) {
        check(identity().second == Role.ADMINISTRADOR) { "No autorizado" }
        val report = detail(id)
        check(report.state == ReportState.APROBADO) { "Solo se envían reportes aprobados" }
        require(report.report.category in institution.categories) { "Institución incompatible" }
        // Pin destination before network IO: a lost response may already have committed remotely.
        val db = store.database.writableDatabase
        val pinned = db.rawQuery("SELECT institution FROM attempts WHERE report_key=? LIMIT 1", arrayOf(id)).use { if(it.moveToFirst()) it.getString(0) else null }
        require(pinned == null || pinned == institution.id) { "Reintenta con el destinatario original: $pinned" }
        attempt(id, institution.id, "INICIADO")
        try {
            val receipt = client.send(report.report, institution.id)
            change(id, ReportState.CANALIZADO, "Recepción confirmada por API simulada", institution.name, receipt.id, receipt.at)
            attempt(id, institution.id, "RECIBIDO ${receipt.id}")
        } catch (e: Exception) {
            attempt(id, institution.id, "ERROR ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            throw e
        }
    }
    private fun attempt(id: String, institution: String, result: String) {
        store.database.writableDatabase.insertOrThrow("attempts", null, ContentValues().apply {
            put("report_key", id); put("institution", institution); put("at", System.currentTimeMillis()); put("result", result)
        })
    }
    fun attempts(id: String): List<String> {
        check(identity().second == Role.ADMINISTRADOR)
        detail(id)
        return store.database.readableDatabase.rawQuery("SELECT at,institution,result FROM attempts WHERE report_key=? ORDER BY id", arrayOf(id)).use { c ->
            buildList { while(c.moveToNext()) add("${java.util.Date(c.getLong(0))} · ${c.getString(1)} · ${c.getString(2)}") }
        }
    }
    fun nearby(latitude: Double, longitude: Double, radius: Int): List<NearbyIncident> {
        val (email, _) = identity()
        require(GeoProjection.validCoordinates(latitude, longitude)) { "Ubicación no válida" }
        require(radius in listOf(100, 500, 1000)) { "Radio no válido" }
        val shared = NearbySource(store).nearby(latitude, longitude, radius)
        val own = ownApprovedIncidents(email).map {
            NearbyIncident(it, GeoProjection.relative(latitude, longitude, it.latitude, it.longitude))
        }.filter { it.position.distance <= radius }
        return (own + shared).distinctBy { it.incident.id }
            .sortedWith(compareBy<NearbyIncident> { it.position.distance }.thenBy { it.incident.id }).take(20)
    }
    private fun ownApprovedIncidents(email: String): List<PublicIncident> = store.getAll()
        .filter { it.userEmail == email }
        .mapNotNull { report ->
            val state = tracking(report).state
            val latitude = report.latitude ?: return@mapNotNull null
            val longitude = report.longitude ?: return@mapNotNull null
            if (state !in listOf(ReportState.APROBADO, ReportState.CANALIZADO) ||
                !GeoProjection.validCoordinates(latitude, longitude)) return@mapNotNull null
            PublicIncident(report.id, report.category, latitude, longitude, state.name,
                report.description.ifBlank { "Sin descripción adicional." }, report.createdAt,
                report.locationConfirmed, isOwn = true)
        }

    /** Recheck ownership and approval when opening a nearby marker. */
    fun nearbyDetail(id: String): PublicIncident {
        val (email, _) = identity()
        return ownApprovedIncidents(email).firstOrNull { it.id == id }
            ?: NearbySource(store).detail(id)
    }
    fun sharedDetail(id: String): PublicIncident {
        identity()
        return NearbySource(store).detail(id)
    }
    fun canReadPrivateDetail(id: String): Boolean {
        val (email, role) = identity()
        return store.database.readableDatabase.rawQuery("SELECT payload FROM reports WHERE report_key=?", arrayOf(id)).use { c ->
            c.moveToFirst() && ReportPolicy.canRead(role, email, org.json.JSONObject(c.getString(0)).optString("user_email"))
        }
    }
    fun visibility(id: String): SharedVisibility {
        check(identity().second == Role.ADMINISTRADOR) { "No autorizado" }
        detail(id)
        return store.database.readableDatabase.rawQuery("SELECT enabled,public_description FROM shared_visibility WHERE report_key=?", arrayOf(id)).use { c ->
            if(c.moveToFirst()) SharedVisibility(c.getInt(0)==1,c.getString(1)) else SharedVisibility(false, "")
        }
    }
    fun setSharedVisibility(id: String, enabled: Boolean, publicDescription: String = "") {
        val db = store.database.writableDatabase
        db.beginTransaction()
        try {
            check(identity().second == Role.ADMINISTRADOR) { "No autorizado" }
            val row = detail(id)
            if(enabled) {
                require(row.state in listOf(ReportState.APROBADO, ReportState.CANALIZADO)) { "Solo se comparten reportes aprobados o canalizados" }
                require(GeoProjection.validCoordinates(row.report.latitude ?: Double.NaN, row.report.longitude ?: Double.NaN)) { "El reporte no tiene coordenadas válidas" }
                require(publicDescription.trim().length in 1..1000) { "Escribe una descripción pública de hasta 1000 caracteres" }
            }
            db.insertWithOnConflict("shared_visibility", null, ContentValues().apply {
                put("report_key", id); put("enabled", if(enabled) 1 else 0)
                put("public_description", if(enabled) publicDescription.trim() else "")
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    companion object { private val sendLock = Any() }
}
