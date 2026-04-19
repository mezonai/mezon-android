package ai.mezon.app.domain.usecase

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

class GenerateQrUseCase {
    fun invoke(value: String, sizePx: Int): Bitmap {
        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q
        )
        val bitMatrix = writer.encode(value, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bmp
    }
}

