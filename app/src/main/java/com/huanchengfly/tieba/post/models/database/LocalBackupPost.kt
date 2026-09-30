package com.huanchengfly.tieba.post.models.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local backup of a tieba thread (Shelf-style offline archive).
 *
 * Distinct from server-side favorites ([FavoriteThread]).
 */
@Entity(
    tableName = "local_backup_posts",
    indices = [Index("threadId"), Index("backupAt")]
)
data class LocalBackupPost(
    @PrimaryKey
    val backupId: String,
    val threadId: Long,
    val seeLz: Boolean,
    val title: String,
    val forumId: Long?,
    val forumName: String?,
    val authorId: Long?,
    val authorName: String?,
    val url: String,
    val totalFloors: Int?,
    val totalPages: Int?,
    val crawledFloors: Int,
    val totalBytes: Long,
    val imageCount: Int,
    val compressedImages: Int,
    val backupAt: Long,
    val status: Int,
    val mdPath: String?,
    val jsonPath: String?,
    val exportKey: String,
    /** 吧 logo（本地缓存路径，v9 新增） */
    val forumAvatar: String? = null,
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_OK = 1
        const val STATUS_PARTIAL = 2
        const val STATUS_FAILED = 3
    }
}
