package com.huanchengfly.tieba.post.ui.page.settings.theme

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.stateInViewModel
import com.huanchengfly.tieba.post.repository.user.SettingsRepository
import com.huanchengfly.tieba.post.utils.extension.set
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class AppFontViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val fontScaleSettings = settingsRepo.fontScale

    val customFontPath = settingsRepo.customFontPath.stateInViewModel(initialValue = "")

    /** 导入 TTF：拷贝到应用私有目录并记录路径（全局 Typography 即时应用） */
    fun importFont(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val dir = File(context.filesDir, "fonts").apply { mkdirs() }
                val target = File(dir, "custom_font.ttf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error(context.getString(R.string.font_import_failed, "无法读取所选文件"))
                if (target.length() <= 0L) error(context.getString(R.string.font_import_failed, "文件为空"))
                settingsRepo.customFontPath.set(target.absolutePath)
            }
            val message = result.fold(
                onSuccess = { context.getString(R.string.font_import_done) },
                onFailure = { context.getString(R.string.font_import_failed, it.message ?: "") }
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 恢复默认字体 */
    fun clearFont(context: Context) {
        runCatching { File(File(context.filesDir, "fonts"), "custom_font.ttf").delete() }
        settingsRepo.customFontPath.set("")
        Toast.makeText(context, context.getString(R.string.font_reset_done), Toast.LENGTH_SHORT).show()
    }

    private val _fontScale = MutableStateFlow(-1.0f)
    val fontScale = _fontScale.asStateFlow()

    val fontScaleChanged = combine(fontScaleSettings, _fontScale) { old, new ->
        abs(old - new) >= 0.01f
    }
    .stateInViewModel(initialValue = false)

    init {
        viewModelScope.launch { onFontScaleChanged(fontScaleSettings.snapshot()) }
    }

    fun onFontScaleChanged(fontScale: Float) = _fontScale.set { fontScale }

    fun onSave() = fontScaleSettings.set(fontScale.value)
}