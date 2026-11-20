package com.terryxtl.tododrop.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 项目。[parentId] 为 null 的是顶层项目；最多 [ProjectTree.MAX_DEPTH] 层。
 * parentId 不加外键（SQLite 不能给已有的表补外键），删除时由 [TodoRepository.deleteProject] 连同子项目一起删。
 */
@Entity(tableName = "projects", indices = [Index("parentId")])
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 同一个父项目下的顺序。 */
    val sortOrder: Int,
    val createdAt: Long,
    val parentId: Long? = null,
)

/** 条目类型：普通待办，或只给人看的提醒（导出时不写进 Markdown，没有完成状态）。 */
object TodoKind {
    const val TODO = 0
    const val NOTE = 1
}

@Entity(
    tableName = "todos",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    /** 正文，可以多行。待办没有单独的标题。 */
    val content: String,
    val done: Boolean = false,
    /** 未完成列表里的顺序。完成 / 恢复时不改，所以恢复后会回到原来的位置。 */
    val sortOrder: Int,
    val createdAt: Long,
    val completedAt: Long? = null,
    /** 见 [TodoKind]。 */
    @ColumnInfo(defaultValue = "0") val kind: Int = TodoKind.TODO,
) {
    val isNote: Boolean get() = kind == TodoKind.NOTE
}

@Entity(tableName = "export_history")
data class ExportHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exportedAt: Long,
    val pcName: String,
    /** 电脑上保存的文件名，多个时用换行分隔。 */
    val savedAs: String,
    val itemCount: Int,
    /** 涉及的项目名，用「、」连接，只用于列表展示。 */
    val projectNames: String,
    /** 旧版本（协议 v1）在手机上生成的 Markdown；新记录为空字符串，内容在 [payload] 里。 */
    val markdown: String,
    /** 新记录：发送的原始数据和电脑返回的文件列表（JSON，见 [com.terryxtl.tododrop.export.HistoryPayload]）。 */
    val payload: String? = null,
) {
    val files: List<String> get() = savedAs.lines().filter { it.isNotBlank() }
}
