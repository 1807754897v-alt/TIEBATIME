package com.huanchengfly.tieba.post.backup

import com.huanchengfly.tieba.post.models.database.LocalBackupSubPost
import com.huanchengfly.tieba.post.models.database.dao.LocalBackupDao
import com.huanchengfly.tieba.post.repository.PbPageRepository
import com.huanchengfly.tieba.post.ui.models.PostData
import java.io.IOException
import javax.inject.Inject

/** Fetch a complete reply snapshot before replacing the previous offline copy. */
class BackupSubPostRefresher @Inject constructor(
    private val repository: PbPageRepository,
    private val dao: LocalBackupDao,
) {
    suspend fun refresh(backupId: String, threadId: Long, forumId: Long, post: PostData, maxPages: Int) {
        val replies = mutableListOf<LocalBackupSubPost>()
        if (post.subPostNumber > 0 || !post.subPosts.isNullOrEmpty()) {
            var page = 1
            while (true) {
                if (page > maxPages) throw IOException("楼中楼超过备份页数上限，已保留原副本")
                val response = repository.pbFloor(threadId, post.id, forumId, page)
                replies += response.subPosts.map { sub ->
                    LocalBackupSubPost(
                        backupId = backupId, threadId = threadId, postId = post.id,
                        floorNumber = post.floor, subPostId = sub.id,
                        authorId = sub.authorId,
                        authorName = sub.author.nameShow.ifBlank { sub.author.name },
                        content = sub.plainText, postTime = sub.time, isLz = sub.author.isLz,
                    )
                }
                if (!response.page.hasMore) break
                if (response.page.current != page) throw IOException("楼中楼分页异常，已保留原副本")
                page++
            }
        }
        dao.replaceSubPostsByPost(backupId, post.id, replies.distinctBy { it.subPostId })
    }
}
