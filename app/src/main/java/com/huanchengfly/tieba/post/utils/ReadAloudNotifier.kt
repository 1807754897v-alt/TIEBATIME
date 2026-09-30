package com.huanchengfly.tieba.post.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.huanchengfly.tieba.post.R

/**
 * 朗读的原生媒体控件：MediaSession + 媒体样式通知（锁屏/通知栏可控制
 * 上一楼/播放暂停/下一楼/停止），配合系统媒体控制中心。
 */
class ReadAloudNotifier(
    private val context: Context,
    private val onPrevious: () -> Unit,
    private val onToggle: () -> Unit,   // pause/resume
    private val onNext: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val mediaSession: MediaSessionCompat = MediaSessionCompat(context, "TieBaTimeReadAloud").apply {
        isActive = true
        // 系统媒体卡片（通知/控制中心）的按钮走 MediaSession 回调，必须接线
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = this@ReadAloudNotifier.onToggle()
            override fun onPause() = this@ReadAloudNotifier.onToggle()
            override fun onSkipToNext() = this@ReadAloudNotifier.onNext()
            override fun onSkipToPrevious() = this@ReadAloudNotifier.onPrevious()
            override fun onStop() = this@ReadAloudNotifier.onStop()
        })
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PREVIOUS -> onPrevious()
                ACTION_TOGGLE -> onToggle()
                ACTION_NEXT -> onNext()
                ACTION_STOP -> onStop()
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(ACTION_PREVIOUS)
                    addAction(ACTION_TOGGLE)
                    addAction(ACTION_NEXT)
                    addAction(ACTION_STOP)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(ACTION_PREVIOUS)
                    addAction(ACTION_TOGGLE)
                    addAction(ACTION_NEXT)
                    addAction(ACTION_STOP)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun playbackState(paused: Boolean) {
        val state = if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, 0L, 1.0f)
                .build()
        )
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, context.getString(R.string.tts_notif_title))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                .build()
        )
    }

    /** 朗读进行中：更新媒体样式通知 */
    fun update(currentFloor: Int, totalFloors: Int, paused: Boolean, threadTitle: String? = null) {
        if (!NotificationUtils.checkPermission(context)) return
        mediaSession.isActive = true
        threadTitle?.let {
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, it)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, context.getString(R.string.tts_notif_title))
                    .build()
            )
        }
        playbackState(paused)
        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_round_drafts)
            .setContentTitle(context.getString(R.string.tts_notif_title))
            .setContentText(
                context.getString(R.string.tts_bar_reading, currentFloor, totalFloors)
            )
            .setOngoing(!paused)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(style)
            .addAction(R.drawable.ic_round_skip_previous, context.getString(R.string.tts_prev_floor), actionIntent(ACTION_PREVIOUS))
            .addAction(
                if (paused) R.drawable.ic_round_play_circle_filled_18dp else R.drawable.ic_round_pause_circle_filled_18dp,
                context.getString(if (paused) R.string.tts_resume else R.string.tts_pause),
                actionIntent(ACTION_TOGGLE),
            )
            .addAction(R.drawable.ic_round_skip_next, context.getString(R.string.tts_next_floor), actionIntent(ACTION_NEXT))
            .addAction(R.drawable.ic_round_close, context.getString(R.string.local_backup_tts_stop), actionIntent(ACTION_STOP))
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(0)
                .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 1.0f)
                .build()
        )
        // 关键：session 失活，否则系统媒体控制卡片残留
        mediaSession.isActive = false
    }

    fun release() {
        runCatching {
            context.unregisterReceiver(receiver)
            mediaSession.release()
        }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "read_aloud"
        const val NOTIFICATION_ID = 100082
        const val ACTION_PREVIOUS = "com.huanchengfly.tieba.post.tts.PREVIOUS"
        const val ACTION_TOGGLE = "com.huanchengfly.tieba.post.tts.TOGGLE"
        const val ACTION_NEXT = "com.huanchengfly.tieba.post.tts.NEXT"
        const val ACTION_STOP = "com.huanchengfly.tieba.post.tts.STOP"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationUtils.createChannel(
                    channelId = CHANNEL_ID,
                    name = context.getString(R.string.tts_notif_channel),
                    importance = NotificationManagerCompat.IMPORTANCE_LOW,
                )
            }
        }
    }
}
