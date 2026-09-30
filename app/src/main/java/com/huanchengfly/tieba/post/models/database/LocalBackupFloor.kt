package com.huanchengfly.tieba.post.models.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_backup_floors",
    indices = [Index("backupId"), Index("threadId")]
)
data class LocalBackupFloor(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val backupId: String,
    val threadId: Long,
    val postId: Long,
    val floorNumber: Int,
    val authorId: Long?,
    val authorName: String?,
    val postTime: Long,
    val ipLocation: String?,
    val content: String,
    val isLz: Boolean,
    val imageUrls: String,
    /** 作者头像（本地缓存路径，离线可用；v8 新增） */
    val authorAvatar: String? = null,
)
