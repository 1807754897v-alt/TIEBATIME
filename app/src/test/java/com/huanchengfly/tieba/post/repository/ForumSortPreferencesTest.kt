package com.huanchengfly.tieba.post.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.huanchengfly.tieba.post.ui.models.settings.ForumSortType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ForumSortPreferencesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun selectionSurvivesClosingAndReopeningDataStore() = runBlocking {
        val file = File(temp.root, "sort.preferences_pb")
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
            val prefs = ForumSortPreferences(store)
            assertTrue(prefs.load("测试吧").isEmpty())
            // Android's JVM FileStorage cannot replace existing files on Windows.
            // A single disk write verifies durability; repeated edits are covered below.
            prefs.save("测试吧", 0, ForumSortType.BY_SEND)
        } finally {
            job.cancelAndJoin()
        }
        val reopenedJob = SupervisorJob()
        try {
            val reopened = ForumSortPreferences(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + reopenedJob)
            ) { file })
            assertEquals(mapOf(0 to ForumSortType.BY_SEND), reopened.load("测试吧"))
        } finally {
            reopenedJob.cancelAndJoin()
        }
    }

    @Test
    fun repeatedSelectionsStayIndependentPerForumAndTab() = runBlocking {
        val values = MutableStateFlow<Preferences>(emptyPreferences())
        val store = mockk<DataStore<Preferences>> {
            every { data } returns values
            coEvery { updateData(any()) } coAnswers {
                firstArg<suspend (Preferences) -> Preferences>()(values.value).also { values.value = it }
            }
        }
        val prefs = ForumSortPreferences(store)
        prefs.save("测试吧", 0, ForumSortType.BY_REPLY)
        prefs.save("测试吧", 7, ForumSortType.BY_SEND)
        prefs.save("另一个吧", 0, ForumSortType.BY_SEND)
        val reopened = ForumSortPreferences(store)
        assertEquals(mapOf(0 to ForumSortType.BY_REPLY, 7 to ForumSortType.BY_SEND), reopened.load("测试吧"))
        assertEquals(mapOf(0 to ForumSortType.BY_SEND), reopened.load("另一个吧"))
        assertTrue(reopened.load("测试").isEmpty())
        reopened.save("测试吧", 0, ForumSortType.BY_SEND)
        assertEquals(ForumSortType.BY_SEND, reopened.load("测试吧")[0])
    }
}
