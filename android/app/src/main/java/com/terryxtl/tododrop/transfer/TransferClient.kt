package com.terryxtl.tododrop.transfer

import com.terryxtl.tododrop.export.ExportProject
import com.terryxtl.tododrop.export.SavedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 传输失败的原因，界面按类型给出不同提示（文案在界面层用字符串资源解析）。 */
sealed interface TransferError {
    /** 所有地址都连不上（不同网络、CLI 已退出、防火墙拦截……）。 */
    data object Unreachable : TransferError

    /** 连上了，但证书指纹不对。 */
    data object FingerprintMismatch : TransferError

    /** token 不对（通常是 CLI 重启过）。 */
    data object Unauthorized : TransferError

    /** 地址上运行的不是 tododrop（`/info` 的 `app` 不对或内容无法解析）。 */
    data object NotTodoDrop : TransferError

    /** 电脑端的协议版本和 App 不一致。 */
    data class ProtocolMismatch(val serverProtocol: Int) : TransferError

    /** 待办已经发出，但等电脑回复时超时了。电脑可能已经保存了文件。 */
    data object UploadTimeout : TransferError

    /** 发送过程中连接断开。电脑可能已经保存了文件。 */
    data object UploadInterrupted : TransferError

    /**
     * 服务器返回了错误（400 / 413 / 500 ……）。
     * [serverMessage] 是响应体里的 `error` 字段（电脑端是我们自己的 CLI，内容是中文），没有时为 null。
     */
    data class Rejected(val httpCode: Int, val serverMessage: String?) : TransferError

    /** 上传成功（2xx），但响应内容无法识别。 */
    data object BadResponse : TransferError

    /** 其他意外错误。 */
    data object Failed : TransferError
}

class TransferException(val error: TransferError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** 探测成功的地址。 */
data class Endpoint(val host: String, val port: Int, val pcName: String)

/** 一次完整发送的结果。 */
data class UploadResult(val endpoint: Endpoint, val files: List<SavedFile>)

@Serializable
internal data class InfoResponse(val app: String, val protocol: Int, val name: String = "", val version: String = "")

@Serializable
internal data class UploadRequest(
    val protocol: Int,
    val device: String,
    val exportedAt: String,
    val projects: List<ExportProject>,
)

@Serializable
internal data class UploadResponse(val files: List<SavedFile>)

@Serializable
internal data class ErrorResponse(val error: String)

class TransferClient {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 完整的一次发送：并发探测二维码里的地址，再向第一个连通的地址 `POST /api/v2/todos`。
     * 只发原始数据（项目树），Markdown 由电脑端生成。
     *
     * 整个过程只建一个固定指纹的 OkHttpClient；上传用的客户端由它 `newBuilder()` 派生，
     * 共享连接池和线程池（探测时建好的 TLS 连接可以直接复用），结束后统一释放。
     *
     * @param onConnected 探测成功、开始上传前调用，用于更新界面
     * @throws TransferException 失败原因见 [TransferError]
     */
    suspend fun send(
        payload: QrPayload,
        device: String,
        exportedAt: String,
        projects: List<ExportProject>,
        onConnected: (Endpoint) -> Unit = {},
    ): UploadResult {
        val client = PinnedHttpClient.create(payload.fingerprint, CONNECT_TIMEOUT_MS, PROBE_READ_TIMEOUT_MS)
        try {
            val endpoint = probe(client, payload)
            onConnected(endpoint)
            val files = upload(client, endpoint, payload, device, exportedAt, projects)
            return UploadResult(endpoint, files)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { release(client) }
        }
    }

    /** 并发探测二维码里的所有地址，返回第一个 `GET /api/v1/info` 成功的。 */
    private suspend fun probe(client: OkHttpClient, payload: QrPayload): Endpoint = coroutineScope {
        val winner = CompletableDeferred<Endpoint>()
        val errors = mutableListOf<Throwable>()
        val jobs = payload.hosts.map { host ->
            launch {
                try {
                    winner.complete(info(client, host, payload))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val all = synchronized(errors) {
                        errors += e
                        errors.toList()
                    }
                    if (all.size == payload.hosts.size) winner.completeExceptionally(classify(all))
                }
            }
        }
        try {
            winner.await()
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    /** `POST /api/v2/todos`，返回电脑上保存的文件。 */
    private suspend fun upload(
        baseClient: OkHttpClient,
        endpoint: Endpoint,
        payload: QrPayload,
        device: String,
        exportedAt: String,
        projects: List<ExportProject>,
    ): List<SavedFile> {
        // 连接拿到之后（TLS 握手和指纹校验都已通过）再出错，说明请求可能已经到了电脑端。
        val connected = AtomicBoolean(false)
        val client = baseClient.newBuilder()
            .readTimeout(UPLOAD_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(UPLOAD_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .eventListener(object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) {
                    connected.set(true)
                }
            })
            .build()
        val body = json.encodeToString(
            UploadRequest.serializer(),
            UploadRequest(protocol = PROTOCOL_VERSION, device = device, exportedAt = exportedAt, projects = projects),
        )
        val request = Request.Builder()
            .url("https://${endpoint.host}:${endpoint.port}/api/v2/todos")
            .header("Authorization", "Bearer ${payload.token}")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val (code, text) = try {
            client.newCall(request).await().use { resp ->
                resp.code to withContext(Dispatchers.IO) { resp.body.string() }
            }
        } catch (e: IOException) {
            throw TransferException(classifyUploadFailure(e, connected.get()), e)
        }
        return when {
            code == 201 || code == 200 -> try {
                json.decodeFromString(UploadResponse.serializer(), text).files.also {
                    if (it.isEmpty()) throw SerializationException("没有返回文件")
                }
            } catch (e: SerializationException) {
                throw TransferException(TransferError.BadResponse, e)
            } catch (e: IllegalArgumentException) {
                throw TransferException(TransferError.BadResponse, e)
            }
            code == 401 -> throw TransferException(TransferError.Unauthorized)
            else -> throw TransferException(TransferError.Rejected(code, serverError(text)))
        }
    }

    private suspend fun info(client: OkHttpClient, host: String, payload: QrPayload): Endpoint {
        val request = Request.Builder()
            .url("https://$host:${payload.port}/api/v1/info")
            .header("Authorization", "Bearer ${payload.token}")
            .get()
            .build()
        return client.newCall(request).await().use { resp ->
            if (resp.code == 401) throw TransferException(TransferError.Unauthorized)
            val text = withContext(Dispatchers.IO) { resp.body.string() }
            if (!resp.isSuccessful) throw TransferException(TransferError.Rejected(resp.code, serverError(text)))
            val info = try {
                json.decodeFromString(InfoResponse.serializer(), text)
            } catch (e: SerializationException) {
                throw TransferException(TransferError.NotTodoDrop, e)
            } catch (e: IllegalArgumentException) {
                throw TransferException(TransferError.NotTodoDrop, e)
            }
            checkInfo(info)?.let { throw TransferException(it) }
            Endpoint(host, payload.port, info.name.ifBlank { payload.pcName ?: host })
        }
    }

    private fun serverError(text: String): String? =
        runCatching { json.decodeFromString(ErrorResponse.serializer(), text).error }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() }

    /** 关掉这次传输用的线程池和连接。 */
    private fun release(client: OkHttpClient) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        /** 接口协议版本；二维码格式仍是 v1，见 [QrPayload.VERSION]。 */
        const val PROTOCOL_VERSION = 2
        const val APP_ID = "tododrop"
        const val CONNECT_TIMEOUT_MS = 2_500L
        const val PROBE_READ_TIMEOUT_MS = 5_000L
        const val UPLOAD_READ_TIMEOUT_MS = 10_000L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 校验 `/info` 的内容；不是 tododrop 或协议版本不一致时返回对应错误。 */
        internal fun checkInfo(info: InfoResponse): TransferError? = when {
            info.app != APP_ID -> TransferError.NotTodoDrop
            info.protocol != PROTOCOL_VERSION -> TransferError.ProtocolMismatch(info.protocol)
            else -> null
        }

        /** 多个地址都失败时，挑最能说明问题的原因：指纹不对 > token 不对 > 服务器给出的错误 > 连不上。 */
        internal fun classify(errors: List<Throwable>): TransferException {
            if (errors.any { it.isFingerprintFailure() }) {
                return TransferException(TransferError.FingerprintMismatch, errors.first())
            }
            if (errors.any { (it as? TransferException)?.error == TransferError.Unauthorized }) {
                return TransferException(TransferError.Unauthorized)
            }
            errors.firstNotNullOfOrNull { it as? TransferException }?.let { return it }
            return TransferException(TransferError.Unreachable, errors.firstOrNull())
        }

        /**
         * 上传时的 I/O 错误。连接建立之前失败算连不上；之后失败说明请求可能已经送达，
         * 区分超时和中断，界面会提醒用户先看看电脑上是否已经保存。
         */
        internal fun classifyUploadFailure(e: IOException, connected: Boolean): TransferError = when {
            e.isFingerprintFailure() -> TransferError.FingerprintMismatch
            !connected -> TransferError.Unreachable
            e is InterruptedIOException -> TransferError.UploadTimeout // SocketTimeoutException 是它的子类
            else -> TransferError.UploadInterrupted
        }

        private fun Throwable.isFingerprintFailure(): Boolean =
            generateSequence(this) { it.cause }.any { it is FingerprintMismatchException || it is SSLPeerUnverifiedException }
    }
}

/** 把 OkHttp 的回调式调用包装成可取消的挂起函数。 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
