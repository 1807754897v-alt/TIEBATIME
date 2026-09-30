package com.huanchengfly.tieba.post.backup

import android.graphics.BitmapFactory
import android.util.Log
import com.huanchengfly.tieba.post.models.database.LocalBackupFloor
import com.huanchengfly.tieba.post.models.database.LocalBackupImage
import com.huanchengfly.tieba.post.models.database.LocalBackupPost
import com.huanchengfly.tieba.post.models.database.dao.LocalBackupDao
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports a TiebaShelf export package (zip of the data folder: export.json,
 * markdowns/, posts/, images/) back into the local backup index.
 *
 * Accepts export.json at the zip root or inside one common top-level folder
 * (TiebaShelf zips its data/ directory).
 */
@Singleton
class BackupImporter @Inject constructor(
    private val localBackupDao: LocalBackupDao,
    private val backupPaths: BackupPaths,
) {
    data class ImportResult(
        val imported: Int,
        val titles: List<String>,
    )

    private val parseFormats = listOf(
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA),
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA),
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA),
    )

    suspend fun importFromStream(input: InputStream, maxPosts: Int = 500): ImportResult {
        val tempDir = File(backupPaths.root, "import_tmp").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val files = unzipTo(input, tempDir)
            val exportFile = files["export.json"]
                ?: throw IllegalArgumentException("压缩包里没有找到 export.json")
            val export = JSONObject(exportFile.readText(Charsets.UTF_8))
            val posts = export.optJSONArray("posts")
                ?: throw IllegalArgumentException("export.json 缺少 posts 字段")

            var imported = 0
            val titles = mutableListOf<String>()
            for (i in 0 until posts.length().coerceAtMost(maxPosts)) {
                val item = posts.optJSONObject(i) ?: continue
                runCatching { importPost(item, files) }
                    .onSuccess {
                        imported++
                        it?.let(titles::add)
                    }
                    .onFailure { Log.w(TAG, "import post #$i failed", it) }
            }
            return ImportResult(imported, titles)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** @return display title when imported, null when the entry was unusable */
    private suspend fun importPost(item: JSONObject, files: Map<String, File>): String? {
        val threadId = item.optString("post_id").toLongOrNull() ?: return null
        val seeLz = item.optBoolean("see_lz", false)
        val url = item.optString("url", "https://tieba.baidu.com/p/$threadId")
        val rawTitle = item.optString("title").ifBlank {
            item.optString("display_name").removeSuffix("(完整版)").removeSuffix("(只看楼主)")
                .removeSuffix(" (完整版)").removeSuffix(" (只看楼主)")
        }
        val exportKey = BackupPaths.exportKey(threadId, seeLz)
        val backupId = exportKey
        val floorsFile = files[item.optString("file_path").trim('/')] ?: return null
        val floorsJson = JSONObject(floorsFile.readText(Charsets.UTF_8))
        val floorsArray = floorsJson.optJSONArray("floors") ?: return null
        val jsonImagesDir = item.optString("images_dir").trim('/')

        val floors = mutableListOf<LocalBackupFloor>()
        val images = mutableListOf<LocalBackupImage>()
        val imageDir = backupPaths.imageDir(exportKey)

        for (i in 0 until floorsArray.length()) {
            val f = floorsArray.optJSONObject(i) ?: continue
            val floorNumber = f.optInt("floor_number", i + 1)
            val content = f.optString("content", "")
            val isLz = f.optBoolean("is_lz", false)
            val author = f.optString("author", "")
            val postTime = parseTime(f.optString("post_time", ""))
            val ipLocation = f.optString("ip_location", "").takeIf { it.isNotBlank() }

            val urls = mutableListOf<String>()
            val imgs = f.optJSONArray("images")
            for (j in 0 until (imgs?.length() ?: 0)) {
                val u = imgs!!.optString(j, "")
                if (u.isNotBlank()) urls.add(u)
            }

            floors.add(
                LocalBackupFloor(
                    backupId = backupId,
                    threadId = threadId,
                    postId = floorNumber.toLong(),
                    floorNumber = floorNumber,
                    authorId = null,
                    authorName = author.takeIf { it.isNotBlank() },
                    postTime = postTime,
                    ipLocation = ipLocation,
                    content = content,
                    isLz = isLz,
                    imageUrls = urls.joinToString("\n"),
                )
            )

            // copy image files from the package when present
            urls.forEach { url ->
                val name = url.substringAfterLast('/')
                val src = files["$jsonImagesDir/$name"]
                if (src != null && src.exists()) {
                    val dest = File(imageDir, name)
                    if (!dest.exists() || dest.length() != src.length()) {
                        src.copyTo(dest, overwrite = true)
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(dest.absolutePath, bounds)
                    images.add(
                        LocalBackupImage(
                            backupId = backupId,
                            threadId = threadId,
                            floorNumber = floorNumber,
                            originalUrl = url,
                            localPath = dest.absolutePath,
                            width = bounds.outWidth.takeIf { it > 0 },
                            height = bounds.outHeight.takeIf { it > 0 },
                            bytes = dest.length(),
                            compressed = false,
                            compressPolicy = null,
                        )
                    )
                }
            }
        }

        if (floors.isEmpty()) return null

        // Replace any previous copy of this backup
        localBackupDao.deleteFloors(backupId)
        localBackupDao.deleteImages(backupId)
        localBackupDao.insertFloors(floors)
        if (images.isNotEmpty()) localBackupDao.insertImages(images)

        val entity = LocalBackupPost(
            backupId = backupId,
            threadId = threadId,
            seeLz = seeLz,
            title = rawTitle.ifBlank { "帖子 $threadId" },
            forumId = null,
            forumName = floorsJson.optString("bar", "").takeIf { it.isNotBlank() },
            authorId = null,
            authorName = floors.firstOrNull { it.isLz }?.authorName,
            url = url,
            totalFloors = floorsArray.length(),
            totalPages = floorsJson.optInt("total_pages", 0).takeIf { it > 0 },
            crawledFloors = floors.size,
            totalBytes = images.sumOf { it.bytes },
            imageCount = images.size,
            compressedImages = 0,
            backupAt = System.currentTimeMillis(),
            status = LocalBackupPost.STATUS_OK,
            mdPath = null,
            jsonPath = null,
            exportKey = exportKey,
        )
        runCatching {
            val md = BackupMarkdownWriter.renderMarkdown(entity, floors, images)
            val json = BackupMarkdownWriter.renderPostJson(entity, floors, images)
            val exportJson = BackupMarkdownWriter.renderExportJson(entity, floors, images)
            backupPaths.markdownFile(exportKey).writeText(md, Charsets.UTF_8)
            backupPaths.jsonFile(exportKey).writeText(json, Charsets.UTF_8)
            File(backupPaths.root, "export.json").writeText(exportJson, Charsets.UTF_8)
            localBackupDao.upsertPost(
                entity.copy(
                    mdPath = "markdowns/$exportKey.md",
                    jsonPath = "posts/$exportKey.json",
                )
            )
        }
        localBackupDao.upsertPost(entity)
        return entity.title
    }

    private fun parseTime(text: String): Long {
        if (text.isBlank()) return 0L
        parseFormats.forEach { fmt ->
            // SimpleDateFormat is not thread safe; ParsePosition keeps parsing non-fatal
            val pos = ParsePosition(0)
            val date = runCatching { fmt.parse(text, pos) }.getOrNull()
            if (date != null) return date.time
        }
        return 0L
    }

    /** Extracts all entries; returns name->file with an optional single top folder stripped. */
    private fun unzipTo(input: InputStream, destDir: File): Map<String, File> {
        val raw = mutableMapOf<String, File>()
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val file = File(destDir, entry.name)
                    if (file.canonicalPath.startsWith(destDir.canonicalPath)) {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { zip.copyTo(it) }
                        raw[entry.name] = file
                    }
                }
                entry = zip.nextEntry
            }
        }
        // strip a single common top-level folder (e.g. TiebaShelf zips data/)
        if (!raw.containsKey("export.json")) {
            val candidates = raw.keys.filter { it.endsWith("export.json") }
            val prefixes = candidates.mapNotNull { name ->
                val idx = name.lastIndexOf("export.json")
                if (idx > 0) name.substring(0, idx) else null
            }
            // pick the shallowest prefix that yields the most matches
            val prefix = prefixes.minByOrNull { it.length } ?: return raw
            val stripped = mutableMapOf<String, File>()
            raw.forEach { (name, file) ->
                stripped[name.removePrefix(prefix)] = file
            }
            return stripped
        }
        return raw
    }

    companion object {
        private const val TAG = "BackupImporter"
    }
}
