package ai.mezon.app.data.repository

import android.graphics.Bitmap

interface QrRepository {
    fun generateQr(value: String, sizePx: Int): Bitmap
}

