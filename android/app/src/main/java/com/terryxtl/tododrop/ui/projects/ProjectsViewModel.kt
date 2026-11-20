package com.terryxtl.tododrop.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terryxtl.tododrop.data.ProjectEntity
import com.terryxtl.tododrop.data.ProjectTree
import com.terryxtl.tododrop.data.TodoEntity
import com.terryxtl.tododrop.data.TodoRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页数据：项目列表 + 按项目分组的待办。
 * 未完成数、总数都从 [open] / [done] 现算，避免两个查询先后更新时数字和列表对不上。
 */
data class HomeState(
    val projects: List<ProjectEntity>,
    /** 未完成的待办和提醒，按 sortOrder 排列。 */
    val open: Map<Long, List<TodoEntity>>,
    /** 已完成，最近完成的在前。 */
    val done: Map<Long, List<TodoEntity>>,
) {
    val tree: ProjectTree = ProjectTree(projects)

    /** 未完成的待办总数（提醒不算）。 */
    val totalOpen: Int get() = open.values.sumOf { list -> list.count { !it.isNote } }

    fun openOf(projectId: Long): List<TodoEntity> = open[projectId].orEmpty()
    fun doneOf(projectId: Long): List<TodoEntity> = done[projectId].orEmpty()

    /** 项目和子孙项目里未完成的待办 id（提醒不算），按 App 里的顺序。 */
    fun subtreeOpenTodoIds(projectId: Long): List<Long> =
        tree.subtreeIds(projectId).flatMap { id -> openOf(id).filter { !it.isNote }.map { it.id } }

    fun subtreeDoneCount(projectId: Long): Int = tree.subtreeIds(projectId).sumOf { doneOf(it).size }

    /** 项目和子孙项目里的全部条目数（删除项目时提示用）。 */
    fun subtreeItemCount(projectId: Long): Int =
        tree.subtreeIds(projectId).sumOf { openOf(it).size + doneOf(it).size }

    fun find(todoId: Long): TodoEntity? =
        open.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == todoId } }
            ?: done.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == todoId } }
}

private val doneOrder = compareByDescending<TodoEntity> { it.completedAt ?: 0L }.thenByDescending { it.id }

class ProjectsViewModel(private val repository: TodoRepository) : ViewModel() {

    /** null 表示还在加载，避免启动时闪一下空状态。 */
    val state: StateFlow<HomeState?> = combine(
        repository.observeProjects(),
        repository.observeAllTodos(),
    ) { projects, todos ->
        val (done, open) = todos.partition { it.done && !it.isNote }
        HomeState(
            projects = projects,
            open = open.groupBy { it.projectId },
            done = done.sortedWith(doneOrder).groupBy { it.projectId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- 项目 ----

    /** [parentId] 不为 null 时建成子项目；建好后回调新项目的 id。 */
    fun create(name: String, parentId: Long?, onCreated: (Long) -> Unit) = viewModelScope.launch {
        repository.createProject(name, parentId)?.let(onCreated)
    }

    fun rename(id: Long, name: String) = viewModelScope.launch { repository.renameProject(id, name) }

    fun delete(id: Long) = viewModelScope.launch { repository.deleteProject(id) }

    fun reorder(ids: List<Long>) = viewModelScope.launch { repository.reorderProjects(ids) }

    // ---- 待办 ----

    fun addTodo(projectId: Long, content: String, kind: Int) =
        viewModelScope.launch { repository.addTodo(projectId, content, kind) }

    fun setDone(id: Long, done: Boolean) = viewModelScope.launch { repository.setDone(id, done) }

    fun setKind(id: Long, kind: Int) = viewModelScope.launch { repository.setTodoKind(id, kind) }

    fun deleteTodo(todo: TodoEntity) = viewModelScope.launch { repository.deleteTodo(todo.id) }

    fun restoreTodo(todo: TodoEntity) = viewModelScope.launch { repository.restoreTodo(todo) }

    fun updateContent(id: Long, content: String) = viewModelScope.launch { repository.updateTodoContent(id, content) }

    fun move(id: Long, targetProjectId: Long) = viewModelScope.launch { repository.moveTodo(id, targetProjectId) }

    fun reorderTodos(ids: List<Long>) = viewModelScope.launch { repository.reorderTodos(ids) }
}
