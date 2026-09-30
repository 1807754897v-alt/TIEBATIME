package com.huanchengfly.tieba.post.ui.page.localbackup

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.backup.BackupExporter
import com.huanchengfly.tieba.post.models.database.LocalBackupPost
import com.huanchengfly.tieba.post.navigateDebounced
import com.huanchengfly.tieba.post.ui.page.Destination
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.PullToRefreshBox
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.states.StateScreen

@Composable
fun LocalBackupListPage(
    navigator: NavController,
    viewModel: LocalBackupViewModel = hiltViewModel(),
) {
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<LocalBackupPost?>(null) }
    var exportTarget by remember { mutableStateOf<LocalBackupPost?>(null) }

    // SAF 结果回调可能晚于进程重建（MIUI 后台查杀），跨重建保存待导出目标，避免 0B 文件
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportFormat by rememberSaveable { mutableStateOf<String?>(null) }

    val onExportResult: (android.net.Uri?) -> Unit = { uri ->
        val backupId = pendingExportId
        val formatName = pendingExportFormat
        pendingExportId = null
        pendingExportFormat = null
        if (uri != null && backupId != null && formatName != null) {
            val format = BackupExporter.Format.entries.firstOrNull { it.name == formatName }
            val post = viewModel.uiState.value.data.firstOrNull { it.backupId == backupId }
            if (format != null && post != null) {
                viewModel.export(post, format, uri)
            }
        }
    }
    val shelfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupExporter.Format.SHELF.mimeType), onExportResult
    )
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupExporter.Format.PDF.mimeType), onExportResult
    )
    val epubLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupExporter.Format.EPUB.mimeType), onExportResult
    )
    val txtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupExporter.Format.TXT.mimeType), onExportResult
    )

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::import)
    }

    fun launchExport(post: LocalBackupPost, format: BackupExporter.Format) {
        pendingExportId = post.backupId
        pendingExportFormat = format.name
        val name = BackupExporter.suggestName(post, format)
        when (format) {
            BackupExporter.Format.SHELF -> shelfLauncher.launch(name)
            BackupExporter.Format.PDF -> pdfLauncher.launch(name)
            BackupExporter.Format.EPUB -> epubLauncher.launch(name)
            BackupExporter.Format.TXT -> txtLauncher.launch(name)
        }
    }

    MyScaffold(
        topBar = {
            TitleCentredToolbar(
                title = stringResource(id = R.string.title_local_backup),
                navigationIcon = {
                    BackNavigationIcon(onBackPressed = navigator::navigateUp)
                },
                actions = {
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.UploadFile,
                            contentDescription = stringResource(id = R.string.local_backup_import),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        val isRefreshing by viewModel.uiState.collectPartialAsState(
            prop1 = LocalBackupUiState::isRefreshing,
            initial = false
        )
        val isEmpty by viewModel.uiState.collectPartialAsState(
            prop1 = LocalBackupUiState::isEmpty,
            initial = true
        )
        val error by viewModel.uiState.collectPartialAsState(
            prop1 = LocalBackupUiState::error,
            initial = null
        )
        val data by viewModel.uiState.collectPartialAsState(
            prop1 = LocalBackupUiState::data,
            initial = emptyList()
        )
        val totalBytes by viewModel.uiState.collectPartialAsState(
            prop1 = LocalBackupUiState::totalBytes,
            initial = 0L
        )
        val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
        val context = LocalContext.current

        StateScreen(
            isEmpty = isEmpty,
            isLoading = isRefreshing && data.isEmpty(),
            error = error,
            onReload = viewModel::refresh,
            screenPadding = contentPadding,
            emptyScreen = {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(id = R.string.local_backup_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        viewModel.search(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(id = R.string.local_backup_search_hint)) },
                    singleLine = true,
                )
                Text(
                    text = stringResource(
                        id = R.string.local_backup_summary,
                        data.size,
                        Formatter.formatFileSize(context, totalBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (isExporting) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(data, key = { it.backupId }) { post ->
                            BackupCard(
                                post = post,
                                onClick = {
                                    // 离线阅读：复用完整帖子页（沉浸/倒序/跳页/楼中楼），数据来自本地
                                    navigator.navigateDebounced(
                                        Destination.Thread(
                                            threadId = post.threadId,
                                            forumId = post.forumId,
                                            seeLz = post.seeLz,
                                            localBackupId = post.backupId,
                                        )
                                    )
                                },
                                onUpdate = { viewModel.updateBackup(post) },
                                onExport = { exportTarget = post },
                                onDelete = { deleteTarget = post },
                            )
                        }
                    }
                }
            }
        }

        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text(stringResource(id = R.string.local_backup_delete_title)) },
                text = {
                    Text(
                        stringResource(
                            id = R.string.local_backup_delete_message,
                            target.title
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteTarget = null
                            viewModel.delete(target)
                        }
                    ) { Text(stringResource(id = R.string.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text(stringResource(id = R.string.action_cancel))
                    }
                },
            )
        }

        exportTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { exportTarget = null },
                title = { Text(stringResource(id = R.string.local_backup_export_title)) },
                text = {
                    Column {
                        listOf(
                            BackupExporter.Format.SHELF to R.string.local_backup_export_fmt_shelf,
                            BackupExporter.Format.PDF to R.string.local_backup_export_fmt_pdf,
                            BackupExporter.Format.EPUB to R.string.local_backup_export_fmt_epub,
                            BackupExporter.Format.TXT to R.string.local_backup_export_fmt_txt,
                        ).forEach { (format, label) ->
                            TextButton(
                                onClick = {
                                    exportTarget = null
                                    launchExport(target, format)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(id = label),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { exportTarget = null }) {
                        Text(stringResource(id = R.string.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun BackupCard(
    post: LocalBackupPost,
    onClick: () -> Unit,
    onUpdate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = post.title,
                style = M3.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = post.forumName ?: "—",
                    style = M3.typography.bodySmall,
                    color = M3.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = statusLabel(post.status),
                    style = M3.typography.bodySmall,
                    color = when (post.status) {
                        1 -> M3.colorScheme.primary
                        2 -> M3.colorScheme.tertiary
                        else -> M3.colorScheme.error
                    },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = context.getString(
                    R.string.local_backup_card_meta,
                    post.crawledFloors,
                    post.imageCount,
                    Formatter.formatFileSize(context, post.totalBytes)
                ),
                style = M3.typography.bodySmall,
                color = M3.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onUpdate) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(id = R.string.local_backup_update),
                    )
                }
                IconButton(onClick = onExport) {
                    Icon(
                        imageVector = Icons.Rounded.IosShare,
                        contentDescription = stringResource(id = R.string.local_backup_export),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(id = R.string.action_delete),
                    )
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: Int): String = stringResource(
    when (status) {
        1 -> R.string.local_backup_status_ok
        2 -> R.string.local_backup_status_partial
        3 -> R.string.local_backup_status_failed
        else -> R.string.local_backup_status_pending
    }
)
