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
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Łączy wideo z CameraX z dźwiękiem z mikrofonu (kodowanym do AAC) w jeden plik MP4 w Galerii. */
object Muxer {
    private class Out(val uri: Uri?, val pfd: ParcelFileDescriptor, val file: File?)

    /** Nazwa pliku według chwili rozpoczęcia nagrania (a nie chwili zapisu). */
    private fun fileName(takenMs: Long, suffix: String) = "trening_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(takenMs)) + suffix + ".mp4"

    private fun openOut(ctx: Context, takenMs: Long, suffix: String): Out {
        val name = fileName(takenMs, suffix)
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TrenerNaWodzie")
                put(MediaStore.Video.Media.DATE_TAKEN, takenMs)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
                ?: throw IllegalStateException("MediaStore insert")
            val pfd = try { ctx.contentResolver.openFileDescriptor(uri, "rw") } catch (e: Exception) { null }
            if (pfd == null) { try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}; throw IllegalStateException("fd") }
            Out(uri, pfd, null)
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

    /** Czy plik wideo da się odczytać (nagranie zostało poprawnie zakończone). */
    fun isPlayable(video: File): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(video.absolutePath)
            (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        } catch (_: Throwable) { false } finally { try { ex.release() } catch (_: Throwable) {} }
    }

    fun saveVideoOnly(ctx: Context, video: File, takenMs: Long, suffix: String = ""): Uri? {
        val o = openOut(ctx, takenMs, suffix)
        try {
            FileInputStream(video).use { inp -> ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(o.pfd.fileDescriptor)).use { inp.copyTo(it, 1 shl 16) } }
            return publish(ctx, o)
        } catch (e: Throwable) { discard(ctx, o); throw e }
    }

    fun mux(ctx: Context, video: File, pcm: File, sampleRate: Int, channels: Int, offsetMs: Long, takenMs: Long, suffix: String = ""): Uri? {
        val ex = MediaExtractor()
        val aacFile = File(video.parentFile, video.nameWithoutExtension + ".aac.tmp")
        var reader: AacReader? = null
        var out: Out? = null
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(video.absolutePath)
            var vTrack = -1
            var vFmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { vTrack = i; vFmt = f; break }
            }
            if (vTrack < 0 || vFmt == null) throw IllegalStateException("brak ścieżki wideo")
            ex.selectTrack(vTrack)
            val t0 = ex.sampleTime.coerceAtLeast(0)
            val durUs = if (vFmt.containsKey(MediaFormat.KEY_DURATION)) vFmt.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE / 4

            // dźwięk kodujemy do pliku tymczasowego (nie do pamięci) – długie nagrania nie wyczerpią pamięci aplikacji
            val aFmt = if (pcm.exists() && pcm.length() > 0 && sampleRate > 0)
                encodeAac(pcm, sampleRate, channels, t0 + offsetMs * 1000, t0 + durUs, aacFile) else null

            val o = openOut(ctx, takenMs, suffix); out = o
            val m = MediaMuxer(o.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); muxer = m
            if (vFmt.containsKey(MediaFormat.KEY_ROTATION)) m.setOrientationHint(vFmt.getInteger(MediaFormat.KEY_ROTATION))
            val mv = m.addTrack(vFmt)
            val ma = if (aFmt != null) m.addTrack(aFmt) else -1
            m.start()
            if (ma >= 0) reader = AacReader(aacFile)
            val maxIn = if (vFmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = ByteBuffer.allocate(maxOf(maxIn, 8 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            var lastV = t0
            fun writeAudioUpTo(t: Long) {
                val r = reader ?: return
                while (true) {
                    val d = r.data ?: break
                    if (r.pts > t) break
                    info.set(0, d.size, r.pts, 0)
                    m.writeSampleData(ma, ByteBuffer.wrap(d), info)
                    r.advance()
                }
            }
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                writeAudioUpTo(t)
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, t, flags)
                m.writeSampleData(mv, buf, info)
                lastV = maxOf(lastV, t)
                ex.advance()
            }
            writeAudioUpTo(lastV + 100_000)
            m.stop()
            m.release(); muxer = null
            val uri = publish(ctx, o)
            out = null
            return uri
        } catch (e: Throwable) {
            try { muxer?.release() } catch (_: Throwable) {}
            out?.let { discard(ctx, it) }
            throw e
        } finally {
            try { reader?.close() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
            aacFile.delete()
        }
    }

    /** Sekwencyjny odczyt próbek AAC zapisanych przez [encodeAac]: [rozmiar:int][czas µs:long][dane]. */
    private class AacReader(f: File) {
        private val inp = DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16))
        var pts = 0L; private set
        var data: ByteArray? = null; private set
        init { advance() }
        fun advance() {
            data = try {
                val n = inp.readInt(); pts = inp.readLong()
                if (n <= 0 || n > 1 shl 20) null else ByteArray(n).also { inp.readFully(it) }
            } catch (_: Exception) { null }
        }
        fun close() { try { inp.close() } catch (_: Exception) {} }
    }

    /** Koduje PCM do AAC i zapisuje próbki z czasami (µs) dopasowanymi do wideo w pliku [dst]. Zwraca format ścieżki. */
    private fun encodeAac(pcm: File, sr: Int, ch: Int, startUs: Long, endUs: Long, dst: File): MediaFormat? {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val bytesPerFrame = 2 * ch
        var outFmt: MediaFormat? = null
        var samples = 0
        val info = MediaCodec.BufferInfo()
        val inp = BufferedInputStream(FileInputStream(pcm), 1 shl 16)
        val out = DataOutputStream(BufferedOutputStream(FileOutputStream(dst), 1 shl 16))
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            // przesunięcie ujemne: pomijamy początek dźwięku
            if (startUs < 0) {
                var toSkip = (-startUs) * sr / 1_000_000L * bytesPerFrame
                while (toSkip > 0) { val k = inp.skip(toSkip); if (k <= 0) break; toSkip -= k }
            }
            val base = maxOf(startUs, 0L)
            var framesIn = 0L
            var inputDone = false
            var outputDone = false
            val tmp = ByteArray(8192)
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
                            out.writeInt(d.size); out.writeLong(info.presentationTimeUs); out.write(d)
                            samples++
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            try { inp.close() } catch (_: Exception) {}
            try { out.close() } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
        return if (samples > 0) outFmt else null
    }
}
