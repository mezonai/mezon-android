package com.mezon.mobile.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

fun generateQrBitmap(value: String, sizePx: Int): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q)
    val bitMatrix = QRCodeWriter().encode(value, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

