package com.terryxtl.tododrop.export

import com.terryxtl.tododrop.data.ProjectEntity
import com.terryxtl.tododrop.data.ProjectTree
import com.terryxtl.tododrop.data.TodoEntity
import com.terryxtl.tododrop.data.TodoKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 发给电脑的原始数据：一个项目节点（协议 v2，见 docs/PROTOCOL.md）。
 * 手机只传原文，Markdown 由电脑端生成；每个顶层项目在电脑上保存为一个文件。
 */
@Serializable
data class ExportProject(
    val name: String,
    /** 选中的未完成待办的原文，按 App 里的顺序。 */
    val todos: List<String> = emptyList(),
    /** 这个项目的全部提醒，不写进文件，只在电脑终端和手机上显示。 */
    val notes: List<String> = emptyList(),
    val children: List<ExportProject> = emptyList(),
)

/** 一条提醒和它所在项目的路径。 */
data class NoteLine(val path: List<String>, val content: String)

/** 电脑端保存的一个文件。 */
@Serializable
data class SavedFile(val project: String, val savedAs: String, val count: Int)

/** 一次导出要发送的内容。 */
data class ExportPlan(
    val projects: List<ExportProject>,
    /** 发送的待办 id，发送成功后把它们标为已完成。 */
    val todoIds: List<Long>,
) {
    val todoCount: Int get() = projects.sumOf { it.todoCount() }
    val notes: List<NoteLine> get() = projects.notes()
    val projectNames: List<String> get() = projects.map { it.name }
}

fun ExportProject.todoCount(): Int = todos.size + children.sumOf { it.todoCount() }

/** 按树的先序收集提醒。 */
fun List<ExportProject>.notes(): List<NoteLine> = buildList {
    fun walk(p: ExportProject, path: List<String>) {
        val here = path + p.name
        p.notes.forEach { add(NoteLine(here, it)) }
        p.children.forEach { walk(it, here) }
    }
    this@notes.forEach { walk(it, emptyList()) }
}

object ExportTreeBuilder {
    private val order = compareBy<TodoEntity>({ it.sortOrder }, { it.id })

    /**
     * 把选中的待办组织成项目树。
     *
     * - 只取选中的、未完成的普通待办；空白正文跳过。
     * - 只包含子树里有选中待办的项目；这样的项目会带上自己的全部提醒。
     * - 顺序和 App 里一致：项目按 sortOrder，项目内先待办后子项目。
     */
    fun build(projects: List<ProjectEntity>, todos: List<TodoEntity>, selectedIds: Collection<Long>): ExportPlan {
        val tree = ProjectTree(projects.sortedWith(compareBy({ it.sortOrder }, { it.id })))
        val selected = selectedIds.toHashSet()
        val byProject = todos.filter { !it.done && it.content.isNotBlank() }.groupBy { it.projectId }
        val exported = ArrayList<Long>()

        fun node(p: ProjectEntity): ExportProject? {
            val own = byProject[p.id].orEmpty().sortedWith(order)
            val picked = own.filter { it.kind == TodoKind.TODO && it.id in selected }
            val children = tree.childrenOf(p.id).mapNotNull { node(it) }
            if (picked.isEmpty() && children.isEmpty()) return null
            picked.mapTo(exported) { it.id }
            return ExportProject(
                name = p.name,
                todos = picked.map { it.content },
                notes = own.filter { it.isNote }.map { it.content },
                children = children,
            )
        }

        return ExportPlan(tree.roots.mapNotNull { node(it) }, exported)
    }
}

/** 导出历史里保存的内容：发出去的原始数据 + 电脑返回的文件列表。 */
@Serializable
data class HistoryPayload(val projects: List<ExportProject>, val files: List<SavedFile>) {
    /** 把文件和顶层项目对上：电脑按发送顺序返回文件，没有有效待办的项目不生成文件。 */
    fun filesWithProjects(): List<Pair<SavedFile?, ExportProject>> {
        var next = 0
        return projects.map { p ->
            val f = files.getOrNull(next)?.takeIf { it.project == p.name.singleLine() }
            if (f != null) next++
            f to p
        }
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decodeOrNull(text: String?): HistoryPayload? = text?.let {
            try {
                json.decodeFromString(serializer(), it)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/** 和电脑端一样把名字规范化成单行，用来匹配电脑返回的项目名。 */
internal fun String.singleLine(): String =
    split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "未命名" }
