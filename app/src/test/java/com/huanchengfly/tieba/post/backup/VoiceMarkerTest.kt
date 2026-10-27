package com.huanchengfly.tieba.post.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 语音标记 `[语音:md5:时长(ms)]` 的两端约定：备份写入 → 离线还原 / 导出 / 朗读。
 * 这里回归的是「备份帖里语音显示成一串 md5 乱码」和「导出把 md5 当正文」两个问题。
 */
class VoiceMarkerTest {

    private val md5 = "0123456789abcdef0123456789abcdef"

    @Test
    fun markerSurvivesRoundTrip() {
        val content = "第一段" + VoiceMarker.encode(md5, 12_000) + "结尾"
        val hits = VoiceMarker.findAll(content).toList()
        assertEquals(1, hits.size)
        assertEquals(md5, hits[0].md5)
        assertEquals(12_000, hits[0].durationMs)
        // 离线渲染按这个区间切片：前文 + 语音条 + 后文
        assertEquals("第一段", content.substring(0, hits[0].start))
        assertEquals("结尾", content.substring(hits[0].end + 1))
    }

    @Test
    fun severalVoicesKeepTheirOrder() {
        val second = "aabbccddeeff00112233445566778899"
        val content = VoiceMarker.encode(md5, 3_000) + "中间" + VoiceMarker.encode(second, 8_000)
        assertEquals(listOf(md5, second), VoiceMarker.findAll(content).map { it.md5 }.toList())
    }

    @Test
    fun voiceWithoutMd5NeverBecomesAMarker() {
        assertNull(VoiceMarker.encode("", 5_000))
        assertNull(VoiceMarker.encode(null, 5_000))
        assertNull(VoiceMarker.encode("ab12", 5_000))
    }

    @Test
    fun markerWithoutDurationStillPlays() {
        val hits = VoiceMarker.findAll("[语音:$md5]").toList()
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].durationMs)
    }

    @Test
    fun brokenMarkersAreDowngradedInsteadOfShowingRawText() {
        // 空 md5 / 被截断的标记都不能把原始串露在正文里
        assertEquals("前[语音]后", VoiceMarker.normalize("前[语音::2000]后"))
        assertEquals("[语音]", VoiceMarker.normalize("[语音:0123"))
        assertEquals("[语音]", VoiceMarker.normalize("[语音]"))
    }

    @Test
    fun exportsShowSecondsInsteadOfMd5() {
        val readable = VoiceMarker.readable("哈" + VoiceMarker.encode(md5, 12_000) + "哈")
        assertEquals("哈[语音 12秒]哈", readable)
        assertFalse(readable.contains(md5))
    }

    @Test
    fun readAloudDropsVoiceEntirely() {
        assertEquals("哈哈", VoiceMarker.strip("哈" + VoiceMarker.encode(md5, 12_000) + "哈"))
        assertEquals("哈哈", VoiceMarker.strip("哈[语音::2000]哈"))
    }
}
