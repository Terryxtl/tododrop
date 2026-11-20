package com.terryxtl.tododrop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY sortOrder, id")
    suspend fun getAll(): List<ProjectEntity>

    /** 同一个父项目下最大的 sortOrder；`IS` 让 parentId 为 null（顶层）时也能比较。 */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM projects WHERE parentId IS :parentId")
    suspend fun maxSortOrder(parentId: Long?): Int

    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Insert
    suspend fun insertAll(projects: List<ProjectEntity>)

    @Query("UPDATE projects SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE projects SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Int)

    @Query("DELETE FROM projects WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM projects")
    suspend fun deleteAll()
}

@Dao
interface TodoDao {
    /** 首页一次取出全部待办，在 ViewModel 里按项目分组。 */
    @Query("SELECT * FROM todos ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<TodoEntity>>

    /** 未完成的待办和全部提醒（提醒没有完成状态）。 */
    @Query("SELECT * FROM todos WHERE done = 0 ORDER BY sortOrder, id")
    fun observeAllOpen(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE done = 0 ORDER BY sortOrder, id")
    suspend fun getAllOpen(): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TodoEntity>

    @Query("SELECT * FROM todos")
    suspend fun getAll(): List<TodoEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM todos WHERE projectId = :projectId")
    suspend fun maxSortOrder(projectId: Long): Int

    @Insert
    suspend fun insert(todo: TodoEntity): Long

    @Insert
    suspend fun insertAll(todos: List<TodoEntity>)

    /** 撤销删除时按原 id 写回。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(todo: TodoEntity)

    @Query("UPDATE todos SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    @Query("UPDATE todos SET done = :done, completedAt = :completedAt WHERE id = :id AND kind = 0")
    suspend fun setDone(id: Long, done: Boolean, completedAt: Long?)

    /** 导出成功后把这些待办标为已完成；提醒和已经完成的不动。 */
    @Query("UPDATE todos SET done = 1, completedAt = :completedAt WHERE id IN (:ids) AND done = 0 AND kind = 0")
    suspend fun completeAll(ids: List<Long>, completedAt: Long): Int

    /** 改类型。改成提醒时清掉完成状态（提醒没有完成状态）。 */
    @Query(
        """
        UPDATE todos SET kind = :kind,
            done = CASE WHEN :kind = 1 THEN 0 ELSE done END,
            completedAt = CASE WHEN :kind = 1 THEN NULL ELSE completedAt END
        WHERE id = :id
        """
    )
    suspend fun setKind(id: Long, kind: Int)

    @Query("UPDATE todos SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Int)

    @Query("UPDATE todos SET projectId = :projectId, sortOrder = :order WHERE id = :id")
    suspend fun move(id: Long, projectId: Long, order: Int)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM todos WHERE projectId IN (:projectIds)")
    suspend fun deleteByProjects(projectIds: List<Long>)

    @Query("DELETE FROM todos")
    suspend fun deleteAll()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM export_history ORDER BY exportedAt DESC, id DESC")
    fun observeAll(): Flow<List<ExportHistoryEntity>>

    @Query("SELECT * FROM export_history ORDER BY exportedAt DESC, id DESC")
    suspend fun getAll(): List<ExportHistoryEntity>

    @Insert
    suspend fun insert(entry: ExportHistoryEntity): Long

    @Insert
    suspend fun insertAll(entries: List<ExportHistoryEntity>)

    @Query("DELETE FROM export_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM export_history")
    suspend fun deleteAll()
}
