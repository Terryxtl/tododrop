package com.terryxtl.tododrop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.terryxtl.tododrop.R

@Database(
    entities = [ProjectEntity::class, TodoEntity::class, ExportHistoryEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class TodoDropDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun todoDao(): TodoDao
    abstract fun historyDao(): HistoryDao

    companion object {
        /** v2：子项目（projects.parentId）、提醒（todos.kind）、导出历史的原始数据（export_history.payload）。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN parentId INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_projects_parentId ON projects (parentId)")
                db.execSQL("ALTER TABLE todos ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE export_history ADD COLUMN payload TEXT DEFAULT NULL")
            }
        }

        fun build(context: Context): TodoDropDatabase {
            val inboxName = context.getString(R.string.inbox_name)
            return Room.databaseBuilder(context, TodoDropDatabase::class.java, "tododrop.db")
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    // 只在数据库第一次创建时预置「收件箱」，之后可以改名或删除
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "INSERT INTO projects (name, sortOrder, createdAt) VALUES (?, 0, ?)",
                            arrayOf<Any>(inboxName, System.currentTimeMillis()),
                        )
                    }
                })
                .build()
        }
    }
}
