package com.huanchengfly.tieba.post.backup

import com.huanchengfly.tieba.post.ui.common.PbContentRender

/**
 * 备份正文里的语音标记：`[语音:md5:时长(ms)]`
 *
 * plainText 只有 `[语音]` 占位，md5 与时长只能从 contentRenders 取，按出现顺序回填；
 * 离线渲染再用本标记还原成可播放条目。
 */
object VoiceMarker {
    // 时长在旧数据里可能缺失，md5 才是能否播放的关键
    private val REGEX = Regex("\\[语音:([0-9a-zA-Z]{6,64})(?::(\\d+))?]")

    /** 只有拿到 md5 才写可还原标记，否则退回 `[语音]`，避免把空 md5 当正文显示 */
    fun encode(md5: String?, durationMs: Int?): String? {
        if (md5.isNullOrBlank()) return null
        val safeMd5 = md5.filter { it.isLetterOrDigit() }
        if (safeMd5.length < 6) return null
        return "[语音:$safeMd5:${durationMs ?: 0}]"
    }

    data class Hit(val start: Int, val end: Int, val md5: String, val durationMs: Int)

    fun findAll(content: String): Sequence<Hit> =
        REGEX.findAll(content).map { m ->
            Hit(
                start = m.range.first,
                end = m.range.last,
                md5 = m.groupValues[1],
                durationMs = m.groupValues[2].toIntOrNull() ?: 0,
            )
        }

    /**
     * 无法还原的残留语音标记降级为 `[语音]`：空 md5、字段被截断、甚至没有右括号（旧版本
     * 抓下的脏正文）。正文里任何 `[语音…` 片段都不该以原样出现，否则就是一串 md5 乱码。
     */
    fun normalize(content: String): String =
        content.replace(Regex("\\[语音(?:[^\\]]{0,80}]|[^\\]]+)")) { m ->
            if (REGEX.matchEntire(m.value) != null) m.value else PbContentRender.MEDIA_VOICE
        }

    /** 给导出/朗读用的可读形式：`[语音:md5:毫秒]` → `[语音 12秒]`，正文里不出现 md5 */
    fun readable(content: String): String =
        REGEX.replace(normalize(content)) { m ->
            "[语音 ${seconds(m.groupValues[2].toIntOrNull() ?: 0)}秒]"
        }

    /** 朗读用：标记和 `[语音]` 占位全部去掉（TTS 念 md5 是噪音） */
    fun strip(content: String): String =
        REGEX.replace(normalize(content), "").replace(PbContentRender.MEDIA_VOICE, "")

    fun seconds(durationMs: Int): Int = (durationMs / 1000).coerceAtLeast(0)
}
