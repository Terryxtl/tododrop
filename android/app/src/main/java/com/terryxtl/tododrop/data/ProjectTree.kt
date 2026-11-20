package com.terryxtl.tododrop.data

/**
 * 按 parentId 组织的项目树，纯 Kotlin，便于单元测试。
 *
 * 子项目保持传入列表里的先后顺序（数据库按 sortOrder 排好；拖拽时界面传入本地重排后的列表）。
 * parentId 指向不存在的项目、或者成环时，这个项目当作顶层项目处理，不会丢。
 */
class ProjectTree(projects: List<ProjectEntity>) {
    val all: List<ProjectEntity> = projects
    private val byId: Map<Long, ProjectEntity> = projects.associateBy { it.id }
    private val parentOf: Map<Long, Long?>
    private val children: Map<Long?, List<ProjectEntity>>

    init {
        // 先找出成环的项目（沿 parent 往上走回到自己），把它们挂到顶层
        val effectiveParent = HashMap<Long, Long?>()
        for (p in projects) {
            val parent = p.parentId?.takeIf { it in byId && it != p.id }
            effectiveParent[p.id] = if (parent != null && reachesSelf(p.id)) null else parent
        }
        parentOf = effectiveParent
        children = projects.groupBy { effectiveParent[it.id] }
    }

    private fun reachesSelf(id: Long): Boolean {
        val seen = HashSet<Long>()
        var cur = byId[id]?.parentId
        while (cur != null && cur in byId) {
            if (cur == id) return true
            if (!seen.add(cur)) return false // 上面另有环，不是经过自己的
            cur = byId[cur]?.parentId
        }
        return false
    }

    val roots: List<ProjectEntity> get() = children[null].orEmpty()

    operator fun get(id: Long): ProjectEntity? = byId[id]

    fun parentOf(id: Long): Long? = parentOf[id]

    fun childrenOf(id: Long): List<ProjectEntity> = children[id].orEmpty()

    /** 顶层项目是第 1 层。 */
    fun depthOf(id: Long): Int {
        var depth = 1
        var cur = parentOf[id]
        while (cur != null) {
            depth++
            cur = parentOf[cur]
        }
        return depth
    }

    /** 以 [id] 为根的子树还有几层（叶子是 1）。 */
    fun heightOf(id: Long): Int = 1 + (childrenOf(id).maxOfOrNull { heightOf(it.id) } ?: 0)

    /** 能不能在 [id] 下面再建子项目。 */
    fun canAddChild(id: Long): Boolean = depthOf(id) < MAX_DEPTH

    /** 从顶层到 [id] 的路径（包括自己）。 */
    fun pathOf(id: Long): List<ProjectEntity> {
        val path = ArrayList<ProjectEntity>()
        var cur: Long? = id
        while (cur != null) {
            byId[cur]?.let { path.add(it) } ?: break
            cur = parentOf[cur]
        }
        return path.asReversed()
    }

    /** [id] 和它的全部子孙项目，先序排列。 */
    fun subtreeIds(id: Long): List<Long> = buildList {
        fun walk(pid: Long) {
            add(pid)
            childrenOf(pid).forEach { walk(it.id) }
        }
        if (id in byId) walk(id)
    }

    /** 全部项目按树的先序排列，带层级（顶层为 1）。 */
    fun preorder(): List<Pair<ProjectEntity, Int>> = buildList {
        fun walk(p: ProjectEntity, depth: Int) {
            add(p to depth)
            childrenOf(p.id).forEach { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 1) }
    }

    companion object {
        /** 项目最多几层（顶层算第 1 层）。 */
        const val MAX_DEPTH = 3
    }
}
