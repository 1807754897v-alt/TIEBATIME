package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.utils.helios.Base32
import com.huanchengfly.tieba.post.utils.helios.Hasher

object CuidUtils {
    // 依赖的 androidId 在设备生命周期内不变，缓存计算结果供每请求 header 复用
    private val cachedNewCuid: String by lazy {
        val cuid = UIDUtil.cUID
        val encode = Base32.encode(Hasher.hash(cuid.toByteArray()))
        "$cuid|V$encode"
    }

    fun getNewCuid(): String = cachedNewCuid
}