package com.terryxtl.tododrop.backup

import android.content.Context
import android.net.Uri
import com.terryxtl.tododrop.data.TodoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 通过 SAF 读写备份文件。失败时统一抛 [BackupException]，界面按 [BackupError] 显示文案。
 */
class BackupManager(private val context: Context, private val repository: TodoRepository) {

    /** 写备份，返回写入的内容（用于提示条数）。 */
    suspend fun backupTo(uri: Uri): BackupFile {
        val file = repository.snapshot()
        val text = BackupCodec.encode(file)
        withContext(Dispatchers.IO) {
            val out = openOrFail { context.contentResolver.openOutputStream(uri, "wt") }
            try {
                out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } catch (e: IOException) {
                throw BackupException(BackupError.WriteFailed, e)
            }
        }
        return file
    }

    /** 读取并校验备份文件，不写数据库。 */
    suspend fun read(uri: Uri): BackupFile {
        val text = withContext(Dispatchers.IO) {
            val input = openOrFail { context.contentResolver.openInputStream(uri) }
            try {
                input.use { stream ->
                    // 边读边数，超过上限立刻停下，不把超大文件整个读进内存
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        buffer.write(chunk, 0, n)
                        if (buffer.size() > MAX_BYTES) throw BackupException(BackupError.TooLarge(MAX_BYTES / (1024 * 1024)))
                    }
                    buffer.toString(Charsets.UTF_8.name())
                }
            } catch (e: IOException) {
                throw BackupException(BackupError.ReadFailed, e)
            }
        }
        return withContext(Dispatchers.Default) { BackupCodec.decode(text) }
    }

    /** 在一个事务里覆盖全部数据；失败时事务回滚，原数据不变。 */
    suspend fun restore(file: BackupFile) {
        try {
            repository.replaceAll(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(BackupError.RestoreFailed, e)
        }
    }

    /** 打开 SAF 流；返回 null、文件不存在或没有权限都算打不开。 */
    private inline fun <T> openOrFail(open: () -> T?): T {
        val stream = try {
            open()
        } catch (e: IOException) { // 包括 FileNotFoundException
            throw BackupException(BackupError.CannotOpenFile, e)
        } catch (e: SecurityException) {
            throw BackupException(BackupError.CannotOpenFile, e)
        }
        return stream ?: throw BackupException(BackupError.CannotOpenFile)
    }

    private companion object {
        const val MAX_BYTES = 50 * 1024 * 1024
    }
}
