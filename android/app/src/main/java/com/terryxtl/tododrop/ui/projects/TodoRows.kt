package com.terryxtl.tododrop.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import com.terryxtl.tododrop.data.TodoKind
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.terryxtl.tododrop.R
import com.terryxtl.tododrop.data.TodoEntity
import com.terryxtl.tododrop.ui.components.AppIcon

/** 项目分组首尾的大圆角，以及组内各行之间的小圆角（Expressive 分段列表）。 */
internal val GroupCornerBig = 24.dp
internal val GroupCornerSmall = 6.dp

internal fun groupShape(top: Dp, bottom: Dp): Shape =
    RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)

/** 分组里头部以下的行：顶部总是小圆角，组里最后一行底部是大圆角。 */
internal fun childShape(last: Boolean): Shape =
    groupShape(GroupCornerSmall, if (last) GroupCornerBig else GroupCornerSmall)

/** 右滑：完成 / 恢复（提醒没有完成状态，[enableStart] 为 false）；左滑：删除。 */
@Composable
internal fun SwipeableTodo(
    stateKey: Any,
    shape: Shape,
    startLabel: String,
    onSwipeStart: () -> Unit,
    onSwipeEnd: () -> Unit,
    enableStart: Boolean = true,
    content: @Composable () -> Unit,
) {
    // 不能用 rememberSwipeToDismissBoxState()：它基于 rememberSaveable，LazyColumn 会按 item key 恢复，
    // 撤销删除 / 撤销完成 / 恢复已完成后，同一 key 的行重新出现时仍停在“已滑走”的位置。
    // 普通 remember 在行重新进入组合或待办状态变化时都从 Settled 开始。
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember(stateKey) { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold) }
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = enableStart,
        backgroundContent = {
            val direction = state.dismissDirection
            val (bg, fg) = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> colors.errorContainer to colors.onErrorContainer
                else -> colors.tertiaryContainer to colors.onTertiaryContainer
            }
            Row(
                modifier = Modifier.fillMaxSize().clip(shape).background(bg).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (direction == SwipeToDismissBoxValue.EndToStart) Arrangement.End else Arrangement.Start,
            ) {
                if (direction == SwipeToDismissBoxValue.EndToStart) {
                    AppIcon(R.drawable.ic_delete, stringResource(R.string.delete), tint = fg)
                } else {
                    AppIcon(R.drawable.ic_check, null, tint = fg)
                    Spacer(Modifier.width(8.dp))
                    Text(startLabel, color = fg, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        onDismiss = { value ->
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onSwipeStart()
                SwipeToDismissBoxValue.EndToStart -> onSwipeEnd()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
    ) { content() }
}

@Composable
internal fun TodoRow(
    todo: TodoEntity,
    shape: Shape,
    dragging: Boolean,
    onClick: () -> Unit,
    onCheckedChange: () -> Unit,
    onDelete: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (dragging) colors.secondaryContainer else colors.surfaceContainer,
        shadowElevation = if (dragging) 6.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (todo.isNote) {
                // 提醒没有完成状态：复选框换成带圈的感叹号，和复选框占同样的宽度
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_error, stringResource(R.string.note_label), tint = colors.tertiary)
                }
            } else {
                Checkbox(checked = todo.done, onCheckedChange = { onCheckedChange() })
            }
            Text(
                todo.content,
                style = MaterialTheme.typography.bodyLarge,
                color = if (todo.done) colors.onSurfaceVariant else colors.onSurface,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            IconButton(onClick = onDelete) {
                AppIcon(R.drawable.ic_delete, stringResource(R.string.delete), tint = colors.onSurfaceVariant)
            }
            trailing()
        }
    }
}

/**
 * 展开的项目里，紧跟未完成待办的输入行。添加后保持焦点，方便连续输入。
 * 左边的图标切换要添加的类型：待办（加号）或提醒（带圈感叹号）。
 */
@Composable
internal fun AddTodoRow(shape: Shape, onAdd: (String, Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableIntStateOf(TodoKind.TODO) }
    val isNote = kind == TodoKind.NOTE
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    fun submit() {
        if (text.isNotBlank()) {
            onAdd(text, kind)
            text = ""
        }
    }
    Surface(shape = shape, color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(if (isNote) R.string.detail_add_note_hint else R.string.detail_add_hint)) },
                leadingIcon = {
                    IconButton(onClick = {
                        kind = if (isNote) TodoKind.TODO else TodoKind.NOTE
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }) {
                        AppIcon(
                            if (isNote) R.drawable.ic_error else R.drawable.ic_add,
                            stringResource(if (isNote) R.string.add_kind_note else R.string.add_kind_todo),
                            tint = if (isNote) colors.tertiary else colors.onSurfaceVariant,
                        )
                    }
                },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
                AppIcon(R.drawable.ic_send, stringResource(R.string.detail_add))
            }
        }
    }
}
