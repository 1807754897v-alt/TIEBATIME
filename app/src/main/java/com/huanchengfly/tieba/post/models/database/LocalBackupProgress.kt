package com.huanchengfly.tieba.post.models.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_backup_progress")
data class LocalBackupProgress(
    @PrimaryKey
    val backupId: String,
    val threadId: Long,
    val floorNumber: Int,
    val updatedAt: Long,
)
