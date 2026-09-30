package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.huanchengfly.tieba.post.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** 一条待朗读的内容（楼层号 + 文本） */
data class ReadAloudItem(val floor: Int, val text: String)

/** 朗读前清洗文本：去掉贴吧表情码 #(...) 与 [图片] 占位 */
fun String.sanitizeForTts(): String =
    replace(Regex("#\\([^)]*\\)"), "")
        .replace("[图片]", "")
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * 朗读控制器：在线 ThreadPage 与离线阅读页共用。
 *
 * - 引擎：先试系统默认，失败则枚举设备上全部 TTS_SERVICE 引擎显式按包名绑定
 *   （国产 ROM 常见「有引擎但未设系统默认」，如 MIUI 小爱语音引擎），语言逐个校验。
 * - 跳楼：previous()/next() 在已加载楼层间自由跳转。
 * - 定时：setSleepTimer/cycleSleepTimer 到时自动停止。
 */
class ReadAloudController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
) {
    data class State(
        val index: Int,
        val currentFloor: Int,
        val totalFloors: Int,
        /** 已加载的最大楼层号，进度条按 currentFloor/maxFloor 显示 */
        val maxFloor: Int = currentFloor,
        /** 正在朗读的帖子 id，用于页面匹配显示朗读条 */
        val threadId: Long = 0,
        /** 定时停止的时间点（epoch ms），0 = 未设定 */
        val timerEndAt: Long = 0L,
        /** true = 已暂停（保留进度，可继续） */
        val paused: Boolean = false,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private var items: List<ReadAloudItem> = emptyList()
    private var pendingIndex = -1
    private var pendingThreadId: Long = 0
    private var currentTitle: String? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var engineQueue: ArrayDeque<String?>? = null
    private var currentEngine: String? = null
    private var triedAny = false
    private var timerJob: Job? = null

    private val notifier by lazy {
        ReadAloudNotifier.ensureChannel(context)
        ReadAloudNotifier(
            context = context,
            onPrevious = { previous() },
            onToggle = { togglePauseResume() },
            onNext = { next() },
            onStop = { stop() },
        )
    }

    companion object {
        const val TAG = "ReadAloudController"

        /**
         * 应用级单例：朗读与媒体控件不随帖子页销毁而中断，
         * 重新进入同一帖子时朗读条自动恢复。
         */
        @Volatile
        private var instance: ReadAloudController? = null

        fun getInstance(context: Context): ReadAloudController =
            instance ?: synchronized(this) {
                instance ?: ReadAloudController(
                    context = context.applicationContext,
                    scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate + kotlinx.coroutines.SupervisorJob()),
                ) { msg ->
                    android.widget.Toast.makeText(context.applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                }.also { instance = it }
            }
    }

    /** 从 [startIndex] 开始朗读（长按哪层就从哪层开始） */
    fun start(newItems: List<ReadAloudItem>, startIndex: Int = 0, title: String? = null, threadId: Long = 0) {
        if (newItems.isEmpty()) return
        items = newItems
        currentTitle = title
        pendingThreadId = threadId
        speakAt(startIndex.coerceIn(0, newItems.lastIndex))
    }

    fun next() {
        val s = _state.value ?: return
        _state.value = s.copy(paused = false)
        speakAt((s.index + 1).coerceAtMost(items.lastIndex))
    }

    fun previous() {
        val s = _state.value ?: return
        _state.value = s.copy(paused = false)
        speakAt((s.index - 1).coerceAtLeast(0))
    }

    fun togglePauseResume() {
        if (_state.value?.paused == true) resume() else pause()
    }

    fun pause() {
        val st = _state.value ?: return
        if (st.paused) return
        runCatching { tts?.stop() }
        _state.value = st.copy(paused = true)
        notifier.update(st.currentFloor, st.totalFloors, paused = true, threadTitle = currentTitle)
    }

    fun resume() {
        val st = _state.value ?: return
        if (!st.paused) return
        _state.value = st.copy(paused = false)
        if (ttsReady) speakPending() else ensureEngine()
    }

    fun stop() {
        runCatching { tts?.stop() }
        pendingIndex = -1
        items = emptyList()
        _state.value = null
        runCatching { notifier.cancel() }
    }

    fun shutdown() {
        stop()
        runCatching {
            tts?.shutdown()
            tts = null
        }
    }

    /** 关闭 → 15 → 30 → 60 → 关闭 */
    fun cycleSleepTimer() {
        val now = System.currentTimeMillis()
        val remaining = (_state.value?.timerEndAt ?: 0L) - now
        val next = when {
            remaining <= 0 -> 15
            remaining <= 15 * 60_000L + 5_000 -> 30
            remaining <= 30 * 60_000L + 5_000 -> 60
            else -> 0
        }
        setSleepTimer(next)
    }

    fun setSleepTimer(minutes: Int) {
        timerJob?.cancel()
        timerJob = null
        val endAt = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value?.copy(timerEndAt = endAt)
        if (minutes > 0) {
            onMessage(context.getString(R.string.tts_timer_set, minutes))
            timerJob = scope.launch {
                delay(minutes * 60_000L)
                if (_state.value != null) {
                    stop()
                    onMessage(context.getString(R.string.tts_timer_done))
                }
            }
        } else {
            onMessage(context.getString(R.string.tts_timer_off))
        }
    }

    // ------------------------------------------------------------------ impl

    private fun speakAt(index: Int) {
        if (items.isEmpty()) return
        val safeIndex = index.coerceIn(0, items.lastIndex)
        pendingIndex = safeIndex
        val item = items[safeIndex]
        _state.value = (_state.value ?: State(safeIndex, item.floor, items.size, items.maxOf { it.floor }, pendingThreadId))
            .copy(index = safeIndex, currentFloor = item.floor, totalFloors = items.size, paused = false)
        runCatching { tts?.stop() }
        notifier.update(_state.value!!.currentFloor, _state.value!!.totalFloors, paused = false, threadTitle = currentTitle)
        if (ttsReady) {
            speakPending()
        } else {
            ensureEngine()
        }
    }

    private fun speakPending() {
        val index = pendingIndex
        if (index !in items.indices) return
        val item = items[index]
        val queued = tts?.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, "read_aloud")
        if (queued == TextToSpeech.ERROR) {
            onMessage(context.getString(R.string.local_backup_tts_unavailable))
            stop()
        }
    }

    private fun advance() {
        val s = _state.value ?: return
        val nextIndex = s.index + 1
        if (nextIndex >= items.size) {
            stop()
            onMessage(context.getString(R.string.tts_read_done))
        } else {
            speakAt(nextIndex)
        }
    }

    private fun ensureEngine() {
        if (tts != null) {
            if (ttsReady) speakPending()
            return
        }
        if (engineQueue == null) {
            val queue = ArrayDeque<String?>()
            queue.add(null) // 系统默认引擎优先
            runCatching {
                context.packageManager.queryIntentServices(
                    Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
                    0,
                )
            }.getOrNull()?.forEach { info ->
                val pkg = info.serviceInfo.packageName
                if (!queue.contains(pkg)) queue.add(pkg)
            }
            engineQueue = queue
        }
        startNextEngine()
    }

    private fun startNextEngine() {
        val queue = engineQueue
        val candidate = queue?.removeFirstOrNull()
        if (candidate == null && (queue == null || queue.isEmpty()) && triedAny) {
            onMessage(context.getString(R.string.tts_no_engine))
            stop()
            return
        }
        runCatching { tts?.shutdown() }
        ttsReady = false
        currentEngine = candidate
        triedAny = true
        Log.d(TAG, "TTS trying engine=${candidate ?: "(system default)"}")
        val engine = if (candidate == null) {
            TextToSpeech(context, initListener)
        } else {
            TextToSpeech(context, initListener, candidate)
        }
        engine.setOnUtteranceProgressListener(utteranceListener)
        tts = engine
    }

    private val initListener = TextToSpeech.OnInitListener { status ->
        if (status != TextToSpeech.SUCCESS) {
            Log.d(TAG, "TTS engine=$currentEngine init failed")
            startNextEngine()
            return@OnInitListener
        }
        val current = tts ?: return@OnInitListener
        val candidates = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.CHINESE,
            Locale.getDefault(),
        )
        val supported = candidates.firstOrNull { locale ->
            current.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
        }
        if (supported == null || current.setLanguage(supported) == TextToSpeech.LANG_MISSING_DATA) {
            Log.d(TAG, "TTS engine=$currentEngine no Chinese, trying next")
            startNextEngine()
            return@OnInitListener
        }
        Log.d(TAG, "TTS engine=$currentEngine ready lang=$supported")
        ttsReady = true
        speakPending()
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            scope.launch { if (_state.value != null) advance() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            scope.launch {
                if (_state.value != null) {
                    onMessage(context.getString(R.string.tts_error, errorCode))
                    stop()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, -1)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
    }
}
