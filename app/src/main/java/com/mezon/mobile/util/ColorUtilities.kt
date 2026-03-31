package com.mezon.mobile.util

import android.graphics.Bitmap
import android.graphics.Color

object ColorUtilities {

    fun getDominantColor(bitmap: Bitmap): Int {
        val newBitmap = Bitmap.createScaledBitmap(bitmap, 30, 30, true)
        val pixels = IntArray(900)
        newBitmap.getPixels(pixels, 0, 30, 0, 0, 30, 30)
        if (newBitmap != bitmap) newBitmap.recycle()

        val validPixels = pixels.filter { Color.alpha(it) > 200 }
        if (validPixels.isEmpty()) return Color.GRAY

        val hsv = FloatArray(3)
        val bestBucket = validPixels.groupBy {
            Color.rgb(Color.red(it) and 0xE0, Color.green(it) and 0xE0, Color.blue(it) and 0xE0)
        }.maxByOrNull { (bucketColor, bucketPixels) ->
            Color.colorToHSV(bucketColor, hsv)
            bucketPixels.size * (1f + (hsv[1] * hsv[2] * 10f))
        }

        val winningPixels = bestBucket?.value ?: return Color.GRAY
        
        val avgR = winningPixels.sumOf { Color.red(it) } / winningPixels.size
        val avgG = winningPixels.sumOf { Color.green(it) } / winningPixels.size
        val avgB = winningPixels.sumOf { Color.blue(it) } / winningPixels.size

        return Color.rgb(avgR, avgG, avgB)
    }
}
