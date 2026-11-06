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
class WorkflowInstrumentedTest {
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
    @Test fun authorizationTraceFailedSendAndReceiptSurviveReopen() {
        val c = isolated()
        val users = UserStore(c)
        var id = ""
        try {
            assertTrue(users.register("A","a@demo.test","","password-test"))
            assertTrue(users.register("B","b@demo.test","","password-test"))
            assertTrue(users.login("a@demo.test","password-test"))
            assertFalse(users.login("a@demo.test","wrong"))
            admin(c,users)
            ReportStore.Database(c).use { db ->
                val service = ReportService(users,ReportStore(db))
                users.saveSession("a@demo.test")
                assertEquals(Role.CIUDADANO,users.sessionIdentity()!!.second)
                val report = report("a@demo.test"); id = report.id
                assertTrue(service.create(report))
                assertThrows(IllegalStateException::class.java) { service.transition(id,ReportState.EN_VALIDACION) }
                users.saveSession("b@demo.test")
                assertTrue(service.list().isEmpty())
                assertThrows(IllegalStateException::class.java) { service.detail(id) }
                users.saveSession("admin@demo.test")
                service.transition(id,ReportState.EN_VALIDACION)
                service.transition(id,ReportState.APROBADO)
                val institution = Institution("obras-demo","Obras DEMO",listOf(report.category))
                val failure = object : InstitutionGateway {
                    override fun institutions() = listOf(institution)
                    override fun send(report: IncidentReport, institution: String): Receipt = throw IOException("timeout after reception")
                }
                assertThrows(IOException::class.java) { service.send(id,institution,failure) }
                assertEquals(ReportState.APROBADO,service.detail(id).state)
                assertEquals(2,service.attempts(id).size)
                val success = object : InstitutionGateway {
                    override fun institutions() = listOf(institution)
                    override fun send(report: IncidentReport, institution: String) = Receipt("same-receipt","2026-11-06T12:00:00Z")
                }
                service.send(id,institution,success)
                assertEquals(ReportState.CANALIZADO,service.detail(id).state)
                assertThrows(IllegalStateException::class.java) { service.send(id,institution,success) }
                assertEquals(listOf("ENVIADO","EN_VALIDACION","APROBADO","CANALIZADO"),service.events(id).map { it.to })
                users.clearSession()
                assertThrows(IllegalStateException::class.java) { service.list() }
            }
            ReportStore.Database(c).use { db ->
                users.saveSession("a@demo.test")
                val service = ReportService(users,ReportStore(db))
                assertEquals("same-receipt",service.detail(id).receipt)
                assertEquals(ReportState.CANALIZADO,service.detail(id).state)
                assertEquals(4,service.events(id).size)
                users.saveSession("admin@demo.test")
                service.transition(id,ReportState.RESUELTO)
                assertEquals("same-receipt",service.detail(id).receipt)
            }
        } finally { cleanup(c) }
    }
    @Test fun migratesVersionOneWithoutDeletingReports() {
        val c = isolated()
        try {
            c.openOrCreateDatabase("georeport_reports.db",0,null).use { db ->
                db.execSQL("CREATE TABLE reports (id INTEGER PRIMARY KEY,payload TEXT NOT NULL,photo_path TEXT)")
                db.execSQL("CREATE TABLE migrations (name TEXT PRIMARY KEY)")
                db.execSQL("INSERT INTO migrations VALUES ('preferences')")
                db.execSQL("INSERT INTO reports(payload) VALUES (?)",arrayOf("""{"ticket":98765,"user_email":"a@demo.test"}"""))
                db.version = 1
            }
            var key = ""
            ReportStore.Database(c).use { db ->
                val report = ReportStore(db).getAll().single()
                assertEquals(98765,report.ticket); assertEquals("a@demo.test",report.userEmail)
                key = report.id; assertTrue(key.isNotBlank())
            }
            ReportStore.Database(c).use { db -> assertEquals(key,ReportStore(db).getAll().single().id) }
        } finally { cleanup(c) }
    }
}
