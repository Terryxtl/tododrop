package com.terryxtl.tododrop.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.terryxtl.tododrop.LocalAppContainer
import com.terryxtl.tododrop.R
import com.terryxtl.tododrop.data.ExportHistoryEntity
import com.terryxtl.tododrop.data.TodoRepository
import com.terryxtl.tododrop.export.HistoryPayload
import com.terryxtl.tododrop.export.notes
import com.terryxtl.tododrop.ui.components.AppIcon
import com.terryxtl.tododrop.ui.components.ConfirmDialog
import com.terryxtl.tododrop.ui.components.EmptyState
import com.terryxtl.tododrop.ui.export.ExportFileCard
import com.terryxtl.tododrop.ui.export.MarkdownBlock
import com.terryxtl.tododrop.ui.export.NotesCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HistoryViewModel(private val repository: TodoRepository) : ViewModel() {
    val history: StateFlow<List<ExportHistoryEntity>?> = repository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(id: Long) = viewModelScope.launch { repository.deleteHistory(id) }
    fun clear() = viewModelScope.launch { repository.clearHistory() }
}

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatTime(epochMs: Long): String =
    TimeFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: HistoryViewModel = viewModel { HistoryViewModel(container.repository) }
    val history by vm.history.collectAsStateWithLifecycle()
    // 只保存 id，旋转屏幕后从最新列表里查回记录
    var viewingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, stringResource(R.string.back)) }
                },
                actions = {
                    if (!history.isNullOrEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.history_clear)) }
                    }
                },
            )
        },
    ) { padding ->
        val list = history
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            list.isEmpty() -> EmptyState(
                icon = R.drawable.ic_history,
                title = stringResource(R.string.history_empty_title),
                body = stringResource(R.string.history_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(list, key = { _, h -> h.id }) { index, entry ->
                    val last = list.size - 1
                    val shape = RoundedCornerShape(
                        topStart = if (index == 0) 24.dp else 6.dp,
                        topEnd = if (index == 0) 24.dp else 6.dp,
                        bottomStart = if (index == last) 24.dp else 6.dp,
                        bottomEnd = if (index == last) 24.dp else 6.dp,
                    )
                    HistoryRow(
                        entry = entry,
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .clip(shape)
                            .clickable { viewingId = entry.id },
                    )
                }
            }
        }
    }

    viewingId?.let { id -> history?.firstOrNull { it.id == id } }?.let { entry ->
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = { viewingId = null },
            sheetState = sheetState,
        ) {
            HistoryDetail(
                entry = entry,
                // 先播放收起动画，再删除记录并移除面板
                onDelete = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        vm.delete(entry.id)
                        viewingId = null
                    }
                },
            )
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.history_clear_title),
            body = stringResource(R.string.history_clear_body),
            confirmLabel = stringResource(R.string.history_clear),
            onDismiss = { confirmClear = false },
            onConfirm = { vm.clear(); confirmClear = false },
        )
    }
}

/** 列表里的一条记录：时间 / 涉及的项目 / 电脑 · 条数 · 文件数。 */
@Composable
private fun HistoryRow(entry: ExportHistoryEntity, modifier: Modifier = Modifier) {
    val files = entry.files
    ListItem(
        overlineContent = { Text(formatTime(entry.exportedAt)) },
        supportingContent = {
            Text(stringResource(R.string.history_item_summary, entry.pcName, entry.itemCount, files.size))
        },
        leadingContent = { AppIcon(R.drawable.ic_computer, null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier,
    ) {
        if (entry.projectNames.isNotBlank()) {
            Text(entry.projectNames, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Text(files.firstOrNull().orEmpty(), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 记录详情：标题行（时间、摘要、删除按钮）+ 每个文件一张卡片 + 提醒。
 * 旧版本留下的记录没有原始数据，显示当时的文件名和 Markdown。
 */
@Composable
private fun HistoryDetail(entry: ExportHistoryEntity, onDelete: () -> Unit) {
    val payload = remember(entry.payload) { HistoryPayload.decodeOrNull(entry.payload) }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(formatTime(entry.exportedAt), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.history_item_summary, entry.pcName, entry.itemCount, entry.files.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                AppIcon(R.drawable.ic_delete, stringResource(R.string.history_delete), tint = colors.error)
            }
        }
        Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (payload != null) {
                payload.filesWithProjects().forEach { (file, project) -> ExportFileCard(project, file?.savedAs) }
                NotesCard(payload.projects.notes())
            } else {
                SelectionContainer {
                    Text(entry.savedAs, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                }
                MarkdownBlock(entry.markdown)
            }
        }
    }
}
