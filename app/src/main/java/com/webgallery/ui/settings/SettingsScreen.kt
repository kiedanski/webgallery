// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 WebGallery contributors
package com.webgallery.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.webgallery.BuildConfig
import com.webgallery.data.SettingsRepository
import com.webgallery.ui.AppViewModelFactory
import com.webgallery.util.FileUtils

private const val SOURCE_URL = "https://github.com/kiedanski/webgallery"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    factory: AppViewModelFactory,
    onDisconnected: () -> Unit
) {
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.recalculateSizes() }

    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val cacheLimit by viewModel.cacheLimit.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val thumbnailCacheSize by viewModel.thumbnailCacheSize.collectAsStateWithLifecycle()
    val imageCacheSize by viewModel.imageCacheSize.collectAsStateWithLifecycle()
    val favoritesSize by viewModel.favoritesSize.collectAsStateWithLifecycle()

    var showCacheLimitDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showClearThumbsDialog by remember { mutableStateOf(false) }
    var showClearImageDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxWidth()) {
            item { SectionHeader("Server") }
            item { SettingItem("Server URL", serverUrl.ifEmpty { "Not configured" }) }
            item { SettingItem("Username", username.ifEmpty { "Not configured" }) }
            item {
                SettingRow(onClick = { showDisconnectDialog = true }) {
                    TextButton(onClick = { showDisconnectDialog = true }) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            item { HorizontalDivider() }
            item { SectionHeader("Storage") }
            item {
                SettingItem(
                    title = "Image cache limit",
                    value = FileUtils.formatFileSize(cacheLimit),
                    onClick = { showCacheLimitDialog = true }
                )
            }
            item {
                SettingItem(
                    title = "Thumbnail cache",
                    value = thumbnailCacheSize,
                    trailing = {
                        TextButton(onClick = { showClearThumbsDialog = true }) { Text("Clear") }
                    }
                )
            }
            item {
                SettingItem(
                    title = "Image cache",
                    value = "$imageCacheSize / ${FileUtils.formatFileSize(cacheLimit)}",
                    trailing = {
                        TextButton(onClick = { showClearImageDialog = true }) { Text("Clear") }
                    }
                )
            }
            item { SettingItem("Favorites", favoritesSize) }

            item { HorizontalDivider() }
            item { SectionHeader("Appearance") }
            item {
                SettingItem(
                    title = "Theme",
                    value = themeMode.replaceFirstChar { it.uppercase() },
                    onClick = { showThemeDialog = true }
                )
            }

            item { HorizontalDivider() }
            item { SectionHeader("About") }
            item { SettingItem("Version", BuildConfig.VERSION_NAME) }
            item {
                SettingItem(
                    title = "Source code",
                    value = SOURCE_URL,
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
                    }
                )
            }
            item { SettingItem("License", "GPLv3") }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }

    if (showCacheLimitDialog) {
        OptionDialog(
            title = "Image cache limit",
            options = SettingsRepository.CACHE_LIMIT_OPTIONS,
            optionLabel = { FileUtils.formatFileSize(it) },
            current = cacheLimit,
            onDismiss = { showCacheLimitDialog = false },
            onSelected = {
                viewModel.setCacheLimit(it)
                showCacheLimitDialog = false
            }
        )
    }
    if (showThemeDialog) {
        OptionDialog(
            title = "Theme",
            options = listOf("system", "light", "dark"),
            optionLabel = { mode ->
                when (mode) {
                    "light" -> "Light"
                    "dark" -> "Dark"
                    else -> "System default"
                }
            },
            current = themeMode,
            onDismiss = { showThemeDialog = false },
            onSelected = {
                viewModel.setThemeMode(it)
                showThemeDialog = false
            }
        )
    }
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Disconnect from server?") },
            text = { Text("This will delete all cached photos and favorites from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    viewModel.disconnect(onDisconnected)
                }) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) { Text("Cancel") }
            }
        )
    }
    if (showClearThumbsDialog) {
        AlertDialog(
            onDismissRequest = { showClearThumbsDialog = false },
            title = { Text("Clear thumbnails?") },
            text = { Text("Clear all downloaded thumbnails? They will re-download on next sync.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearThumbsDialog = false
                    viewModel.clearThumbnailCache()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearThumbsDialog = false }) { Text("Cancel") } }
        )
    }
    if (showClearImageDialog) {
        AlertDialog(
            onDismissRequest = { showClearImageDialog = false },
            title = { Text("Clear image cache?") },
            text = { Text("Clear cached full-size images? Favorites are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearImageDialog = false
                    viewModel.clearImageCache()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearImageDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingItem(
    title: String,
    value: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    SettingRow(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingRow(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val baseModifier = Modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 16.dp, vertical = 12.dp)
    Row(
        modifier = baseModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun <T> OptionDialog(
    title: String,
    options: List<T>,
    optionLabel: (T) -> String,
    current: T,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == current,
                                onClick = { onSelected(option) }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == current, onClick = { onSelected(option) })
                        Spacer(Modifier.width(8.dp))
                        Text(optionLabel(option))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
