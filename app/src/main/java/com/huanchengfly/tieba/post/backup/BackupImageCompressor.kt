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

    /**
     * 下载语音原样存档（不压缩）。
     *
     * Baidu 对失效 md5 一样回 200 + 空 body + text/html，只看 isSuccessful 会落盘一个
     * 0 字节"语音文件"，而离线渲染优先用本地文件 → 点了必哑。所以这里校验响应体确实是
     * 音频再落盘，并按嗅探到的格式补扩展名（ExoPlayer 对无扩展名 file:// 只能靠内容嗅探）。
     * 全部候选地址都拿不到音频时返回 null，交给在线流兜底。
     */
    suspend fun downloadVoice(urls: List<String>, destFile: File): File? = withContext(Dispatchers.IO) {
        for (url in urls) {
            val body = fetchAudio(url) ?: continue
            val extension = sniffAudioExtension(body) ?: continue
            val file = File(destFile.parentFile, destFile.name + extension)
            val written = runCatching {
                destFile.parentFile?.mkdirs()
                file.writeBytes(body)
                file
            }.onFailure { e -> Log.w(TAG, "voice write fail ${file.absolutePath}", e) }.isSuccess
            if (written) return@withContext file
        }
        null
    }

    private suspend fun fetchAudio(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                if (!response.isSuccessful ||
                    contentType.contains("html", true) ||
                    contentType.contains("json", true)
                ) {
                    Log.w(TAG, "voice http reject ${response.code} $contentType $url")
                    return@withContext null
                }
                response.body?.bytes()?.takeIf { it.size >= MIN_AUDIO_BYTES }
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice download fail $url", e)
            null
        }
    }

    /** 只认音频容器的文件头；空页面、错误 JSON、被劫持的 HTML 一律返回 null */
    private fun sniffAudioExtension(body: ByteArray): String? {
        fun magic(offset: Int, vararg bytes: Int): Boolean {
            if (body.size < offset + bytes.size) return false
            return bytes.indices.all { body[offset + it].toInt() and 0xFF == bytes[it] }
        }

        return when {
            magic(0, 0x49, 0x44, 0x33) -> ".mp3"                       // ID3
            magic(0, 0x66, 0x4C, 0x61, 0x43) -> ".flac"                 // fLaC
            magic(0, 0x4F, 0x67, 0x67, 0x53) -> ".ogg"                  // OggS
            magic(0, 0x23, 0x21, 0x41, 0x4D, 0x52) -> ".amr"            // #!AMR
            magic(0, 0x52, 0x49, 0x46, 0x46) &&
                magic(8, 0x57, 0x41, 0x56, 0x45) -> ".wav"              // RIFF....WAVE
            magic(4, 0x66, 0x74, 0x79, 0x70) &&                         // ftyp box
                magic(8, 0x41, 0x41, 0x4D, 0x46) -> ".aac"
            magic(4, 0x66, 0x74, 0x79, 0x70) -> ".m4a"
            magic(0, 0xFF) && (body[1].toInt() and 0xF6) == 0xF0 -> ".aac" // ADTS frame sync
            else -> null
        }
    }

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

        /** 小于此值的一律当作错误页/空响应，不当音频存档 */
        private const val MIN_AUDIO_BYTES = 512
    }
}
