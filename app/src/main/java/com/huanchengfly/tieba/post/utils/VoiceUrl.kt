package com.huanchengfly.tieba.post.utils

/**
 * 贴吧语音取流地址。
 * c.tieba.baidu.com 是老客户端实际取到音频的域名，tiebac 只是图片 CDN（对 /c/p/voice 回空页面），
 * 保留它只作为最后兜底。
 */
object VoiceUrl {
    private const val PATH = "/c/p/voice?voice_md5=%s&play_from=pb_voice_play"

    fun online(md5: String): String = "https://c.tieba.baidu.com" + PATH.format(md5)

    fun candidates(md5: String): List<String> = listOf(
        "https://c.tieba.baidu.com" + PATH.format(md5),
        "http://c.tieba.baidu.com" + PATH.format(md5),
        "https://tiebac.baidu.com" + PATH.format(md5),
    )
}
