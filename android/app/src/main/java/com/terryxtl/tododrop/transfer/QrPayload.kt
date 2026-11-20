package com.terryxtl.tododrop.transfer

import androidx.annotation.StringRes
import com.terryxtl.tododrop.R
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Base64

/**
 * 二维码内容：`tododrop://v1?h=<ip1>,<ip2>&p=<port>&t=<token>&f=<fingerprint>&n=<name>`
 * 见 docs/PROTOCOL.md 第 1 节。
 */
class QrPayload(
    val hosts: List<String>,
    val port: Int,
    val token: String,
    /** 服务器证书 DER 的 SHA-256（32 字节）。 */
    val fingerprint: ByteArray,
    val pcName: String?,
) {
    companion object {
        const val SCHEME = "tododrop"
        const val VERSION = "v1"
        const val MAX_HOSTS = 4
        const val FINGERPRINT_CHARS = 43

        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
        private val TOKEN = Regex("""^[A-Za-z0-9_-]{16,128}$""")

        fun parse(text: String): QrParseResult {
            val raw = text.trim()
            if (!raw.startsWith("$SCHEME://", ignoreCase = true)) return QrParseResult.NotTodoDrop
            val uri = try {
                URI(raw)
            } catch (_: URISyntaxException) {
                return QrParseResult.Invalid(QrInvalidReason.FORMAT)
            }
            if (!uri.host.equals(VERSION, ignoreCase = true)) return QrParseResult.UnsupportedVersion

            val params = (uri.rawQuery ?: "").split('&')
                .filter { it.isNotEmpty() }
                .associate { part ->
                    val i = part.indexOf('=')
                    if (i < 0) part to "" else part.substring(0, i) to decode(part.substring(i + 1))
                }

            val hosts = params["h"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (hosts.isEmpty()) return QrParseResult.Invalid(QrInvalidReason.HOSTS_MISSING)
            if (hosts.size > MAX_HOSTS) return QrParseResult.Invalid(QrInvalidReason.HOSTS_TOO_MANY)
            if (!hosts.all(::isIpv4)) return QrParseResult.Invalid(QrInvalidReason.HOST_INVALID)

            val port = params["p"]?.toIntOrNull()
            if (port == null || port !in 1..65535) return QrParseResult.Invalid(QrInvalidReason.PORT)

            val token = params["t"].orEmpty()
            if (!TOKEN.matches(token)) return QrParseResult.Invalid(QrInvalidReason.TOKEN)

            // 协议规定 43 个字符、无填充；不检查长度的话，带 `=` 填充的 44 字符也能解码出 32 字节。
            val fingerprintText = params["f"].orEmpty()
            if (fingerprintText.length != FINGERPRINT_CHARS) return QrParseResult.Invalid(QrInvalidReason.FINGERPRINT)
            val fingerprint = try {
                Base64.getUrlDecoder().decode(fingerprintText)
            } catch (_: IllegalArgumentException) {
                return QrParseResult.Invalid(QrInvalidReason.FINGERPRINT)
            }
            if (fingerprint.size != 32) return QrParseResult.Invalid(QrInvalidReason.FINGERPRINT)

            val name = params["n"]?.trim()?.takeIf { it.isNotEmpty() }?.take(32)
            return QrParseResult.Ok(QrPayload(hosts, port, token, fingerprint, name))
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            s
        }

        private fun isIpv4(s: String): Boolean {
            val m = IPV4.matchEntire(s) ?: return false
            return m.groupValues.drop(1).all { it.toInt() in 0..255 }
        }
    }
}

sealed interface QrParseResult {
    data class Ok(val payload: QrPayload) : QrParseResult
    data object NotTodoDrop : QrParseResult
    data object UnsupportedVersion : QrParseResult
    data class Invalid(val reason: QrInvalidReason) : QrParseResult
}

/** 二维码内容不完整的具体原因，界面用 [messageRes] 显示。 */
enum class QrInvalidReason(@StringRes val messageRes: Int) {
    FORMAT(R.string.qr_invalid_format),
    HOSTS_MISSING(R.string.qr_invalid_hosts_missing),
    HOSTS_TOO_MANY(R.string.qr_invalid_hosts_too_many),
    HOST_INVALID(R.string.qr_invalid_host),
    PORT(R.string.qr_invalid_port),
    TOKEN(R.string.qr_invalid_token),
    FINGERPRINT(R.string.qr_invalid_fingerprint),
}
