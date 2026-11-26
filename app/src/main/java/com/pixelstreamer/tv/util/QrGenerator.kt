package com.pixelstreamer.tv.util

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrGenerator {
    private const val BLACK_ARGB = 0xFF000000.toInt()
    private const val WHITE_ARGB = 0xFFFFFFFF.toInt()

    fun generate(content: String, sizePx: Int = 360): Bitmap? {
        val pixels = encodePixelBuffer(content, sizePx) ?: return null
        return try {
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun encodePixelBuffer(content: String, sizePx: Int): IntArray? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val rowOffset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[rowOffset + x] = if (matrix.get(x, y)) BLACK_ARGB else WHITE_ARGB
                }
            }
            pixels
        } catch (_: Exception) {
            null
        }
    }
}
