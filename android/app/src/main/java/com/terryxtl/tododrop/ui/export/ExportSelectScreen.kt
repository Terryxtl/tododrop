package com.terryxtl.tododrop.ui.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.terryxtl.tododrop.LocalAppContainer
import com.terryxtl.tododrop.R
import com.terryxtl.tododrop.ui.components.AppIcon
import com.terryxtl.tododrop.ui.components.EmptyState
import com.terryxtl.tododrop.ui.components.ShapeBadge
import com.terryxtl.tododrop.ui.components.badgeShapeFor

@Composable
fun ExportSelectScreen(onBack: () -> Unit, onScan: (List<Long>) -> Unit) {
    val container = LocalAppContainer.current
    val vm: ExportViewModel = viewModel { ExportViewModel(container.repository, createSavedStateHandle()) }
    val data by vm.data.collectAsStateWithLifecycle()
    val groups = data?.groups
    val selected by vm.selected.collectAsStateWithLifecycle()
    var showPreview by rememberSaveable { mutableStateOf(false) }

    val allIds = groups.orEmpty().sumOf { it.todos.size }
    val selectedCount = groups.orEmpty().sumOf { g -> g.todos.count { it.id in selected } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, stringResource(R.string.back)) }
                },
                actions = {
                    if (allIds > 0) {
                        val allSelected = selectedCount == allIds
                        TextButton(onClick = { if (allSelected) vm.selectNone() else vm.selectAll() }) {
                            Text(stringResource(if (allSelected) R.string.export_select_none else R.string.export_select_all))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (allIds > 0) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.export_selected, selectedCount),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val enabled = selectedCount > 0
                        val previewLabel = stringResource(R.string.export_preview)
                        val scanLabel = stringResource(R.string.export_scan)
                        val openPreview = { showPreview = true }
                        val startScan = { onScan(vm.selectedIdsInOrder()) }
                        // Expressive 按钮组：按下时按钮变宽、邻居让位；主操作「扫码发送」占更多宽度
                        ButtonGroup(
                            overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
                            modifier = Modifier.weight(1f),
                        ) {
                            customItem(
                                buttonGroupContent = {
                                    val interaction = remember { MutableInteractionSource() }
                                    OutlinedButton(
                                        onClick = openPreview,
                                        enabled = enabled,
                                        shapes = ButtonDefaults.shapes(),
                                        interactionSource = interaction,
                                        modifier = Modifier.weight(1f).animateWidth(interaction),
                                    ) { Text(previewLabel, maxLines = 1) }
                                },
                                menuContent = { menuState ->
                                    DropdownMenuItem(
                                        text = { Text(previewLabel) },
                                        enabled = enabled,
                                        onClick = { menuState.dismiss(); openPreview() },
                                    )
                                },
                            )
                            customItem(
                                buttonGroupContent = {
                                    val interaction = remember { MutableInteractionSource() }
                                    Button(
                                        onClick = startScan,
                                        enabled = enabled,
                                        shapes = ButtonDefaults.shapes(),
                                        interactionSource = interaction,
                                        modifier = Modifier.weight(1.6f).animateWidth(interaction),
                                    ) {
                                        AppIcon(R.drawable.ic_qr_code_scanner, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(scanLabel, maxLines = 1)
                                    }
                                },
                                menuContent = { menuState ->
                                    DropdownMenuItem(
                                        text = { Text(scanLabel) },
                                        leadingIcon = { AppIcon(R.drawable.ic_qr_code_scanner, null) },
                                        enabled = enabled,
                                        onClick = { menuState.dismiss(); startScan() },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        val list = groups
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            list.isEmpty() -> EmptyState(
                icon = R.drawable.ic_done_all,
                title = stringResource(R.string.export_empty_title),
                body = stringResource(R.string.export_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                list.forEach { group ->
                    val indent = ((group.depth - 1) * 20).dp
                    item(key = "p-${group.project.id}") {
                        val count = group.todos.count { it.id in selected }
                        val state = when (count) {
                            0 -> ToggleableState.Off
                            group.todos.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        val hasTodos = group.todos.isNotEmpty()
                        Row(
                            modifier = Modifier
                                .padding(start = indent, top = if (group.depth == 1) 12.dp else 4.dp, bottom = 4.dp)
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .clickable(enabled = hasTodos) { vm.toggleGroup(group) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (hasTodos) {
                                TriStateCheckbox(state = state, onClick = { vm.toggleGroup(group) })
                            } else {
                                Spacer(Modifier.width(12.dp))
                            }
                            if (group.depth == 1) {
                                ShapeBadge(
                                    shape = badgeShapeFor(group.project.id),
                                    container = MaterialTheme.colorScheme.primaryContainer,
                                    size = 28.dp,
                                ) {
                                    Text(
                                        group.project.name.trim().take(1),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            } else {
                                AppIcon(
                                    R.drawable.ic_subdirectory_arrow_right,
                                    null,
                                    Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                group.project.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                            if (hasTodos) {
                                Text(
                                    stringResource(R.string.export_group_count, count, group.todos.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                            }
                        }
                    }
                    itemsIndexed(group.items, key = { _, t -> "t-${t.id}" }) { index, todo ->
                        val isSelected = todo.id in selected
                        val last = group.items.size - 1
                        val shape = RoundedCornerShape(
                            topStart = if (index == 0) 20.dp else 6.dp,
                            topEnd = if (index == 0) 20.dp else 6.dp,
                            bottomStart = if (index == last) 20.dp else 6.dp,
                            bottomEnd = if (index == last) 20.dp else 6.dp,
                        )
                        if (todo.isNote) {
                            NoteSelectRow(todo.content, shape, Modifier.padding(start = indent))
                        } else {
                            Surface(
                                shape = shape,
                                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .padding(start = indent)
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .clickable { vm.toggle(todo.id) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = isSelected, onCheckedChange = { vm.toggle(todo.id) })
                                    Text(
                                        todo.content,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPreview) {
        val plan = remember(data, selected) { vm.previewPlan() }
        ModalBottomSheet(
            onDismissRequest = { showPreview = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.export_preview_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.export_preview_body, plan.projects.size, plan.todoCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                plan.projects.forEach { ExportFileCard(it, savedAs = null) }
                NotesCard(plan.notes)
            }
        }
    }
}

/** 勾选页里的提醒：没有复选框，换成带圈的感叹号；不能勾选，跟着项目一起在电脑上显示。 */
@Composable
private fun NoteSelectRow(content: String, shape: Shape, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = shape, color = colors.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_error, null, Modifier.size(20.dp), tint = colors.tertiary)
            Text(
                content,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            )
            Text(stringResource(R.string.note_label), style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
        }
    }
}
