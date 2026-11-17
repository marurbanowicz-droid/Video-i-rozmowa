package pl.trener.nawodzie

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notatka głosowa: nagrywa dźwięk z tego samego mikrofonu, który obsługuje rozmowę i nagrywanie wideo
 * (więc działa równolegle z nimi), na czas notatki wstrzymuje wysyłanie głosu do zawodników,
 * zapisuje plik .m4a oraz obok niego .txt z transkrypcją.
 * Pliki trafiają do: Dokumenty / TrenerNaWodzie / Notatki.
 */
object VoiceNote {
    @Volatile var recording = false; private set
    @Volatile var startedAt = 0L; private set
    @Volatile var processing = false; private set

    private val lock = Any()
    private var out: OutputStream? = null
    private var pcm: File? = null
    private var rate = 0
    private var channels = 1
    private var takenMs = 0L

    fun toggle(ctx: Context) { if (recording) stop(ctx) else start(ctx) }

    fun start(ctx: Context) {
        if (recording) return
        if (Engine.role == Engine.Role.NONE) { Notifier.toast("Notatkę można nagrać w trakcie sesji"); return }
        val app = ctx.applicationContext
        takenMs = System.currentTimeMillis()
        val f = File(app.filesDir, "note_$takenMs.pcm")
        try { synchronized(lock) { out = BufferedOutputStream(FileOutputStream(f), 1 shl 16) } } catch (e: Exception) { Notifier.toast("Nie można zacząć notatki: ${e.message}"); return }
        pcm = f; rate = 0; channels = 1
        Engine.noteSink = { b, r, c ->
            synchronized(lock) {
                if (rate == 0) { rate = r; channels = c }
                try { out?.write(b) } catch (_: Exception) {}
            }
        }
        Engine.setNoteMute(true)
        recording = true; startedAt = takenMs
        Notifier.beep("ptt")
        Notifier.toast("Notatka: nagrywam. Zawodnicy w tym czasie Cię nie słyszą.")
    }

    fun stop(ctx: Context) {
        if (!recording) return
        recording = false
        Engine.noteSink = null
        Engine.setNoteMute(false)
        Notifier.beep("ptt_off")
        val f = pcm; var r = 0; var c = 1
        synchronized(lock) { try { out?.flush(); out?.close() } catch (_: Exception) {}; out = null; r = rate; c = channels }
        val rr = r; val cc = c
        pcm = null
        val app = ctx.applicationContext
        if (f == null || rr <= 0 || f.length() < rr * 2L * cc / 5) { f?.delete(); Notifier.toast("Notatka jest pusta – nie zapisano"); return }
        processing = true
        Thread { try { process(app, f, rr, cc, takenMs) } catch (e: Throwable) { Notifier.toast("Notatka: błąd zapisu (${e.message})") } finally { processing = false; f.delete() } }.start()
    }

    private fun baseName(t: Long) = "Notatka_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(t))

    private fun process(ctx: Context, pcm: File, rate: Int, ch: Int, t: Long) {
        val name = baseName(t)
        val tmp = File(ctx.cacheDir, "$name.m4a")
        try {
            encodeM4a(pcm, rate, ch, tmp)
            val where = saveFile(ctx, "$name.m4a", "audio/mp4", tmp, audio = true)
            if (where == null) { Notifier.toast("Nie udało się zapisać notatki"); return }
            Notifier.toast("Notatka zapisana (Dokumenty/TrenerNaWodzie/Notatki)")
            if (!Prefs.noteTranscribe) return
            Notifier.toast("Rozpoznaję mowę…")
            val res = Transcriber.run(ctx, pcm, rate, ch, tmp)
            if (res.text.isNullOrBlank()) { Notifier.toast("Bez transkrypcji: " + (res.error ?: "nie rozpoznano mowy")); return }
            val head = "Notatka głosowa – " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(t)) + "\n\n"
            val txt = File(ctx.cacheDir, "$name.txt"); txt.writeText(head + res.text.trim() + "\n")
            saveFile(ctx, "$name.txt", "text/plain", txt, audio = false)
            txt.delete()
            Notifier.toast("Transkrypcja zapisana: " + res.text.trim().take(90) + if (res.text.length > 90) "…" else "")
        } finally { tmp.delete() }
    }

    /** Zapisuje plik w „Dokumenty/TrenerNaWodzie/Notatki”; gdy system nie pozwala na dźwięk w Dokumentach – w Nagraniach. */
    private fun saveFile(ctx: Context, name: String, mime: String, src: File, audio: Boolean): Uri? {
        val rel = "Documents/TrenerNaWodzie/Notatki"
        if (Build.VERSION.SDK_INT >= 29) {
            val attempts = buildList {
                add(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to rel)
                if (audio) add(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    (if (Build.VERSION.SDK_INT >= 31) "Recordings/TrenerNaWodzie" else "Music/TrenerNaWodzie"))
            }
            for ((coll, path) in attempts) {
                var uri: Uri? = null
                try {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, path); put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    uri = ctx.contentResolver.insert(coll, cv) ?: continue
                    ctx.contentResolver.openOutputStream(uri)!!.use { o -> FileInputStream(src).use { it.copyTo(o) } }
                    ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    return uri
                } catch (_: Exception) { uri?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {} } }
            }
            return null
        }
        return try {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "TrenerNaWodzie/Notatki").apply { mkdirs() }
            val f = File(dir, name); src.copyTo(f, true)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime), null)
            Uri.fromFile(f)
        } catch (_: Exception) { null }
    }

    /** PCM 16-bit → AAC w kontenerze .m4a (64 kb/s – wystarczy dla mowy). */
    private fun encodeM4a(pcm: File, sr: Int, ch: Int, dst: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000 * ch)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val inp = BufferedInputStream(FileInputStream(pcm), 1 shl 16)
        val info = MediaCodec.BufferInfo()
        val bpf = 2 * ch
        var track = -1; var started = false
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            var frames = 0L; var inDone = false; var outDone = false
            val tmp = ByteArray(8192)
            while (!outDone) {
                if (!inDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = codec.getInputBuffer(idx)!!; ib.clear()
                        val want = minOf(ib.remaining(), tmp.size) / bpf * bpf
                        var n = 0
                        while (n < want) { val r = inp.read(tmp, n, want - n); if (r <= 0) break; n += r }
                        n = n / bpf * bpf
                        val pts = frames * 1_000_000L / sr
                        if (n <= 0) { codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { ib.put(tmp, 0, n); codec.queueInputBuffer(idx, 0, n, pts, 0); frames += n / bpf }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                    o >= 0 -> {
                        val ob = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && started) {
                            ob.position(info.offset); ob.limit(info.offset + info.size); muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally {
            try { inp.close() } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { if (started) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }
}
