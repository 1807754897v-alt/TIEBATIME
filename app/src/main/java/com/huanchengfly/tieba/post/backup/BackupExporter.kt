package com.huanchengfly.tieba.post.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.huanchengfly.tieba.post.models.database.LocalBackupFloor
import com.huanchengfly.tieba.post.models.database.LocalBackupImage
import com.huanchengfly.tieba.post.models.database.LocalBackupPost
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports one [LocalBackupPost] to user-shareable formats.
 *
 * - [Format.SHELF]: TiebaShelf-compatible package (export.json + markdowns/ + posts/ + images/)
 * - [Format.PDF] / [Format.TXT]: reading-friendly renderings that keep the
 *   Tieba client look (楼主 badge, floor number, time, IP, dividers)
 * - [Format.EPUB]: minimal EPUB 3 (mimetype stored first, OPF + NCX + nav)
 */
object BackupExporter {

    enum class Format(val mimeType: String, val extension: String) {
        SHELF("application/zip", "zip"),
        PDF("application/pdf", "pdf"),
        EPUB("application/epub+zip", "epub"),
        TXT("text/plain", "txt"),
    }

    private val displayTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    private fun time(postTime: Long): String =
        if (postTime > 0) displayTime.format(Date(postTime)) else ""

    private fun floorTitle(floor: LocalBackupFloor): String {
        val parts = buildList {
            if (floor.isLz) add("楼主")
            if (floor.floorNumber > 0) add("${floor.floorNumber}楼")
            time(floor.postTime).takeIf { it.isNotBlank() }?.let { add(it) }
            floor.ipLocation?.takeIf { it.isNotBlank() }?.let { add("IP $it") }
        }
        return parts.joinToString(" · ")
    }

    private fun imagesByFloor(images: List<LocalBackupImage>) = images.groupBy { it.floorNumber }

    fun suggestName(post: LocalBackupPost, format: Format): String =
        "${post.title.ifBlank { post.exportKey }}.${format.extension}"
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

    // ---------------------------------------------------------------- Shelf

    fun exportShelf(
        post: LocalBackupPost,
        floors: List<LocalBackupFloor>,
        images: List<LocalBackupImage>,
        output: OutputStream,
    ) {
        val md = BackupMarkdownWriter.renderMarkdown(post, floors, images)
        val json = BackupMarkdownWriter.renderPostJson(post, floors, images)
        val exportJson = BackupMarkdownWriter.renderExportJson(post, floors, images)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putEntry("export.json", exportJson)
            zip.putEntry("posts/${post.exportKey}.json", json)
            zip.putEntry("markdowns/${post.exportKey}.md", md)
            images.forEach { img ->
                val file = File(img.localPath)
                if (file.exists()) {
                    zip.putFileEntry("images/${post.exportKey}/${file.name}", file)
                }
            }
        }
    }

    // ------------------------------------------------------------------ TXT

    fun exportTxt(
        post: LocalBackupPost,
        floors: List<LocalBackupFloor>,
        output: OutputStream,
    ) {
        val divider = "━".repeat(24)
        val sb = StringBuilder()
        sb.appendLine("《${post.title}》")
        sb.appendLine("原始链接：${post.url}")
        sb.appendLine("所在贴吧：${post.forumName ?: "未知"}")
        sb.appendLine("模式：${if (post.seeLz) "只看楼主" else "完整版"}")
        sb.appendLine("备份时间：${displayTime.format(Date(post.backupAt))}")
        sb.appendLine()
        floors.sortedBy { it.floorNumber }.forEach { floor ->
            sb.appendLine(divider)
            val who = buildString {
                if (floor.isLz) append("【楼主】")
                append(floor.authorName ?: "未知作者")
                append("  ").append(floorTitle(floor).removePrefix(if (floor.isLz) "楼主 · " else ""))
            }
            sb.appendLine(who)
            sb.appendLine()
            if (floor.content.isBlank()) {
                sb.appendLine("（本层无文字内容）")
            } else {
                sb.appendLine(floor.content)
            }
            floor.imageUrls.split('\n').map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { sb.appendLine("[图片] $it") }
            sb.appendLine()
        }
        output.bufferedWriter(Charsets.UTF_8).use { it.write(sb.toString()) }
    }

    // ------------------------------------------------------------------ PDF

    private const val PAGE_W = 595 // A4 @72dpi
    private const val PAGE_H = 842
    private const val MARGIN = 44f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN

    private const val COLOR_TEXT = 0xFF333333.toInt()
    private const val COLOR_META = 0xFF999999.toInt()
    private const val COLOR_LZ = 0xFF4A6FA5.toInt()
    private const val COLOR_DIVIDER = 0xFFE5E5E5.toInt()

    fun exportPdf(
        post: LocalBackupPost,
        floors: List<LocalBackupFloor>,
        images: List<LocalBackupImage>,
        output: OutputStream,
    ) {
        val doc = PdfDocument()
        val metaPaint = Paint().apply { color = COLOR_META }
        val dividerPaint = Paint().apply { color = COLOR_DIVIDER; strokeWidth = 0.75f }

        var pageNum = 0
        var page: PdfDocument.Page? = null
        var canvas = android.graphics.Canvas()
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNum++
            val p = doc.startPage(
                PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create()
            )
            page = p
            canvas = p.canvas
            canvas.drawColor(Color.WHITE)
            y = MARGIN
        }

        fun ensureSpace(height: Float) {
            if (page == null || y + height > PAGE_H - MARGIN) newPage()
        }

        fun drawStatic(text: CharSequence, textPaint: TextPaint, spacingAfter: Float = 6f) {
            var remaining = text
            while (remaining.isNotEmpty()) {
                val layout = staticLayout(remaining, textPaint, CONTENT_W.toInt())
                val usable = PAGE_H - MARGIN - y
                if (layout.height <= usable) {
                    canvas.withTranslation(MARGIN, y) { layout.draw(this) }
                    y += layout.height + spacingAfter
                    return
                }
                // Block taller than the remaining space: split at a line boundary
                val lineHeight = layout.height.toFloat() / layout.lineCount
                val linesFit = (usable / lineHeight).toInt().coerceAtLeast(1)
                if (linesFit >= layout.lineCount) {
                    canvas.withTranslation(MARGIN, y) { layout.draw(this) }
                    y += layout.height + spacingAfter
                    return
                }
                val cut = layout.getLineVisibleEnd(linesFit - 1)
                    .coerceIn(1, remaining.length)
                canvas.withTranslation(MARGIN, y) {
                    staticLayout(remaining.subSequence(0, cut), textPaint, CONTENT_W.toInt()).draw(this)
                }
                y = PAGE_H - MARGIN
                newPage()
                remaining = remaining.subSequence(cut, remaining.length).trimStart('\n')
            }
        }

        newPage()

        // Title + meta header (贴吧客户端风格：标题 + 灰色元信息)
        drawStatic(
            post.title,
            TextPaint().apply { color = COLOR_TEXT; textSize = 19f; typeface = Typeface.DEFAULT_BOLD },
            spacingAfter = 4f,
        )
        drawStatic(
            buildMetaLine(post),
            TextPaint().apply { color = COLOR_META; textSize = 8.5f },
            spacingAfter = 10f,
        )

        val byFloor = imagesByFloor(images)
        floors.sortedBy { it.floorNumber }.forEach { floor ->
            // 作者行：楼主徽标 + 昵称 + 灰色楼层/时间/IP
            drawStatic(authorLine(floor), TextPaint(), spacingAfter = 3f)
            // 内容
            if (floor.content.isNotBlank()) {
                drawStatic(
                    floor.content,
                    TextPaint().apply { color = COLOR_TEXT; textSize = 10.5f },
                    spacingAfter = 4f,
                )
            }
            // 本楼图片
            byFloor[floor.floorNumber].orEmpty().forEach { img ->
                val bitmap = decodeScaled(File(img.localPath), maxW = (CONTENT_W * 1.6f).toInt())
                if (bitmap != null) {
                    val drawW = minOf(CONTENT_W, bitmap.width.toFloat())
                    val drawH = bitmap.height * (drawW / bitmap.width)
                    if (drawH <= PAGE_H - 2 * MARGIN) {
                        ensureSpace(drawH + 6f)
                        canvas.drawBitmap(
                            bitmap, null,
                            RectF(MARGIN, y, MARGIN + drawW, y + drawH),
                            null,
                        )
                        y += drawH + 6f
                    } else {
                        // 超高图：缩放到一页高
                        ensureSpace(PAGE_H - 2 * MARGIN)
                        val fitH = PAGE_H - 2 * MARGIN
                        val fitW = bitmap.width * (fitH / bitmap.height)
                        canvas.drawBitmap(
                            bitmap, null,
                            RectF(MARGIN, y, MARGIN + fitW, y + fitH),
                            null,
                        )
                        y += fitH + 6f
                    }
                    bitmap.recycle()
                }
            }
            // 分隔线
            ensureSpace(14f)
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, dividerPaint)
            y += 14f
        }
        page?.let { doc.finishPage(it) }
        doc.writeTo(output)
        doc.close()
    }

    private fun buildMetaLine(post: LocalBackupPost): String = buildString {
        append("所在贴吧：${post.forumName ?: "未知"}    ")
        append("模式：${if (post.seeLz) "只看楼主" else "完整版"}    ")
        append("备份时间：${displayTime.format(Date(post.backupAt))}")
    }

    private fun authorLine(floor: LocalBackupFloor): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val bold = StyleSpan(Typeface.BOLD)
        if (floor.isLz) {
            val badgeStart = sb.length
            sb.append("楼主 ")
            sb.setSpan(ForegroundColorSpan(COLOR_LZ), badgeStart, sb.length, 0)
            sb.setSpan(StyleSpan(Typeface.BOLD), badgeStart, sb.length, 0)
        }
        val nameStart = sb.length
        sb.append(floor.authorName ?: "未知作者")
        sb.setSpan(ForegroundColorSpan(COLOR_TEXT), nameStart, sb.length, 0)
        sb.setSpan(bold, nameStart, sb.length, 0)
        val metaStart = sb.length
        sb.append("   ${floorTitle(floor).let { if (floor.isLz) it.removePrefix("楼主 · ") else it }}")
        sb.setSpan(ForegroundColorSpan(COLOR_META), metaStart, sb.length, 0)
        sb.setSpan(AbsoluteSizeSpan(17, true), metaStart, sb.length, 0)
        sb.setSpan(AbsoluteSizeSpan(23, true), 0, metaStart, 0)
        return sb
    }

    private fun staticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.35f)
            .setIncludePad(false)
            .build()

    private inline fun android.graphics.Canvas.withTranslation(
        x: Float,
        y: Float,
        block: android.graphics.Canvas.() -> Unit,
    ) {
        val checkpoint = save()
        translate(x, y)
        try {
            block()
        } finally {
            restoreToCount(checkpoint)
        }
    }

    private fun decodeScaled(file: File, maxW: Int): Bitmap? {
        if (!file.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()
    }

    // ------------------------------------------------------------------ EPUB

    private const val CHAPTER_FLOORS = 50

    fun exportEpub(
        post: LocalBackupPost,
        floors: List<LocalBackupFloor>,
        images: List<LocalBackupImage>,
        output: OutputStream,
    ) {
        val uuid = UUID.randomUUID().toString()
        val sorted = floors.sortedBy { it.floorNumber }
        val chapters = sorted.chunked(CHAPTER_FLOORS)
        val byFloor = imagesByFloor(images)
        val usedImageNames = mutableMapOf<String, String>() // localPath -> epub name

        ZipOutputStream(output.buffered()).use { zip ->
            // mimetype must be the first entry and stored uncompressed
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipOutputStream.STORED
                    size = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                }
            )
            zip.write(mimetype)
            zip.closeEntry()

            zip.putEntry("META-INF/container.xml", containerXml())

            images.forEach { img ->
                val file = File(img.localPath)
                if (file.exists()) {
                    val name = "img_${usedImageNames.size + 1}.${file.extension.ifBlank { "webp" }}"
                    usedImageNames[img.localPath] = name
                    zip.putFileEntry("OEBPS/images/$name", file)
                }
            }

            chapters.forEachIndexed { index, chunk ->
                zip.putEntry("OEBPS/chapter${index + 1}.xhtml", chapterXhtml(post, index + 1, chunk, byFloor, usedImageNames))
            }

            zip.putEntry("OEBPS/style.css", CSS)
            zip.putEntry("OEBPS/nav.xhtml", navXhtml(post, chapters.size))
            zip.putEntry("OEBPS/toc.ncx", tocNcx(post, uuid, chapters.size))
            zip.putEntry("OEBPS/content.opf", contentOpf(post, uuid, chapters.size, usedImageNames.values))
        }
    }

    private fun containerXml(): String = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private fun contentOpf(
        post: LocalBackupPost,
        uuid: String,
        chapterCount: Int,
        imageNames: Collection<String>,
    ): String {
        val manifest = StringBuilder()
        manifest.append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        manifest.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
        manifest.append("    <item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
        for (i in 1..chapterCount) {
            manifest.append("    <item id=\"ch$i\" href=\"chapter$i.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
        }
        imageNames.forEachIndexed { i, name ->
            manifest.append("    <item id=\"img${i + 1}\" href=\"images/$name\" media-type=\"image/webp\"/>\n")
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="zh-CN">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:$uuid</dc:identifier>
    <dc:title>${xmlEscape(BackupMarkdownWriter.displayName(post.title, post.seeLz))}</dc:title>
    <dc:language>zh-CN</dc:language>
    <dc:creator>${xmlEscape(post.authorName ?: "贴吧楼主")}</dc:creator>
    <meta property="dcterms:modified">${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())}</meta>
  </metadata>
  <manifest>
$manifest
  </manifest>
  <spine toc="ncx">
${(1..chapterCount).joinToString("\n") { "    <itemref idref=\"ch$it\"/>" }}
  </spine>
</package>"""
    }

    private fun navXhtml(post: LocalBackupPost, chapterCount: Int): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN">
<head><title>${xmlEscape(post.title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>目录</h1>
    <ol>
${(1..chapterCount).joinToString("\n") { "      <li><a href=\"chapter$it.xhtml\">第 $it 节</a></li>" }}
    </ol>
  </nav>
</body>
</html>"""

    private fun tocNcx(post: LocalBackupPost, uuid: String, chapterCount: Int): String {
        val points = (1..chapterCount).joinToString("\n") {
            """    <navPoint id="np$it" playOrder="$it">
      <navLabel><text>第 $it 节</text></navLabel>
      <content src="chapter$it.xhtml"/>
    </navPoint>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:uuid:$uuid"/>
  </head>
  <docTitle><text>${xmlEscape(post.title)}</text></docTitle>
  <navMap>
$points
  </navMap>
</ncx>"""
    }

    private fun chapterXhtml(
        post: LocalBackupPost,
        chapterNo: Int,
        chunk: List<LocalBackupFloor>,
        byFloor: Map<Int, List<LocalBackupImage>>,
        imageNames: Map<String, String>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<!DOCTYPE html>""")
        sb.appendLine("""<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">""")
        sb.appendLine("""<head><title>${xmlEscape(post.title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>""")
        sb.appendLine("<body>")
        if (chapterNo == 1) {
            sb.appendLine("  <h1>${xmlEscape(post.title)}</h1>")
            sb.appendLine("  <p class=\"meta\">${xmlEscape(buildMetaLine(post))}</p>")
        }
        chunk.forEach { floor ->
            sb.appendLine("  <div class=\"floor\">")
            sb.append("    <p class=\"author\">")
            if (floor.isLz) sb.append("<span class=\"lz\">楼主</span> ")
            sb.appendLine("${xmlEscape(floor.authorName ?: "未知作者")} <span class=\"floorinfo\">${xmlEscape(floorTitle(floor).let { if (floor.isLz) it.removePrefix("楼主 · ") else it })}</span></p>")
            if (floor.content.isNotBlank()) {
                floor.content.split('\n').filter { it.isNotBlank() }.forEach { line ->
                    sb.appendLine("    <p>${xmlEscape(line)}</p>")
                }
            }
            byFloor[floor.floorNumber].orEmpty().forEach { img ->
                imageNames[img.localPath]?.let { name ->
                    sb.appendLine("    <img src=\"images/$name\" alt=\"图片\"/>")
                }
            }
            sb.appendLine("  </div>")
        }
        sb.appendLine("</body>")
        sb.appendLine("</html>")
        return sb.toString()
    }

    private const val CSS = """body { font-family: serif; line-height: 1.6; margin: 1em; }
h1 { font-size: 1.4em; }
p.meta { color: #999999; font-size: 0.85em; }
div.floor { border-bottom: 1px solid #e5e5e5; padding: 0.6em 0; }
p.author { font-weight: bold; margin: 0 0 0.3em 0; }
span.lz { color: #4a6fa5; }
span.floorinfo { color: #999999; font-weight: normal; font-size: 0.85em; }
img { max-width: 100%; margin: 0.4em 0; }"""

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    // ---------------------------------------------------------------- utils

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putFileEntry(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().buffered().use { it.copyTo(this, BUFFER) }
        closeEntry()
    }

    private const val BUFFER = 8 * 1024
}
