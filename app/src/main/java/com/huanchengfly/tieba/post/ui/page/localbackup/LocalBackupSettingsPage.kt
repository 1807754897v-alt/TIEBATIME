package com.huanchengfly.tieba.post.ui.page.localbackup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.backup.LocalBackupPrefs
import com.huanchengfly.tieba.post.navigateDebounced
import com.huanchengfly.tieba.post.ui.page.Destination
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar

/**
 * 「本地备份」设置：与「设置备份」（账号配置备份）无关。
 */
@Composable
fun LocalBackupSettingsPage(
    navigator: NavController,
    viewModel: LocalBackupViewModel = hiltViewModel(),
) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    var showImagePolicyDialog by remember { mutableStateOf(false) }
    var showMaxPagesDialog by remember { mutableStateOf(false) }

    MyScaffold(
        topBar = {
            TitleCentredToolbar(
                title = stringResource(id = R.string.title_local_backup_settings),
                navigationIcon = {
                    BackNavigationIcon(onBackPressed = navigator::navigateUp)
                },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxWidth().padding(contentPadding)) {
            SettingsRow(
                title = stringResource(id = R.string.title_local_backup),
                summary = stringResource(id = R.string.settings_local_backup_entry),
                onClick = { navigator.navigateDebounced(Destination.LocalBackupList) },
            )
            SwitchSettingsRow(
                title = stringResource(id = R.string.settings_local_backup_sync),
                summary = stringResource(id = R.string.settings_local_backup_sync_desc),
                checked = prefs?.syncOnFavorite == true,
                onCheckedChange = viewModel::setSyncOnFavorite,
            )
            SettingsRow(
                title = stringResource(id = R.string.settings_local_backup_image_policy),
                summary = when (prefs?.imagePolicy) {
                    LocalBackupPrefs.ImagePolicy.ORIGINAL -> stringResource(id = R.string.settings_local_backup_image_original)
                    LocalBackupPrefs.ImagePolicy.WEBP_TINY -> stringResource(id = R.string.settings_local_backup_image_tiny)
                    LocalBackupPrefs.ImagePolicy.NONE -> stringResource(id = R.string.settings_local_backup_image_none)
                    else -> stringResource(id = R.string.settings_local_backup_image_small)
                },
                onClick = { showImagePolicyDialog = true },
            )
            SettingsRow(
                title = stringResource(id = R.string.settings_local_backup_max_pages),
                summary = prefs?.maxPages?.let { "第 1 - $it 页" } ?: "200",
                onClick = { showMaxPagesDialog = true },
            )
            Text(
                text = stringResource(id = R.string.settings_local_backup_note),
                style = M3.typography.bodySmall,
                color = M3.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (showImagePolicyDialog) {
        AlertDialog(
            onDismissRequest = { showImagePolicyDialog = false },
            title = { Text(stringResource(id = R.string.settings_local_backup_image_policy)) },
            text = {
                Column {
                    listOf(
                        LocalBackupPrefs.ImagePolicy.WEBP_SMALL to R.string.settings_local_backup_image_small,
                        LocalBackupPrefs.ImagePolicy.ORIGINAL to R.string.settings_local_backup_image_original,
                        LocalBackupPrefs.ImagePolicy.WEBP_TINY to R.string.settings_local_backup_image_tiny,
                        LocalBackupPrefs.ImagePolicy.NONE to R.string.settings_local_backup_image_none,
                    ).forEach { (policy, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setImagePolicy(policy)
                                    showImagePolicyDialog = false
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(
                                selected = prefs?.imagePolicy == policy,
                                onClick = {
                                    viewModel.setImagePolicy(policy)
                                    showImagePolicyDialog = false
                                },
                            )
                            Text(text = stringResource(id = label))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImagePolicyDialog = false }) {
                    Text(stringResource(id = R.string.action_cancel))
                }
            },
        )
    }

    if (showMaxPagesDialog) {
        AlertDialog(
            onDismissRequest = { showMaxPagesDialog = false },
            title = { Text(stringResource(id = R.string.settings_local_backup_max_pages)) },
            text = {
                Column {
                    listOf(50, 100, 200, 500).forEach { pages ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setMaxPages(pages)
                                    showMaxPagesDialog = false
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(
                                selected = prefs?.maxPages == pages,
                                onClick = {
                                    viewModel.setMaxPages(pages)
                                    showMaxPagesDialog = false
                                },
                            )
                            Text(text = "第 1 - $pages 页")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMaxPagesDialog = false }) {
                    Text(stringResource(id = R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (summary.isNotBlank()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchSettingsRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
