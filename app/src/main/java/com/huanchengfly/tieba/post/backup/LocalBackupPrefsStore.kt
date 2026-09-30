package com.huanchengfly.tieba.post.backup

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences for local post backup (Shelf-in-Lite).
 *
 * Separate from SettingsRepository so the existing 「设置备份」 feature
 * stays unambiguous.
 */
data class LocalBackupPrefs(
    val imagePolicy: ImagePolicy = ImagePolicy.WEBP_SMALL,
    val syncOnFavorite: Boolean = false,
    val favoritePromptDismissed: Boolean = false,
    val maxPages: Int = 200,
    val autoSeeLz: Boolean = false,
) {
    enum class ImagePolicy {
        ORIGINAL,
        WEBP_SMALL,
        WEBP_TINY,
        NONE,
    }
}

private val Context.localBackupDataStore by preferencesDataStore(name = "local_backup_prefs")

private val KEY_IMAGE_POLICY = stringPreferencesKey("image_policy")
private val KEY_SYNC_ON_FAVORITE = booleanPreferencesKey("sync_on_favorite")
private val KEY_FAVORITE_PROMPT_DISMISSED = booleanPreferencesKey("favorite_prompt_dismissed")
private val KEY_MAX_PAGES = intPreferencesKey("max_pages")
private val KEY_AUTO_SEE_LZ = booleanPreferencesKey("auto_see_lz")

@Singleton
class LocalBackupPrefsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.localBackupDataStore

    val prefs: Flow<LocalBackupPrefs> = dataStore.data
        .map { it.toPrefs() }
        .flowOn(Dispatchers.IO)

    suspend fun snapshot(): LocalBackupPrefs = prefs.first()

    suspend fun update(transform: (LocalBackupPrefs) -> LocalBackupPrefs) {
        val new = transform(snapshot())
        dataStore.edit { p ->
            p[KEY_IMAGE_POLICY] = new.imagePolicy.name
            p[KEY_SYNC_ON_FAVORITE] = new.syncOnFavorite
            p[KEY_FAVORITE_PROMPT_DISMISSED] = new.favoritePromptDismissed
            p[KEY_MAX_PAGES] = new.maxPages
            p[KEY_AUTO_SEE_LZ] = new.autoSeeLz
        }
    }
}

private fun Preferences.toPrefs(): LocalBackupPrefs {
    val policy = this[KEY_IMAGE_POLICY]
        ?.let { name -> LocalBackupPrefs.ImagePolicy.entries.firstOrNull { it.name == name } }
        ?: LocalBackupPrefs.ImagePolicy.WEBP_SMALL
    return LocalBackupPrefs(
        imagePolicy = policy,
        syncOnFavorite = this[KEY_SYNC_ON_FAVORITE] ?: false,
        favoritePromptDismissed = this[KEY_FAVORITE_PROMPT_DISMISSED] ?: false,
        maxPages = this[KEY_MAX_PAGES] ?: 200,
        autoSeeLz = this[KEY_AUTO_SEE_LZ] ?: false,
    )
}
