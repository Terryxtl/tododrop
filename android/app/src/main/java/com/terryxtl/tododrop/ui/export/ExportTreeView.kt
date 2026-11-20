package com.terryxtl.tododrop.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terryxtl.tododrop.R
import com.terryxtl.tododrop.export.ExportProject
import com.terryxtl.tododrop.export.NoteLine
import com.terryxtl.tododrop.export.todoCount
import com.terryxtl.tododrop.ui.components.AppIcon

/**
 * 一个顶层项目对应电脑上的一个文件：卡片顶部是文件名（还没发送时显示项目名），
 * 下面按电脑端生成的结构列出待办，子项目作为小标题。提醒不在这里显示，见 [NotesCard]。
 */
@Composable
fun ExportFileCard(project: ExportProject, savedAs: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(R.drawable.ic_upload_file, null, Modifier.size(20.dp), tint = colors.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    if (savedAs != null) {
                        SelectionContainer {
                            Text(
                                savedAs,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    } else {
                        Text(project.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    stringResource(R.string.export_file_count, project.todoCount()),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.outlineVariant)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProjectItems(project, level = 1)
            }
        }
    }
}

@Composable
private fun ProjectItems(project: ExportProject, level: Int) {
    val colors = MaterialTheme.colorScheme
    project.todos.forEach { content ->
        Row(verticalAlignment = Alignment.Top) {
            AppIcon(
                R.drawable.ic_check_box_outline_blank,
                null,
                Modifier.padding(top = 2.dp).size(18.dp),
                tint = colors.onSurfaceVariant,
            )
            Text(
                content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
    project.children.filter { it.todoCount() > 0 }.forEach { child ->
        Text(
            "#".repeat(level + 1) + " " + child.name,
            style = if (level == 1) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        ProjectItems(child, level + 1)
    }
}

/** 只给人看的提醒：带圈感叹号 + 所在项目 + 内容。 */
@Composable
fun NotesCard(notes: List<NoteLine>, modifier: Modifier = Modifier) {
    if (notes.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = colors.tertiaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_error, null, Modifier.size(20.dp), tint = colors.onTertiaryContainer)
                Text(
                    stringResource(R.string.notes_title, notes.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onTertiaryContainer,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            notes.forEach { note ->
                Column {
                    Text(
                        note.path.joinToString(" / "),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onTertiaryContainer.copy(alpha = 0.72f),
                    )
                    Text(note.content, style = MaterialTheme.typography.bodyMedium, color = colors.onTertiaryContainer)
                }
            }
        }
    }
}

/** 等宽字体展示 Markdown，可选中复制。只用于旧版本（在手机上生成 Markdown）留下的导出历史。 */
@Composable
fun MarkdownBlock(markdown: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                markdown,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
