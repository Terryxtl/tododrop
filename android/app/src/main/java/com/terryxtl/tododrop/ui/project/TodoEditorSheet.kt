package com.terryxtl.tododrop.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terryxtl.tododrop.R
import com.terryxtl.tododrop.data.ProjectTree
import com.terryxtl.tododrop.data.TodoKind
import com.terryxtl.tododrop.data.TodoEntity
import com.terryxtl.tododrop.ui.components.AppIcon
import kotlinx.coroutines.launch

/** [onSave] / [onDelete] 在面板收起动画结束后才调用。 */
@Composable
fun TodoEditorSheet(
    todo: TodoEntity,
    tree: ProjectTree,
    onDismiss: () -> Unit,
    onSave: (content: String, projectId: Long, kind: Int) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    var content by rememberSaveable(todo.id) { mutableStateOf(todo.content) }
    var projectId by rememberSaveable(todo.id) { mutableLongStateOf(todo.projectId) }
    var kind by rememberSaveable(todo.id) { mutableIntStateOf(todo.kind) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    var closing by remember { mutableStateOf(false) }

    /** 先播放收起动画，再执行 [action]（action 负责把面板从组合里移除）。收起过程中重复点击无效。 */
    fun hideThen(action: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.todo_edit_title), style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.todo_content_hint)) },
                minLines = 3,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )

            // 类型：待办 / 提醒（提醒没有完成状态，导出时不写进 Markdown，只在屏幕上提醒）
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(TodoKind.TODO to R.string.add_kind_todo, TodoKind.NOTE to R.string.add_kind_note)
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = kind == value,
                        onClick = { kind = value },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = kind == value) {
                                AppIcon(
                                    if (value == TodoKind.NOTE) R.drawable.ic_error else R.drawable.ic_check_box_outline_blank,
                                    null,
                                    Modifier.size(SegmentedButtonDefaults.IconSize),
                                )
                            }
                        },
                    ) { Text(stringResource(label)) }
                }
            }
            if (kind == TodoKind.NOTE) {
                Text(
                    stringResource(R.string.note_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
                OutlinedTextField(
                    value = tree.pathOf(projectId).joinToString(" / ") { it.name },
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.todo_project)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    tree.preorder().forEach { (p, depth) ->
                        DropdownMenuItem(
                            text = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = if (depth > 1) {
                                { AppIcon(R.drawable.ic_subdirectory_arrow_right, null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                            onClick = { projectId = p.id; menuOpen = false },
                            modifier = Modifier.padding(start = ((depth - 1) * 16).dp),
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { hideThen(onDelete) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    AppIcon(R.drawable.ic_delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.delete))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val trimmed = content.trim()
                        val target = projectId
                        val newKind = kind
                        hideThen { onSave(trimmed, target, newKind) }
                    },
                    enabled = content.isNotBlank() && !closing,
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}
