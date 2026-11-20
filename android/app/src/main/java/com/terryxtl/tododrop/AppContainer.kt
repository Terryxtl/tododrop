package com.terryxtl.tododrop

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.terryxtl.tododrop.backup.BackupManager
import com.terryxtl.tododrop.data.TodoDropDatabase
import com.terryxtl.tododrop.data.TodoRepository
import com.terryxtl.tododrop.transfer.DeviceName
import com.terryxtl.tododrop.transfer.TransferClient

/** 手写的依赖容器，整个 App 共用一份。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: TodoDropDatabase by lazy { TodoDropDatabase.build(appContext) }
    val repository: TodoRepository by lazy { TodoRepository(database) }
    val backupManager: BackupManager by lazy { BackupManager(appContext, repository) }
    val transferClient: TransferClient by lazy { TransferClient() }
    val deviceName: String by lazy { DeviceName.get(appContext) }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer 未提供") }
