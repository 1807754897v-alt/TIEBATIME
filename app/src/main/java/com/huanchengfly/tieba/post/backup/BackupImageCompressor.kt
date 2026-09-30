package com.huanchengfly.tieba.post.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads and compresses backup images.
 * Default policy favors smaller files for long novel-like threads.
 */
@Singleton
class BackupImageCompressor @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    data class CompressedImage(
        val file: File,
        val width: Int?,
        val height: Int?,
        val bytes: Long,
        val compressed: Boolean,
        val policy: String,
    )

    suspend fun downloadAndCompress(
        url: String,
        destDir: File,
        fileName: String,
        policy: LocalBackupPrefs.ImagePolicy,
    ): CompressedImage? = withContext(Dispatchers.IO) {
        if (policy == LocalBackupPrefs.ImagePolicy.NONE) return@withContext null
        if (url.isBlank()) return@withContext null
        val safeName = sanitizeFileName(fileName)
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "image http fail ${response.code} $url")
                    return@withContext null
                }
                val body = response.body?.bytes() ?: return@withContext null
                when (policy) {
                    LocalBackupPrefs.ImagePolicy.ORIGINAL -> {
                        val file = File(destDir, safeName)
                        file.writeBytes(body)
                        val (w, h) = decodeSize(body)
                        CompressedImage(
                            file = file,
                            width = w,
                            height = h,
                            bytes = file.length(),
                            compressed = false,
                            policy = policy.name,
                        )
                    }
                    else -> {
                        val bitmap = BitmapFactory.decodeByteArray(body, 0, body.size)
                            ?: return@withContext null
                        if (bitmap.isGifLike(body)) {
                            // Keep animated/webp/gif bytes as-is to avoid breaking frames
                            val file = File(destDir, safeName)
                            file.writeBytes(body)
                            bitmap.recycle()
                            return@withContext CompressedImage(
                                file = file,
                                width = null,
                                height = null,
                                bytes = file.length(),
                                compressed = false,
                                policy = policy.name,
                            )
                        }
                        val result = compress(bitmap, destDir, safeName, policy)
                        bitmap.recycle()
                        result
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "image download/compress failed: $url", e)
            null
        }
    }

    private fun compress(
        bitmap: Bitmap,
        destDir: File,
        fileName: String,
        policy: LocalBackupPrefs.ImagePolicy,
    ): CompressedImage? {
        val maxWidth = when (policy) {
            LocalBackupPrefs.ImagePolicy.WEBP_TINY -> 960
            LocalBackupPrefs.ImagePolicy.WEBP_SMALL -> 1280
            else -> bitmap.width
        }
        var target = bitmap
        if (bitmap.width > maxWidth) {
            val scale = maxWidth.toFloat() / bitmap.width
            val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
            target = Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
        }
        val quality = if (policy == LocalBackupPrefs.ImagePolicy.WEBP_TINY) 70 else 80
        val out = ByteArrayOutputStream()
        // Prefer WebP when available; fall back to JPEG.
        val ok = try {
            target.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
        } catch (_: Throwable) {
            target.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        if (!ok) return null
        val ext = if (out.size() < target.byteCount && fileName.endsWith(".png", true)) {
            // Detect encoded format by trying WebP magic
            if (out.toByteArray().isWebp()) "webp" else "jpg"
        } else if (out.toByteArray().isWebp()) {
            "webp"
        } else {
            "jpg"
        }
        val base = fileName.substringBeforeLast('.')
        val file = File(destDir, "$base.$ext")
        file.writeBytes(out.toByteArray())
        if (target !== bitmap) target.recycle()
        return CompressedImage(
            file = file,
            width = target.width,
            height = target.height,
            bytes = file.length(),
            compressed = true,
            policy = policy.name,
        )
    }

    private fun decodeSize(bytes: ByteArray): Pair<Int?, Int?> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }

    private fun ByteArray.isWebp(): Boolean {
        if (size < 12) return false
        // RIFF....WEBP
        return this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
            this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
            this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte()
    }

    private fun Bitmap.isGifLike(body: ByteArray): Boolean {
        if (body.size > 6 && body[0] == 'G'.code.toByte() && body[1] == 'I'.code.toByte()) return true
        return false
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[<>:\"/\\\\|?*]"), "_").ifBlank { "image.jpg" }

    companion object {
        private const val TAG = "BackupImageCompressor"
    }
}
