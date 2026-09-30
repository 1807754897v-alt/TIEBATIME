package com.huanchengfly.tieba.post.models.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.huanchengfly.tieba.post.models.database.LocalBackupFloor
import com.huanchengfly.tieba.post.models.database.LocalBackupImage
import com.huanchengfly.tieba.post.models.database.LocalBackupPost
import com.huanchengfly.tieba.post.models.database.LocalBackupProgress
import com.huanchengfly.tieba.post.models.database.LocalBackupSubPost
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalBackupDao {

    @Query("SELECT * FROM local_backup_posts ORDER BY backupAt DESC")
    fun observePosts(): Flow<List<LocalBackupPost>>

    @Query("SELECT * FROM local_backup_posts ORDER BY backupAt DESC")
    suspend fun listPosts(): List<LocalBackupPost>

    @Query("SELECT * FROM local_backup_posts WHERE backupId = :backupId LIMIT 1")
    suspend fun getPost(backupId: String): LocalBackupPost?

    @Query("SELECT * FROM local_backup_posts WHERE threadId = :threadId AND seeLz = :seeLz ORDER BY backupAt DESC LIMIT 1")
    suspend fun getLatestByThread(threadId: Long, seeLz: Boolean): LocalBackupPost?

    @Query("SELECT COUNT(*) FROM local_backup_posts")
    suspend fun countPosts(): Int

    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM local_backup_posts")
    suspend fun sumBytes(): Long

    @Upsert
    suspend fun upsertPost(post: LocalBackupPost)

    @Query("DELETE FROM local_backup_posts WHERE backupId = :backupId")
    suspend fun deletePost(backupId: String)

    @Query("DELETE FROM local_backup_floors WHERE backupId = :backupId")
    suspend fun deleteFloors(backupId: String)

    @Query("DELETE FROM local_backup_images WHERE backupId = :backupId")
    suspend fun deleteImages(backupId: String)

    @Query("DELETE FROM local_backup_progress WHERE backupId = :backupId")
    suspend fun deleteProgress(backupId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubPosts(subPosts: List<LocalBackupSubPost>)

    @Query("DELETE FROM local_backup_subposts WHERE backupId = :backupId")
    suspend fun deleteSubPosts(backupId: String)

    @Query(
        "DELETE FROM local_backup_subposts WHERE backupId = :backupId AND postId = :postId"
    )
    suspend fun deleteSubPostsByPost(backupId: String, postId: Long)

    @Query(
        "DELETE FROM local_backup_subposts WHERE backupId = :backupId AND postId NOT IN (:keepPostIds)"
    )
    suspend fun deleteStaleSubPosts(backupId: String, keepPostIds: List<Long>)

    @Query("SELECT * FROM local_backup_subposts WHERE backupId = :backupId")
    suspend fun listSubPosts(backupId: String): List<LocalBackupSubPost>

    @Transaction
    suspend fun deleteBackup(backupId: String) {
        deleteFloors(backupId)
        deleteImages(backupId)
        deleteSubPosts(backupId)
        deleteProgress(backupId)
        deletePost(backupId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFloors(floors: List<LocalBackupFloor>)

    /** 增量更新后清掉服务端已删除、本地多出的楼层 */
    @Query(
        "DELETE FROM local_backup_floors WHERE backupId = :backupId AND postId NOT IN (:keepPostIds)"
    )
    suspend fun deleteStaleFloors(backupId: String, keepPostIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<LocalBackupImage>)

    @Query("SELECT * FROM local_backup_floors WHERE backupId = :backupId ORDER BY floorNumber ASC")
    suspend fun listFloors(backupId: String): List<LocalBackupFloor>

    @Query("SELECT * FROM local_backup_images WHERE backupId = :backupId ORDER BY id ASC")
    suspend fun listImages(backupId: String): List<LocalBackupImage>

    @Query("SELECT * FROM local_backup_progress WHERE backupId = :backupId LIMIT 1")
    suspend fun getProgress(backupId: String): LocalBackupProgress?

    @Upsert
    suspend fun upsertProgress(progress: LocalBackupProgress)

    @Query(
        """
        SELECT * FROM local_backup_posts
        WHERE title LIKE '%' || :query || '%'
           OR forumName LIKE '%' || :query || '%'
           OR authorName LIKE '%' || :query || '%'
        ORDER BY backupAt DESC
        """
    )
    suspend fun searchPosts(query: String): List<LocalBackupPost>
}
