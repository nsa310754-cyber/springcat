package com.apkbuilder.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** Exact in-APK paths of the template's launcher icon resources (see apkbuilder/template). */
private val ICON_DENSITIES = mapOf(
    "res/mipmap-mdpi-v4" to 48,
    "res/mipmap-hdpi-v4" to 72,
    "res/mipmap-xhdpi-v4" to 96,
    "res/mipmap-xxhdpi-v4" to 144,
    "res/mipmap-xxxhdpi-v4" to 192,
)

object IconResizer {

    /** Decodes [sourceBytes] and returns PNG bytes resized for every density the template ships. */
    fun buildIconOverrides(sourceBytes: ByteArray): Map<String, ByteArray> {
        val original = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
            ?: error("could not decode the chosen image as a bitmap")
        val overrides = HashMap<String, ByteArray>()
        try {
            for ((dir, size) in ICON_DENSITIES) {
                val scaled = Bitmap.createScaledBitmap(original, size, size, true)
                val png = encodePng(scaled)
                overrides["$dir/ic_launcher.png"] = png
                overrides["$dir/ic_launcher_round.png"] = png
                if (scaled !== original) scaled.recycle()
            }
        } finally {
            original.recycle()
        }
        return overrides
    }

    private fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
