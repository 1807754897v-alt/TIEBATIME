package com.huanchengfly.tieba.post.ui.page.localbackup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.BaseStateViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.TbLiteExceptionHandler
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.backup.BackupExporter
import com.huanchengfly.tieba.post.backup.BackupImporter
import com.huanchengfly.tieba.post.backup.BackupRepository
import com.huanchengfly.tieba.post.backup.LocalBackupPrefs
import com.huanchengfly.tieba.post.backup.LocalBackupStarter
import com.huanchengfly.tieba.post.models.database.LocalBackupPost
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@Immutable
data class LocalBackupUiState(
    val isRefreshing: Boolean = false,
    val data: List<LocalBackupPost> = emptyList(),
    val totalBytes: Long = 0L,
    val query: String = "",
    val error: Throwable? = null,
) : UiState {
    val isEmpty: Boolean get() = data.isEmpty()
}

@HiltViewModel
class LocalBackupViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val backupRepository: BackupRepository,
    private val backupImporter: BackupImporter,
    private val backupStarter: LocalBackupStarter,
) : BaseStateViewModel<LocalBackupUiState>() {

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _prefs = MutableStateFlow<LocalBackupPrefs?>(null)
    val prefs: StateFlow<LocalBackupPrefs?> = _prefs.asStateFlow()

    override val errorHandler = TbLiteExceptionHandler(this::class.simpleName ?: "LocalBackupViewModel") { _, e, _ ->
        _uiState.update {
            it.copy(isRefreshing = false, error = e)
        }
        sendUiEvent(CommonUiEvent.ToastError(e))
    }

    override fun createInitialState(): LocalBackupUiState = LocalBackupUiState()

    init {
        refresh()
        loadPrefs()
    }

    private fun loadPrefs() = launchInVM {
        _prefs.value = backupRepository.snapshotPrefs()
    }

    fun setSyncOnFavorite(enabled: Boolean) = launchInVM {
        backupRepository.setSyncOnFavorite(enabled)
        loadPrefs()
    }

    fun setImagePolicy(policy: LocalBackupPrefs.ImagePolicy) = launchInVM {
        backupRepository.setImagePolicy(policy)
        loadPrefs()
    }

    fun setMaxPages(pages: Int) = launchInVM {
        backupRepository.setMaxPages(pages)
        loadPrefs()
    }

    fun refresh() = launchInVM {
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        val data = backupRepository.listPosts()
        val bytes = data.sumOf { it.totalBytes }
        _uiState.update {
            it.copy(isRefreshing = false, data = data, totalBytes = bytes)
        }
    }

    fun search(query: String) = launchInVM {
        _uiState.update { it.copy(query = query, isRefreshing = true) }
        val data = backupRepository.searchPosts(query)
        val bytes = data.sumOf { it.totalBytes }
        _uiState.update {
            it.copy(isRefreshing = false, data = data, totalBytes = bytes)
        }
    }

    fun delete(post: LocalBackupPost) = launchInVM {
        backupRepository.deleteBackup(post.backupId)
        refresh()
    }

    /** 增量更新：同一管线，已存在的楼层/图片只补差异 */
    fun updateBackup(post: LocalBackupPost) = launchInVM {
        backupStarter.start(
            threadId = post.threadId,
            forumId = post.forumId,
            seeLz = post.seeLz,
        )
    }

    // ---------------------------------------------------------------- export

    fun export(post: LocalBackupPost, format: BackupExporter.Format, target: Uri) = launchInVM {
        _isExporting.value = true
        try {
            runCatching {
                val floors = backupRepository.getFloors(post.backupId)
                val images = backupRepository.getImages(post.backupId)
                require(floors.isNotEmpty()) { "该备份没有楼层内容" }
                appContext.contentResolver.openOutputStream(target)?.use { out ->
                    when (format) {
                        BackupExporter.Format.SHELF -> BackupExporter.exportShelf(post, floors, images, out)
                        BackupExporter.Format.PDF -> BackupExporter.exportPdf(post, floors, images, out)
                        BackupExporter.Format.EPUB -> BackupExporter.exportEpub(post, floors, images, out)
                        BackupExporter.Format.TXT -> BackupExporter.exportTxt(post, floors, out)
                    }
                } ?: error("无法打开导出目标")
            }.onSuccess {
                sendUiEvent(CommonUiEvent.Toast(appContext.getString(R.string.local_backup_export_done)))
            }.onFailure { e ->
                Log.e("LocalBackupExport", "export failed format=${format.name}", e)
                sendUiEvent(CommonUiEvent.Toast(appContext.getString(R.string.local_backup_export_failed, e.message ?: "")))
            }
        } finally {
            _isExporting.value = false
        }
    }

    // ---------------------------------------------------------------- import

    fun import(source: Uri) = launchInVM(Dispatchers.IO + errorHandler) {
        _uiState.update { it.copy(isRefreshing = true) }
        runCatching {
            appContext.contentResolver.openInputStream(source)?.use { input ->
                backupImporter.importFromStream(input)
            } ?: error("无法读取所选文件")
        }.onSuccess { result ->
            sendUiEvent(
                CommonUiEvent.Toast(
                    appContext.getString(R.string.local_backup_import_done, result.imported)
                )
            )
            refresh()
        }.onFailure { e ->
            _uiState.update { it.copy(isRefreshing = false) }
            sendUiEvent(
                CommonUiEvent.Toast(
                    appContext.getString(R.string.local_backup_import_failed, e.message ?: "")
                )
            )
        }
    }
}
