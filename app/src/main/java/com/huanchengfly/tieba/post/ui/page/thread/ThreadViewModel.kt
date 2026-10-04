package com.huanchengfly.tieba.post.ui.page.thread

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.compose.ui.unit.IntSize
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.Error
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.booleanToString
import com.huanchengfly.tieba.post.api.models.protos.Page
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorCode
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseStateViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.TbLiteExceptionHandler
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.backup.BackupRepository
import com.huanchengfly.tieba.post.backup.LocalBackupStarter
import com.huanchengfly.tieba.post.components.ClipBoardLinkDetector
import com.huanchengfly.tieba.post.models.PicItem
import com.huanchengfly.tieba.post.models.database.LocalBackupFloor
import com.huanchengfly.tieba.post.models.database.LocalBackupImage
import com.huanchengfly.tieba.post.models.database.LocalBackupSubPost
import com.huanchengfly.tieba.post.models.PhotoViewData
import com.huanchengfly.tieba.post.models.database.ThreadHistory
import com.huanchengfly.tieba.post.repository.HistoryRepository
import com.huanchengfly.tieba.post.repository.PageData
import com.huanchengfly.tieba.post.repository.PbPageRepository
import com.huanchengfly.tieba.post.repository.PbPageUiResponse
import com.huanchengfly.tieba.post.repository.ThreadStoreRepository
import com.huanchengfly.tieba.post.repository.user.SettingsRepository
import com.huanchengfly.tieba.post.ui.common.PicContentRender
import com.huanchengfly.tieba.post.ui.common.PureTextContentRender
import com.huanchengfly.tieba.post.ui.common.TextContentRender
import com.huanchengfly.tieba.post.ui.common.VoiceContentRender
import com.huanchengfly.tieba.post.ui.models.LikeZero
import com.huanchengfly.tieba.post.ui.models.PostData
import com.huanchengfly.tieba.post.ui.models.SimpleForum
import com.huanchengfly.tieba.post.ui.models.SubPostItemData
import com.huanchengfly.tieba.post.ui.models.ThreadInfoData
import com.huanchengfly.tieba.post.ui.models.UserData
import com.huanchengfly.tieba.post.ui.page.Destination
import com.huanchengfly.tieba.post.ui.page.Destination.Companion.navTypeOf
import com.huanchengfly.tieba.post.ui.page.Destination.Reply
import com.huanchengfly.tieba.post.ui.page.Destination.SubPosts
import com.huanchengfly.tieba.post.ui.page.threadstore.ThreadStoreUiEvent
import com.huanchengfly.tieba.post.utils.EmoticonUtil.emoticonString
import com.huanchengfly.tieba.post.utils.ImageUtil
import com.huanchengfly.tieba.post.utils.ReadAloudController
import com.huanchengfly.tieba.post.utils.ReadAloudItem
import com.huanchengfly.tieba.post.utils.sanitizeForTts
import com.huanchengfly.tieba.post.utils.TiebaUtil
import com.huanchengfly.tieba.post.utils.extension.set
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.reflect.typeOf

@Stable
@HiltViewModel
class ThreadViewModel @Inject constructor(
    @ApplicationContext val context: Context,
    private val historyRepo: HistoryRepository,
    private val storeRepo: ThreadStoreRepository,
    private val threadRepo: PbPageRepository,
    private val localBackupStarter: LocalBackupStarter,
    private val backupRepository: BackupRepository,
    settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle
) : BaseStateViewModel<ThreadUiState>() {

    private val params = savedStateHandle.toRoute<Destination.Thread>(
        typeMap = mapOf(typeOf<ThreadFrom?>() to navTypeOf<ThreadFrom?>(isNullableAllowed = true))
    )

    private val threadId: Long = params.threadId
    private val postId: Long = params.postId
    private val historyTimeStamp = System.currentTimeMillis()

    private var from: String = params.from?.tag ?: ""

    /** 朗读（TTS）：应用级单例，退出页面朗读与媒体控件继续存活，重进本帖自动恢复朗读条 */
    val readAloud = ReadAloudController.getInstance(context)

    /** 非空 = 离线阅读本地备份：数据全部来自 Room，无网可用 */
    private val localBackupId: String? = params.localBackupId
    val isOfflineBackup: Boolean
        get() = localBackupId != null

    private var offlineAllPosts: List<PostData> = emptyList()

    /**
     * Post or Thread(FirstPost) marked for deletion.
     *
     * @see onDeletePost
     * @see onDeleteThread
     * */
    private val _deletePost: MutableStateFlow<PostData?> = MutableStateFlow(null)
    val deletePost: StateFlow<PostData?> = _deletePost.asStateFlow()

    var isImmersiveMode by mutableStateOf(false)
        private set

    var hideReply by mutableStateOf(false)
        private set

    private val isRefreshing: Boolean
        get() = currentState.isRefreshing

    private val isLoadingMore: Boolean
        get() = currentState.isLoadingMore

    suspend fun loadSubPostPhotoData(
        post: PostData,
        subPost: SubPostItemData,
        photoIndex: Int,
    ): PhotoViewData? {
        val thread = currentState.thread ?: return null
        val photos = threadRepo.getSubPostPhotos(
            threadId = thread.id,
            postId = post.id,
            forumId = thread.simpleForum.first,
            subPostId = subPost.id,
        ) ?: return null

        return photos.getOrNull(photoIndex)?.photoViewData
    }

    /**
     * Job of Add/Update/Remove thread collections, cancelable.
     *
     * @see updateCollections
     * @see removeFromCollections
     * */
    private var collectionsJob: Job? = null

    override val errorHandler = TbLiteExceptionHandler(TAG) { _, e, _ ->
        _uiState.update {
            it.copy(isRefreshing = false, isLoadingMore = false, isLoadingLatestReply = false, error = e)
        }
    }

    private val loadMoreHandler = CoroutineExceptionHandler { context, e ->
        if (e.getErrorCode() == Error.ERROR_POST_NOMORE || currentState.data.isNotEmpty()) {
            _uiState.update {
                it.copy(isRefreshing = false, isLoadingMore = false, isLoadingLatestReply = false, error = null)
            }
            if (e.getErrorCode() == Error.ERROR_POST_NOMORE) {
                sendUiEvent(CommonUiEvent.Toast(this.context.getString(R.string.no_more)))
            } else {
                sendUiEvent(CommonUiEvent.ToastError(e))
            }
        } else {
            errorHandler.handleException(context = context, exception = e)
        }
    }

    private val firstPostId: Long
        get() = currentState.firstPost?.id ?: 0L

    private val forumId: Long?
        get() = params.forumId ?: currentState.forum?.first

    private val forumName: String?
        get() = currentState.forum?.second

    override fun createInitialState(): ThreadUiState {
        return ThreadUiState(seeLz = params.seeLz, sortType = params.sortType)
    }

    init {
        if (isOfflineBackup) {
            loadLocalBackup()
        } else {
            requestLoad(page = 0, postId = postId, scrollToReply = params.scrollToReply)
        }
        viewModelScope.launch {
            hideReply = settingsRepository.habitSettings.snapshot().hideReply
        }
    }

    /**
     * 离线装载：本地楼层 → 原生 UI 状态（firstPost/data/thread/pageData），
     * 之后沉浸阅读、只看楼主、倒序、跳页、长按菜单、朗读全部照常工作。
     */
    private fun loadLocalBackup() {
        val backupId = localBackupId ?: return
        launchInVM {
            _uiState.update { it.copy(isRefreshing = true) }
            runCatching {
                val post = backupRepository.getPost(backupId)
                    ?: throw IllegalStateException(context.getString(R.string.local_backup_tts_unavailable))
                val floors = backupRepository.getFloors(backupId).sortedBy { it.floorNumber }
                val imagesByFloor = backupRepository.getImages(backupId).groupBy { it.floorNumber }
                val imagesByUrl = imagesByFloor.values.flatten().associateBy { it.originalUrl }
                val subPostsByPost = backupRepository.listSubPosts(backupId).groupBy { it.postId }

                // 全帖图集：任意楼层点开图片都能在整个帖子的图片间滑动
                fun imageSrc(url: String): String {
                    val local = imagesByUrl[url]
                    return local?.let { "file://" + it.localPath } ?: url
                }
                val globalPicItems = floors.flatMap { f ->
                    f.imageUrls.split('\n')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .mapIndexed { i, url ->
                            PicItem(
                                picId = ImageUtil.getPicId(url),
                                picIndex = i + 1,
                                originUrl = imageSrc(url),
                                postId = f.postId,
                            )
                        }
                }
                // 全局索引映射：查看器按此定位打开位置（楼内索引会导致永远打开第一张）
                val globalIndexByUrl = HashMap<String, Int>()
                globalPicItems.forEachIndexed { idx, item ->
                    globalIndexByUrl.putIfAbsent(item.originUrl, idx)
                }
                val posts = floors.map { floor ->
                    floor.toOfflinePostData(
                        images = imagesByFloor[floor.floorNumber].orEmpty(),
                        imagesByUrl = imagesByUrl,
                        subPosts = subPostsByPost[floor.postId].orEmpty(),
                        globalPicItems = globalPicItems,
                        globalIndexByUrl = globalIndexByUrl,
                    )
                }
                val firstPost = posts.firstOrNull { it.floor <= 1 } ?: posts.firstOrNull()
                offlineAllPosts = posts
                val data = posts.filter { it.id != firstPost?.id }
                val threadInfo = ThreadInfoData(
                    id = post.threadId,
                    title = post.title,
                    collectMarkPid = null,
                    firstPostId = firstPost?.id ?: 0L,
                    like = LikeZero,
                    originThreadInfo = null,
                    replyNum = post.totalFloors ?: floors.size,
                    simpleForum = SimpleForum(
                        post.forumId ?: 0L,
                        post.forumName ?: "",
                        post.forumAvatar?.let { "file://" + it } ?: "",
                    ),
                    pollInfo = null,
                )
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        error = null,
                        thread = threadInfo,
                        firstPost = firstPost,
                        data = data,
                        tbs = null,
                        pageData = PageData(
                            current = 1,
                            previous = 1,
                            total = post.totalPages ?: 1,
                            postCount = floors.size,
                            hasMore = false,
                            hasPrevious = false,
                        ),
                    )
                }
                // 恢复上次阅读进度（页码同步更新，并提示用户避免误解为随机跳页）
                val savedFloor = backupRepository.getProgress(backupId)?.floorNumber ?: 0
                val savedPostId = floors.firstOrNull { it.floorNumber >= savedFloor }?.postId ?: 0L
                if (savedPostId > 0L && savedFloor > 0) {
                    val totalPages = (post.totalPages ?: 1).coerceAtLeast(1)
                    val perPage = ((floors.size + totalPages - 1) / totalPages).coerceAtLeast(1)
                    val page = ((savedFloor + perPage - 1) / perPage).coerceIn(1, totalPages)
                    _uiState.update { st ->
                        st.copy(pageData = st.pageData.copy(current = page, previous = page))
                    }
                    sendUiEvent(ThreadUiEvent.LoadSuccess(0, savedPostId))
                }
            }.onFailure { e ->
                _uiState.update { it.copy(isRefreshing = false, error = e) }
                sendUiEvent(CommonUiEvent.ToastError(e))
            }
        }
    }

    /** 离线阅读：保存当前阅读楼层到备份进度 */
    fun saveOfflineProgress(post: PostData?) {
        val backupId = localBackupId ?: return
        val floor = post?.floor ?: return
        launchJobInVM {
            runCatching { backupRepository.saveProgress(backupId, threadId, floor) }
        }
    }

    fun requestLocalBackup(seeLz: Boolean = false) {
        launchJobInVM {
            runCatching {
                localBackupStarter.start(
                    threadId = threadId,
                    forumId = forumId,
                    seeLz = seeLz,
                )
            }.onFailure {
                sendUiEvent(CommonUiEvent.ToastError(it))
            }
        }
    }

    /**
     * 朗读全文：从长按的楼层开始（再次点击停止），跳过楼中楼。
     * 默认读全部楼层；「只看楼主」模式下只读楼主发言。
     */
    fun onReadAloudClicked(startFloor: Int) {
        if (readAloud.state.value != null) {
            readAloud.stop()
            return
        }
        launchJobInVM {
            runCatching {
                val state = _uiState.first()
                val seeLzOnly = state.seeLz
                val base = (listOfNotNull(state.firstPost) + state.data)
                    .filter { (!seeLzOnly || it.author.isLz) && it.plainText.isNotBlank() }
                    .distinctBy { it.floor }
                    .sortedBy { it.floor }
                    .filter { it.floor >= startFloor }
                // 不播报楼号；月份更迭时播报一次「xxxx年x月」
                var lastMonth: String? = null
                val items = base.map { p ->
                    val month = if (p.time > 0) monthLabel(p.time) else null
                    val prefix = if (month != null && month != lastMonth) "$month。" else ""
                    lastMonth = month
                    ReadAloudItem(
                        floor = p.floor,
                        text = (prefix + p.plainText.sanitizeForTts()).trim(),
                        time = p.time,
                    )
                }
                require(items.isNotEmpty()) {
                    context.getString(
                        if (seeLzOnly) R.string.tts_no_lz_after_floor else R.string.tts_no_more_content
                    )
                }
                readAloud.start(items, title = state.thread?.title, threadId = threadId)
            }.onFailure {
                sendUiEvent(CommonUiEvent.Toast(it.message ?: context.getString(R.string.local_backup_tts_unavailable)))
            }
        }
    }

    /** 楼层时间 → 「yyyy年M月」标签。数据源秒/毫秒混杂，按量级归一化 */
    private fun monthLabel(time: Long): String {
        val millis = if (time < 10_000_000_000L) time * 1000 else time
        return java.text.SimpleDateFormat("yyyy年M月", java.util.Locale.CHINA).format(java.util.Date(millis))
    }

    /** 当前已加载楼层的月份列表（升序去重），供按月跳转选择 */
    fun availableMonths(): List<String> {
        val all = listOfNotNull(currentState.firstPost) + currentState.data
        return all.filter { it.time > 0 }.map { monthLabel(it.time) }.distinct()
    }

    /** 按月跳转：离线在已加载楼层中精确跳；在线按页二分定位目标月份后加载跳转 */
    fun jumpToMonth(label: String) {
        val (year, month) = parseMonthLabel(label) ?: return
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, 1, 0, 0, 0)
        val monthStart = cal.timeInMillis / 1000
        cal.add(java.util.Calendar.MONTH, 1)
        val monthEnd = cal.timeInMillis / 1000

        if (isOfflineBackup) {
            // 离线楼层时间戳单位不定（秒/毫秒），用与展示一致的 monthLabel 匹配
            val all = listOfNotNull(currentState.firstPost) + currentState.data
            val target = all.firstOrNull { it.time > 0 && monthLabel(it.time) == label } ?: return
            sendUiEvent(ThreadUiEvent.JumpToPost(target.id))
            return
        }

        launchJobInVM {
            runCatching {
                sendUiEvent(CommonUiEvent.Toast(context.getString(R.string.jump_month_locating, label)))
                val seeLz = currentState.seeLz
                var lo = 1L
                var hi = currentState.pageData.total.toLong().coerceAtLeast(1L)
                var hitPage = -1
                var hitPostId = 0L
                // 页内楼层时间按正序单调，二分找包含目标月的页（约 log2(页数) 次请求）
                while (lo <= hi) {
                    val mid = ((lo + hi) / 2).toInt()
                    val resp = threadRepo.pbPage(threadId, mid, 0, forumId, seeLz, ThreadSortType.BY_ASC)
                    val posts = resp.posts.sortedBy { it.floor }
                    if (posts.isEmpty()) break
                    val first = posts.first().time.toLong()
                    val last = posts.last().time.toLong()
                    when {
                        last < monthStart -> lo = mid + 1L
                        first > monthEnd -> hi = mid - 1L
                        else -> {
                            hitPage = mid
                            hitPostId = posts.firstOrNull { it.time.toLong() in monthStart until monthEnd }?.id ?: 0L
                            break
                        }
                    }
                }
                if (hitPage == -1) {
                    sendUiEvent(CommonUiEvent.Toast(context.getString(R.string.jump_month_not_found, label)))
                    return@launchJobInVM
                }
                // 加载目标页（正序）并定位到该月第一楼
                val response = threadRepo.pbPage(threadId, hitPage, 0, forumId, seeLz, ThreadSortType.BY_ASC)
                val pageData = response.page.let {
                    it.mapToUiModel(
                        previous = it.current_page,
                        nextPagePostId = response.nextPagePostId,
                        hasPrevious = it.has_prev != 0,
                    )
                }
                _uiState.update {
                    it.updateStateFrom(response)
                        .copy(sortType = ThreadSortType.BY_ASC, pageData = pageData)
                }
                if (hitPostId != 0L) {
                    sendUiEvent(ThreadUiEvent.JumpToPost(hitPostId))
                } else {
                    sendUiEvent(ThreadUiEvent.LoadSuccess(hitPage, 0))
                }
            }.onFailure {
                sendUiEvent(CommonUiEvent.ToastError(it))
            }
        }
    }

    /** 「2023年5月」/「2023-5」→ (2023, 5) */
    fun parseMonthLabel(label: String): Pair<Int, Int>? {
        val m = Regex("(\\d{4})年(\\d{1,2})月").find(label.trim())
            ?: Regex("(\\d{4})-(\\d{1,2})").find(label.trim())
            ?: return null
        val (y, mo) = m.destructured
        val month = mo.toInt()
        if (month !in 1..12) return null
        return y.toInt() to month
    }

    fun requestLoad(page: Int = 1, postId: Long, scrollToReply: Boolean = true) {
        if (isOfflineBackup) return
        if (isRefreshing) return // Check refreshing

        val oldState = _uiState.updateAndGet { it.copy(isRefreshing = true, error = null) }
        launchInVM {
            val sortType = oldState.sortType
            val fromType = from.takeIf { it == FROM_STORE }.orEmpty()
            val response = threadRepo
                .pbPage(threadId, page, postId, forumId, oldState.seeLz, sortType, from = fromType)
            val pageData = response.page.let {
                it.mapToUiModel(
                    previous = it.current_page,
                    nextPagePostId = response.nextPagePostId,
                    hasPrevious = if (sortType != ThreadSortType.BY_DESC) {
                        it.has_prev != 0 // Bug: Server returns wrong has_prev when FROM_STORE with seeLz enabled
                    } else {
                        // Check has previous manually if sort by DESC
                        it.total_page > 1 && it.current_page < it.total_page
                    }
                )
            }
            _uiState.update {
                it.updateStateFrom(response).copy(pageData = pageData)
            }
            if (scrollToReply) {
                sendUiEvent(ThreadUiEvent.LoadSuccess(page, postId))
            }
        }
    }

    fun requestLoadFirstPage(silent: Boolean = false, fallbackSortType: Int? = null) {
        if (isOfflineBackup) {
            _uiState.update { st ->
                st.copy(data = allOfflineFloors(st))
            }
            sendUiEvent(ThreadUiEvent.ScrollToFirstReply)
            return
        }
        if (isRefreshing) return // Check refreshing

        // 静默模式（切序）：保持现有内容展示，不触发全屏 loading
        val oldState = _uiState.updateAndGet {
            it.copy(
                isRefreshing = if (silent) it.isRefreshing else true,
                error = if (silent) it.error else null,
            )
        }
        launchInVM {
            runCatching {
                val sortType = oldState.sortType
                val isAscSorting = sortType == ThreadSortType.BY_ASC
                val response = threadRepo.pbPage(threadId, 0, 0, forumId, oldState.seeLz, sortType)
                val pageData = response.page.run {
                    mapToUiModel(
                        previous = total_page,
                        current = if (isAscSorting) current_page else total_page,
                        nextPagePostId = if (isAscSorting) {
                            response.nextPagePostId
                        } else {
                            response.posts.lastOrNull()?.id ?: 0
                        }
                    )
                }
                _uiState.update {
                    it.updateStateFrom(response).copy(firstPost = it.firstPost, pageData = pageData)
                }
                // Scroll LazyList based on current sort type
                if (isAscSorting) {
                    emitUiEvent(ThreadUiEvent.ScrollToFirstReply)
                } else {
                    emitUiEvent(ThreadUiEvent.ScrollToLatestReply)
                }
            }.onFailure { e ->
                if (silent && fallbackSortType != null) {
                    // 静默切序失败：回退排序并提示，不打断当前阅读
                    _uiState.update {
                        it.copy(sortType = fallbackSortType, isRefreshing = false, error = null)
                    }
                    sendUiEvent(CommonUiEvent.ToastError(e))
                } else {
                    throw e
                }
            }
        }
    }

    fun requestLoad(page: Int) {
        if (isOfflineBackup) {
            val state = currentState
            val total = state.pageData.total.coerceAtLeast(1)
            val perPage = (state.data.size + total - 1) / total
            val index = ((page - 1).coerceAtLeast(0) * perPage).coerceIn(0, (state.data.size - 1).coerceAtLeast(0))
            val targetPostId = state.data.getOrNull(index)?.id ?: state.firstPost?.id ?: 0L
            // 同步页码：跳页后顶栏页码跟着走，不再永远停在第一页
            val safePage = page.coerceAtLeast(1)
            _uiState.update { st ->
                st.copy(pageData = st.pageData.copy(current = safePage, previous = safePage))
            }
            if (targetPostId > 0L) sendUiEvent(ThreadUiEvent.LoadSuccess(page, targetPostId))
            return
        }
        val state = currentState
        // Check target page is first page
        if ((page <= 1 && state.sortType == ThreadSortType.BY_ASC) ||
            (page == state.pageData.total && state.sortType == ThreadSortType.BY_DESC)
        ) {
            requestLoadFirstPage()
        } else {
            requestLoad(page, postId = 0)
        }
    }

    /**
     * Load previous page.
     *
     * @param offset offset of the first visible post. Will be used as backward scroll offset
     *   in [ThreadUiEvent.LoadPreviousSuccess]. This is a workaround for broken scroll
     *   position preservation caused by LoadPreviousButton.
     *
     * @see [PageData.hasPrevious]
     * */
    fun requestLoadPrevious(offset: Int) {
        if (isOfflineBackup) return
        if (isLoadingMore) return else _uiState.set { copy(isLoadingMore = true, error = null) }

        launchInVM(loadMoreHandler) {
            val state = currentState
            val sortType = state.sortType
            val page = state.pageData.previousPage(sortType)
            val postId = state.data.first().id
            val response = threadRepo
                .pbPage(threadId, page, postId, forumId, state.seeLz, sortType, back = true)
            val newData = concatNewPostList(old = state.data, new = response.posts, asc = false)
            val pageData = response.page.mapToUiModel(
                previous = response.page.current_page,
                current = state.pageData.current,
                hasMore = state.pageData.hasMore
            )

            _uiState.update {
                it.copy(isLoadingMore = false, thread = response.thread, data = newData, pageData = pageData)
            }
            // Scroll to previous floor
            val previousIndex = withContext(Dispatchers.Default) {
                newData.indexOfFirst { p -> p.floor == state.data[0].floor }
            }
            // Check no visible post(covered by BottomBar) || empty new data
            if (offset > 0 && previousIndex > 0) {
                emitUiEvent(ThreadUiEvent.LoadPreviousSuccess(previousIndex, -offset))
            }
        }
    }

    fun requestLoadMore() {
        if (isOfflineBackup) return
        if (isLoadingMore) return else _uiState.set { copy(isLoadingMore = true, error = null) }

        launchInVM(loadMoreHandler) {
            val state = currentState
            val sortType = state.sortType
            val nextPage = state.pageData.nextPage(sortType)
            val response = threadRepo
                .pbPage(threadId, nextPage, state.pageData.nextPagePostId, forumId, state.seeLz, sortType)
            val newData = concatNewPostList(old = state.data, new = response.posts)
            val pageData = response.page.mapToUiModel(
                previous = state.pageData.previous,
                nextPagePostId = response.nextPagePostId,
                hasPrevious = state.pageData.hasPrevious
            )

            _uiState.update {
                it.updateStateFrom(response).copy(data = newData, pageData = pageData)
            }
        }
    }

    /**
     * 加载当前贴子的最新回复
     */
    fun requestLoadLatestPosts() = launchInVM(loadMoreHandler) {
        if (isOfflineBackup) return@launchInVM
        if (isLoadingMore) return@launchInVM // Check loading status

        val state = _uiState.updateAndGet { it.copy(isLoadingMore = true, error = null) }
        val curLatestPostId = state.data.last().id
        val response = threadRepo.pbPage(
            threadId = threadId,
            page = 0,
            postId = curLatestPostId,
            forumId = forumId,
            seeLz = state.seeLz,
            sortType = state.sortType,
            lastPostId = curLatestPostId
        )
        val data = concatNewPostList(state.data, response.posts)
        val pageData = response.page.mapToUiModel(
            previous = state.pageData.previous,
            nextPagePostId = response.nextPagePostId,
            hasPrevious = state.pageData.hasPrevious
        )
        _uiState.update {
            it.copy(isLoadingMore = false, data = data, thread = response.thread, latestPosts = null, pageData = pageData)
        }
    }

    /**
     * 当前用户发送新的回复时，加载用户发送的回复
     */
    fun requestLoadMyLatestReply(newPostId: Long) {
        if (currentState.isLoadingLatestReply) return

        launchInVM(loadMoreHandler) {
            val state = _uiState.updateAndGet { it.copy(isLoadingLatestReply = true, error = null) }
            val isDesc = state.sortType == ThreadSortType.BY_DESC
            val curLatestPostFloor = if (isDesc) {
                state.data.firstOrNull()?.floor ?: 1 // DESC -> first
            } else {
                state.data.lastOrNull()?.floor ?: 1  // ASC  -> last
            }

            val response = threadRepo.pbPage(threadId, page = 0, postId = newPostId, forumId = forumId)
            val hasNewPost: Boolean
            val newState = withContext(Dispatchers.Default) {
                val postData = response.posts
                val oldPostData = state.data
                val oldPostIds = oldPostData.mapTo(HashSet()) { it.id }
                hasNewPost = postData.any { !oldPostIds.contains(it.id) }
                val firstLatestPost = postData.first()
                val isContinuous = firstLatestPost.floor == curLatestPostFloor + 1
                val continuous = isContinuous || response.page.current_page == state.pageData.current

                val replacePostIndexes = oldPostData.mapIndexedNotNull { index, old ->
                    val replaceItemIndex = postData.indexOfFirst { it.id == old.id }
                    if (replaceItemIndex != -1) index to replaceItemIndex else null
                }
                val newPost = oldPostData.mapIndexed { index, oldItem ->
                    val replaceIndex = replacePostIndexes.firstOrNull { it.first == index }
                    if (replaceIndex != null) postData[replaceIndex.second] else oldItem
                }
                val addPosts = postData.filter { old ->
                    !newPost.any { new -> new.id == old.id }
                }
                ensureActive()

                when {
                    hasNewPost && continuous -> state.copy(
                        data = if (isDesc) addPosts.reversed() + newPost else newPost + addPosts,
                        latestPosts = null
                    )

                    hasNewPost -> state.copy(data = newPost, latestPosts = postData)

                    !hasNewPost -> state.copy(data = newPost, latestPosts = null)

                    else -> state
                }
            }

            _uiState.update {
                it.copy(isLoadingLatestReply = false, error = null, tbs = response.tbs, data = newState.data, latestPosts = newState.latestPosts)
            }
            if (hasNewPost) {
                emitUiEvent(ThreadUiEvent.ScrollToLatestReply)
            }
        }
    }

    /**
     * 收藏/更新这个帖子到 [markedPost] 楼
     * */
    fun updateCollections(markedPost: PostData) {
        collectionsJob?.let { if (it.isActive) it.cancel() }
        // Launch in different CoroutineScope
        collectionsJob = MainScope().launch {
            storeRepo.add(threadId, postId = markedPost.id)
                .onFailure { e ->
                    emitUiEvent(ThreadStoreUiEvent.Add.Failure(message = e.getErrorMessage()))
                }
                .onSuccess {
                    _uiState.update {
                        it.copy(thread = it.thread!!.copy(collectMarkPid = markedPost.id))
                    }
                    emitUiEvent(ThreadStoreUiEvent.Add.Success(markedPost.floor))
                    launchJobInVM {
                        runCatching {
                            // 收藏时同步本地备份（若已开启则静默执行，不弹窗）
                            localBackupStarter.afterFavorite(
                                threadId = threadId,
                                forumId = forumId,
                                seeLz = _uiState.value.seeLz,
                            )
                        }
                    }
                }
        }
    }

    /**
     * 取消收藏这个帖子
     * */
    fun removeFromCollections() {
        if (collectionsJob?.isActive == true) {
            sendUiEvent(ThreadStoreUiEvent.Loading)
            return
        }

        collectionsJob = launchJobInVM {
            val state = _uiState.first()
            runCatching {
                require(state.thread!!.collected)
                storeRepo.remove(threadId, forumId = forumId, tbs = state.tbs)
            }
            .onFailure { e ->
                emitUiEvent(ThreadStoreUiEvent.Delete.Failure(message = e.getErrorMessage()))
            }
            .onSuccess {
                _uiState.update { it.copy(thread = it.thread!!.copy(collectMarkPid = null)) }
                emitUiEvent(ThreadStoreUiEvent.Delete.Success)
            }
        }
    }

    fun onPostLikeClicked(post: PostData) {
        if (currentState.user == null) {
            sendUiEvent(ThreadLikeUiEvent.NotLoggedIn); return
        } else if (post.like.loading) {
            sendUiEvent(ThreadLikeUiEvent.Connecting); return
        }

        viewModelScope.launch {
            val start = System.currentTimeMillis()
            val liked = post.like.liked
            val opType = if (liked) 1 else 0 // 操作 0 = 点赞, 1 = 取消点赞

            TiebaApi.getInstance()
                .opAgreeFlow(threadId.toString(), post.id.toString(), opType, objType = 1)
                .onStart {
                    _uiState.update { it.updateLikedPost(post.id, !liked, loading = true) }
                }
                .catch { e ->
                    sendUiEvent(ThreadLikeUiEvent.Failed(e))
                    _uiState.update { it.updateLikedPost(post.id, liked, loading = false) }
                }
                .collect {
                    if (System.currentTimeMillis() - start < 400) { // Wait for button animation
                        delay(250)
                    }
                    _uiState.update { it.updateLikedPost(post.id, !liked, loading = false) }
                }
        }
    }

    fun onThreadLikeClicked(): Unit = launchInVM {
        val stateSnapshot = currentState
        val oldThread = stateSnapshot.thread ?: throw NullPointerException()
        val like = oldThread.like

        // check user logged in & requesting like status update
        if (stateSnapshot.user == null) {
            emitUiEvent(ThreadLikeUiEvent.NotLoggedIn); return@launchInVM
        } else if (like.loading) {
            emitUiEvent(ThreadLikeUiEvent.Connecting); return@launchInVM
        }

        _uiState.update { it.copy(thread = oldThread.updateLikeStatus(liked = !like.liked, loading = true)) }
        runCatching {
            threadRepo.requestLikeThread(oldThread)
        }
        .onFailure { e ->
            sendUiEvent(ThreadLikeUiEvent.Failed(e))
            _uiState.update {
                it.copy(thread = it.thread!!.updateLikeStatus(liked = like.liked, loading = false))
            }
        }
        .onSuccess { _ ->
            _uiState.update { // Update like loading status
                it.copy(thread = it.thread!!.updateLikeStatus(liked = !like.liked, loading = false))
            }
        }
    }

    fun onDeleteConfirmed(): Job = launchJobInVM {
        val post = _deletePost.getAndUpdate { null } ?: throw NullPointerException()
        if (post.id == currentState.firstPost!!.id) {
            requestDeleteThread()
        } else {
            requestDeletePost(post)
        }
    }

    /**
     * Mark my post for deletion
     *
     * @see onDeleteConfirmed
     * */
    fun onDeletePost(post: PostData) = _deletePost.update { post }

    /**
     * Mark my thread for deletion
     *
     * @see onDeleteConfirmed
     * */
    fun onDeleteThread() = _deletePost.update { currentState.firstPost }

    fun onDeleteCancelled() = _deletePost.update { null }

    private suspend fun requestDeletePost(post: PostData) {
        val state = _uiState.first()
        val delMyPost = post.author.id == state.user?.id
        runCatching {
            threadRepo.deletePost(post.id, state.thread!!, state.tbs, delMyPost)
        }
        .onFailure { e -> sendUiEvent(ThreadUiEvent.DeletePostFailed(message = e.getErrorMessage())) }
        .onSuccess {
            // Remove this post from data list
            _uiState.update { it.copy(data = it.data.fastFilter { p -> p.id != post.id }) }
            sendUiEvent(ThreadUiEvent.DeletePostSuccess)
        }
    }

    private suspend fun requestDeleteThread() {
        val state = currentState
        val delMyThread = state.lz!!.id == state.user?.id
        runCatching {
            threadRepo.deleteThread(state.thread!!, state.tbs, delMyThread)
        }
        .onFailure { e -> sendUiEvent(ThreadUiEvent.DeletePostFailed(message = e.getErrorMessage())) }
        .onSuccess {
            sendUiEvent(CommonUiEvent.NavigateUp)
        }
    }

    fun requestPollPost(options: List<Int>) = launchInVM {
        val thread = currentState.thread!!
        if (thread.pollInfo!!.isLoading) return@launchInVM

        _uiState.update { it.copy(thread = thread.updatePollStatus(loading = true)) }
        runCatching {
            threadRepo.requestPollPost(forumId, threadId, options)
        }
        .onFailure { e ->
            sendUiEvent(CommonUiEvent.ToastError(e))
            _uiState.update { it.copy(thread = thread.updatePollStatus(loading = false)) }
        }
        .onSuccess { pollInfo ->
            _uiState.update { it.copy(thread = thread.copy(pollInfo = pollInfo)) }
        }
    }

    fun onSeeLzChanged() {
        if (isOfflineBackup) {
            _uiState.updateAndGet { st ->
                val newSeeLz = !st.seeLz
                st.copy(seeLz = newSeeLz, data = if (newSeeLz) st.data.filter { it.author.isLz } else allOfflineFloors(st))
            }
            return
        }
        val newState = _uiState.updateAndGet { it.copy(seeLz = !it.seeLz) }
        val collectMarkPid = newState.thread?.collectMarkPid
        // Jump to collectMarkPid when seeLz switched off
        if (!newState.seeLz && collectMarkPid != null && collectMarkPid != newState.firstPost?.id) {
            requestLoad(0, postId = newState.thread.collectMarkPid)
        } else {
            requestLoadFirstPage()
        }
    }

    fun onSortChanged(@ThreadSortType sortType: Int) {
        if (isOfflineBackup) {
            // 离线：直接翻转已装载的楼层
            _uiState.update { st ->
                st.copy(
                    sortType = sortType,
                    data = if (sortType == ThreadSortType.BY_DESC) st.data.reversed()
                    else st.data.sortedBy { it.floor },
                )
            }
            return
        }
        // 在线：对齐原版体验，切序不整页重载，静默请求后原地替换
        val oldSortType = currentState.sortType
        _uiState.update { it.copy(sortType = sortType) }
        requestLoadFirstPage(silent = true, fallbackSortType = oldSortType)
    }

    fun onSaveHistory(lastVisiblePost: PostData?) = launchInVM {
        val state = currentState
        val author = state.lz ?: return@launchInVM
        val title = state.thread?.title ?: return@launchInVM

        val history = ThreadHistory(
            id = threadId,
            avatar = author.avatarUrl,
            name = author.nameShow,
            forum = forumName,
            title = title,
            isSeeLz = state.seeLz,
            pid = lastVisiblePost?.takeIf { it.id > 0 && it.floor > 5 }?.id ?: 0, // 大于 5 楼
            timestamp = historyTimeStamp,
        )
        historyRepo.saveHistory(history)
    }

    fun onShareThread() = TiebaUtil.shareThread(context, currentState.thread?.title?: "", threadId)

    fun onCopyThreadLink() {
        val seeLz = currentState.seeLz
        val link = "https://tieba.baidu.com/p/$threadId?see_lz=${seeLz.booleanToString()}"
        TiebaUtil.copyText(context = context, text = link)
        ClipBoardLinkDetector.onCopyTiebaLink(link)
    }

    fun onImmersiveModeChanged() {
        if (!isImmersiveMode && !currentState.seeLz) {
            onSeeLzChanged()
        }
        isImmersiveMode = !isImmersiveMode
    }

    fun onReplyThread() = sendUiEvent(
        event = ThreadUiEvent.ToReplyDestination(
            Reply(forumId = forumId ?: 0, forumName = forumName ?: "", threadId = threadId)
        )
    )

    fun onReplyPost(post: PostData) = sendUiEvent(
        event = ThreadUiEvent.ToReplyDestination(
            Reply(
                forumId = forumId ?: 0,
                forumName = forumName.orEmpty(),
                threadId = threadId,
                postId = post.id,
                replyUserId = post.author.id,
                replyUserName = post.author.nameShow.takeIf { name -> name.isNotEmpty() } ?: post.author.name,
                replyUserPortrait = post.author.portrait
            )
        )
    )

    fun onReplyClicked(post: PostData) {
        if (post.id == firstPostId) {
            onReplyThread()
        } else {
            onReplyPost(post)
        }
    }

    fun onReplySubPost(post: PostData, subPost: SubPostItemData) = sendUiEvent(
        ThreadUiEvent.ToReplyDestination(
            Reply(
                forumId = forumId ?: 0,
                forumName = forumName.orEmpty(),
                threadId = threadId,
                postId = post.id,
                subPostId = subPost.id,
                replyUserId = subPost.author.id,
                replyUserName = subPost.author.nameShow.takeIf { name -> name.isNotEmpty() }
                    ?: subPost.author.name,
                replyUserPortrait = subPost.author.portrait,
            )
        )
    )

    fun onOpenSubPost(post: PostData, subPostId: Long) {
        val forumId = forumId ?: return
        sendUiEvent(
            ThreadUiEvent.ToSubPostsDestination(SubPosts(threadId, forumId, post.id, subPostId))
        )
    }

    private fun ThreadUiState.updateStateFrom(response: PbPageUiResponse): ThreadUiState {
        if (response.user == null) {
            hideReply = true
        }

        val firstPost = this.firstPost ?: response.firstPost // use old firstPost if possible
        return this.copy(
            isRefreshing = false,
            isLoadingMore = false,
            isLoadingLatestReply = false,
            error = null,
            user = response.user,
            data = response.posts,
            firstPost = firstPost,
            tbs = response.tbs,
            thread = response.thread.copy(firstPostId = firstPost?.id ?: firstPostId),
            latestPosts = null,
        )
    }

    private fun Page.mapToUiModel(
        current: Int = current_page,
        previous: Int = 0,
        nextPagePostId: Long = 0,
        // Note: Do not use has_more, check manually
        hasMore: Boolean = if (currentState.sortType != ThreadSortType.BY_DESC) {
            new_total_page > 1 && current < new_total_page
        } else {
            new_total_page > 1 && current > 1
        },
        // Note: Use has_prev only when load with post
        hasPrevious: Boolean = if (currentState.sortType != ThreadSortType.BY_DESC) {
            new_total_page > 1 && previous > 1 && previous < new_total_page
        } else {
            new_total_page > 1 && previous < new_total_page
        },
    ): PageData = PageData(
        current = current,
        previous = previous,
        total = new_total_page,
        postCount = total_count,
        nextPagePostId = nextPagePostId,
        hasMore = hasMore,
        hasPrevious = hasPrevious
    )

    override fun onCleared() {
        // 朗读控制器是应用级单例：页面销毁不停朗读、不撤媒体控件（跨页面/重进恢复）
        super.onCleared()
    }

    // ------------------------------------------------------------ 离线备份阅读

    private fun allOfflineFloors(state: ThreadUiState): List<PostData> {
        val firstId = state.firstPost?.id
        val asc = offlineAllPosts.filter { it.id != firstId }.sortedBy { it.floor }
        return if (state.sortType == ThreadSortType.BY_DESC) asc.reversed() else asc
    }

    /** 本地备份楼层 → 原生 [PostData]（文本+表情 / 本地图片 file:// + 图集 / 楼中楼 / 头像缓存） */
    private fun LocalBackupFloor.toOfflinePostData(
        images: List<LocalBackupImage>,
        imagesByUrl: Map<String, LocalBackupImage>,
        subPosts: List<LocalBackupSubPost>,
        globalPicItems: List<PicItem>,
        globalIndexByUrl: Map<String, Int>,
    ): PostData {
        val floorPicUrls = imageUrls.split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        fun srcOf(url: String): String {
            val local = images.firstOrNull { it.originalUrl == url } ?: imagesByUrl[url]
            return local?.let { "file://" + it.localPath } ?: url
        }
        // protobuf 内容分段在纯文本里以换行分隔，会把表情挤成独立行；表情行内化还原排版
        fun inlineEmoticonLines(text: String): String =
            text.replace(Regex("\n+(#\\([^)\n]{1,30}\\))"), "$1")
                .replace(Regex("(#\\([^)\n]{1,30}\\))\n+"), "$1")
        // 备份时编码的语音标记 [语音:md5:秒] → 可播放的语音条目
        val voiceRegex = Regex("\\[语音:([0-9a-zA-Z]+):(\\d+)]")
        // 全帖图集（data=null 时查看器直接用 picItems，离线可点击放大并跨楼层滑动）
        val renders = buildList {
            // #(表情码) → 原生表情（经典表情走内置 assets，离线可用）；语音标记拆成可播放条目
            if (content.isNotBlank()) {
                val inlined = inlineEmoticonLines(content)
                var last = 0
                voiceRegex.findAll(inlined).forEach { m ->
                    val before = inlined.substring(last, m.range.first).trim('\n', ' ')
                    if (before.isNotBlank()) add(TextContentRender(before.emoticonString))
                    // 优先播放备份存档的本地语音文件，缺失时回退在线流
                    val voiceLocal = images.firstOrNull { it.originalUrl == "voice:${m.groupValues[1]}" }
                    add(VoiceContentRender(m.groupValues[1], m.groupValues[2].toInt(), voiceLocal?.localPath))
                    last = m.range.last + 1
                }
                val tail = inlined.substring(last).trim('\n', ' ')
                if (tail.isNotBlank()) add(TextContentRender(tail.emoticonString))
            }
            floorPicUrls.forEachIndexed { i, url ->
                val src = srcOf(url)
                val img = images.firstOrNull { it.originalUrl == url } ?: imagesByUrl[url]
                add(
                    PicContentRender(
                        picUrl = src,
                        originUrl = src,
                        originSize = img?.bytes?.toInt() ?: 0,
                        dimensions = img?.width?.let { w -> img.height?.let { h -> IntSize(w, h) } },
                        picId = ImageUtil.getPicId(url),
                        photoViewData = PhotoViewData(
                            data = null,
                            picItems = globalPicItems,
                            index = globalIndexByUrl[src] ?: i,
                        ),
                    )
                )
            }
        }
        val author = UserData(
            id = authorId ?: 0L,
            name = authorName ?: "",
            nameShow = authorName ?: "",
            showBothName = false,
            avatarUrl = authorAvatar?.let { "file://" + it } ?: "",
            portrait = "",
            ip = ipLocation ?: "",
            levelId = 0,
            bawuType = null,
            isLz = isLz,
        )
        val subPostItems = subPosts.map { sub ->
            val subAuthor = UserData(
                id = sub.authorId ?: 0L,
                name = sub.authorName ?: "",
                nameShow = sub.authorName ?: "",
                showBothName = false,
                avatarUrl = "",
                portrait = "",
                ip = "",
                levelId = 0,
                bawuType = null,
                isLz = sub.isLz,
            )
            SubPostItemData(
                author = subAuthor,
                id = sub.subPostId,
                blocked = false,
                time = sub.postTime,
                like = LikeZero,
                plainText = sub.content,
                // PostCard 楼中楼渲染的是 abstractContent（null 会 NPE），
                // 离线用「作者：内容」代替，并保留表情码转原生表情
                abstractContent = ((sub.authorName ?: "") + "：" + sub.content).emoticonString,
            )
        }
        return PostData(
            id = postId,
            author = author,
            floor = floorNumber,
            title = null,
            time = postTime,
            like = LikeZero,
            blocked = false,
            plainText = content,
            contentRenders = renders,
            subPosts = subPostItems.ifEmpty { null },
            subPostNumber = subPostItems.size,
        )
    }

    companion object {

        private const val TAG = "ThreadViewModel"

        private fun PageData.nextPage(sortType: Int): Int {
            val page = if (sortType == ThreadSortType.BY_DESC) current - 1 else current + 1
            return page.coerceIn(1, total)
        }

        private fun PageData.previousPage(sortType: Int): Int {
            val page = if (sortType == ThreadSortType.BY_DESC) previous + 1 else previous - 1
            return page.coerceIn(1, total)
        }

        private fun ThreadUiState.updateLikedPost(postId: Long, liked: Boolean, loading: Boolean) = copy(
            data = this.data.fastMap { post ->
                if (post.id == postId) post.updateLikesCount(liked, loading) else post
            }
        )

        private suspend fun concatNewPostList(
            old: List<PostData>,
            new: List<PostData>,
            asc: Boolean = true
        ): List<PostData> = withContext(Dispatchers.Default) {
            val postIds = old.mapTo(HashSet()) { it.id }
            new.filterNot { postIds.contains(it.id) } // filter out old post
                .let { new ->
                    if (asc) old + new else new + old
                }
        }
    }
}

sealed interface ThreadUiEvent : UiEvent {
    class DeletePostFailed(val message: String) : ThreadUiEvent

    object DeletePostSuccess : ThreadUiEvent

    object ScrollToFirstReply : ThreadUiEvent

    object ScrollToLatestReply : ThreadUiEvent

    data class LoadPreviousSuccess(val previousIndex: Int, val offset: Int) : ThreadUiEvent

    data class LoadSuccess(val page: Int, val postId: Long) : ThreadUiEvent

    /** 定位到指定楼层（按月跳转、朗读跟随等） */
    data class JumpToPost(val postId: Long) : ThreadUiEvent

    data class ToReplyDestination(val direction: Reply): ThreadUiEvent

    data class ToSubPostsDestination(val direction: SubPosts): ThreadUiEvent
}
