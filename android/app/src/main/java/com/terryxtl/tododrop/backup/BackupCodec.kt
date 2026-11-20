package com.terryxtl.tododrop.backup

import androidx.annotation.StringRes
import com.terryxtl.tododrop.R
import com.terryxtl.tododrop.data.ExportHistoryEntity
import com.terryxtl.tododrop.data.ProjectEntity
import com.terryxtl.tododrop.data.ProjectTree
import com.terryxtl.tododrop.data.TodoEntity
import com.terryxtl.tododrop.data.TodoKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 备份文件格式。字段和实体分开定义，实体以后改动时备份格式保持稳定。
 *
 * - v1：项目、待办、导出历史
 * - v2：项目加 `parentId`（子项目），待办加 `kind`（提醒），导出历史加 `payload`。新字段都有默认值，v1 文件照常能读。
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    /** 备份时间（毫秒）。手写的文件可以省略。 */
    val exportedAt: Long = 0,
    val projects: List<BackupProject>,
    val todos: List<BackupTodo>,
    val history: List<BackupHistory> = emptyList(),
) {
    companion object {
        const val FORMAT = "tododrop-backup"
        const val VERSION = 2

        /** 能读取的版本。 */
        val SUPPORTED_VERSIONS = 1..VERSION
    }
}

@Serializable
data class BackupProject(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
    val parentId: Long? = null,
)

@Serializable
data class BackupTodo(
    val id: Long,
    val projectId: Long,
    val content: String,
    val done: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val completedAt: Long? = null,
    /** 0 = 待办，1 = 提醒。 */
    val kind: Int = TodoKind.TODO,
)

@Serializable
data class BackupHistory(
    val id: Long,
    val exportedAt: Long,
    val pcName: String,
    val savedAs: String,
    val itemCount: Int,
    @SerialName("projectNames") val projectNames: String,
    val markdown: String,
    val payload: String? = null,
)

/**
 * 备份 / 恢复失败的原因。界面用 [messageRes] 和 [formatArgs] 显示，
 * 不直接展示异常信息（可能是英文或内部细节）。
 */
sealed class BackupError(@StringRes val messageRes: Int, vararg args: Any) {
    val formatArgs: Array<out Any> = args

    /** SAF 没能打开文件（返回 null）。 */
    data object CannotOpenFile : BackupError(R.string.backup_err_cannot_open)

    /** 读文件时出错。 */
    data object ReadFailed : BackupError(R.string.backup_err_read)

    /** 写文件时出错。 */
    data object WriteFailed : BackupError(R.string.backup_err_write)

    /** 文件超过大小上限。 */
    data class TooLarge(val maxMiB: Int) : BackupError(R.string.backup_err_too_large, maxMiB)

    /** 不是合法的 JSON，或字段缺失 / 类型不对。 */
    data object MalformedJson : BackupError(R.string.backup_err_malformed)

    /** `format` 不是 tododrop-backup。 */
    data object WrongFormat : BackupError(R.string.backup_err_wrong_format)

    data class UnsupportedVersion(val version: Int) : BackupError(R.string.backup_err_version, version)
    data object DuplicateProjectId : BackupError(R.string.backup_err_duplicate_project)
    data object InvalidProject : BackupError(R.string.backup_err_invalid_project)
    data object DuplicateTodoId : BackupError(R.string.backup_err_duplicate_todo)
    data object InvalidTodo : BackupError(R.string.backup_err_invalid_todo)

    /** 待办引用了备份里不存在的项目。 */
    data class OrphanTodo(val todoId: Long, val projectId: Long) :
        BackupError(R.string.backup_err_orphan_todo, todoId, projectId)

    data object DuplicateHistoryId : BackupError(R.string.backup_err_duplicate_history)

    /** 项目的父项目不存在、成环，或者层级超过上限。 */
    data class InvalidHierarchy(val projectId: Long) : BackupError(R.string.backup_err_hierarchy, projectId)

    /** 写入数据库失败（事务已回滚，原数据未改动）。 */
    data object RestoreFailed : BackupError(R.string.backup_err_restore)

    /** 其他意外错误。 */
    data object Unknown : BackupError(R.string.backup_err_unknown)
}

class BackupException(val error: BackupError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** 备份的序列化、校验和实体互转，纯 Kotlin，便于单元测试。 */
object BackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun fromEntities(
        projects: List<ProjectEntity>,
        todos: List<TodoEntity>,
        history: List<ExportHistoryEntity>,
        now: Long,
    ) = BackupFile(
        exportedAt = now,
        projects = projects.map { BackupProject(it.id, it.name, it.sortOrder, it.createdAt, it.parentId) },
        todos = todos.map {
            BackupTodo(it.id, it.projectId, it.content, it.done, it.sortOrder, it.createdAt, it.completedAt, it.kind)
        },
        history = history.map {
            BackupHistory(it.id, it.exportedAt, it.pcName, it.savedAs, it.itemCount, it.projectNames, it.markdown, it.payload)
        },
    )

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /** 解析并校验；不合法时抛 [BackupException]，原因见 [BackupException.error]。 */
    fun decode(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupException(BackupError.MalformedJson, e)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupError.MalformedJson, e)
        }
        fun reject(error: BackupError): Nothing = throw BackupException(error)

        if (file.format != BackupFile.FORMAT) reject(BackupError.WrongFormat)
        if (file.version !in BackupFile.SUPPORTED_VERSIONS) reject(BackupError.UnsupportedVersion(file.version))

        val projectIds = file.projects.map { it.id }
        if (projectIds.size != projectIds.toSet().size) reject(BackupError.DuplicateProjectId)
        if (file.projects.any { it.id <= 0 || it.name.isBlank() }) reject(BackupError.InvalidProject)
        val todoIds = file.todos.map { it.id }
        if (todoIds.size != todoIds.toSet().size) reject(BackupError.DuplicateTodoId)
        if (file.todos.any { it.id <= 0 || (it.kind != TodoKind.TODO && it.kind != TodoKind.NOTE) }) reject(BackupError.InvalidTodo)
        val known = projectIds.toSet()
        val orphan = file.todos.firstOrNull { it.projectId !in known }
        if (orphan != null) reject(BackupError.OrphanTodo(orphan.id, orphan.projectId))
        val historyIds = file.history.map { it.id }
        if (historyIds.size != historyIds.toSet().size) reject(BackupError.DuplicateHistoryId)
        checkHierarchy(file.projects)?.let { reject(BackupError.InvalidHierarchy(it)) }
        return file
    }

    /** 父项目必须存在、不能成环、层级不超过上限；有问题时返回出问题的项目 id。 */
    private fun checkHierarchy(projects: List<BackupProject>): Long? {
        val parentOf = projects.associate { it.id to it.parentId }
        for (p in projects) {
            var depth = 1
            var cur = p.parentId
            while (cur != null) {
                if (cur !in parentOf || cur == p.id) return p.id
                depth++
                if (depth > ProjectTree.MAX_DEPTH) return p.id
                cur = parentOf[cur]
            }
        }
        return null
    }

    fun toProjects(file: BackupFile) = file.projects.map {
        ProjectEntity(id = it.id, name = it.name, sortOrder = it.sortOrder, createdAt = it.createdAt, parentId = it.parentId)
    }

    fun toTodos(file: BackupFile) = file.todos.map {
        TodoEntity(
            id = it.id,
            projectId = it.projectId,
            content = it.content,
            // 提醒没有完成状态
            done = it.done && it.kind == TodoKind.TODO,
            sortOrder = it.sortOrder,
            createdAt = it.createdAt,
            completedAt = if (it.kind == TodoKind.TODO) it.completedAt else null,
            kind = it.kind,
        )
    }

    fun toHistory(file: BackupFile) = file.history.map {
        ExportHistoryEntity(
            id = it.id,
            exportedAt = it.exportedAt,
            pcName = it.pcName,
            savedAs = it.savedAs,
            itemCount = it.itemCount,
            projectNames = it.projectNames,
            markdown = it.markdown,
            payload = it.payload,
        )
    }
}
