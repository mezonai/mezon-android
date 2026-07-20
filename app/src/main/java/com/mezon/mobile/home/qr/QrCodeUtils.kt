package com.mezon.mobile.home.qr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.NotFoundException
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource

object QrCodeUtils {

    fun generateQr(value: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to 1
        )
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }

    fun decodeFromBitmap(bitmap: Bitmap): String? {
        val argb = toSoftwareArgb8888(bitmap)
        val ownsArgbCopy = argb !== bitmap
        try {
            for (cropFraction in floatArrayOf(1f, 0.75f, 0.5f)) {
                if (cropFraction >= 1f) {
                    decodeWithRotations(argb)?.let { return it }
                } else {
                    val cropped = centerCrop(argb, cropFraction)
                    try {
                        decodeWithRotations(cropped)?.let { return it }
                    } finally {
                        cropped.recycle()
                    }
                }
            }
            return null
        } finally {
            if (ownsArgbCopy) argb.recycle()
        }
    }

    fun decodeLiveCameraBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.ALSO_INVERTED to true 
                )
            )
        }

        fun fastScan(cropFraction: Float, tryRotate: Boolean = false): String? {
            val cropW = (width * cropFraction).toInt().coerceAtLeast(1)
            val cropH = (height * cropFraction).toInt().coerceAtLeast(1)
            val left = (width - cropW) / 2
            val top = (height - cropH) / 2
            
            var source: com.google.zxing.LuminanceSource = RGBLuminanceSource(width, height, pixels).crop(left, top, cropW, cropH)
            
            try {
                reader.decode(BinaryBitmap(HybridBinarizer(source))).text?.let { return it }
            } catch (_: Exception) {
                reader.reset()
            }

            if (tryRotate) {
                source = source.rotateCounterClockwise()
                try {
                    reader.decode(BinaryBitmap(HybridBinarizer(source))).text?.let { return it }
                } catch (_: Exception) {
                    reader.reset()
                }
            }
            return null
        }

        fastScan(0.7f, tryRotate = true)?.let { return it }
        fastScan(1f, tryRotate = true)?.let { return it }

        return null
    }

    private fun decodeWithRotations(bitmap: Bitmap): String? {
        decodeFromArgbBitmap(bitmap)?.let { return it }
        for (rotation in intArrayOf(90, 180, 270)) {
            val rotated = rotateBitmap(bitmap, rotation.toFloat()) ?: continue
            try {
                decodeFromArgbBitmap(rotated)?.let { return it }
            } finally {
                rotated.recycle()
            }
        }
        return null
    }

    private fun centerCrop(bitmap: Bitmap, widthFraction: Float): Bitmap {
        val cropW = (bitmap.width * widthFraction).toInt().coerceAtLeast(1)
        val cropH = (bitmap.height * widthFraction).toInt().coerceAtLeast(1)
        val x = (bitmap.width - cropW) / 2
        val y = (bitmap.height - cropH) / 2
        return Bitmap.createBitmap(bitmap, x, y, cropW, cropH)
    }

    private fun decodeFromArgbBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        return decodeWithBinarizers(source)
    }

    private fun decodeWithBinarizers(source: LuminanceSource): String? {
        val binarizers = listOf(
            { HybridBinarizer(source) },
            { GlobalHistogramBinarizer(source) }
        )
        for (factory in binarizers) {
            decode(BinaryBitmap(factory()))?.let { return it }
        }
        return null
    }

    private fun toSoftwareArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config != Bitmap.Config.HARDWARE) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap? {
        if (degrees % 360f == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }



    private fun decode(bitmap: BinaryBitmap): String? {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.ALSO_INVERTED to true
                )
            )
        }
        return try {
            val result: Result = reader.decode(bitmap)
            result.text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}

