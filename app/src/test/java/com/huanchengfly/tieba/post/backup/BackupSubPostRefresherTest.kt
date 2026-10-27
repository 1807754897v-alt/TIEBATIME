package com.huanchengfly.tieba.post.backup

import com.huanchengfly.tieba.post.models.database.LocalBackupSubPost
import com.huanchengfly.tieba.post.models.database.dao.LocalBackupDao
import com.huanchengfly.tieba.post.repository.PageData
import com.huanchengfly.tieba.post.repository.PbFloorUiResponse
import com.huanchengfly.tieba.post.repository.PbPageRepository
import com.huanchengfly.tieba.post.ui.models.PostData
import com.huanchengfly.tieba.post.ui.models.SubPostItemData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class BackupSubPostRefresherTest {
    private val repository = mockk<PbPageRepository>()
    private val dao = mockk<LocalBackupDao>(relaxed = true)
    private val refresher = BackupSubPostRefresher(repository, dao)
    private fun post(count: Int) = mockk<PostData> {
        every { id } returns 42L
        every { floor } returns 3
        every { subPostNumber } returns count
        every { subPosts } returns emptyList()
    }
    private fun sub(id: Long, text: String) = mockk<SubPostItemData>(relaxed = true) {
        every { this@mockk.id } returns id
        every { plainText } returns text
        every { author.nameShow } returns "测试作者"
    }
    private fun response(page: Int, more: Boolean, vararg subs: SubPostItemData) =
        PbFloorUiResponse(null, subs.toList(), "", mockk(), PageData(current = page, total = 2, hasMore = more))

    @Test
    fun refreshFetchesAllPagesAndReplacesOldRepliesWithoutDuplicates() = runTest {
        coEvery { repository.pbFloor(1, 42, 2, 1) } returns response(1, true, sub(100, "原回复已编辑"))
        coEvery { repository.pbFloor(1, 42, 2, 2) } returns response(2, false, sub(100, "原回复已编辑"), sub(101, "新增回复"))
        refresher.refresh("1_full", 1, 2, post(2), 200)
        val rows = slot<List<LocalBackupSubPost>>()
        coVerify(exactly = 1) { dao.replaceSubPostsByPost("1_full", 42, capture(rows)) }
        assertEquals(listOf(100L, 101L), rows.captured.map { it.subPostId })
        assertEquals(listOf("原回复已编辑", "新增回复"), rows.captured.map { it.content })
    }

    @Test
    fun emptyServerSnapshotClearsDeletedReplies() = runTest {
        refresher.refresh("1_full", 1, 2, post(0), 200)
        coVerify { dao.replaceSubPostsByPost("1_full", 42, emptyList()) }
        coVerify(exactly = 0) { repository.pbFloor(any(), any(), any(), any(), any()) }
    }

    @Test
    fun laterPageFailurePreservesPreviousSnapshot() = runTest {
        coEvery { repository.pbFloor(1, 42, 2, 1) } returns response(1, true, sub(100, "回复"))
        coEvery { repository.pbFloor(1, 42, 2, 2) } throws IOException("offline")
        try {
            refresher.refresh("1_full", 1, 2, post(2), 200)
            fail("Expected fetch failure")
        } catch (_: IOException) { }
        coVerify(exactly = 0) { dao.replaceSubPostsByPost(any(), any(), any()) }
    }

    @Test
    fun pageLimitDoesNotReplaceSnapshotWithTruncatedReplies() = runTest {
        coEvery { repository.pbFloor(1, 42, 2, 1) } returns response(1, true, sub(100, "回复"))
        try {
            refresher.refresh("1_full", 1, 2, post(2), 1)
            fail("Expected page limit")
        } catch (_: IOException) { }
        coVerify(exactly = 0) { dao.replaceSubPostsByPost(any(), any(), any()) }
    }
}
