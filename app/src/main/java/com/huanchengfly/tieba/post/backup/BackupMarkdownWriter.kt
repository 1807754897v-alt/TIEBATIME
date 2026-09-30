package com.huanchengfly.tieba.post.backup

import com.huanchengfly.tieba.post.models.database.LocalBackupFloor
import com.huanchengfly.tieba.post.models.database.LocalBackupImage
import com.huanchengfly.tieba.post.models.database.LocalBackupPost
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders Shelf-compatible markdown + export.json.
 *
 * Field names intentionally match TiebaShelf export packages so the
 * desktop EPUB converter can consume Lite exports without code changes.
 */
object BackupMarkdownWriter {

    private val displayTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val crawlTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun renderMarkdown(
        post: LocalBackupPost,
        floors: List<LocalBackupFloor>,
        images: List<LocalBackupImage>,
        imageBaseUrl: String = "../images",
    ): String {
        val sb = StringBuilder()
        sb.append("# ").append(post.title).append('\n').append('\n')
        sb.append("> **原始链接**: ").append(post.url).append("  \n")
        sb.append("> **帖子所在**: ").append(post.forumName ?: "未知吧名").append("  \n")
        sb.append("> **模式**: ").append(if (post.seeLz) "只看楼主" else "完整版").append("  \n")
        sb.append("> **总楼层数**: ").append(post.totalFloors ?: floors.size).append("  \n")
        sb.append("> **总页数**: ").append(post.totalPages ?: 0).append("  \n")
        sb.append("> **备份时间**: ").append(displayTime.format(Date(post.backupAt))).append("  \n")
        sb.append("\n---\n\n")

        val imagesByFloor = images.groupBy { it.floorNumber }

        floors.sortedBy { it.floorNumber }.forEach { floor ->
            val author = floor.authorName ?: "未知作者"
            val time = if (floor.postTime > 0) displayTime.format(Date(floor.postTime)) else ""
            val meta = listOfNotNull(
                floor.floorNumber.takeIf { it > 1 }?.let { "${it}楼" },
                time,
                floor.ipLocation?.takeIf { it.isNotBlank() }?.let { "IP: $it" },
            ).joinToString(" · ")

            sb.append("### ").append(author).append('\n')
            if (meta.isNotBlank()) sb.append(meta).append("  \n")
            sb.append('\n')

            var content = floor.content
            val urls = floor.imageUrls.split('\n').map { it.trim() }.filter { it.isNotBlank() }
            urls.forEach { url ->
                val img = imagesByFloor[floor.floorNumber]?.firstOrNull {
                    it.originalUrl == url || it.localPath.contains(url.substringAfterLast('/'))
                }
                val name = img?.fileBaseName() ?: url.substringAfterLast('/')
                val rel = "$imageBaseUrl/${post.exportKey}/$name"
                sb.append("![image](").append(rel).append(")\n\n")
            }
            if (content.isBlank()) {
                sb.append("（本层无文字内容）\n")
            } else {
                sb.append(content).append('\n')
            }
            sb.append("\n---\n\n")
        }
        return sb.toString()
    }

    fun renderExportJson(
        post: LocalBackupPost,
        floors: List<LocalBackupFloor>,
        images: List<LocalBackupImage>,
        createdAt: String = crawlTime.format(Date()),
    ): String {
        val imageDirName = post.exportKey
        val maxFloor = floors.maxOfOrNull { it.floorNumber } ?: 0
        val item = JSONObject().apply {
            put("post_id", post.threadId.toString())
            put("title", post.title)
            put("see_lz", post.seeLz)
            put("url", post.url)
            put("last_crawled", createdAt)
            put("total_pages", post.totalPages ?: 0)
            put("total_floors", post.totalFloors ?: floors.size)
            put("file_path", "posts/${post.exportKey}.json")
            put("display_name", displayName(post.title, post.seeLz))
            put("max_floor_number", maxFloor)
            put("markdown", "markdowns/${post.exportKey}.md")
            put("images_dir", "images/$imageDirName/")
        }
        val export = JSONObject().apply {
            put("export_version", "1.0")
            put("created_at", createdAt)
            put("tool", "TieBaTime LocalBackup")
            put("total_posts", 1)
            put("posts", JSONArray().put(item))
        }
        return export.toString(2)
    }

    fun renderPostJson(
        post: LocalBackupPost,
        floors: List<LocalBackupFloor>,
        images: List<LocalBackupImage>,
    ): String {
        val floorArray = JSONArray()
        floors.sortedBy { it.floorNumber }.forEach { floor ->
            val imgs = floor.imageUrls.split('\n').map { it.trim() }.filter { it.isNotBlank() }
            floorArray.put(
                JSONObject().apply {
                    put("floor_number", floor.floorNumber)
                    put("author", floor.authorName ?: "")
                    put("post_time", if (floor.postTime > 0) displayTime.format(Date(floor.postTime)) else "")
                    put("ip_location", floor.ipLocation ?: "")
                    put("content", floor.content)
                    put("is_lz", floor.isLz)
                    put("images", JSONArray(imgs))
                }
            )
        }
        val obj = JSONObject().apply {
            put("post_id", post.threadId.toString())
            put("title", post.title)
            put("see_lz", post.seeLz)
            put("url", post.url)
            put("bar", post.forumName ?: "")
            put("crawl_time", crawlTime.format(Date(post.backupAt)))
            put("total_pages", post.totalPages ?: 0)
            put("total_floors", floors.size)
            put("floors", floorArray)
        }
        return obj.toString(2)
    }

    fun displayName(title: String, seeLz: Boolean): String {
        val clean = title.ifBlank { "未命名帖子" }
        return clean + if (seeLz) " (只看楼主)" else " (完整版)"
    }

    private fun LocalBackupImage.fileBaseName(): String =
        localPath.substringAfterLast('/')
}
