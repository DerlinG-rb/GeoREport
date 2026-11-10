package com.example.georeport.data

import com.example.georeport.domain.GeoProjection

/** Public summary, or an owner's summary authorized by ReportService. No owner identity or photo. */
data class PublicIncident(
    val id: String, val category: String, val latitude: Double, val longitude: Double,
    val state: String, val description: String, val createdAt: Long, val locationConfirmed: Boolean,
    val isOwn: Boolean = false
)
data class NearbyIncident(val incident: PublicIncident, val position: GeoProjection.Position)
data class SharedVisibility(val enabled: Boolean, val description: String)

/** SQLite bounding-box query. The application service authenticates before entering this boundary. */
internal class NearbySource(private val store: ReportStore) {
    private val projection = """
        SELECT l.report_key,l.category,l.latitude,l.longitude,w.state,v.public_description,l.created_at,l.confirmed
        FROM report_locations l JOIN workflow w ON w.report_key=l.report_key
        JOIN shared_visibility v ON v.report_key=l.report_key
        WHERE v.enabled=1 AND w.state IN ('APROBADO','CANALIZADO')
        AND length(trim(v.public_description))>0
    """.trimIndent()
    private fun read(where: String, args: Array<String>): List<PublicIncident> =
        store.database.readableDatabase.rawQuery(projection + where, args).use { c ->
            buildList {
                while(c.moveToNext()) {
                    val lat = c.getDouble(2); val lon = c.getDouble(3)
                    if(GeoProjection.validCoordinates(lat, lon)) add(PublicIncident(
                        c.getString(0),c.getString(1),lat,lon,c.getString(4),c.getString(5),c.getLong(6),c.getInt(7)==1
                    ))
                }
            }
        }
    fun nearby(lat: Double, lon: Double, radius: Int): List<NearbyIncident> {
        require(radius in listOf(100, 500, 1000)) { "Radio no válido" }
        val b = GeoProjection.bounds(lat, lon, radius)
        val longitude = if(b.west <= b.east) "l.longitude BETWEEN ? AND ?" else "(l.longitude >= ? OR l.longitude <= ?)"
        return read(" AND l.latitude BETWEEN ? AND ? AND $longitude", arrayOf(b.south.toString(),b.north.toString(),b.west.toString(),b.east.toString()))
            .map { NearbyIncident(it, GeoProjection.relative(lat, lon, it.latitude, it.longitude)) }
            .filter { it.position.distance <= radius }.sortedWith(compareBy<NearbyIncident> { it.position.distance }.thenBy { it.incident.id }).take(20)
    }
    fun detail(id: String): PublicIncident = read(" AND l.report_key=?", arrayOf(id)).singleOrNull()
        ?: error("La incidencia ya no está disponible para consulta compartida")
}
