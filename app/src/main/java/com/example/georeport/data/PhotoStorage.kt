package com.example.georeport.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/** Evidence stays in private internal storage; external providers are read only. */
class PhotoStorage(context: Context) {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, "report_photos")

    fun resolve(path: String?): File? {
        if (path == null) return null
        val file = File(path).canonicalFile
        return file.takeIf { it.parentFile == directory.canonicalFile && it.isFile }
    }

    suspend fun import(uri: Uri): File {
        require(uri.scheme == "content") { "Unsupported image source" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Storage unavailable")
        val destination = File.createTempFile("photo_", ".image", directory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) throw IOException("Image exceeds 20 MiB")
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("Cannot read image")
            dimensions(destination) // Reject empty, malformed, or excessively large images.
            currentCoroutineContext().ensureActive()
            return destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    fun preview(file: File): Bitmap {
        val options = dimensions(file)
        options.inJustDecodeBounds = false
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1280) {
            options.inSampleSize *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("Invalid image")
    }

    private fun dimensions(file: File): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 ||
            options.outWidth.toLong() * options.outHeight > 100_000_000L) {
            throw IOException("Invalid image dimensions")
        }
        return options
    }

    companion object {
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    }
}
