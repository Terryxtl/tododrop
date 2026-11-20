package com.terryxtl.tododrop.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class QrPayloadTest {

    private companion object {
        /** 32 字节的“证书指纹”，编码成 base64url 无填充后正好 43 个字符。 */
        val FP_BYTES = ByteArray(32) { (it * 7 + 3).toByte() }
        val FP: String = Base64.getUrlEncoder().withoutPadding().encodeToString(FP_BYTES)

        /** 16 字节随机数的 base64url 无填充形式（22 个字符），和 CLI 的 token 一致。 */
        const val TOKEN = "q3J-x_9Zt0aB1cD2eF3gHw"
        const val HOSTS = "192.168.110.123,10.147.17.200"

        /** CLI 用 urlencoding::encode 编码 "DESKTOP-ABC 书房" 的结果（见 cli/src/qr.rs 的测试）。 */
        const val NAME_ENCODED = "DESKTOP-ABC%20%E4%B9%A6%E6%88%BF"
        const val NAME = "DESKTOP-ABC 书房"
    }

    /** 参数顺序与 CLI 的 `Payload::to_url` 一致：h, p, t, f, n。 */
    private fun defaults() = listOf(
        "h" to HOSTS,
        "p" to "53320",
        "t" to TOKEN,
        "f" to FP,
        "n" to NAME_ENCODED,
    )

    private fun build(params: List<Pair<String, String>>, prefix: String = "tododrop://v1?") =
        prefix + params.joinToString("&") { (k, v) -> "$k=$v" }

    private fun url() = build(defaults())
    private fun withoutParam(key: String) = build(defaults().filter { it.first != key })
    private fun withParam(key: String, value: String) = build(defaults().map { if (it.first == key) key to value else it })

    private fun parseOk(text: String): QrPayload {
        val r = QrPayload.parse(text)
        if (r !is QrParseResult.Ok) fail("expected Ok for <$text> but got $r")
        return (r as QrParseResult.Ok).payload
    }

    private fun assertInvalid(text: String, reason: QrInvalidReason) {
        val r = QrPayload.parse(text)
        assertTrue("expected Invalid for <$text> but got $r", r is QrParseResult.Invalid)
        assertEquals("reason for <$text>", reason, (r as QrParseResult.Invalid).reason)
    }

    // ---- 合法链接 ----

    @Test
    fun parsesExactCliGeneratedUrl() {
        // 与 cli/src/qr.rs 中 url_format 测试的期望输出逐字一致。
        val fp = "B".repeat(43)
        val text = "tododrop://v1?h=192.168.110.123,10.147.17.200&p=53320&t=AAAAAAAAAAAAAAAAAAAAAA&f=$fp" +
            "&n=DESKTOP-ABC%20%E4%B9%A6%E6%88%BF"
        val p = parseOk(text)
        assertEquals(listOf("192.168.110.123", "10.147.17.200"), p.hosts)
        assertEquals(53320, p.port)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", p.token)
        assertEquals(32, p.fingerprint.size)
        assertArrayEquals(Base64.getUrlDecoder().decode(fp), p.fingerprint)
        assertEquals("DESKTOP-ABC 书房", p.pcName)
    }

    @Test
    fun parsesAllFields() {
        assertEquals(43, FP.length)
        val p = parseOk(url())
        assertEquals(listOf("192.168.110.123", "10.147.17.200"), p.hosts)
        assertEquals(53320, p.port)
        assertEquals(TOKEN, p.token)
        assertArrayEquals(FP_BYTES, p.fingerprint)
        assertEquals(NAME, p.pcName)
    }

    @Test
    fun parsesSingleAndFourHosts() {
        assertEquals(listOf("192.168.1.5"), parseOk(withParam("h", "192.168.1.5")).hosts)
        val four = "192.168.1.5,10.147.17.3,172.16.0.1,100.64.0.7"
        assertEquals(four.split(','), parseOk(withParam("h", four)).hosts)
    }

    @Test
    fun decodesUrlEncodedChineseName() {
        val raw = "书房电脑"
        val p = parseOk(withParam("n", URLEncoder.encode(raw, "UTF-8")))
        assertEquals(raw, p.pcName)
    }

    @Test
    fun nameIsOptional() {
        assertNull(parseOk(withoutParam("n")).pcName)
        assertNull(parseOk(withParam("n", "")).pcName)
    }

    @Test
    fun nameIsTruncatedTo32Chars() {
        val p = parseOk(withParam("n", URLEncoder.encode("名".repeat(50), "UTF-8")))
        assertEquals("名".repeat(32), p.pcName)
    }

    @Test
    fun unknownParametersAreIgnored() {
        val p = parseOk(url() + "&x=1&future=abc")
        assertEquals(53320, p.port)
        assertEquals(NAME, p.pcName)
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(53320, parseOk("  " + url() + "\n").port)
    }

    @Test
    fun portBoundariesAccepted() {
        assertEquals(1, parseOk(withParam("p", "1")).port)
        assertEquals(65535, parseOk(withParam("p", "65535")).port)
    }

    // ---- scheme / 版本 ----

    @Test
    fun wrongSchemeIsNotTodoDrop() {
        val query = url().substringAfter("?")
        assertEquals(QrParseResult.NotTodoDrop, QrPayload.parse("https://v1?$query"))
        assertEquals(QrParseResult.NotTodoDrop, QrPayload.parse("tododrop2://v1?$query"))
        assertEquals(QrParseResult.NotTodoDrop, QrPayload.parse("tododrop:v1?$query"))
        assertEquals(QrParseResult.NotTodoDrop, QrPayload.parse("hello world"))
        assertEquals(QrParseResult.NotTodoDrop, QrPayload.parse(""))
    }

    @Test
    fun wrongVersionIsUnsupported() {
        val query = url().substringAfter("?")
        assertEquals(QrParseResult.UnsupportedVersion, QrPayload.parse("tododrop://v2?$query"))
        assertEquals(QrParseResult.UnsupportedVersion, QrPayload.parse("tododrop://v10?$query"))
        assertEquals(QrParseResult.UnsupportedVersion, QrPayload.parse("tododrop://example?$query"))
    }

    @Test
    fun malformedUriIsInvalid() {
        assertInvalid("tododrop://v1 ?" + url().substringAfter("?"), QrInvalidReason.FORMAT)
    }

    // ---- 缺字段 ----

    @Test
    fun missingHostsIsInvalid() {
        assertInvalid(withoutParam("h"), QrInvalidReason.HOSTS_MISSING)
        assertInvalid(withParam("h", ""), QrInvalidReason.HOSTS_MISSING)
        assertInvalid(withParam("h", ",,"), QrInvalidReason.HOSTS_MISSING)
    }

    @Test
    fun missingPortIsInvalid() {
        assertInvalid(withoutParam("p"), QrInvalidReason.PORT)
    }

    @Test
    fun missingTokenIsInvalid() {
        assertInvalid(withoutParam("t"), QrInvalidReason.TOKEN)
        assertInvalid(withParam("t", ""), QrInvalidReason.TOKEN)
    }

    @Test
    fun missingFingerprintIsInvalid() {
        assertInvalid(withoutParam("f"), QrInvalidReason.FINGERPRINT)
        assertInvalid(withParam("f", ""), QrInvalidReason.FINGERPRINT)
    }

    @Test
    fun noQueryAtAllIsInvalid() {
        assertInvalid("tododrop://v1", QrInvalidReason.HOSTS_MISSING)
    }

    // ---- 字段值不合法 ----

    @Test
    fun badHostsAreInvalid() {
        assertInvalid(withParam("h", "999.1.1.1"), QrInvalidReason.HOST_INVALID)
        assertInvalid(withParam("h", "pc.local"), QrInvalidReason.HOST_INVALID)
        assertInvalid(withParam("h", "192.168.1"), QrInvalidReason.HOST_INVALID)
        assertInvalid(withParam("h", "192.168.1.5,fe80::1"), QrInvalidReason.HOST_INVALID)
        // 超过 4 个
        assertInvalid(withParam("h", "1.1.1.1,2.2.2.2,3.3.3.3,4.4.4.4,5.5.5.5"), QrInvalidReason.HOSTS_TOO_MANY)
    }

    @Test
    fun badPortIsInvalid() {
        for (port in listOf("0", "65536", "-1", "abc", "", "53320x", "99999999999")) {
            assertInvalid(withParam("p", port), QrInvalidReason.PORT)
        }
    }

    @Test
    fun badTokenIsInvalid() {
        assertInvalid(withParam("t", "short"), QrInvalidReason.TOKEN)
        assertInvalid(withParam("t", "q3J+x/9Zt0aB1cD2eF3gHw"), QrInvalidReason.TOKEN)
    }

    @Test
    fun fingerprintWithWrongLengthIsInvalid() {
        val fp = QrInvalidReason.FINGERPRINT
        assertInvalid(withParam("f", FP.dropLast(1)), fp) // 42 字符 → 31 字节
        assertInvalid(withParam("f", FP + "A"), fp) // 44 字符 → 33 字节
        assertInvalid(withParam("f", FP + "="), fp) // 44 字符，带填充也能解出 32 字节，但协议要求 43 字符无填充
        assertInvalid(withParam("f", FP.take(22)), fp)
        val longer = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48) { 1 })
        assertInvalid(withParam("f", longer), fp)
    }

    @Test
    fun fingerprintThatIsNotBase64UrlIsInvalid() {
        // 长度都是 43，但内容不是合法的 base64url。
        val fp = QrInvalidReason.FINGERPRINT
        assertInvalid(withParam("f", FP.dropLast(1) + "*"), fp)
        assertInvalid(withParam("f", "/" + FP.drop(1)), fp) // 标准 base64 字符
        assertInvalid(withParam("f", "%2B" + FP.drop(1)), fp) // 解码后是 '+'
        assertInvalid(withParam("f", "A".repeat(42) + "="), fp) // 填充位置错误
        assertInvalid(withParam("f", "A".repeat(41) + "=="), fp)
    }

    @Test
    fun everyInvalidReasonHasItsOwnMessage() {
        val ids = QrInvalidReason.entries.map { it.messageRes }
        assertTrue(ids.all { it != 0 })
        assertEquals(ids.size, ids.toSet().size)
    }
}
