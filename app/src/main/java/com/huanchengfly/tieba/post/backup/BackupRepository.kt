package com.huanchengfly.tieba.post.backup

import android.util.Log
import com.huanchengfly.tieba.post.models.database.LocalBackupFloor
import com.huanchengfly.tieba.post.models.database.LocalBackupImage
import com.huanchengfly.tieba.post.models.database.LocalBackupPost
import com.huanchengfly.tieba.post.models.database.LocalBackupProgress
import com.huanchengfly.tieba.post.models.database.LocalBackupSubPost
import com.huanchengfly.tieba.post.models.database.dao.LocalBackupDao
import com.huanchengfly.tieba.post.repository.PbPageRepository
import com.huanchengfly.tieba.post.ui.common.PicContentRender
import com.huanchengfly.tieba.post.ui.page.thread.ThreadSortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local post backup pipeline (Shelf core, Lite-native storage).
 *
 * Uses Lite's existing authenticated [PbPageRepository] instead of a separate crawler.
 *
 * Reliability rules:
 * - the index row is written [LocalBackupPost.STATUS_PENDING] before fetching starts,
 *   floors/images are inserted page by page, so a killed run leaves partial data;
 * - failures upsert a [LocalBackupPost.STATUS_FAILED] row instead of disappearing.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val localBackupDao: LocalBackupDao,
    private val pbPageRepository: PbPageRepository,
    private val backupPaths: BackupPaths,
    private val imageCompressor: BackupImageCompressor,
    private val prefsStore: LocalBackupPrefsStore,
) {
    data class BackupProgress(
        val stage: Stage,
        val current: Int = 0,
        val total: Int = 0,
        val message: String? = null,
    ) {
        enum class Stage {
            FETCHING, SAVING, IMAGES, MARKDOWN, DONE, FAILED
        }
    }

    data class BackupResult(
        val backupId: String,
        val status: Int,
        val floors: Int,
        val images: Int,
        val bytes: Long,
        val message: String? = null,
    )

    fun observePosts(): Flow<List<LocalBackupPost>> = localBackupDao.observePosts()

    suspend fun snapshotPrefs(): LocalBackupPrefs = prefsStore.snapshot()

    suspend fun listPosts(): List<LocalBackupPost> = localBackupDao.listPosts()

    suspend fun getPost(backupId: String): LocalBackupPost? = localBackupDao.getPost(backupId)

    suspend fun searchPosts(query: String): List<LocalBackupPost> =
        if (query.isBlank()) localBackupDao.listPosts() else localBackupDao.searchPosts(query)

    suspend fun getFloors(backupId: String): List<LocalBackupFloor> =
        localBackupDao.listFloors(backupId)

    suspend fun getImages(backupId: String): List<LocalBackupImage> =
        localBackupDao.listImages(backupId)

    suspend fun listSubPosts(backupId: String): List<LocalBackupSubPost> =
        localBackupDao.listSubPosts(backupId)

    suspend fun getProgress(backupId: String): LocalBackupProgress? =
        localBackupDao.getProgress(backupId)

    suspend fun saveProgress(backupId: String, threadId: Long, floor: Int) {
        localBackupDao.upsertProgress(
            LocalBackupProgress(
                backupId = backupId,
                threadId = threadId,
                floorNumber = floor,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteBackup(backupId: String) {
        val post = localBackupDao.getPost(backupId)
        localBackupDao.deleteBackup(backupId)
        post?.let { backupPaths.deleteBackupFiles(it.exportKey) }
    }

    suspend fun isFavoritePromptDismissed(): Boolean =
        prefsStore.snapshot().favoritePromptDismissed

    suspend fun setFavoritePromptDismissed(dismissed: Boolean) {
        prefsStore.update { it.copy(favoritePromptDismissed = dismissed) }
    }

    suspend fun setSyncOnFavorite(enabled: Boolean) {
        prefsStore.update { it.copy(syncOnFavorite = enabled) }
    }

    suspend fun setImagePolicy(policy: LocalBackupPrefs.ImagePolicy) {
        prefsStore.update { it.copy(imagePolicy = policy) }
    }

    suspend fun setMaxPages(pages: Int) {
        prefsStore.update { it.copy(maxPages = pages) }
    }

    /** 下载并压缩头像到本地，失败时回退原 URL；同一 URL 本次备份只下载一次 */
    private suspend fun resolveAvatar(
        avatarUrl: String,
        cache: MutableMap<String, String>,
    ): String? {
        if (avatarUrl.isBlank()) return null
        cache[avatarUrl]?.let { return it }
        val resolved = runCatching {
            // 用 URL 摘要做文件名，避免不同作者头像互相覆盖
            val digest = java.security.MessageDigest.getInstance("MD5")
                .digest(avatarUrl.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(10)
            val compressed = imageCompressor.downloadAndCompress(
                url = avatarUrl,
                destDir = backupPaths.avatarsDir,
                fileName = "avatar_$digest",
                policy = LocalBackupPrefs.ImagePolicy.WEBP_SMALL,
            )
            compressed?.file?.absolutePath ?: avatarUrl
        }.getOrDefault(avatarUrl)
        cache[avatarUrl] = resolved
        return resolved
    }

    /**
     * Backup one thread. Safe to call from WorkManager or a coroutine scope.
     */
    suspend fun backupThread(
        threadId: Long,
        forumId: Long? = null,
        seeLz: Boolean = false,
        onProgress: ((BackupProgress) -> Unit)? = null,
    ): BackupResult = withContext(Dispatchers.IO) {
        val prefs = prefsStore.snapshot()
        val exportKey = BackupPaths.exportKey(threadId, seeLz)
        val backupId = exportKey
        val imageDir = backupPaths.imageDir(exportKey)
        val crawledFloors = mutableListOf<LocalBackupFloor>()
        val crawledImages = mutableListOf<LocalBackupImage>()
        val seenPostIds = mutableSetOf<Long>()
        val avatarCache = mutableMapOf<String, String>() // avatarUrl -> local path
        val subPostRows = mutableListOf<LocalBackupSubPost>()
        var totalPages: Int? = null
        var title = ""
        var forumName: String? = null
        var forumAvatar: String? = null
        var authorId: Long? = null
        var authorName: String? = null
        val url = "https://tieba.baidu.com/p/$threadId"
        var imageBytes = 0L
        var compressedCount = 0
        var imageCount = 0
        var failedImages = 0
        var page = 1
        var lastPostId: Long? = null
        var hasMore = true

        fun entity(status: Int, mdPath: String? = null, jsonPath: String? = null) = LocalBackupPost(
            backupId = backupId,
            threadId = threadId,
            seeLz = seeLz,
            title = title.ifBlank { "帖子 $threadId" },
            forumId = forumId,
            forumName = forumName,
            authorId = authorId,
            authorName = authorName,
            url = url,
            totalFloors = totalPages,
            totalPages = totalPages,
            crawledFloors = crawledFloors.size,
            totalBytes = imageBytes,
            imageCount = imageCount,
            compressedImages = compressedCount,
            backupAt = System.currentTimeMillis(),
            status = status,
            mdPath = mdPath,
            jsonPath = jsonPath,
            exportKey = exportKey,
            forumAvatar = forumAvatar,
        )

        onProgress?.invoke(BackupProgress(BackupProgress.Stage.FETCHING, message = "获取帖子…"))

        // 增量更新：已有备份时只补新增楼层/图片，不重下旧图
        val existingFloors = localBackupDao.listFloors(backupId)
        val incremental = existingFloors.isNotEmpty()
        val oldTotalPages = localBackupDao.getPost(backupId)?.totalPages
        seenPostIds.addAll(existingFloors.map { it.postId })

        try {
            if (!incremental) {
                // 全新备份：清旧数据 + 可见占位行，慢/被杀也能在列表看到
                localBackupDao.deleteFloors(backupId)
                localBackupDao.deleteImages(backupId)
                localBackupDao.deleteSubPosts(backupId)
                localBackupDao.upsertPost(entity(LocalBackupPost.STATUS_PENDING))
            }

            while (hasMore && page <= prefs.maxPages) {
                val response = pbPageRepository.pbPage(
                    threadId = threadId,
                    page = page,
                    postId = lastPostId ?: 0,
                    forumId = forumId,
                    seeLz = seeLz,
                    sortType = ThreadSortType.DEFAULT,
                    lastPostId = lastPostId,
                )
                val thread = response.thread
                if (title.isBlank()) {
                    title = thread.title
                    forumName = thread.simpleForum.second
                    forumAvatar = resolveAvatar(thread.simpleForum.third.orEmpty(), avatarCache)
                    totalPages = response.page.new_total_page.takeIf { it > 0 }
                        ?: response.page.total_page.takeIf { it > 0 }
                        ?: 1
                }

                val pageFloors = mutableListOf<LocalBackupFloor>()
                suspend fun addFloor(post: com.huanchengfly.tieba.post.ui.models.PostData) {
                    if (!seenPostIds.add(post.id)) return
                    if (authorId == null && post.author.isLz) {
                        authorId = post.author.id
                        authorName = post.author.name
                    }
                    val floorNumber = post.floor.takeIf { it >= 1 } ?: (crawledFloors.size + pageFloors.size + 1)
                    val avatarPath = resolveAvatar(post.author.avatarUrl, avatarCache)
                    pageFloors.add(
                        LocalBackupFloor(
                            backupId = backupId,
                            threadId = threadId,
                            postId = post.id,
                            floorNumber = floorNumber,
                            authorId = post.author.id,
                            authorName = post.author.name,
                            postTime = post.time,
                            ipLocation = post.author.ip.takeIf { it.isNotBlank() },
                            content = post.plainText,
                            isLz = post.author.isLz,
                            imageUrls = post.contentRenders
                                .filterIsInstance<PicContentRender>()
                                .mapNotNull { r -> r.originUrl.ifBlank { null } ?: r.picUrl.ifBlank { null } }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .joinToString("\n"),
                            authorAvatar = avatarPath,
                        )
                    )
                    // 楼中楼预览（pbPage 每楼返回的子回复列表）
                    post.subPosts.orEmpty().forEach { sub ->
                        subPostRows.add(
                            LocalBackupSubPost(
                                backupId = backupId,
                                threadId = threadId,
                                postId = post.id,
                                floorNumber = floorNumber,
                                subPostId = sub.id,
                                authorId = sub.authorId,
                                authorName = sub.author.nameShow.ifBlank { sub.author.name },
                                content = sub.plainText,
                                postTime = sub.time,
                                isLz = sub.author.isLz,
                            )
                        )
                    }
                }
                // 1楼 arrives as firstPost, not in post_list (which only keeps floor >= 2).
                // Especially for see_lz backups this is often the LZ's only post.
                response.firstPost?.let { addFloor(it) }
                response.posts.forEach { addFloor(it) }

                if (pageFloors.isNotEmpty()) {
                    crawledFloors += pageFloors
                    localBackupDao.insertFloors(pageFloors)
                    if (subPostRows.isNotEmpty()) {
                        // 该页楼层对应的楼中楼整体替换
                        pageFloors.forEach { f -> localBackupDao.deleteSubPostsByPost(backupId, f.postId) }
                        localBackupDao.insertSubPosts(subPostRows.toList())
                        subPostRows.clear()
                    }
                    if (!incremental) {
                        localBackupDao.upsertPost(entity(LocalBackupPost.STATUS_PENDING))
                    }
                }
                // 增量：翻到旧数据最后一页仍无新楼 → 视为无更新
                if (incremental && pageFloors.isEmpty() && oldTotalPages != null && page >= oldTotalPages) {
                    hasMore = false
                }

                onProgress?.invoke(
                    BackupProgress(
                        BackupProgress.Stage.FETCHING,
                        current = crawledFloors.size,
                        total = totalPages ?: crawledFloors.size,
                        message = "已抓取 ${crawledFloors.size} 楼",
                    )
                )

                val pageObj = response.page
                val totalPage = pageObj.new_total_page.takeIf { it > 0 }
                    ?: pageObj.total_page.takeIf { it > 0 }
                    ?: Int.MAX_VALUE
                val currentPage = pageObj.current_page.takeIf { it > 0 } ?: page
                val pageHasMore = pageObj.has_more != 0 && currentPage < totalPage
                lastPostId = response.nextPagePostId.takeIf { it != 0L }
                hasMore = pageHasMore && response.posts.isNotEmpty()
                page++
            }

            if (incremental && crawledFloors.isEmpty()) {
                // 无新增楼层：仅刷新 backupAt 表示检查过更新
                localBackupDao.getPost(backupId)?.let {
                    localBackupDao.upsertPost(it.copy(backupAt = System.currentTimeMillis()))
                }
                onProgress?.invoke(BackupProgress(BackupProgress.Stage.DONE, message = "备份无更新"))
                return@withContext BackupResult(
                    backupId = backupId,
                    status = LocalBackupPost.STATUS_OK,
                    floors = 0,
                    images = 0,
                    bytes = 0,
                    message = "备份无更新",
                )
            }

            if (crawledFloors.isEmpty()) {
                localBackupDao.upsertPost(entity(LocalBackupPost.STATUS_FAILED))
                onProgress?.invoke(
                    BackupProgress(BackupProgress.Stage.FAILED, message = "未获取到楼层内容")
                )
                return@withContext BackupResult(
                    backupId = backupId,
                    status = LocalBackupPost.STATUS_FAILED,
                    floors = 0,
                    images = 0,
                    bytes = 0,
                    message = "未获取到楼层内容",
                )
            }

            // Images
            if (prefs.imagePolicy != LocalBackupPrefs.ImagePolicy.NONE) {
                onProgress?.invoke(BackupProgress(BackupProgress.Stage.IMAGES, message = "下载图片…"))
                val allUrls = crawledFloors.flatMap { f ->
                    f.imageUrls.split('\n').map { it.trim() }.filter { it.isNotBlank() }
                        .map { it to f.floorNumber }
                }.distinctBy { it.first }

                val pendingImages = mutableListOf<LocalBackupImage>()
                suspend fun flushImages() {
                    if (pendingImages.isNotEmpty()) {
                        localBackupDao.insertImages(pendingImages.toList())
                        pendingImages.clear()
                        localBackupDao.upsertPost(entity(LocalBackupPost.STATUS_PENDING))
                    }
                }

                allUrls.forEachIndexed { index, (imgUrl, floorNumber) ->
                    val base = imgUrl.substringAfterLast('/').ifBlank { "img_$index" }
                    val compressed = imageCompressor.downloadAndCompress(
                        url = imgUrl,
                        destDir = imageDir,
                        fileName = base,
                        policy = prefs.imagePolicy,
                    )
                    if (compressed != null) {
                        imageCount++
                        imageBytes += compressed.bytes
                        if (compressed.compressed) compressedCount++
                        pendingImages.add(
                            LocalBackupImage(
                                backupId = backupId,
                                threadId = threadId,
                                floorNumber = floorNumber,
                                originalUrl = imgUrl,
                                localPath = compressed.file.absolutePath,
                                width = compressed.width,
                                height = compressed.height,
                                bytes = compressed.bytes,
                                compressed = compressed.compressed,
                                compressPolicy = compressed.policy,
                            )
                        )
                    } else {
                        failedImages++
                    }
                    if (pendingImages.size >= 20) flushImages()
                    if ((index + 1) % 10 == 0) {
                        onProgress?.invoke(
                            BackupProgress(
                                BackupProgress.Stage.IMAGES,
                                current = index + 1,
                                total = allUrls.size,
                            )
                        )
                    }
                }
                flushImages()
            }

            onProgress?.invoke(BackupProgress(BackupProgress.Stage.MARKDOWN, message = "生成 Markdown…"))

            // 增量时统计与 Markdown 需覆盖旧数据 + 新增
            val allFloors: List<LocalBackupFloor> =
                if (incremental) localBackupDao.listFloors(backupId) else crawledFloors
            val allImages: List<LocalBackupImage> =
                if (incremental) localBackupDao.listImages(backupId) else crawledImages
            if (incremental) {
                imageCount = allImages.size
                imageBytes = allImages.sumOf { it.bytes }
                compressedCount = allImages.count { it.compressed }
                // 清掉服务端已删除、本地多出的楼层及其楼中楼
                localBackupDao.deleteStaleFloors(backupId, allFloors.map { it.postId })
                localBackupDao.deleteStaleSubPosts(backupId, allFloors.map { it.postId })
            }

            val status = if (failedImages == 0) LocalBackupPost.STATUS_OK
            else LocalBackupPost.STATUS_PARTIAL

            var finalEntity = entity(status)
            if (incremental) {
                finalEntity = finalEntity.copy(crawledFloors = allFloors.size)
            }
            try {
                val md = BackupMarkdownWriter.renderMarkdown(finalEntity, allFloors, allImages)
                val json = BackupMarkdownWriter.renderPostJson(finalEntity, allFloors, allImages)
                val exportJson = BackupMarkdownWriter.renderExportJson(finalEntity, allFloors, allImages)
                backupPaths.markdownFile(exportKey).writeText(md, Charsets.UTF_8)
                backupPaths.jsonFile(exportKey).writeText(json, Charsets.UTF_8)
                File(backupPaths.root, "export.json").writeText(exportJson, Charsets.UTF_8)
                finalEntity = finalEntity.copy(
                    mdPath = "markdowns/$exportKey.md",
                    jsonPath = "posts/$exportKey.json",
                    totalBytes = imageBytes + backupPaths.markdownFile(exportKey).length() +
                        backupPaths.jsonFile(exportKey).length(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "write markdown/json failed", e)
            }
            localBackupDao.upsertPost(finalEntity)

            onProgress?.invoke(BackupProgress(BackupProgress.Stage.DONE, message = "备份完成"))
            BackupResult(
                backupId = backupId,
                status = status,
                floors = crawledFloors.size,
                images = imageCount,
                bytes = imageBytes,
                message = if (failedImages > 0) "有 $failedImages 张图片未下载" else null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "backup failed thread=$threadId", e)
            // 增量失败：保留原备份（含已插入的新楼），不覆盖状态
            if (!incremental) {
                val failedStatus =
                    if (crawledFloors.isEmpty()) LocalBackupPost.STATUS_FAILED
                    else LocalBackupPost.STATUS_PARTIAL
                runCatching { localBackupDao.upsertPost(entity(failedStatus)) }
            }
            onProgress?.invoke(BackupProgress(BackupProgress.Stage.FAILED, message = e.message))
            BackupResult(
                backupId = backupId,
                status = if (incremental) LocalBackupPost.STATUS_PARTIAL else LocalBackupPost.STATUS_FAILED,
                floors = crawledFloors.size,
                images = imageCount,
                bytes = imageBytes,
                message = e.message,
            )
        }
    }

    companion object {
        private const val TAG = "BackupRepository"
    }
}
