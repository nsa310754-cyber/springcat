package site.ragdollp.vidai

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer

/**
 * Android 標準の MediaExtractor + MediaMuxer で指定区間を抜き出して 1 本に連結する。
 * コーデックはそのままコピー(= 再エンコード無し)なので高速で画質劣化なし。
 *
 * 制限:
 *  - カット位置はキーフレーム (sync frame) に丸められるので、指定した start より
 *    少し前のキーフレームから始まる。end も次のキーフレームまで含まれる場合あり。
 *  - 入力が複数音声トラックなどを持つ場合は 1 本目のオーディオのみ採用。
 */
object VideoCutter {

    fun cut(
        cr: ContentResolver,
        inputUri: Uri,
        segments: List<ClaudeClient.Segment>,
        outFile: File,
        onProgress: (pct: Int) -> Unit = {},
    ): File {
        val pfd: ParcelFileDescriptor = cr.openFileDescriptor(inputUri, "r")!!
        val extractor = MediaExtractor()
        extractor.setDataSource(pfd.fileDescriptor)

        // トラック選定: video と audio を 1 本ずつ
        var videoTrack = -1; var audioTrack = -1
        var videoFmt: MediaFormat? = null; var audioFmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                videoTrack < 0 && m.startsWith("video/") -> { videoTrack = i; videoFmt = f }
                audioTrack < 0 && m.startsWith("audio/") -> { audioTrack = i; audioFmt = f }
            }
        }
        if (videoTrack < 0) throw RuntimeException("動画トラックが見つかりません")

        val muxer = MediaMuxer(outFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outVideoIdx = muxer.addTrack(videoFmt!!)
        val outAudioIdx = if (audioFmt != null) muxer.addTrack(audioFmt) else -1
        muxer.start()

        val buf = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()

        // セグメントごとの出力タイムスタンプを連続化するためのオフセット
        var outTsOffsetUs = 0L

        for ((segIdx, seg) in segments.withIndex()) {
            val startUs = (seg.start * 1_000_000L).toLong()
            val endUs = (seg.end * 1_000_000L).toLong()
            val segLenUs = endUs - startUs
            if (segLenUs <= 0) continue

            // --- Video track ---
            copyRange(extractor, videoTrack, startUs, endUs, muxer, outVideoIdx, buf, info, outTsOffsetUs)
            // --- Audio track ---
            if (audioTrack >= 0) {
                copyRange(extractor, audioTrack, startUs, endUs, muxer, outAudioIdx, buf, info, outTsOffsetUs)
            }
            outTsOffsetUs += segLenUs
            onProgress(((segIdx + 1) * 100 / segments.size))
        }

        try { muxer.stop() } catch (_: Throwable) {}
        muxer.release()
        extractor.release()
        pfd.close()
        return outFile
    }

    private fun copyRange(
        extractor: MediaExtractor, srcTrack: Int,
        startUs: Long, endUs: Long,
        muxer: MediaMuxer, dstTrack: Int,
        buf: ByteBuffer, info: MediaCodec.BufferInfo,
        outTsOffsetUs: Long,
    ) {
        extractor.selectTrack(srcTrack)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val segStartSampleUs = extractor.sampleTime  // 実際の開始 (前方 keyframe)
        while (true) {
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            val sTime = extractor.sampleTime
            if (sTime > endUs) break
            info.offset = 0
            info.size = size
            info.flags = extractor.sampleFlags
            info.presentationTimeUs = (sTime - segStartSampleUs) + outTsOffsetUs
            muxer.writeSampleData(dstTrack, buf, info)
            if (!extractor.advance()) break
        }
        extractor.unselectTrack(srcTrack)
    }
}
