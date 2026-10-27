package com.huanchengfly.tieba.post.ui.widgets.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.huanchengfly.tieba.post.ui.widgets.VoicePlayerView

@Composable
fun VoicePlayer(
    url: String,
    duration: Int,
    modifier: Modifier = Modifier
) {
    val viewState = remember { mutableStateOf<VoicePlayerView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = {
            VoicePlayerView(it).also { view -> viewState.value = view }
        },
        update = {
            it.url = url
            it.duration = duration
        }
    )
    DisposableEffect(key1 = url) {
        onDispose {
            // ExoPlayer 是全局单例：滚动列表时任意一条 dispose 都不能把它整个释放，
            // 否则会把正在播的那条（甚至自己）打断
            VoicePlayerView.Manager.releaseIfPlaying(viewState.value)
        }
    }
}
