package com.huanchengfly.tieba.post.ui.widgets.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.utils.ReadAloudController
import kotlinx.coroutines.delay

/**
 * 朗读控制胶囊：上一楼 / 进度+定时 / 下一楼 / 停止。
 * 在线 ThreadPage 与离线阅读页共用，出现在底部评论工具栏的位置。
 */
@Composable
fun ReadAloudBar(
    state: ReadAloudController.State,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onSetTimer: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showTimerPicker by remember { mutableStateOf(false) }
    // 定时剩余分钟，每 30 秒刷新
    val timerText by produceState(0L, state.timerEndAt) {
        while (true) {
            value = (state.timerEndAt - System.currentTimeMillis()).coerceAtLeast(0L)
            if (value == 0L && state.timerEndAt == 0L) break
            delay(30_000L)
        }
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = stringResource(id = R.string.tts_prev_floor),
                    )
                }
                Text(
                    text = buildString {
                        append(stringResource(R.string.tts_bar_reading, state.currentFloor, state.totalFloors))
                        if (timerText > 0L) {
                            append(" · ")
                            append(stringResource(R.string.tts_timer_left, timerText / 60_000L + 1))
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = 4.dp),
                )
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = stringResource(id = R.string.tts_next_floor),
                    )
                }
                IconButton(onClick = onPauseResume) {
                    Icon(
                        imageVector = if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = stringResource(
                            id = if (state.paused) R.string.tts_resume else R.string.tts_pause
                        ),
                    )
                }
                IconButton(onClick = { showTimerPicker = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Timer,
                        contentDescription = stringResource(id = R.string.tts_timer),
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = stringResource(id = R.string.local_backup_tts_stop),
                    )
                }
            }
            LinearProgressIndicator(
                progress = {
                    state.currentFloor / state.maxFloor.coerceAtLeast(1).toFloat()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )
        }
    }

    if (showTimerPicker) {
        AlertDialog(
            onDismissRequest = { showTimerPicker = false },
            title = { Text(stringResource(id = R.string.tts_timer_pick)) },
            text = {
                Column {
                    listOf(0, 15, 30, 60).forEach { minutes ->
                        TextButton(
                            onClick = {
                                showTimerPicker = false
                                onSetTimer(minutes)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (minutes == 0) stringResource(id = R.string.tts_timer_off_option)
                                else stringResource(id = R.string.tts_timer_option, minutes),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTimerPicker = false }) {
                    Text(stringResource(id = R.string.action_cancel))
                }
            },
        )
    }
}
