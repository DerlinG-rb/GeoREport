package com.example.georeport

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.georeport.data.*
import com.example.georeport.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NearbyInstrumentedTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(): Context = object : ContextWrapper(base) {
        val prefix = "workflow_${UUID.randomUUID()}_"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix+name,mode)
        override fun getDatabasePath(name: String) = base.getDatabasePath(prefix+name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: android.database.DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
    }
    private fun admin(context: Context, users: UserStore) {
        assertTrue(users.register("Admin de prueba","admin@demo.test","","password-test"))
        val prefs = context.getSharedPreferences("georeport_users",Context.MODE_PRIVATE)
        val all = JSONObject(prefs.getString("users","{}")!!)
        all.getJSONObject("admin@demo.test").put("role","ADMINISTRADOR")
        prefs.edit().putString("users",all.toString()).commit()
    }
    private fun report(owner: String) = IncidentReport(12345,owner,"Bache / Vía en mal estado","DEMO","DEMO",-2.19,-79.88,null,System.currentTimeMillis())
    private fun cleanup(c: Context) {
        SQLiteDatabase.deleteDatabase(c.getDatabasePath("georeport_reports.db"))
        c.getSharedPreferences("georeport_users",Context.MODE_PRIVATE).edit().clear().commit()
        c.getSharedPreferences("georeport_reports",Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun approved(service: ReportService, users: UserStore, lat: Double? = 0.0, lon: Double? = 0.0): IncidentReport {
        users.saveSession("a@demo.test")
        val r = report("a@demo.test").copy(latitude=lat, longitude=lon, description="PRIVATE citizen@example.com", photoPath="private/photo.jpg")
        assertTrue(service.create(r))
        users.saveSession("admin@demo.test")
        service.transition(r.id,ReportState.EN_VALIDACION); service.transition(r.id,ReportState.APROBADO)
        return r
    }
    @Test fun realStorageFiltersStateConsentRadiusAndPrivateFields() {
        val c = isolated(); val users = UserStore(c)
        try {
            users.register("A","a@demo.test","","password-test")
            users.register("B","b@demo.test","","password-test"); admin(c,users)
            ReportStore.Database(c).use { db ->
                val service = ReportService(users,ReportStore(db))
                val near = approved(service,users,0.0,0.0005)
                val far = approved(service,users,0.0,0.006)
                val hidden = approved(service,users,0.0,0.0001)
                val invalid = approved(service,users,91.0,0.0)
                val missing = approved(service,users,null,null)
                assertFalse(service.visibility(hidden.id).enabled)
                assertThrows(IllegalArgumentException::class.java) { service.setSharedVisibility(invalid.id,true,"Public") }
                assertThrows(IllegalArgumentException::class.java) { service.setSharedVisibility(missing.id,true,"Public") }
                assertThrows(IllegalArgumentException::class.java) { service.setSharedVisibility(near.id,true," ") }
                service.setSharedVisibility(near.id,true,"Bache visible en la vía")
                service.setSharedVisibility(far.id,true,"Revisión vial")
                users.saveSession("b@demo.test")
                assertTrue(service.list().isEmpty())
                assertFalse(service.canReadPrivateDetail(near.id))
                assertThrows(IllegalStateException::class.java) { service.detail(near.id) }
                assertThrows(IllegalStateException::class.java) { service.setSharedVisibility(hidden.id,true,"Public") }
                assertThrows(IllegalStateException::class.java) { service.visibility(near.id) }
                val result = service.nearby(0.0,0.0,100)
                assertEquals(listOf(near.id),result.map { it.incident.id })
                assertEquals("Bache visible en la vía",result.single().incident.description)
                assertFalse(result.toString().contains("PRIVATE")); assertFalse(result.toString().contains("photo.jpg"))
                assertEquals(listOf(near.id),service.nearby(0.0,0.0,500).map { it.incident.id })
                assertEquals(listOf(near.id,far.id),service.nearby(0.0,0.0,1000).map { it.incident.id })
                assertTrue(service.nearby(10.0,10.0,1000).isEmpty())
                assertThrows(IllegalStateException::class.java) { service.sharedDetail(hidden.id) }
                users.saveSession("admin@demo.test")
                // Exercise the storage filter even if an obsolete visibility flag remains.
                for(state in listOf("ENVIADO","EN_VALIDACION","RECHAZADO","RESUELTO")) {
                    db.writableDatabase.execSQL("UPDATE workflow SET state=? WHERE report_key=?",arrayOf(state,near.id))
                    assertTrue(service.nearby(0.0,0.0,100).isEmpty())
                    assertThrows(IllegalStateException::class.java) { service.sharedDetail(near.id) }
                    assertThrows(IllegalArgumentException::class.java) { service.setSharedVisibility(near.id,true,"Public") }
                }
                db.writableDatabase.execSQL("UPDATE workflow SET state='CANALIZADO' WHERE report_key=?",arrayOf(near.id))
                assertEquals("CANALIZADO",service.nearby(0.0,0.0,100).single().incident.state)
                service.setSharedVisibility(near.id,false)
                assertTrue(service.nearby(0.0,0.0,100).isEmpty())
                assertThrows(IllegalStateException::class.java) { service.sharedDetail(near.id) }
                users.clearSession()
                assertThrows(IllegalStateException::class.java) { service.nearby(0.0,0.0,500) }
                assertThrows(IllegalStateException::class.java) { service.sharedDetail(far.id) }
            }
        } finally { cleanup(c) }
    }
    @Test fun sortedLimitDatelinePersistenceAndFailuresNeverReturnExamples() {
        val c = isolated(); val users = UserStore(c)
        try {
            users.register("A","a@demo.test","","password-test"); admin(c,users)
            var firstId = ""
            ReportStore.Database(c).use { db ->
                val service = ReportService(users,ReportStore(db))
                for(i in 25 downTo 1) {
                    val r = approved(service,users,0.0,i*0.00001)
                    service.setSharedVisibility(r.id,true,"Public $i")
                    if(i==1) firstId=r.id
                }
                val rows = service.nearby(0.0,0.0,100)
                assertEquals(20,rows.size); assertEquals(firstId,rows.first().incident.id)
                assertTrue(rows.zipWithNext().all { it.first.position.distance <= it.second.position.distance })
                val dateline = approved(service,users,0.0,-179.9999)
                service.setSharedVisibility(dateline.id,true,"Across dateline")
                assertEquals(dateline.id,service.nearby(0.0,179.9999,100).single().incident.id)
                assertThrows(IllegalArgumentException::class.java) { service.nearby(Double.NaN,0.0,100) }
                assertThrows(IllegalArgumentException::class.java) { service.nearby(0.0,0.0,200) }
            }
            ReportStore.Database(c).use { db ->
                val service=ReportService(users,ReportStore(db))
                assertEquals(firstId,service.nearby(0.0,0.0,100).first().incident.id)
                db.writableDatabase.execSQL("DROP TABLE shared_visibility")
                assertThrows(android.database.sqlite.SQLiteException::class.java) { service.nearby(0.0,0.0,100) }
            }
        } finally { cleanup(c) }
    }
    @Test fun migrationFromVersionTwoIndexesCoordinatesWithoutPublishingHistory() {
        val c=isolated(); val users=UserStore(c)
        try {
            users.register("A","a@demo.test","","password-test"); admin(c,users)
            c.openOrCreateDatabase("georeport_reports.db",0,null).use { db ->
                db.execSQL("CREATE TABLE reports (id INTEGER PRIMARY KEY,payload TEXT NOT NULL,photo_path TEXT,report_key TEXT)")
                db.execSQL("CREATE TABLE workflow (report_key TEXT PRIMARY KEY,state TEXT,institution TEXT,receipt TEXT,received_at TEXT)")
                db.execSQL("CREATE TABLE migrations (name TEXT PRIMARY KEY)")
                db.execSQL("INSERT INTO migrations VALUES ('preferences')")
                db.execSQL("INSERT INTO reports(payload,report_key) VALUES (?, 'old')",arrayOf("""{"ticket":123,"user_email":"a@demo.test","category":"Bache","latitude":0.0,"longitude":0.0,"created_at":1234}"""))
                db.execSQL("INSERT INTO workflow(report_key,state) VALUES ('old','APROBADO')")
                db.version=2
            }
            ReportStore.Database(c).use { db ->
                users.saveSession("admin@demo.test")
                val service=ReportService(users,ReportStore(db))
                assertTrue(service.nearby(0.0,0.0,100).isEmpty())
                assertFalse(service.visibility("old").enabled)
                service.setSharedVisibility("old",true,"Revisión pública")
                val r=service.nearby(0.0,0.0,100).single().incident
                assertEquals("old",r.id); assertFalse(r.locationConfirmed); assertEquals(1234L,r.createdAt)
            }
        } finally { cleanup(c) }
    }
}
