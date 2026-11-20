package com.terryxtl.tododrop.data

import androidx.room.withTransaction
import com.terryxtl.tododrop.backup.BackupCodec
import com.terryxtl.tododrop.backup.BackupFile
import com.terryxtl.tododrop.export.ExportPlan
import com.terryxtl.tododrop.export.ExportTreeBuilder
import kotlinx.coroutines.flow.Flow

class TodoRepository(private val db: TodoDropDatabase) {
    private val projects = db.projectDao()
    private val todos = db.todoDao()
    private val history = db.historyDao()

    private fun now() = System.currentTimeMillis()

    // ---- 项目 ----

    fun observeProjects(): Flow<List<ProjectEntity>> = projects.observeAll()

    /**
     * 新建项目；[parentId] 不为 null 时建成它的子项目。
     * 父项目已经是第 [ProjectTree.MAX_DEPTH] 层或者不存在时返回 null。
     */
    suspend fun createProject(name: String, parentId: Long? = null): Long? = db.withTransaction {
        if (parentId != null) {
            val tree = ProjectTree(projects.getAll())
            if (tree[parentId] == null || !tree.canAddChild(parentId)) return@withTransaction null
        }
        projects.insert(
            ProjectEntity(
                name = name.trim(),
                sortOrder = projects.maxSortOrder(parentId) + 1,
                createdAt = now(),
                parentId = parentId,
            )
        )
    }

    suspend fun renameProject(id: Long, name: String) = projects.rename(id, name.trim())

    /** 删除项目，连同它的全部子孙项目和其中的待办。 */
    suspend fun deleteProject(id: Long) = db.withTransaction {
        val ids = ProjectTree(projects.getAll()).subtreeIds(id).ifEmpty { listOf(id) }
        ids.chunked(500).forEach {
            todos.deleteByProjects(it)
            projects.deleteByIds(it)
        }
    }

    /** 同一个父项目下的新顺序。 */
    suspend fun reorderProjects(idsInOrder: List<Long>) = db.withTransaction {
        idsInOrder.forEachIndexed { index, id -> projects.setSortOrder(id, index) }
    }

    // ---- 待办 ----

    fun observeAllTodos(): Flow<List<TodoEntity>> = todos.observeAll()
    fun observeAllOpenTodos(): Flow<List<TodoEntity>> = todos.observeAllOpen()

    suspend fun addTodo(projectId: Long, content: String, kind: Int = TodoKind.TODO): Long = db.withTransaction {
        todos.insert(
            TodoEntity(
                projectId = projectId,
                content = content.trim(),
                sortOrder = todos.maxSortOrder(projectId) + 1,
                createdAt = now(),
                kind = kind,
            )
        )
    }

    suspend fun updateTodoContent(id: Long, content: String) = todos.updateContent(id, content.trim())

    suspend fun setTodoKind(id: Long, kind: Int) = todos.setKind(id, kind)

    suspend fun setDone(id: Long, done: Boolean) = todos.setDone(id, done, if (done) now() else null)

    suspend fun deleteTodo(id: Long) = todos.delete(id)

    /** 撤销删除：按原样写回。 */
    suspend fun restoreTodo(todo: TodoEntity) = todos.upsert(todo)

    suspend fun moveTodo(id: Long, targetProjectId: Long) = db.withTransaction {
        todos.move(id, targetProjectId, todos.maxSortOrder(targetProjectId) + 1)
    }

    /**
     * 拖拽结束后保存未完成列表的新顺序。复用这些待办原有的 sortOrder（见 [reassignSortOrders]），
     * 不和已完成待办的序号冲突。读取和写回在同一个事务里。
     */
    suspend fun reorderTodos(idsInOrder: List<Long>) = db.withTransaction {
        val current = idsInOrder.distinct().chunked(500)
            .flatMap { todos.getByIds(it) }
            .associate { it.id to it.sortOrder }
        reassignSortOrders(current, idsInOrder).forEach { (id, order) -> todos.setSortOrder(id, order) }
    }

    // ---- 导出 ----

    /** 根据选中的 id 组织要发送的项目树；已经完成或删除的会被跳过。 */
    suspend fun buildExport(todoIds: List<Long>): ExportPlan = db.withTransaction {
        ExportTreeBuilder.build(projects.getAll(), todos.getAllOpen(), todoIds)
    }

    /** 发送成功后：把发出去的待办标为已完成，并记一条导出历史。 */
    suspend fun finishExport(todoIds: List<Long>, entry: ExportHistoryEntity) = db.withTransaction {
        val at = now()
        todoIds.distinct().chunked(500).forEach { todos.completeAll(it, at) }
        history.insert(entry)
    }

    // ---- 导出历史 ----

    fun observeHistory(): Flow<List<ExportHistoryEntity>> = history.observeAll()
    suspend fun deleteHistory(id: Long) = history.delete(id)
    suspend fun clearHistory() = history.deleteAll()

    // ---- 备份 ----

    suspend fun snapshot(): BackupFile = db.withTransaction {
        BackupCodec.fromEntities(projects.getAll(), todos.getAll(), history.getAll(), now())
    }

    /** 在一个事务里清空并写入备份内容。 */
    suspend fun replaceAll(file: BackupFile) = db.withTransaction {
        todos.deleteAll()
        projects.deleteAll()
        history.deleteAll()
        projects.insertAll(BackupCodec.toProjects(file))
        todos.insertAll(BackupCodec.toTodos(file))
        history.insertAll(BackupCodec.toHistory(file))
    }
}
