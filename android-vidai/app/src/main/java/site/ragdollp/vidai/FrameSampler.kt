package site.ragdollp.vidai

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * 動画 URI から等間隔にフレームを取り出し、小さい JPEG (base64 向け) に落とす。
 * MediaMetadataRetriever は少し遅いが追加ライブラリ不要で確実。
 */
object FrameSampler {
    data class Frame(val tsSec: Double, val jpegBase64: String)

    fun sample(
        cr: ContentResolver,
        uri: Uri,
        fps: Double,
        maxFrames: Int,
        targetWidth: Int = 640,
        onProgress: (cur: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<Double, List<Frame>> {
        val mmr = MediaMetadataRetriever()
        cr.openFileDescriptor(uri, "r")!!.use { pfd ->
            mmr.setDataSource(pfd.fileDescriptor)
        }
        val durMs = mmr.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val durSec = durMs / 1000.0
        val stepSec = (1.0 / fps).coerceAtLeast(0.2)
        val total = (durSec / stepSec).toInt().coerceAtMost(maxFrames)
        val out = ArrayList<Frame>(total)
        for (i in 0 until total) {
            val ts = i * stepSec
            val bmp = mmr.getScaledFrameAtTime(
                (ts * 1_000_000L).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetWidth, (targetWidth * 9 / 16),
            ) ?: continue
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            bmp.recycle()
            out.add(Frame(ts, android.util.Base64.encodeToString(
                baos.toByteArray(), android.util.Base64.NO_WRAP)))
            onProgress(i + 1, total)
        }
        mmr.release()
        return durSec to out
    }
}
