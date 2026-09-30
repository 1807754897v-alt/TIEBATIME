package com.huanchengfly.tieba.post.models.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 楼中楼（子回复）离线副本（v8 新增）。附带 pbPage 返回的预览条目。 */
@Entity(
    tableName = "local_backup_subposts",
    indices = [Index("backupId"), Index("postId")]
)
data class LocalBackupSubPost(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val backupId: String,
    val threadId: Long,
    /** 所属楼层（主楼）的 postId */
    val postId: Long,
    val floorNumber: Int,
    val subPostId: Long,
    val authorId: Long?,
    val authorName: String?,
    val content: String,
    val postTime: Long,
    val isLz: Boolean,
)
