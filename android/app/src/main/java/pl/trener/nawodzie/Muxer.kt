package pl.trener.nawodzie

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Łączy wideo z CameraX z dźwiękiem z mikrofonu (kodowanym do AAC) w jeden plik MP4 w Galerii. */
object Muxer {
    private class Out(val uri: Uri?, val pfd: ParcelFileDescriptor, val file: File?)
    private class AacSample(val data: ByteArray, val pts: Long, val flags: Int)

    private fun fileName() = "trening_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".mp4"

    private fun openOut(ctx: Context, name: String): Out {
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TrenerNaWodzie")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
                ?: throw IllegalStateException("MediaStore insert")
            Out(uri, ctx.contentResolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("fd"), null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TrenerNaWodzie").apply { mkdirs() }
            val f = File(dir, name)
            Out(null, ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE), f)
        }
    }

    private fun publish(ctx: Context, o: Out): Uri? {
        o.pfd.close()
        return if (o.uri != null) {
            ctx.contentResolver.update(o.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            o.uri
        } else {
            MediaScannerConnection.scanFile(ctx, arrayOf(o.file!!.absolutePath), arrayOf("video/mp4"), null)
            Uri.fromFile(o.file)
        }
    }

    private fun discard(ctx: Context, o: Out) {
        try { o.pfd.close() } catch (_: Exception) {}
        if (o.uri != null) try { ctx.contentResolver.delete(o.uri, null, null) } catch (_: Exception) {}
        o.file?.delete()
    }

    fun saveVideoOnly(ctx: Context, video: File): Uri? {
        val o = openOut(ctx, fileName())
        try {
            FileInputStream(video).use { inp -> ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(o.pfd.fileDescriptor)).use { inp.copyTo(it) } }
            return publish(ctx, o)
        } catch (e: Exception) { discard(ctx, o); throw e }
    }

    fun mux(ctx: Context, video: File, pcm: File, sampleRate: Int, channels: Int, offsetMs: Long): Uri? {
        val ex = MediaExtractor()
        ex.setDataSource(video.absolutePath)
        var vTrack = -1
        var vFmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { vTrack = i; vFmt = f; break }
        }
        if (vTrack < 0 || vFmt == null) { ex.release(); throw IllegalStateException("brak ścieżki wideo") }
        ex.selectTrack(vTrack)
        val t0 = ex.sampleTime.coerceAtLeast(0)
        val durUs = if (vFmt.containsKey(MediaFormat.KEY_DURATION)) vFmt.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE

        val audio = if (pcm.exists() && pcm.length() > 0 && sampleRate > 0) encodeAac(pcm, sampleRate, channels, t0 + offsetMs * 1000, t0 + durUs) else null

        val o = openOut(ctx, fileName())
        val muxer = MediaMuxer(o.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            if (vFmt.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(vFmt.getInteger(MediaFormat.KEY_ROTATION))
            val mv = muxer.addTrack(vFmt)
            val ma = audio?.let { muxer.addTrack(it.first) } ?: -1
            muxer.start()
            val maxIn = if (vFmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = ByteBuffer.allocate(maxOf(maxIn, 8 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            val samples = audio?.second ?: emptyList()
            var ai = 0
            var lastV = t0
            fun writeAudioUpTo(t: Long) {
                while (ai < samples.size && samples[ai].pts <= t) {
                    val s = samples[ai++]
                    info.set(0, s.data.size, s.pts, s.flags)
                    muxer.writeSampleData(ma, ByteBuffer.wrap(s.data), info)
                }
            }
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (ma >= 0) writeAudioUpTo(t)
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, t, flags)
                muxer.writeSampleData(mv, buf, info)
                lastV = maxOf(lastV, t)
                ex.advance()
            }
            if (ma >= 0) writeAudioUpTo(lastV + 100_000)
            muxer.stop()
            muxer.release()
            ex.release()
            return publish(ctx, o)
        } catch (e: Exception) {
            try { muxer.release() } catch (_: Exception) {}
            ex.release(); discard(ctx, o)
            throw e
        }
    }

    /** Koduje PCM do AAC. Zwraca format ścieżki i listę próbek z czasami (µs) dopasowanymi do wideo. */
    private fun encodeAac(pcm: File, sr: Int, ch: Int, startUs: Long, endUs: Long): Pair<MediaFormat, List<AacSample>>? {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val bytesPerFrame = 2 * ch
        val out = ArrayList<AacSample>()
        var outFmt: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        val inp = BufferedInputStream(FileInputStream(pcm), 1 shl 16)
        // przesunięcie ujemne: pomijamy początek dźwięku
        var framesIn = 0L
        if (startUs < 0) {
            val skipFrames = (-startUs) * sr / 1_000_000L
            inp.skip(skipFrames * bytesPerFrame); framesIn = 0
        }
        val base = maxOf(startUs, 0L)
        var inputDone = false
        var outputDone = false
        val tmp = ByteArray(8192)
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = codec.getInputBuffer(idx)!!
                        ib.clear()
                        val want = minOf(ib.remaining(), tmp.size) / bytesPerFrame * bytesPerFrame
                        var n = 0
                        while (n < want) { val r = inp.read(tmp, n, want - n); if (r <= 0) break; n += r }
                        n = n / bytesPerFrame * bytesPerFrame
                        val pts = base + framesIn * 1_000_000L / sr
                        if (n <= 0 || pts > endUs) {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            ib.put(tmp, 0, n)
                            codec.queueInputBuffer(idx, 0, n, pts, 0)
                            framesIn += n / bytesPerFrame
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFmt = codec.outputFormat
                    o >= 0 -> {
                        val ob = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val d = ByteArray(info.size); ob.position(info.offset); ob.get(d, 0, info.size)
                            out.add(AacSample(d, info.presentationTimeUs, 0))
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            inp.close(); codec.stop(); codec.release()
        }
        val f = outFmt ?: return null
        return f to out
    }
}
