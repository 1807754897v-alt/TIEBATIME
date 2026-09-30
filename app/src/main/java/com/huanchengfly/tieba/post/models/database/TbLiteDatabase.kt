package com.huanchengfly.tieba.post.models.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.ExperimentalRoomApi
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import com.huanchengfly.tieba.post.models.database.dao.AccountDao
import com.huanchengfly.tieba.post.models.database.dao.BlockDao
import com.huanchengfly.tieba.post.models.database.dao.DraftDao
import com.huanchengfly.tieba.post.models.database.dao.FavoriteThreadDao
import com.huanchengfly.tieba.post.models.database.dao.ForumHistoryDao
import com.huanchengfly.tieba.post.models.database.dao.HiddenThreadDao
import com.huanchengfly.tieba.post.models.database.dao.ImageCacheIndexDao
import com.huanchengfly.tieba.post.models.database.dao.LikedForumDao
import com.huanchengfly.tieba.post.models.database.dao.LocalBackupDao
import com.huanchengfly.tieba.post.models.database.dao.SearchDao
import com.huanchengfly.tieba.post.models.database.dao.SearchPostDao
import com.huanchengfly.tieba.post.models.database.dao.ThreadHistoryDao
import com.huanchengfly.tieba.post.models.database.dao.TimestampDao
import com.huanchengfly.tieba.post.models.database.dao.TransactionRunnerDao
import com.huanchengfly.tieba.post.models.database.dao.UserProfileDao
import com.huanchengfly.tieba.post.models.database.TbLiteDatabase.Companion.Migrations
import java.util.concurrent.TimeUnit

@Database(
    entities = [
        Account::class,
        BlockForum::class,
        BlockKeyword::class,
        BlockUser::class,
        Draft::class,
        FavoriteThread::class,
        ForumHistory::class,
        HiddenThread::class,
        ImageCacheIndex::class,
        LocalBackupFloor::class,
        LocalBackupSubPost::class,
        LocalBackupImage::class,
        LocalBackupPost::class,
        LocalBackupProgress::class,
        LocalLikedForum::class,
        SearchHistory::class,
        SearchPostHistory::class,
        ThreadHistory::class,
        TopForum::class,
        Timestamp::class,
        UserProfile::class,
    ],
    version = 9,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = Migrations.Migration_1_2::class),
        AutoMigration(from = 2, to = 3, spec = Migrations.Migration_2_3::class),
        AutoMigration(from = 3, to = 4, spec = Migrations.Migration_3_4::class),
        AutoMigration(from = 4, to = 5, spec = Migrations.Migration_4_5::class),
        AutoMigration(from = 5, to = 6, spec = Migrations.Migration_5_6::class),
        AutoMigration(from = 6, to = 7, spec = Migrations.Migration_6_7::class),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
    ]
)
abstract class TbLiteDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao

    abstract fun blockDao(): BlockDao

    abstract fun draftDao(): DraftDao

    abstract fun favoriteThreadDao(): FavoriteThreadDao

    abstract fun forumHistoryDao(): ForumHistoryDao

    abstract fun hiddenThreadDao(): HiddenThreadDao

    abstract fun imageCacheIndexDao(): ImageCacheIndexDao

    abstract fun likedForumDao(): LikedForumDao

    abstract fun localBackupDao(): LocalBackupDao

    abstract fun searchDao(): SearchDao

    abstract fun searchPostDao(): SearchPostDao

    abstract fun threadHistoryDao(): ThreadHistoryDao

    abstract fun timestampDao(): TimestampDao

    abstract fun transactionRunnerDao(): TransactionRunnerDao

    abstract fun userProfileDao(): UserProfileDao

    companion object {

        @Volatile
        private var INSTANCE: TbLiteDatabase? = null

        // Some old utils can not work with hilt inject, get instance manually for now
        @OptIn(ExperimentalRoomApi::class)
        fun getInstance(context: Context): TbLiteDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room
                    .databaseBuilder(context, TbLiteDatabase::class.java, "tb_lite.db")
                    .setAutoCloseTimeout(15, TimeUnit.MINUTES)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        @Suppress("ClassName")
        object Migrations {

            class Migration_1_2 : AutoMigrationSpec {
                override fun onPostMigrate(connection: SQLiteConnection) {
                }
            }

            class Migration_2_3 : AutoMigrationSpec {
                override fun onPostMigrate(connection: SQLiteConnection) {
                }
            }

            class Migration_3_4 : AutoMigrationSpec {
                override fun onPostMigrate(connection: SQLiteConnection) {
                }
            }

            class Migration_4_5 : AutoMigrationSpec {
                override fun onPostMigrate(connection: SQLiteConnection) {
                }
            }

            class Migration_5_6 : AutoMigrationSpec {
                override fun onPostMigrate(connection: SQLiteConnection) {
                }
            }

            /**
             * [LocalBackupPost] / [LocalBackupFloor] / [LocalBackupImage] / [LocalBackupProgress]
             */
            class Migration_6_7 : AutoMigrationSpec {
                override fun onPostMigrate(connection: SQLiteConnection) {
                }
            }
        }
    }
}
