package com.huanchengfly.tieba.post.backup

import com.huanchengfly.tieba.post.api.models.protos.Page
import com.huanchengfly.tieba.post.models.database.LocalBackupFloor
import com.huanchengfly.tieba.post.models.database.LocalBackupPost
import com.huanchengfly.tieba.post.models.database.dao.LocalBackupDao
import com.huanchengfly.tieba.post.repository.PbPageRepository
import com.huanchengfly.tieba.post.repository.PbPageUiResponse
import com.huanchengfly.tieba.post.ui.models.PostData
import com.huanchengfly.tieba.post.ui.models.ThreadInfoData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class BackupRepositoryIncrementalTest {
    @Test
    fun existingFloorStillRefreshesRepliesWithoutRedownloadingFloorOrImages() = runTest {
        val dao = mockk<LocalBackupDao>(relaxed = true)
        val api = mockk<PbPageRepository>()
        val paths = mockk<BackupPaths> {
            every { imageDir(any()) } returns File("unused")
        }
        val compressor = mockk<BackupImageCompressor>()
        val prefs = mockk<LocalBackupPrefsStore>()
        val refresher = mockk<BackupSubPostRefresher>(relaxed = true)
        coEvery { prefs.snapshot() } returns LocalBackupPrefs()
        val floor = mockk<LocalBackupFloor> { every { postId } returns 42L }
        coEvery { dao.listFloors("1_full") } returns listOf(floor)
        coEvery { dao.getPost("1_full") } returns null
        val post = mockk<PostData> { every { id } returns 42L }
        val thread = mockk<ThreadInfoData>(relaxed = true) {
            every { title } returns "test"
            every { simpleForum } returns Triple(2L, "test", "")
        }
        coEvery { api.pbPage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            PbPageUiResponse(null, post, emptyList(), "", thread, Page(total_page = 1, new_total_page = 1, current_page = 1, has_more = 0), 0)
        val result = BackupRepository(dao, api, paths, compressor, prefs, refresher).backupThread(1, 2)
        assertEquals(LocalBackupPost.STATUS_OK, result.status)
        assertEquals(0, result.floors)
        coVerify(exactly = 1) { refresher.refresh("1_full", 1, 2, post, 200) }
        coVerify(exactly = 0) { dao.insertFloors(any()) }
        coVerify(exactly = 0) { compressor.downloadAndCompress(any(), any(), any(), any()) }
    }
}
