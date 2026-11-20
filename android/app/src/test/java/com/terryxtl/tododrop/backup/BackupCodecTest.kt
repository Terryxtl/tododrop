package com.terryxtl.tododrop.backup

import com.terryxtl.tododrop.data.ExportHistoryEntity
import com.terryxtl.tododrop.data.ProjectEntity
import com.terryxtl.tododrop.data.TodoEntity
import com.terryxtl.tododrop.data.TodoKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val projects = listOf(
        ProjectEntity(id = 1, name = "收件箱", sortOrder = 0, createdAt = 1_763_000_000_000),
        ProjectEntity(id = 2, name = "工作 [重要]", sortOrder = 1, createdAt = 1_763_000_100_000),
        ProjectEntity(id = 5, name = "生活", sortOrder = 2, createdAt = 1_763_000_200_000),
        ProjectEntity(id = 6, name = "后端", sortOrder = 0, createdAt = 1_763_000_250_000, parentId = 2),
        ProjectEntity(id = 8, name = "数据库", sortOrder = 0, createdAt = 1_763_000_260_000, parentId = 6),
    )

    private val todos = listOf(
        TodoEntity(id = 1, projectId = 1, content = "买牛奶", done = false, sortOrder = 0, createdAt = 1_763_000_300_000),
        TodoEntity(
            id = 2,
            projectId = 2,
            content = "修复登录页崩溃\n第二行 \"引号\" \\ 反斜杠\ttab",
            done = false,
            sortOrder = 0,
            createdAt = 1_763_000_400_000,
        ),
        TodoEntity(
            id = 3,
            projectId = 2,
            content = "写周报",
            done = true,
            sortOrder = 1,
            createdAt = 1_763_000_500_000,
            completedAt = 1_763_100_000_000,
        ),
        TodoEntity(id = 7, projectId = 5, content = "emoji 🥛", done = false, sortOrder = 3, createdAt = 1_763_000_600_000),
        TodoEntity(
            id = 8,
            projectId = 6,
            content = "先和前端对接口",
            sortOrder = 0,
            createdAt = 1_763_000_700_000,
            kind = TodoKind.NOTE,
        ),
    )

    private val history = listOf(
        ExportHistoryEntity(
            id = 1,
            exportedAt = 1_763_537_445_000,
            pcName = "DESKTOP-ABC 书房",
            savedAs = "TODOS_20261119_153045.md",
            itemCount = 2,
            projectNames = "收件箱、工作 [重要]",
            payload = null,
            markdown = "- [ ] [收件箱] 买牛奶\n- [ ] [工作 \\[重要\\]] 修复登录页崩溃\n  第二行\n",
        ),
    )

    private val history2 = ExportHistoryEntity(
        id = 2,
        exportedAt = 1_763_600_000_000,
        pcName = "DESKTOP-ABC",
        savedAs = "TODOS_工作_20261120_200710.md\nTODOS_生活_20261120_200710.md",
        itemCount = 2,
        projectNames = "工作、生活",
        markdown = "",
        payload = """{"projects":[],"files":[]}""",
    )

    private val now = 1_763_600_000_000L

    private fun sample() = BackupCodec.fromEntities(projects, todos, history + history2, now)

    private fun assertRejected(file: BackupFile): BackupException =
        assertRejected(BackupCodec.encode(file))

    private fun assertRejected(text: String): BackupException =
        assertThrows(BackupException::class.java) { BackupCodec.decode(text) }

    private fun assertRejected(file: BackupFile, expected: BackupError) =
        assertEquals(expected, assertRejected(file).error)

    private fun assertRejected(text: String, expected: BackupError) =
        assertEquals("error for <$text>", expected, assertRejected(text).error)

    // ---- 往返 ----

    @Test
    fun encodeDecodeRoundTripPreservesEverything() {
        val file = sample()
        val decoded = BackupCodec.decode(BackupCodec.encode(file))

        assertEquals(file, decoded)
        assertEquals(now, decoded.exportedAt)
        assertEquals(projects, BackupCodec.toProjects(decoded))
        assertEquals(todos, BackupCodec.toTodos(decoded))
        assertEquals(history + history2, BackupCodec.toHistory(decoded))
    }

    @Test
    fun roundTripOfEmptyBackup() {
        val file = BackupCodec.fromEntities(emptyList(), emptyList(), emptyList(), now)
        val decoded = BackupCodec.decode(BackupCodec.encode(file))
        assertEquals(file, decoded)
        assertTrue(BackupCodec.toProjects(decoded).isEmpty())
        assertTrue(BackupCodec.toTodos(decoded).isEmpty())
        assertTrue(BackupCodec.toHistory(decoded).isEmpty())
    }

    @Test
    fun encodedJsonHasFormatVersionAndCollections() {
        val root = Json.parseToJsonElement(BackupCodec.encode(sample())).jsonObject
        assertEquals("tododrop-backup", root["format"]!!.jsonPrimitive.content)
        assertEquals(2, root["version"]!!.jsonPrimitive.int)
        assertTrue(root.containsKey("projects"))
        assertTrue(root.containsKey("todos"))
        assertTrue(root.containsKey("history"))
    }

    @Test
    fun decodesHandWrittenFileWithoutHistoryAndWithUnknownKeys() {
        val text = """
            {
              "format": "tododrop-backup",
              "version": 1,
              "exportedAt": 0,
              "somethingNew": {"a": 1},
              "projects": [{"id": 1, "name": "收件箱", "sortOrder": 0, "createdAt": 0, "color": "red"}],
              "todos": [{"id": 1, "projectId": 1, "content": "x", "done": false, "sortOrder": 0, "createdAt": 0}]
            }
        """.trimIndent()
        val file = BackupCodec.decode(text)
        assertEquals(1, file.projects.size)
        assertEquals(null, file.todos.single().completedAt)
        assertTrue(file.history.isEmpty())
    }

    @Test
    fun decodesSpecExampleWithoutExportedAt() {
        // 方案文档里的最小示例，没有 exportedAt
        val text = """{"format":"tododrop-backup","version":1,"projects":[],"todos":[],"history":[]}"""
        val file = BackupCodec.decode(text)
        assertEquals(0L, file.exportedAt)
        assertTrue(file.projects.isEmpty())
        assertTrue(file.todos.isEmpty())
        assertTrue(file.history.isEmpty())
    }

    // ---- 拒绝 ----

    @Test
    fun rejectsWrongFormat() {
        assertRejected(sample().copy(format = "something-else"), BackupError.WrongFormat)
        assertRejected(sample().copy(format = ""), BackupError.WrongFormat)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertRejected(sample().copy(version = 3), BackupError.UnsupportedVersion(3))
        assertRejected(sample().copy(version = 0), BackupError.UnsupportedVersion(0))
    }

    @Test
    fun rejectsTodoWhoseProjectDoesNotExist() {
        val file = sample()
        val orphan = BackupTodo(
            id = 99,
            projectId = 42,
            content = "孤儿",
            done = false,
            sortOrder = 0,
            createdAt = 0,
        )
        val e = assertRejected(file.copy(todos = file.todos + orphan))
        assertEquals(BackupError.OrphanTodo(todoId = 99, projectId = 42), e.error)
    }

    @Test
    fun rejectsTodosWhenThereAreNoProjects() {
        val e = assertRejected(sample().copy(projects = emptyList()))
        assertTrue(e.error is BackupError.OrphanTodo)
    }

    @Test
    fun rejectsMalformedJson() {
        assertRejected("not json", BackupError.MalformedJson)
        assertRejected("", BackupError.MalformedJson)
        assertRejected("{}", BackupError.MalformedJson) // 缺少 projects / todos
        assertRejected("[]", BackupError.MalformedJson)
    }

    @Test
    fun rejectsDuplicateIds() {
        val file = sample()
        assertRejected(file.copy(projects = file.projects + file.projects.first()), BackupError.DuplicateProjectId)
        assertRejected(file.copy(todos = file.todos + file.todos.first()), BackupError.DuplicateTodoId)
        assertRejected(file.copy(history = file.history + file.history.first()), BackupError.DuplicateHistoryId)
    }

    @Test
    fun rejectsInvalidProjectsAndTodos() {
        val file = sample()
        assertRejected(
            file.copy(projects = file.projects + BackupProject(id = 0, name = "零", sortOrder = 9, createdAt = 0)),
            BackupError.InvalidProject,
        )
        assertRejected(
            file.copy(projects = file.projects + BackupProject(id = 9, name = "  ", sortOrder = 9, createdAt = 0)),
            BackupError.InvalidProject,
        )
        assertRejected(
            file.copy(
                todos = file.todos + BackupTodo(
                    id = -1,
                    projectId = 1,
                    content = "x",
                    done = false,
                    sortOrder = 0,
                    createdAt = 0,
                ),
            ),
            BackupError.InvalidTodo,
        )
    }

    // ---- 错误类型 ----

    @Test
    fun errorsCarryFormatArgsForTheirMessages() {
        assertArrayEquals(arrayOf<Any>(99L, 42L), BackupError.OrphanTodo(99, 42).formatArgs)
        assertArrayEquals(arrayOf<Any>(3), BackupError.UnsupportedVersion(3).formatArgs)
        assertArrayEquals(arrayOf<Any>(50), BackupError.TooLarge(50).formatArgs)
        assertEquals(0, BackupError.MalformedJson.formatArgs.size)
        assertEquals(0, BackupError.Unknown.formatArgs.size)
    }

    @Test
    fun everyErrorHasAMessage() {
        val errors = listOf(
            BackupError.CannotOpenFile,
            BackupError.ReadFailed,
            BackupError.WriteFailed,
            BackupError.TooLarge(50),
            BackupError.MalformedJson,
            BackupError.WrongFormat,
            BackupError.UnsupportedVersion(2),
            BackupError.DuplicateProjectId,
            BackupError.InvalidProject,
            BackupError.DuplicateTodoId,
            BackupError.InvalidTodo,
            BackupError.OrphanTodo(1, 2),
            BackupError.DuplicateHistoryId,
            BackupError.InvalidHierarchy(1),
            BackupError.RestoreFailed,
            BackupError.Unknown,
        )
        val ids = errors.map { it.messageRes }
        assertTrue(ids.all { it != 0 })
        assertEquals("每种错误都应有自己的文案", ids.size, ids.toSet().size)
    }

    // ---- v1 兼容 / 子项目 / 提醒 ----

    @Test
    fun decodesVersion1FileWithoutNewFields() {
        val text = """
            {
              "format": "tododrop-backup",
              "version": 1,
              "projects": [{"id": 1, "name": "收件箱", "sortOrder": 0, "createdAt": 0}],
              "todos": [{"id": 1, "projectId": 1, "content": "x", "done": true, "sortOrder": 0, "createdAt": 0, "completedAt": 5}],
              "history": [{"id": 1, "exportedAt": 0, "pcName": "PC", "savedAs": "TODOS_1.md", "itemCount": 1,
                "projectNames": "收件箱", "markdown": "- [ ] x"}]
            }
        """.trimIndent()
        val file = BackupCodec.decode(text)
        assertEquals(null, BackupCodec.toProjects(file).single().parentId)
        assertEquals(TodoKind.TODO, BackupCodec.toTodos(file).single().kind)
        assertEquals(true, BackupCodec.toTodos(file).single().done)
        assertEquals(null, BackupCodec.toHistory(file).single().payload)
    }

    @Test
    fun rejectsBrokenHierarchy() {
        val file = sample()
        fun withProject(p: BackupProject) = file.copy(projects = file.projects + p)
        // 父项目不存在
        assertRejected(
            withProject(BackupProject(id = 20, name = "孤儿", sortOrder = 0, createdAt = 0, parentId = 99)),
            BackupError.InvalidHierarchy(20),
        )
        // 自己是自己的父项目
        assertRejected(
            withProject(BackupProject(id = 21, name = "自环", sortOrder = 0, createdAt = 0, parentId = 21)),
            BackupError.InvalidHierarchy(21),
        )
        // 第 4 层（8 已经是第 3 层）
        assertRejected(
            withProject(BackupProject(id = 22, name = "太深", sortOrder = 0, createdAt = 0, parentId = 8)),
            BackupError.InvalidHierarchy(22),
        )
        // 两个项目互为父项目
        val cycle = listOf(
            BackupProject(id = 30, name = "A", sortOrder = 0, createdAt = 0, parentId = 31),
            BackupProject(id = 31, name = "B", sortOrder = 0, createdAt = 0, parentId = 30),
        )
        assertTrue(assertRejected(file.copy(projects = file.projects + cycle)).error is BackupError.InvalidHierarchy)
    }

    @Test
    fun noteNeverRestoresAsDone() {
        val file = sample()
        val doneNote = BackupTodo(
            id = 50,
            projectId = 1,
            content = "n",
            done = true,
            sortOrder = 0,
            createdAt = 0,
            completedAt = 9,
            kind = TodoKind.NOTE,
        )
        val restored = BackupCodec.toTodos(BackupCodec.decode(BackupCodec.encode(file.copy(todos = file.todos + doneNote))))
            .single { it.id == 50L }
        assertEquals(false, restored.done)
        assertEquals(null, restored.completedAt)
        assertRejected(file.copy(todos = file.todos + doneNote.copy(id = 51, kind = 7)), BackupError.InvalidTodo)
    }
}
