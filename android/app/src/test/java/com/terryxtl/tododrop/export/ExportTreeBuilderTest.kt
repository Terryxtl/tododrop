package com.terryxtl.tododrop.export

import com.terryxtl.tododrop.data.ProjectEntity
import com.terryxtl.tododrop.data.TodoEntity
import com.terryxtl.tododrop.data.TodoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTreeBuilderTest {

    private fun project(id: Long, name: String, sortOrder: Int, parentId: Long? = null) =
        ProjectEntity(id = id, name = name, sortOrder = sortOrder, createdAt = 0L, parentId = parentId)

    private fun todo(id: Long, projectId: Long, content: String, sortOrder: Int, done: Boolean = false) =
        TodoEntity(id = id, projectId = projectId, content = content, done = done, sortOrder = sortOrder, createdAt = 0L)

    private fun note(id: Long, projectId: Long, content: String, sortOrder: Int) =
        TodoEntity(id = id, projectId = projectId, content = content, sortOrder = sortOrder, createdAt = 0L, kind = TodoKind.NOTE)

    private val projects = listOf(
        project(1, "工作", 0),
        project(2, "生活", 1),
        project(3, "后端", 0, parentId = 1),
        project(4, "数据库", 0, parentId = 3),
        project(5, "前端", 1, parentId = 1),
    )

    private val todos = listOf(
        todo(10, 1, "写周报", 1),
        note(11, 1, "周五前交", 0),
        todo(12, 3, "接口文档", 0),
        todo(13, 4, "加索引", 0),
        note(14, 5, "前端的提醒", 0),
        todo(15, 5, "改样式", 1),
        todo(16, 2, "买牛奶", 0),
        todo(17, 2, "已完成的", 1, done = true),
    )

    @Test
    fun buildsTreeWithTodosNotesAndChildrenInOrder() {
        val plan = ExportTreeBuilder.build(projects, todos, listOf(10, 12, 13, 15, 16, 17))
        assertEquals(
            listOf(
                ExportProject(
                    name = "工作",
                    todos = listOf("写周报"),
                    notes = listOf("周五前交"),
                    children = listOf(
                        ExportProject("后端", todos = listOf("接口文档"), children = listOf(ExportProject("数据库", todos = listOf("加索引")))),
                        ExportProject("前端", todos = listOf("改样式"), notes = listOf("前端的提醒")),
                    ),
                ),
                ExportProject("生活", todos = listOf("买牛奶")),
            ),
            plan.projects,
        )
        assertEquals(5, plan.todoCount)
        assertEquals(setOf(10L, 12L, 13L, 15L, 16L), plan.todoIds.toSet())
        assertEquals(listOf("工作", "生活"), plan.projectNames)
    }

    @Test
    fun projectsWithoutSelectedTodosAreLeftOutWithTheirNotes() {
        // 只选了数据库里的一条：上级「工作」「后端」作为路径保留，「前端」和「生活」整个不出现
        val plan = ExportTreeBuilder.build(projects, todos, listOf(13))
        assertEquals(
            listOf(
                ExportProject(
                    name = "工作",
                    notes = listOf("周五前交"),
                    children = listOf(ExportProject("后端", children = listOf(ExportProject("数据库", todos = listOf("加索引"))))),
                ),
            ),
            plan.projects,
        )
        assertEquals(listOf(NoteLine(listOf("工作"), "周五前交")), plan.notes)
    }

    @Test
    fun notesAreNeverExportedAsTodosEvenWhenSelected() {
        val plan = ExportTreeBuilder.build(projects, todos, listOf(11, 14))
        assertTrue(plan.projects.isEmpty())
        assertTrue(plan.todoIds.isEmpty())
    }

    @Test
    fun doneBlankAndUnknownAreSkipped() {
        val extra = todos + todo(20, 2, "  \n ", 2) + todo(21, 99, "孤儿", 0)
        val plan = ExportTreeBuilder.build(projects, extra, listOf(17, 20, 21))
        assertTrue(plan.projects.isEmpty())
        assertEquals(0, plan.todoCount)
    }

    @Test
    fun orderFollowsSortOrderThenId() {
        val ps = listOf(project(2, "B", 1), project(1, "A", 1), project(3, "C", 0))
        val ts = listOf(todo(6, 1, "a2", 0), todo(5, 1, "a1", 0), todo(7, 2, "b", 0), todo(8, 3, "c", 0))
        val plan = ExportTreeBuilder.build(ps, ts, listOf(5, 6, 7, 8))
        assertEquals(listOf("C", "A", "B"), plan.projectNames)
        assertEquals(listOf("a1", "a2"), plan.projects[1].todos)
    }

    @Test
    fun notesAreCollectedWithFullPaths() {
        val plan = ExportTreeBuilder.build(projects, todos, listOf(15))
        assertEquals(
            listOf(NoteLine(listOf("工作"), "周五前交"), NoteLine(listOf("工作", "前端"), "前端的提醒")),
            plan.notes,
        )
    }

    @Test
    fun historyPayloadPairsFilesWithProjectsAndRoundTrips() {
        val projectsSent = listOf(
            ExportProject("工作", todos = listOf("a")),
            ExportProject("只有空白", todos = listOf("x")),
            ExportProject(" 生\n活 ", todos = listOf("b")),
        )
        val files = listOf(
            SavedFile("工作", "TODOS_工作_20261120_200710.md", 1),
            SavedFile("生 活", "TODOS_生 活_20261120_200710.md", 1),
        )
        val payload = HistoryPayload(projectsSent, files)
        val decoded = HistoryPayload.decodeOrNull(payload.encode())
        assertEquals(payload, decoded)
        assertEquals(
            listOf(files[0], null, files[1]),
            payload.filesWithProjects().map { it.first },
        )
        assertEquals(null, HistoryPayload.decodeOrNull("not json"))
        assertEquals(null, HistoryPayload.decodeOrNull(null))
    }
}
