package com.terryxtl.tododrop.ui.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * 用 ZXing 从相机帧里识别二维码。
 *
 * 只取 YUV 的 Y（亮度）平面，二维码与方向无关，所以不需要按旋转角度转图。
 * 在后台线程调用；识别到内容后回调 [onDecoded]（仍在分析线程上）。
 */
internal class QrCodeAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                // 电脑端 --invert 会输出反色二维码
                DecodeHintType.ALSO_INVERTED to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            )
        )
    }
    private var buffer = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val data = plane.buffer
            val size = data.remaining()
            if (buffer.size != size) buffer = ByteArray(size)
            data.get(buffer)
            // 行宽可能带填充（rowStride > width），把 rowStride 当作数据宽度，再裁出实际画面
            val source = PlanarYUVLuminanceSource(
                buffer, plane.rowStride, it.height, 0, 0, it.width, it.height, false,
            )
            val text = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (text != null) onDecoded(text)
        }
    }
}
