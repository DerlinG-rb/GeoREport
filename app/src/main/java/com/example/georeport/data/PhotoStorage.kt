package com.example.georeport.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
        val destination = File.createTempFile("photo_", ".pending", directory)
        var completed: File? = null
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
            val metadata = dimensions(destination)
            currentCoroutineContext().ensureActive()
            val extension = when (metadata.outMimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/heif", "image/heic" -> "heic"
                else -> "image"
            }
            val target = File(directory, "${destination.nameWithoutExtension}.$extension")
            if (!destination.renameTo(target)) throw IOException("Cannot finalize image")
            completed = target
            currentCoroutineContext().ensureActive()
            return target
        } catch (error: Throwable) {
            destination.delete()
            completed?.delete()
            throw error
        }
    }

    fun preview(file: File): Bitmap {
        val options = dimensions(file)
        options.inJustDecodeBounds = false
        // BitmapFactory defaults to zero; our sampling arithmetic requires one.
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) > 1280 * options.inSampleSize) {
            options.inSampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("Invalid image")
        // Preserve the original evidence bytes; orient only the bounded preview.
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
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
