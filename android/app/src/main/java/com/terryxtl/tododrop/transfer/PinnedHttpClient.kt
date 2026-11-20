package com.terryxtl.tododrop.transfer

import okhttp3.OkHttpClient
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** 服务器证书的 SHA-256 与二维码里的指纹不一致。 */
class FingerprintMismatchException : CertificateException("证书指纹不匹配")

/**
 * 只信任 SHA-256 指纹等于二维码里 `f` 参数的叶子证书。
 * CLI 每次启动生成新的自签证书，所以不做 CA 链校验。
 */
class FingerprintTrustManager(private val expected: ByteArray) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("服务器没有提供证书")
        if (!matches(leaf, expected)) throw FingerprintMismatchException()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("不接受客户端证书")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun matches(cert: java.security.cert.Certificate, expected: ByteArray): Boolean {
            val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            return MessageDigest.isEqual(actual, expected) // 常量时间比较
        }
    }
}

object PinnedHttpClient {
    fun create(fingerprint: ByteArray, connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient {
        val trustManager = FingerprintTrustManager(fingerprint)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        // 证书是自签的，主机名对不上很正常；身份已由指纹保证，这里再核对一次会话里的证书
        val hostnameVerifier = HostnameVerifier { _, session ->
            val leaf = runCatching { session.peerCertificates.firstOrNull() }.getOrNull()
            leaf != null && FingerprintTrustManager.matches(leaf, fingerprint)
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .proxy(Proxy.NO_PROXY) // 局域网直连，不走系统代理
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
