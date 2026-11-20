package com.terryxtl.tododrop.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.terryxtl.tododrop.LocalAppContainer
import com.terryxtl.tododrop.R
import com.terryxtl.tododrop.backup.BackupError
import com.terryxtl.tododrop.backup.BackupException
import com.terryxtl.tododrop.backup.BackupFile
import com.terryxtl.tododrop.backup.BackupManager
import com.terryxtl.tododrop.ui.components.AppIcon
import com.terryxtl.tododrop.ui.components.ConfirmDialog
import com.terryxtl.tododrop.ui.components.ShapeBadge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 提示消息：备份成功 / 恢复成功 / 出错。 */
sealed interface BackupMessage {
    data class BackedUp(val projects: Int, val todos: Int) : BackupMessage
    data class Restored(val projects: Int, val todos: Int) : BackupMessage
    data class Error(val error: BackupError) : BackupMessage
}

class BackupViewModel(private val manager: BackupManager) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 已读取、等待用户确认覆盖的备份。 */
    private val _pending = MutableStateFlow<BackupFile?>(null)
    val pending: StateFlow<BackupFile?> = _pending.asStateFlow()

    private val _messages = Channel<BackupMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            _messages.send(BackupMessage.Error(e.error))
        } catch (e: Exception) {
            // 不把原始异常信息给用户看，只显示通用提示
            _messages.send(BackupMessage.Error(BackupError.Unknown))
        } finally {
            _busy.value = false
        }
    }

    fun backup(uri: Uri) = run {
        val file = manager.backupTo(uri)
        _messages.send(BackupMessage.BackedUp(file.projects.size, file.todos.size))
    }

    fun read(uri: Uri) = run { _pending.value = manager.read(uri) }

    fun cancelRestore() {
        _pending.value = null
    }

    fun confirmRestore() {
        val file = _pending.value ?: return
        _pending.value = null
        run {
            manager.restore(file)
            _messages.send(BackupMessage.Restored(file.projects.size, file.todos.size))
        }
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: BackupViewModel = viewModel { BackupViewModel(container.backupManager) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::backup)
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::read)
    }

    LaunchedEffect(vm) {
        vm.messages.collect { msg ->
            val text = when (msg) {
                is BackupMessage.BackedUp -> context.getString(R.string.backup_done, msg.projects, msg.todos)
                is BackupMessage.Restored -> context.getString(R.string.backup_restored, msg.projects, msg.todos)
                is BackupMessage.Error -> context.getString(
                    R.string.backup_error,
                    context.getString(msg.error.messageRes, *msg.error.formatArgs),
                )
            }
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { AppIcon(R.drawable.ic_arrow_back, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionCard(
                    icon = R.drawable.ic_download,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    badgeColor = MaterialTheme.colorScheme.primaryContainer,
                    badgeContent = MaterialTheme.colorScheme.onPrimaryContainer,
                    title = stringResource(R.string.backup_export_title),
                    body = stringResource(R.string.backup_export_body),
                ) {
                    Button(
                        onClick = {
                            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDoc.launch("tododrop-backup-$stamp.json")
                        },
                        enabled = !busy,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.backup_export_action)) }
                }
                ActionCard(
                    icon = R.drawable.ic_upload_file,
                    shape = MaterialShapes.Clover4Leaf.toShape(),
                    badgeColor = MaterialTheme.colorScheme.tertiaryContainer,
                    badgeContent = MaterialTheme.colorScheme.onTertiaryContainer,
                    title = stringResource(R.string.backup_import_title),
                    body = stringResource(R.string.backup_import_body),
                ) {
                    FilledTonalButton(
                        // 有些文件管理器给 JSON 文件报的类型不是 application/json，所以放宽到所有文件
                        onClick = { openDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
                        enabled = !busy,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.backup_import_action)) }
                }
            }
            if (busy) LoadingIndicator(Modifier.align(Alignment.Center))
        }
    }

    pending?.let { file ->
        ConfirmDialog(
            title = stringResource(R.string.backup_restore_confirm_title),
            body = stringResource(R.string.backup_restore_confirm_body, file.projects.size, file.todos.size, file.history.size),
            confirmLabel = stringResource(R.string.backup_restore_action),
            onDismiss = vm::cancelRestore,
            onConfirm = vm::confirmRestore,
        )
    }
}

@Composable
private fun ActionCard(
    @DrawableRes icon: Int,
    shape: Shape,
    badgeColor: Color,
    badgeContent: Color,
    title: String,
    body: String,
    action: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeBadge(shape = shape, container = badgeColor, size = 56.dp) {
                    AppIcon(icon, null, Modifier.size(28.dp), tint = badgeContent)
                }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp))
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}
