package com.terryxtl.tododrop.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class TransferClientTest {

    private fun fingerprintFailure() = SSLHandshakeException("handshake failed").apply {
        initCause(FingerprintMismatchException())
    }

    // ---- /info 校验 ----

    @Test
    fun infoFromTododropProtocol2IsAccepted() {
        assertNull(TransferClient.checkInfo(InfoResponse(app = "tododrop", protocol = 2, name = "PC")))
    }

    @Test
    fun infoFromAnotherAppIsNotTododrop() {
        assertEquals(TransferError.NotTodoDrop, TransferClient.checkInfo(InfoResponse(app = "other", protocol = 2)))
    }

    @Test
    fun infoWithOtherProtocolIsMismatch() {
        // 旧版 CLI（协议 1，手机生成 Markdown）和更新的版本都不兼容
        assertEquals(
            TransferError.ProtocolMismatch(1),
            TransferClient.checkInfo(InfoResponse(app = "tododrop", protocol = 1)),
        )
        assertEquals(
            TransferError.ProtocolMismatch(3),
            TransferClient.checkInfo(InfoResponse(app = "tododrop", protocol = 3)),
        )
    }

    @Test
    fun uploadRequestOmitsEmptyListsAndMatchesProtocol() {
        val json = kotlinx.serialization.json.Json
        val body = json.encodeToString(
            UploadRequest.serializer(),
            UploadRequest(
                protocol = 2,
                device = "Pixel",
                exportedAt = "2026-11-20T20:07:10+08:00",
                projects = listOf(
                    com.terryxtl.tododrop.export.ExportProject(
                        name = "工作",
                        todos = listOf("写周报"),
                        children = listOf(com.terryxtl.tododrop.export.ExportProject("后端", notes = listOf("先对接口"))),
                    ),
                ),
            ),
        )
        assertEquals(
            """{"protocol":2,"device":"Pixel","exportedAt":"2026-11-20T20:07:10+08:00",""" +
                """"projects":[{"name":"工作","todos":["写周报"],"children":[{"name":"后端","notes":["先对接口"]}]}]}""",
            body,
        )
        // 和 TransferClient 一样忽略未知字段（响应里的 notes）
        val response = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
            UploadResponse.serializer(),
            """{"files":[{"project":"工作","savedAs":"TODOS_工作_20261120_200710.md","count":1}],"notes":1}""",
        )
        assertEquals("TODOS_工作_20261120_200710.md", response.files.single().savedAs)
    }

    // ---- 探测失败的归类 ----

    @Test
    fun allHostsUnreachable() {
        val e = TransferClient.classify(listOf(ConnectException("refused"), SocketTimeoutException("connect timed out")))
        assertEquals(TransferError.Unreachable, e.error)
    }

    @Test
    fun fingerprintMismatchWinsOverEverything() {
        val e = TransferClient.classify(
            listOf(
                TransferException(TransferError.Unauthorized),
                ConnectException("refused"),
                fingerprintFailure(),
            ),
        )
        assertEquals(TransferError.FingerprintMismatch, e.error)
        assertEquals(
            TransferError.FingerprintMismatch,
            TransferClient.classify(listOf(SSLPeerUnverifiedException("peer"))).error,
        )
    }

    @Test
    fun unauthorizedWinsOverServerErrorsAndUnreachable() {
        val e = TransferClient.classify(
            listOf(
                TransferException(TransferError.Rejected(500, "写入失败")),
                ConnectException("refused"),
                TransferException(TransferError.Unauthorized),
            ),
        )
        assertEquals(TransferError.Unauthorized, e.error)
    }

    @Test
    fun serverSideErrorWinsOverUnreachable() {
        val e = TransferClient.classify(
            listOf(ConnectException("refused"), TransferException(TransferError.ProtocolMismatch(2))),
        )
        assertEquals(TransferError.ProtocolMismatch(2), e.error)

        val rejected = TransferClient.classify(listOf(TransferException(TransferError.Rejected(503, null))))
        assertEquals(TransferError.Rejected(503, null), rejected.error)
    }

    // ---- 上传失败的归类 ----

    @Test
    fun failureBeforeConnectionIsUnreachable() {
        assertEquals(TransferError.Unreachable, TransferClient.classifyUploadFailure(ConnectException("refused"), connected = false))
        assertEquals(
            TransferError.Unreachable,
            TransferClient.classifyUploadFailure(SocketTimeoutException("connect timed out"), connected = false),
        )
    }

    @Test
    fun timeoutAfterConnectingIsUploadTimeout() {
        assertEquals(
            TransferError.UploadTimeout,
            TransferClient.classifyUploadFailure(SocketTimeoutException("timeout"), connected = true),
        )
    }

    @Test
    fun dropAfterConnectingIsUploadInterrupted() {
        assertEquals(
            TransferError.UploadInterrupted,
            TransferClient.classifyUploadFailure(IOException("unexpected end of stream"), connected = true),
        )
    }

    @Test
    fun fingerprintFailureDuringUploadIsReported() {
        assertEquals(
            TransferError.FingerprintMismatch,
            TransferClient.classifyUploadFailure(fingerprintFailure(), connected = false),
        )
    }
}
