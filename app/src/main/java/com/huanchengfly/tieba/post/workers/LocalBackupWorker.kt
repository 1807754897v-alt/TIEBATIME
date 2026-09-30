package com.huanchengfly.tieba.post.workers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.backup.BackupRepository
import com.huanchengfly.tieba.post.utils.NotificationUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class LocalBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backupRepository: BackupRepository,
) : CoroutineWorker(context, params) {

    private val title: String
        get() = applicationContext.getString(R.string.local_backup_notif_title)

    override suspend fun doWork(): Result {
        val threadId = inputData.getLong(KEY_THREAD_ID, -1L)
        if (threadId <= 0L) return Result.failure()
        val forumId = inputData.getLong(KEY_FORUM_ID, 0L).takeIf { it != 0L }
        val seeLz = inputData.getBoolean(KEY_SEE_LZ, false)

        createChannel()
        // Foreground service keeps long backups alive under aggressive OEM background rules
        // and shows the native progress notification the user watches.
        runCatching { setForeground(buildForegroundInfo(title, indeterminate = true)) }

        return try {
            val result = backupRepository.backupThread(
                threadId = threadId,
                forumId = forumId,
                seeLz = seeLz,
            ) { progress ->
                setProgressAsync(
                    workDataOf(
                        KEY_STAGE to progress.stage.name,
                        KEY_CURRENT to progress.current,
                        KEY_TOTAL to progress.total,
                        KEY_MESSAGE to (progress.message ?: ""),
                    )
                )
                updateProgressNotification(progress.current, progress.total, progress.message)
            }
            Log.i(TAG, "backup ok ${result.backupId} floors=${result.floors} images=${result.images}")
            showFinishedNotification(succeeded = true, detail = result.message)
            if (result.status == 3 && result.floors == 0) Result.failure() else Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "LocalBackupWorker failed", e)
            showFinishedNotification(succeeded = false, detail = e.message)
            if (runAttemptCount >= 2) Result.failure() else Result.retry()
        }
    }

    private fun buildForegroundInfo(text: String, indeterminate: Boolean): ForegroundInfo {
        val notification = progressBuilder(text, indeterminate).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun progressBuilder(text: String, indeterminate: Boolean): NotificationCompat.Builder =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, indeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun updateProgressNotification(current: Int, total: Int, message: String?) {
        if (!NotificationUtils.checkPermission(applicationContext)) return
        val builder = progressBuilder(message ?: title, indeterminate = total <= 0)
        if (total > 0) builder.setProgress(total, current.coerceAtMost(total), false)
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun showFinishedNotification(succeeded: Boolean, detail: String?) {
        if (!NotificationUtils.checkPermission(applicationContext)) return
        val text = when {
            !succeeded -> applicationContext.getString(R.string.local_backup_notif_failed)
            detail != null -> applicationContext.getString(R.string.local_backup_notif_done) + "（$detail）"
            else -> applicationContext.getString(R.string.local_backup_notif_done)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply {
                applicationContext.packageManager.getLaunchIntentForPackage(
                    applicationContext.packageName
                )?.let { intent ->
                    setContentIntent(
                        PendingIntent.getActivity(
                            applicationContext,
                            0,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    )
                }
            }
            .build()
        val manager = NotificationManagerCompat.from(applicationContext)
        runCatching {
            manager.notify(NOTIFICATION_ID + 1, notification)
            manager.cancel(NOTIFICATION_ID)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationUtils.createChannel(
                channelId = CHANNEL_ID,
                name = applicationContext.getString(R.string.local_backup_notif_channel),
                importance = NotificationManagerCompat.IMPORTANCE_LOW,
            )
        }
    }

    companion object {
        const val TAG = "LocalBackupWorker"
        private const val WORK_PREFIX = "local_backup_"
        private const val CHANNEL_ID = "local_backup"
        private const val NOTIFICATION_ID = 100081
        private const val KEY_THREAD_ID = "threadId"
        private const val KEY_FORUM_ID = "forumId"
        private const val KEY_SEE_LZ = "seeLz"
        private const val KEY_STAGE = "stage"
        private const val KEY_CURRENT = "current"
        private const val KEY_TOTAL = "total"
        private const val KEY_MESSAGE = "message"

        /** 单帖备份请求（批量备份时按序串联用） */
        fun buildRequest(threadId: Long, forumId: Long? = null, seeLz: Boolean = false) =
            OneTimeWorkRequestBuilder<LocalBackupWorker>()
                .setInputData(
                    workDataOf(
                        KEY_THREAD_ID to threadId,
                        KEY_FORUM_ID to (forumId ?: 0L),
                        KEY_SEE_LZ to seeLz,
                    )
                )
                .addTag(TAG)
                .setInitialDelay(0, TimeUnit.SECONDS)
                .build()

        fun startNow(
            workManager: WorkManager,
            threadId: Long,
            forumId: Long? = null,
            seeLz: Boolean = false,
        ) {
            workManager.enqueueUniqueWork(
                "$WORK_PREFIX$threadId",
                ExistingWorkPolicy.REPLACE,
                buildRequest(threadId, forumId, seeLz),
            )
        }
    }
}
