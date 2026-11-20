package com.terryxtl.tododrop.ui.projects

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.terryxtl.tododrop.LocalAppContainer
import com.terryxtl.tododrop.R
import com.terryxtl.tododrop.data.ProjectEntity
import com.terryxtl.tododrop.data.ProjectTree
import com.terryxtl.tododrop.data.TodoEntity
import com.terryxtl.tododrop.ui.components.AppIcon
import com.terryxtl.tododrop.ui.components.ConfirmDialog
import com.terryxtl.tododrop.ui.components.EmptyState
import com.terryxtl.tododrop.ui.components.NameDialog
import com.terryxtl.tododrop.ui.components.ShapeBadge
import com.terryxtl.tododrop.ui.components.badgeShapeFor
import com.terryxtl.tododrop.ui.project.TodoEditorSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// LazyColumn 的 item key 前缀。拖拽时按前缀区分：项目只和同一个父项目下的项目换位，待办只在同一项目内换位。
private const val PROJECT_PREFIX = "p-"
private const val OPEN_PREFIX = "t-"
private const val ADD_PREFIX = "add-"
private const val DONE_HEADER_PREFIX = "done-header-"
private const val DONE_PREFIX = "done-"

/** 每深一层，整行往右缩进这么多。 */
private val IndentStep = 14.dp

private fun indentFor(depth: Int): Dp = IndentStep * (depth - 1)

/** 展开集合按 LongArray 保存，旋转屏幕 / 进程重建后保持展开状态。 */
private val LongSetSaver = Saver<Set<Long>, LongArray>(save = { it.toLongArray() }, restore = { it.toSet() })

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

/**
 * 首页平铺列表里的一行。一个展开的顶层项目由「头部 + 待办 + 输入行 + 已完成 + 子项目」几行拼成一组卡片，
 * 子项目递归地用同样的方式展开。[depth] 是这一行所属项目的层级（顶层为 1），[last] 表示是不是这组卡片的最后一行。
 */
private sealed interface HomeRow {
    val key: String
    val depth: Int
    val last: Boolean
    fun asLast(): HomeRow

    data class Header(
        val project: ProjectEntity,
        override val depth: Int,
        /** 自己和子孙项目里未完成的待办数（提醒不算）。 */
        val openCount: Int,
        val doneCount: Int,
        val childCount: Int,
        val expanded: Boolean,
        val first: Boolean,
        val canAddChild: Boolean,
        override val last: Boolean = false,
    ) : HomeRow {
        override val key = PROJECT_PREFIX + project.id
        override fun asLast() = copy(last = true)
    }

    data class Open(val todo: TodoEntity, override val depth: Int, override val last: Boolean = false) : HomeRow {
        override val key = OPEN_PREFIX + todo.id
        override fun asLast() = copy(last = true)
    }

    data class Add(val projectId: Long, override val depth: Int, override val last: Boolean = false) : HomeRow {
        override val key = ADD_PREFIX + projectId
        override fun asLast() = copy(last = true)
    }

    data class DoneHeader(
        val projectId: Long,
        override val depth: Int,
        val count: Int,
        val expanded: Boolean,
        override val last: Boolean = false,
    ) : HomeRow {
        override val key = DONE_HEADER_PREFIX + projectId
        override fun asLast() = copy(last = true)
    }

    data class Done(val todo: TodoEntity, override val depth: Int, override val last: Boolean = false) : HomeRow {
        override val key = DONE_PREFIX + todo.id
        override fun asLast() = copy(last = true)
    }
}

private fun buildRows(
    tree: ProjectTree,
    open: Map<Long, List<TodoEntity>>,
    done: Map<Long, List<TodoEntity>>,
    expanded: Set<Long>,
    doneExpanded: Set<Long>,
): List<HomeRow> {
    fun openTodoCount(id: Long): Int =
        tree.subtreeIds(id).sumOf { pid -> open[pid].orEmpty().count { !it.isNote } }

    fun MutableList<HomeRow>.addProject(project: ProjectEntity, depth: Int, first: Boolean) {
        val openList = open[project.id].orEmpty()
        val doneList = done[project.id].orEmpty()
        val children = tree.childrenOf(project.id)
        val isExpanded = project.id in expanded
        add(
            HomeRow.Header(
                project = project,
                depth = depth,
                openCount = openTodoCount(project.id),
                doneCount = tree.subtreeIds(project.id).sumOf { done[it].orEmpty().size },
                childCount = children.size,
                expanded = isExpanded,
                first = first,
                canAddChild = depth < ProjectTree.MAX_DEPTH,
            )
        )
        if (!isExpanded) return
        openList.forEach { add(HomeRow.Open(it, depth)) }
        add(HomeRow.Add(project.id, depth))
        if (doneList.isNotEmpty()) {
            val showDone = project.id in doneExpanded
            add(HomeRow.DoneHeader(project.id, depth, doneList.size, showDone))
            if (showDone) doneList.forEach { add(HomeRow.Done(it, depth)) }
        }
        children.forEach { addProject(it, depth + 1, first = false) }
    }

    return buildList {
        tree.roots.forEachIndexed { index, root ->
            val group = mutableListOf<HomeRow>()
            group.addProject(root, 1, first = index == 0)
            group[group.lastIndex] = group.last().asLast()
            addAll(group)
        }
    }
}

@Composable
fun ProjectsScreen(
    onExportAll: () -> Unit,
    onExportProject: (Long) -> Unit,
    /** 右滑项目卡片：跳过勾选页，带着这些待办 id 直接进入扫码。 */
    onQuickExport: (List<Long>) -> Unit,
    onHistory: () -> Unit,
    onBackup: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: ProjectsViewModel = viewModel { ProjectsViewModel(container.repository) }
    val state by vm.state.collectAsStateWithLifecycle()

    var showCreate by rememberSaveable { mutableStateOf(false) }
    // 新建子项目时的父项目 id；新建顶层项目时为 null
    var createParentId by rememberSaveable { mutableStateOf<Long?>(null) }
    // 只保存 id，旋转屏幕后从最新数据里查回
    var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var expanded by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(emptySet<Long>()) }
    var doneExpanded by rememberSaveable(stateSaver = LongSetSaver) { mutableStateOf(emptySet<Long>()) }
    var menuOpen by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val totalOpen = state?.totalOpen ?: 0

    val msgCompleted = stringResource(R.string.todo_completed)
    val msgDeleted = stringResource(R.string.todo_deleted)
    val msgReopened = stringResource(R.string.todo_reopened)
    val undoLabel = stringResource(R.string.undo)

    fun complete(todo: TodoEntity) {
        vm.setDone(todo.id, true)
        scope.showUndo(snackbar, msgCompleted, undoLabel) { vm.setDone(todo.id, false) }
    }

    fun reopen(todo: TodoEntity) {
        vm.setDone(todo.id, false)
        scope.showUndo(snackbar, msgReopened, undoLabel) { vm.setDone(todo.id, true) }
    }

    fun deleteTodo(todo: TodoEntity) {
        vm.deleteTodo(todo)
        scope.showUndo(snackbar, msgDeleted, undoLabel) { vm.restoreTodo(todo) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                subtitle = {
                    state?.let { Text(stringResource(R.string.projects_subtitle, it.projects.size, totalOpen)) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            AppIcon(R.drawable.ic_more_vert, stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_history)) },
                                leadingIcon = { AppIcon(R.drawable.ic_history, null) },
                                onClick = { menuOpen = false; onHistory() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_backup)) },
                                leadingIcon = { AppIcon(R.drawable.ic_settings_backup_restore, null) },
                                onClick = { menuOpen = false; onBackup() },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            val current = state
            when {
                current == null -> LoadingIndicator(Modifier.align(Alignment.Center))
                current.projects.isEmpty() -> EmptyState(
                    icon = R.drawable.ic_checklist,
                    title = stringResource(R.string.projects_empty_title),
                    body = stringResource(R.string.projects_empty_body),
                    action = {
                        Button(onClick = { createParentId = null; showCreate = true }, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.project_new))
                        }
                    },
                )
                else -> ProjectList(
                    state = current,
                    expanded = expanded,
                    doneExpanded = doneExpanded,
                    bottomPadding = padding.calculateBottomPadding(),
                    onToggleExpanded = { expanded = expanded.toggle(it) },
                    onCollapse = { expanded = expanded - it },
                    onToggleDone = { doneExpanded = doneExpanded.toggle(it) },
                    onQuickExport = { projectId ->
                        current.subtreeOpenTodoIds(projectId).takeIf { it.isNotEmpty() }?.let(onQuickExport)
                    },
                    onRenameProject = { renamingId = it },
                    onAddSubproject = { createParentId = it; showCreate = true },
                    onExportProject = onExportProject,
                    onDeleteProject = { deletingId = it },
                    onReorderProjects = vm::reorder,
                    onAddTodo = vm::addTodo,
                    onEditTodo = { editingId = it.id },
                    onComplete = ::complete,
                    onReopen = ::reopen,
                    onDeleteTodo = ::deleteTodo,
                    onReorderTodos = vm::reorderTodos,
                )
            }

            HorizontalFloatingToolbar(
                expanded = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = { createParentId = null; showCreate = true }) {
                        AppIcon(R.drawable.ic_add, stringResource(R.string.project_new))
                    }
                },
            ) {
                TextButton(
                    onClick = onExportAll,
                    enabled = totalOpen > 0,
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                ) {
                    AppIcon(R.drawable.ic_qr_code_scanner, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_export), fontWeight = FontWeight.SemiBold)
                }
            }

            // 放在浮动工具栏上方，避免撤销提示被工具栏挡住
            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp),
            )
        }
    }

    if (showCreate) {
        val parent = createParentId?.let { id -> state?.tree?.get(id) }
        NameDialog(
            title = if (parent != null) stringResource(R.string.project_new_child, parent.name) else stringResource(R.string.project_new),
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                val parentId = parent?.id
                // 新建的项目直接展开（子项目连同父项目一起展开），可以马上添加待办
                vm.create(name, parentId) { id -> expanded = expanded + id + listOfNotNull(parentId) }
            },
        )
    }
    renamingId?.let { id -> state?.tree?.get(id) }?.let { p ->
        NameDialog(
            title = stringResource(R.string.project_rename),
            initial = p.name,
            onDismiss = { renamingId = null },
            onConfirm = { vm.rename(p.id, it); renamingId = null },
        )
    }
    state?.let { s ->
        deletingId?.let { id -> s.tree[id] }?.let { p ->
            val descendants = s.tree.subtreeIds(p.id).size - 1
            val items = s.subtreeItemCount(p.id)
            ConfirmDialog(
                title = stringResource(R.string.project_delete_title),
                body = if (descendants > 0) {
                    stringResource(R.string.project_delete_body_children, p.name, descendants, items)
                } else {
                    stringResource(R.string.project_delete_body, p.name, items)
                },
                confirmLabel = stringResource(R.string.delete),
                onDismiss = { deletingId = null },
                onConfirm = {
                    val removed = s.tree.subtreeIds(p.id).toSet()
                    vm.delete(p.id)
                    deletingId = null
                    expanded = expanded - removed
                    doneExpanded = doneExpanded - removed
                },
            )
        }
        editingId?.let { s.find(it) }?.let { todo ->
            val movedTemplate = stringResource(R.string.todo_moved, "%s")
            // 回调都在面板收起动画结束后才触发，数据在面板消失后再变
            TodoEditorSheet(
                todo = todo,
                tree = s.tree,
                onDismiss = { editingId = null },
                onSave = { content, targetProjectId, kind ->
                    editingId = null
                    if (content != todo.content) vm.updateContent(todo.id, content)
                    if (kind != todo.kind) vm.setKind(todo.id, kind)
                    if (targetProjectId != todo.projectId) {
                        vm.move(todo.id, targetProjectId)
                        // 展开目标项目和它的上级，移过去的待办马上能看到
                        expanded = expanded + s.tree.pathOf(targetProjectId).map { it.id }
                        val name = s.tree[targetProjectId]?.name.orEmpty()
                        scope.launch { snackbar.showSnackbar(movedTemplate.replace("%s", name)) }
                    }
                },
                onDelete = { editingId = null; deleteTodo(todo) },
            )
        }
    }
}

private fun CoroutineScope.showUndo(
    host: SnackbarHostState,
    message: String,
    undoLabel: String,
    onUndo: () -> Unit,
) = launch {
    host.currentSnackbarData?.dismiss()
    val result = host.showSnackbar(message, actionLabel = undoLabel, duration = SnackbarDuration.Short)
    if (result == SnackbarResult.ActionPerformed) onUndo()
}

@Composable
private fun ProjectList(
    state: HomeState,
    expanded: Set<Long>,
    doneExpanded: Set<Long>,
    bottomPadding: Dp,
    onToggleExpanded: (Long) -> Unit,
    onCollapse: (Long) -> Unit,
    onToggleDone: (Long) -> Unit,
    onQuickExport: (Long) -> Unit,
    onRenameProject: (Long) -> Unit,
    onAddSubproject: (Long) -> Unit,
    onExportProject: (Long) -> Unit,
    onDeleteProject: (Long) -> Unit,
    onReorderProjects: (List<Long>) -> Unit,
    onAddTodo: (Long, String, Int) -> Unit,
    onEditTodo: (TodoEntity) -> Unit,
    onComplete: (TodoEntity) -> Unit,
    onReopen: (TodoEntity) -> Unit,
    onDeleteTodo: (TodoEntity) -> Unit,
    onReorderTodos: (List<Long>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    // 拖拽过程中用本地副本排序，松手后才写库；不拖拽时跟随数据库
    var projects by remember { mutableStateOf(state.projects) }
    var open by remember { mutableStateOf(state.open) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (!dragging) {
            projects = state.projects
            open = state.open
        }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        when {
            fromKey.startsWith(PROJECT_PREFIX) && toKey.startsWith(PROJECT_PREFIX) -> {
                // 只在同一个父项目下换位；列表是平铺的，按父项目过滤后的相对顺序就是新顺序
                val fromIdx = projects.indexOfFirst { PROJECT_PREFIX + it.id == fromKey }
                val toIdx = projects.indexOfFirst { PROJECT_PREFIX + it.id == toKey }
                if (fromIdx >= 0 && toIdx >= 0 && projects[fromIdx].parentId == projects[toIdx].parentId) {
                    projects = projects.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }
            fromKey.startsWith(OPEN_PREFIX) && toKey.startsWith(OPEN_PREFIX) -> {
                // 只在同一个项目内换位，拖到别的项目的待办上不处理
                val entry = open.entries.firstOrNull { e -> e.value.any { OPEN_PREFIX + it.id == fromKey } }
                    ?: return@rememberReorderableLazyListState
                val list = entry.value
                val fromIdx = list.indexOfFirst { OPEN_PREFIX + it.id == fromKey }
                val toIdx = list.indexOfFirst { OPEN_PREFIX + it.id == toKey }
                if (fromIdx >= 0 && toIdx >= 0) {
                    open = open + (entry.key to list.toMutableList().apply { add(toIdx, removeAt(fromIdx)) })
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }
        }
    }

    val tree = remember(projects) { ProjectTree(projects) }
    val rows = remember(tree, open, state.done, expanded, doneExpanded) {
        buildRows(tree, open, state.done, expanded, doneExpanded)
    }
    val swipeDone = stringResource(R.string.todo_swipe_done)
    val swipeReopen = stringResource(R.string.todo_swipe_reopen)
    val dragLabel = stringResource(R.string.drag_handle)

    LazyColumn(
        state = listState,
        // 键盘弹出时缩小列表，正在输入的那一行会自动滚到可见位置
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 112.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(rows, key = { it.key }, contentType = { it::class }) { row ->
            val indent = Modifier.padding(start = indentFor(row.depth))
            when (row) {
                is HomeRow.Header -> ReorderableItem(
                    reorderState,
                    key = row.key,
                    modifier = Modifier.padding(top = if (row.first || row.depth > 1) 0.dp else 10.dp).then(indent),
                ) { isDragging ->
                    val id = row.project.id
                    ProjectHeader(
                        row = row,
                        dragging = isDragging,
                        onToggle = { onToggleExpanded(id) },
                        onQuickExport = { onQuickExport(id) },
                        onRename = { onRenameProject(id) },
                        onAddSubproject = { onAddSubproject(id) },
                        onExport = { onExportProject(id) },
                        onDelete = { onDeleteProject(id) },
                        handle = {
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                        dragging = true
                                        // 拖动项目时先收起它，只移动一张卡片
                                        onCollapse(id)
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        dragging = false
                                        val parent = projects.firstOrNull { it.id == id }?.parentId
                                        onReorderProjects(projects.filter { it.parentId == parent }.map { it.id })
                                    },
                                ),
                            ) {
                                AppIcon(R.drawable.ic_drag_indicator, dragLabel)
                            }
                        },
                    )
                }

                is HomeRow.Open -> ReorderableItem(reorderState, key = row.key, modifier = indent) { isDragging ->
                    val todo = row.todo
                    val shape = childShape(row.last)
                    SwipeableTodo(
                        stateKey = todo.id to todo.done,
                        shape = shape,
                        startLabel = swipeDone,
                        onSwipeStart = { onComplete(todo) },
                        onSwipeEnd = { onDeleteTodo(todo) },
                        enableStart = !todo.isNote,
                    ) {
                        TodoRow(
                            todo = todo,
                            shape = shape,
                            dragging = isDragging,
                            onClick = { onEditTodo(todo) },
                            onCheckedChange = { onComplete(todo) },
                            onDelete = { onDeleteTodo(todo) },
                            trailing = {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(
                                        onDragStarted = {
                                            dragging = true
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDragStopped = {
                                            dragging = false
                                            onReorderTodos(open[todo.projectId].orEmpty().map { it.id })
                                        },
                                    ),
                                ) {
                                    AppIcon(R.drawable.ic_drag_indicator, dragLabel)
                                }
                            },
                        )
                    }
                }

                is HomeRow.Add -> Box(Modifier.animateItem().then(indent)) {
                    AddTodoRow(shape = childShape(row.last), onAdd = { text, kind -> onAddTodo(row.projectId, text, kind) })
                }

                is HomeRow.DoneHeader -> Box(Modifier.animateItem().then(indent)) {
                    DoneHeaderRow(row = row, onClick = { onToggleDone(row.projectId) })
                }

                is HomeRow.Done -> Box(Modifier.animateItem().then(indent)) {
                    val todo = row.todo
                    val shape = childShape(row.last)
                    SwipeableTodo(
                        stateKey = todo.id to todo.done,
                        shape = shape,
                        startLabel = swipeReopen,
                        onSwipeStart = { onReopen(todo) },
                        onSwipeEnd = { onDeleteTodo(todo) },
                    ) {
                        TodoRow(
                            todo = todo,
                            shape = shape,
                            dragging = false,
                            onClick = { onEditTodo(todo) },
                            onCheckedChange = { onReopen(todo) },
                            onDelete = { onDeleteTodo(todo) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectHeader(
    row: HomeRow.Header,
    dragging: Boolean,
    onToggle: () -> Unit,
    onQuickExport: () -> Unit,
    onRename: () -> Unit,
    onAddSubproject: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    handle: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val scope = rememberCoroutineScope()
    val isRoot = row.depth == 1
    // 普通 remember（不用 rememberSwipeToDismissBoxState），原因同 SwipeableTodo
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val swipeState = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold) }
    // 顶层项目：顶部大圆角，展开时底部变成小圆角，和下面的行连成一组。
    // 子项目是组里的一行：顶部小圆角，是整组最后一行时底部大圆角。
    val topCorner = if (isRoot) GroupCornerBig else GroupCornerSmall
    val bottomCorner by animateDpAsState(
        if (row.expanded || !row.last) GroupCornerSmall else GroupCornerBig,
        motion.defaultSpatialSpec(),
        label = "headerCorner",
    )
    val rotation by animateFloatAsState(if (row.expanded) 180f else 0f, motion.defaultSpatialSpec(), label = "headerArrow")
    val shape = groupShape(topCorner, bottomCorner)

    Box {
        // 右滑：直接打开相机，把这个项目（含子项目）的全部未完成待办扫码发送；松手后卡片弹回原位
        SwipeToDismissBox(
            state = swipeState,
            enableDismissFromStartToEnd = row.openCount > 0 && !dragging,
            enableDismissFromEndToStart = false,
            backgroundContent = {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(colors.primaryContainer)
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(R.drawable.ic_qr_code_scanner, null, tint = colors.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.project_swipe_export, row.openCount),
                        color = colors.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            },
            onDismiss = { value ->
                if (value == SwipeToDismissBoxValue.StartToEnd) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onQuickExport()
                }
                scope.launch { swipeState.reset() }
            },
        ) {
            Surface(
                shape = shape,
                color = if (dragging) colors.secondaryContainer else colors.surfaceContainerHigh,
                tonalElevation = if (dragging) 6.dp else 0.dp,
                shadowElevation = if (dragging) 6.dp else 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .combinedClickable(
                        onClickLabel = stringResource(if (row.expanded) R.string.project_collapse else R.string.project_expand),
                        onClick = onToggle,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = if (isRoot) 16.dp else 12.dp,
                        end = 4.dp,
                        top = if (isRoot) 12.dp else 6.dp,
                        bottom = if (isRoot) 12.dp else 6.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isRoot) {
                        ShapeBadge(
                            shape = badgeShapeFor(row.project.id),
                            container = colors.primaryContainer,
                            size = 44.dp,
                        ) {
                            Text(
                                row.project.name.trim().take(1),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.onPrimaryContainer,
                            )
                        }
                    } else {
                        // 子项目：小号徽章，用次要色和顶层项目区分
                        ShapeBadge(
                            shape = badgeShapeFor(row.project.id),
                            container = colors.secondaryContainer,
                            size = 32.dp,
                        ) {
                            Text(
                                row.project.name.trim().take(1),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSecondaryContainer,
                            )
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = if (isRoot) 16.dp else 12.dp)) {
                        Text(
                            row.project.name,
                            style = if (isRoot) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val status = when {
                            row.openCount > 0 -> stringResource(R.string.project_open_count, row.openCount)
                            row.doneCount > 0 -> stringResource(R.string.project_all_done)
                            else -> stringResource(R.string.project_empty)
                        }
                        Text(
                            if (row.childCount > 0) stringResource(R.string.project_children_suffix, status, row.childCount) else status,
                            style = if (isRoot) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                            color = if (row.openCount > 0) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                    AppIcon(R.drawable.ic_expand_more, null, Modifier.rotate(rotation), tint = colors.onSurfaceVariant)
                    handle()
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                leadingIcon = { AppIcon(R.drawable.ic_edit, null) },
                onClick = { menuOpen = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.project_add_child)) },
                leadingIcon = { AppIcon(R.drawable.ic_subdirectory_arrow_right, null) },
                enabled = row.canAddChild,
                onClick = { menuOpen = false; onAddSubproject() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.detail_export)) },
                leadingIcon = { AppIcon(R.drawable.ic_qr_code_scanner, null) },
                enabled = row.openCount > 0,
                onClick = { menuOpen = false; onExport() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = colors.error) },
                leadingIcon = { AppIcon(R.drawable.ic_delete, null, tint = colors.error) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

@Composable
private fun DoneHeaderRow(row: HomeRow.DoneHeader, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(
        if (row.expanded) 180f else 0f,
        MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "doneArrow",
    )
    val shape = childShape(row.last)
    Surface(
        shape = shape,
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.detail_completed, row.count),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            AppIcon(R.drawable.ic_expand_more, null, Modifier.rotate(rotation), tint = colors.onSurfaceVariant)
        }
    }
}
