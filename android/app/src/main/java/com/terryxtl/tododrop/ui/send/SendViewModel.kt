package com.terryxtl.tododrop.ui.send

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.terryxtl.tododrop.R
import com.terryxtl.tododrop.data.ExportHistoryEntity
import com.terryxtl.tododrop.data.TodoRepository
import com.terryxtl.tododrop.export.HistoryPayload
import com.terryxtl.tododrop.export.NoteLine
import com.terryxtl.tododrop.export.SavedFile
import com.terryxtl.tododrop.transfer.QrParseResult
import com.terryxtl.tododrop.transfer.QrPayload
import com.terryxtl.tododrop.transfer.TransferClient
import com.terryxtl.tododrop.transfer.TransferError
import com.terryxtl.tododrop.transfer.TransferException
import com.terryxtl.tododrop.ui.navigation.SendRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

sealed interface FailureReason {
    data class Transfer(val error: TransferError) : FailureReason
    data object NothingToSend : FailureReason
    data class InvalidQr(@StringRes val messageRes: Int) : FailureReason
}

sealed interface SendUiState {
    data class Connecting(val pcName: String?) : SendUiState
    data class Uploading(val pcName: String, val count: Int) : SendUiState
    data class Success(
        val pcName: String,
        val files: List<SavedFile>,
        val count: Int,
        /** 只给人看的提醒，发送完在屏幕上列出来。 */
        val notes: List<NoteLine>,
    ) : SendUiState
    data class Failed(val reason: FailureReason) : SendUiState
}

class SendViewModel(
    private val repository: TodoRepository,
    private val transfer: TransferClient,
    private val deviceName: String,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<SendRoute>()
    private val _state = MutableStateFlow<SendUiState>(SendUiState.Connecting(null))
    val state: StateFlow<SendUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        start()
    }

    fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            val payload = when (val parsed = QrPayload.parse(route.qr)) {
                is QrParseResult.Ok -> parsed.payload
                is QrParseResult.Invalid -> return@launch fail(FailureReason.InvalidQr(parsed.reason.messageRes))
                QrParseResult.NotTodoDrop -> return@launch fail(FailureReason.InvalidQr(R.string.scan_not_tododrop))
                QrParseResult.UnsupportedVersion -> return@launch fail(FailureReason.InvalidQr(R.string.scan_unsupported))
            }
            val startedAt = System.currentTimeMillis()
            _state.value = SendUiState.Connecting(payload.pcName)

            val export = repository.buildExport(route.todoIds)
            val count = export.todoCount
            if (count == 0) return@launch fail(FailureReason.NothingToSend)

            try {
                val (endpoint, files) = transfer.send(
                    payload = payload,
                    device = deviceName,
                    exportedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                    projects = export.projects,
                    onConnected = { _state.value = SendUiState.Uploading(it.pcName, count) },
                )
                // 电脑已经保存，即使界面被关掉也要把待办标为已完成、记下历史
                withContext(NonCancellable) {
                    repository.finishExport(
                        todoIds = export.todoIds,
                        entry = ExportHistoryEntity(
                            exportedAt = System.currentTimeMillis(),
                            pcName = endpoint.pcName,
                            savedAs = files.joinToString("\n") { it.savedAs },
                            itemCount = count,
                            projectNames = export.projectNames.joinToString("、"),
                            markdown = "",
                            payload = HistoryPayload(export.projects, files).encode(),
                        ),
                    )
                }
                // 局域网里通常几十毫秒就完成，留一点时间让加载动画完整地变形一次
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed < MIN_PROGRESS_MS) delay(MIN_PROGRESS_MS - elapsed)
                _state.value = SendUiState.Success(endpoint.pcName, files, count, export.notes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransferException) {
                fail(FailureReason.Transfer(e.error))
            } catch (e: Exception) {
                // 原始异常信息不给用户看（可能是英文或内部细节），只显示通用提示
                fail(FailureReason.Transfer(TransferError.Failed))
            }
        }
    }

    private fun fail(reason: FailureReason) {
        _state.value = SendUiState.Failed(reason)
    }

    private companion object {
        const val MIN_PROGRESS_MS = 800L
    }
}
