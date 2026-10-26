package com.example.georeport

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.georeport.data.IncidentReport
import com.example.georeport.data.PhotoStorage
import com.example.georeport.data.ReportStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StorageInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolatedContext(): Context = object : ContextWrapper(context) {
        private val prefix = "storage_test_${UUID.randomUUID()}_"
        override fun getDatabasePath(name: String): File = context.getDatabasePath(prefix + name)
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?
        ): android.database.sqlite.SQLiteDatabase =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,
            errorHandler: android.database.DatabaseErrorHandler?
        ): android.database.sqlite.SQLiteDatabase =
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)

        override fun getSharedPreferences(name: String, mode: Int) =
            context.getSharedPreferences(prefix + name, mode)
    }

    @Test
    fun migrationIsDurableAndRunsOnlyOnce() {
        val isolated = isolatedContext()
        val prefs = isolated.getSharedPreferences("georeport_reports", Context.MODE_PRIVATE)
        prefs.edit().putString("reports", """[{"ticket":12345,"photo_path":"/private/evidence.jpg"}]""").commit()
        try {
            ReportStore.Database(isolated).use { database ->
                val store = ReportStore(database)
                assertEquals(1, store.count())
                assertTrue(store.usesPhoto("/private/evidence.jpg"))
                assertFalse(prefs.contains("reports"))
            }
            ReportStore.Database(isolated).use { database ->
                assertEquals(1, ReportStore(database).count())
                assertEquals(12345, ReportStore(database).getAll().single().ticket)
            }
        } finally {
            cleanupDatabase(isolated)
        }
    }

    @Test
    fun corruptMigrationDoesNotDiscardLegacyData() {
        val isolated = isolatedContext()
        val prefs = isolated.getSharedPreferences("georeport_reports", Context.MODE_PRIVATE)
        prefs.edit().putString("reports", "broken-json").commit()
        try {
            ReportStore.Database(isolated).use { database ->
                assertFalse(ReportStore(database).save(report(1)))
                assertEquals("broken-json", prefs.getString("reports", null))
            }
        } finally {
            cleanupDatabase(isolated)
        }
    }

    @Test
    fun concurrentSavesDoNotLoseReports() {
        val isolated = isolatedContext()
        try {
            ReportStore.Database(isolated).use { database ->
                val store = ReportStore(database)
                val workers = Executors.newFixedThreadPool(4)
                try {
                    val results = (1..40).map { number -> workers.submit<Boolean> { store.save(report(number)) } }
                    results.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
                    assertEquals(40, store.count())
                } finally {
                    workers.shutdownNow()
                }
            }
        } finally {
            cleanupDatabase(isolated)
        }
    }

    @Test
    fun externalContentIsCopiedPrivatelyAndPreviewIsBounded() = runBlocking {
        val camera = File(context.cacheDir, "camera_photos").apply { mkdirs() }
        val source = File.createTempFile("test_", ".png", camera)
        var imported: File? = null
        try {
            val bitmap = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val storage = PhotoStorage(context)
            imported = storage.import(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source))
            assertEquals(File(context.filesDir, "report_photos"), imported.parentFile)
            val preview = storage.preview(imported)
            assertTrue(maxOf(preview.width, preview.height) <= 1280)
            preview.recycle()
            assertNull(storage.resolve(source.absolutePath))
            assertNull(storage.resolve(File(context.filesDir, "report_photos/../private.txt").path))
        } finally {
            source.delete()
            imported?.delete()
        }
    }

    @Test
    fun invalidAndOversizedImportsLeaveNoPartialFile() = runBlocking {
        val camera = File(context.cacheDir, "camera_photos").apply { mkdirs() }
        val source = File.createTempFile("test_", ".png", camera)
        val photos = File(context.filesDir, "report_photos")
        val before = photos.list()?.toSet().orEmpty()
        try {
            val storage = PhotoStorage(context)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            source.writeText("not an image")
            try {
                storage.import(uri)
                fail("Malformed image accepted")
            } catch (_: java.io.IOException) {
                assertEquals(before, photos.list()?.toSet().orEmpty())
            }
            java.io.RandomAccessFile(source, "rw").use { it.setLength(PhotoStorage.MAX_IMAGE_BYTES + 1) }
            try {
                storage.import(uri)
                fail("Oversized image accepted")
            } catch (_: java.io.IOException) {
                assertEquals(before, photos.list()?.toSet().orEmpty())
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun fileProviderCannotExposeOtherCacheFiles() {
        val file = File.createTempFile("private_", ".txt", context.cacheDir)
        try {
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                fail("Unrelated cache files must not be shared")
            } catch (_: IllegalArgumentException) {
                // Expected: only camera_photos/ is exposed.
            }
        } finally {
            file.delete()
        }
    }

    private fun report(ticket: Int) = IncidentReport(
        ticket, "test@example.com", "Test", "", "", null, null, null, 1L
    )

    private fun cleanupDatabase(isolated: Context) {
        android.database.sqlite.SQLiteDatabase.deleteDatabase(isolated.getDatabasePath("georeport_reports.db"))
        isolated.getSharedPreferences("georeport_reports", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
