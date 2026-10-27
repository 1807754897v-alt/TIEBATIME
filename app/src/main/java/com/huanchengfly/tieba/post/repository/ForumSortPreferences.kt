package com.huanchengfly.tieba.post.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.huanchengfly.tieba.post.ui.models.settings.ForumSortType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.forumSortDataStore by preferencesDataStore(name = "forum_sort_preferences")

/** Each forum and tab keeps its own override; unset tabs use the global habit setting. */
@Singleton
class ForumSortPreferences internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.forumSortDataStore)

    private fun prefix(forumName: String) = "forum:${forumName.length}:$forumName:tab:"

    suspend fun load(forumName: String): Map<Int, Int> {
        val prefix = prefix(forumName)
        return dataStore.data.first().asMap().mapNotNull { (key, value) ->
            val tabId = key.name.takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)?.toIntOrNull()
            if (tabId != null && (value == ForumSortType.BY_REPLY || value == ForumSortType.BY_SEND)) {
                tabId to value as Int
            } else null
        }.toMap()
    }

    suspend fun save(forumName: String, tabId: Int, @ForumSortType sortType: Int) {
        require(sortType == ForumSortType.BY_REPLY || sortType == ForumSortType.BY_SEND)
        dataStore.edit { it[intPreferencesKey(prefix(forumName) + tabId)] = sortType }
    }
}
