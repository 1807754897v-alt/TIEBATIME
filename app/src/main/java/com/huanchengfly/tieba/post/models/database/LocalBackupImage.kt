package com.huanchengfly.tieba.post.models.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_backup_images",
    indices = [Index("backupId"), Index("originalUrl")]
)
data class LocalBackupImage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val backupId: String,
    val threadId: Long,
    val floorNumber: Int,
    val originalUrl: String,
    val localPath: String,
    val width: Int?,
    val height: Int?,
    val bytes: Long,
    val compressed: Boolean,
    val compressPolicy: String?,
)
