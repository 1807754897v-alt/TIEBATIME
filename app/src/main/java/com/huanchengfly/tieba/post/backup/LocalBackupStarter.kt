package com.huanchengfly.tieba.post.backup

import android.content.Context
import androidx.work.WorkManager
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.toastShort
import com.huanchengfly.tieba.post.workers.LocalBackupWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry points to start local backup from list menus / favorite flows.
 */
@Singleton
class LocalBackupStarter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsStore: LocalBackupPrefsStore,
) {
    fun start(
        threadId: Long,
        forumId: Long? = null,
        seeLz: Boolean = false,
        notify: Boolean = true,
    ) {
        LocalBackupWorker.startNow(
            workManager = WorkManager.getInstance(context),
            threadId = threadId,
            forumId = forumId,
            seeLz = seeLz,
        )
        if (notify) {
            context.toastShort(R.string.toast_backup_started)
        }
    }

    suspend fun prefs(): LocalBackupPrefs = prefsStore.snapshot()

    suspend fun setSyncOnFavorite(enabled: Boolean) {
        prefsStore.update { it.copy(syncOnFavorite = enabled) }
    }

    suspend fun dismissFavoritePrompt() {
        prefsStore.update { it.copy(favoritePromptDismissed = true) }
    }

    /**
     * After a successful favorite: silently start a backup when sync-on-favorite is enabled.
     * (The first-time prompt dialog was removed by product decision 2026-09-30.)
     */
    suspend fun afterFavorite(threadId: Long, forumId: Long?, seeLz: Boolean) {
        val prefs = prefsStore.snapshot()
        if (prefs.syncOnFavorite) {
            start(threadId, forumId, seeLz, notify = false)
        }
    }
}
