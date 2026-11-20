package com.terryxtl.tododrop.ui.export

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.terryxtl.tododrop.data.ProjectEntity
import com.terryxtl.tododrop.data.ProjectTree
import com.terryxtl.tododrop.data.TodoEntity
import com.terryxtl.tododrop.data.TodoRepository
import com.terryxtl.tododrop.export.ExportPlan
import com.terryxtl.tododrop.export.ExportTreeBuilder
import com.terryxtl.tododrop.ui.navigation.ExportRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 勾选页里的一个项目（按树的先序排列）。只列出子树里有未完成待办的项目。
 * [items] 是项目自己的未完成待办和提醒，按 App 里的顺序；提醒不能勾选，会跟着项目一起显示在电脑上。
 */
data class ExportGroup(
    val project: ProjectEntity,
    /** 顶层为 1。 */
    val depth: Int,
    val items: List<TodoEntity>,
) {
    val todos: List<TodoEntity> = items.filter { !it.isNote }
}

data class ExportData(
    val groups: List<ExportGroup>,
    val projects: List<ProjectEntity>,
    val open: List<TodoEntity>,
)

class ExportViewModel(
    repository: TodoRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ExportRoute>()

    val data: StateFlow<ExportData?> =
        combine(repository.observeProjects(), repository.observeAllOpenTodos()) { projects, open ->
            val tree = ProjectTree(projects)
            val byProject = open.groupBy { it.projectId }
            val hasTodos = HashMap<Long, Boolean>()
            fun subtreeHasTodos(id: Long): Boolean = hasTodos.getOrPut(id) {
                byProject[id].orEmpty().any { !it.isNote } || tree.childrenOf(id).any { subtreeHasTodos(it.id) }
            }
            val groups = tree.preorder().mapNotNull { (p, depth) ->
                if (subtreeHasTodos(p.id)) ExportGroup(p, depth, byProject[p.id].orEmpty()) else null
            }
            ExportData(groups, projects, open)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 选中的待办 id；存进 SavedStateHandle，进程被杀后也能恢复。 */
    val selected: StateFlow<Set<Long>> = savedStateHandle.getStateFlow<LongArray?>(KEY_SELECTED, null)
        .map { it?.toSet() ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        if (savedStateHandle.get<LongArray>(KEY_SELECTED) == null) {
            viewModelScope.launch {
                val first = data.filterNotNull().first()
                // 从某个项目进来时，预选它和它的子项目里的待办
                val scope = if (route.projectId == ExportRoute.ALL) null
                else ProjectTree(first.projects).subtreeIds(route.projectId).toSet()
                val initial = first.groups
                    .filter { scope == null || it.project.id in scope }
                    .flatMap { g -> g.todos.map { it.id } }
                setSelected(initial.toSet())
            }
        }
    }

    private fun setSelected(ids: Set<Long>) {
        savedStateHandle[KEY_SELECTED] = ids.toLongArray()
    }

    fun toggle(id: Long) {
        val cur = selected.value
        setSelected(if (id in cur) cur - id else cur + id)
    }

    /** 项目头的三态复选框：全选中时点一下全取消，否则全选。只管这个项目自己的待办。 */
    fun toggleGroup(group: ExportGroup) {
        val ids = group.todos.map { it.id }
        val cur = selected.value
        setSelected(if (cur.containsAll(ids)) cur - ids.toSet() else cur + ids)
    }

    fun selectAll() = setSelected(data.value?.groups.orEmpty().flatMap { g -> g.todos.map { it.id } }.toSet())

    fun selectNone() = setSelected(emptySet())

    /** 当前仍然存在、且被选中的待办 id，按导出顺序排列。 */
    fun selectedIdsInOrder(): List<Long> {
        val sel = selected.value
        return data.value?.groups.orEmpty().flatMap { g -> g.todos.filter { it.id in sel }.map { it.id } }
    }

    /** 预览：按当前勾选组织出的项目树（电脑上每个顶层项目一个文件）。 */
    fun previewPlan(): ExportPlan {
        val d = data.value ?: return ExportPlan(emptyList(), emptyList())
        return ExportTreeBuilder.build(d.projects, d.open, selected.value)
    }

    private companion object {
        const val KEY_SELECTED = "selected"
    }
}
