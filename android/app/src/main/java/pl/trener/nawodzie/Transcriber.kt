package pl.trener.nawodzie

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Zamiana nagranej notatki na tekst (po polsku).
 * 1) Jeśli w ustawieniach jest klucz OpenAI – model Whisper (dokładny, wymaga internetu, płatny groszowo).
 * 2) W przeciwnym razie (lub gdy Whisper zawiedzie) – rozpoznawanie mowy w telefonie (Android 13+), nagranie dzielone na krótkie fragmenty.
 */
object Transcriber {
    class Result(val text: String?, val error: String?)

    fun run(ctx: Context, pcm: File, rate: Int, ch: Int, m4a: File): Result {
        var err: String? = null
        val key = Prefs.openaiKey
        if (key.isNotEmpty()) {
            try { return Result(whisper(key, m4a), null) } catch (e: Exception) { err = "Whisper: ${e.message}" }
        }
        val dev = try { onDevice(ctx, pcm, rate, ch) } catch (e: Throwable) { Result(null, "rozpoznawanie w telefonie: ${e.message}") }
        if (!dev.text.isNullOrBlank()) return dev
        return Result(null, listOfNotNull(err, dev.error).joinToString("; ").ifEmpty { null })
    }

    // ---------------- Whisper (OpenAI) ----------------
    private fun whisper(key: String, file: File): String {
        val client = OkHttpClient.Builder().callTimeout(180, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1").addFormDataPart("language", "pl").addFormDataPart("response_format", "json")
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType())).build()
        val req = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions").header("Authorization", "Bearer $key").post(body).build()
        client.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val msg = try { JSONObject(txt).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IllegalStateException(if (r.code == 401) "nieprawidłowy klucz OpenAI" else "błąd ${r.code}" + (msg?.let { " – $it" } ?: ""))
            }
            return JSONObject(txt).optString("text").trim()
        }
    }

    // ---------------- rozpoznawanie w telefonie ----------------
    private fun onDevice(ctx: Context, pcm: File, rate: Int, ch: Int): Result {
        if (Build.VERSION.SDK_INT < 33) return Result(null, "rozpoznawanie z pliku w telefonie wymaga Androida 13 – wpisz klucz OpenAI w ustawieniach")
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return Result(null, "w telefonie nie ma usługi rozpoznawania mowy")
        val samples = to16k(pcm, rate, ch)
        if (samples.isEmpty()) return Result(null, "pusty dźwięk")
        val parts = split(samples)
        val sb = StringBuilder()
        var firstErr: String? = null
        var offline = true          // najpierw tryb offline; przy braku polskiego pakietu – zwykła usługa mowy (z internetem)
        for ((i, seg) in parts.withIndex()) {
            val f = File(ctx.cacheDir, "stt_$i.pcm")
            try {
                f.outputStream().use { o -> val b = java.nio.ByteBuffer.allocate(seg.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN); seg.forEach { b.putShort(it) }; o.write(b.array()) }
                var r = recognize(ctx, f, offline)
                if (offline && r.third in LANG_ERRORS) {
                    if (i == 0) requestPlPack(ctx)
                    offline = false
                    r = recognize(ctx, f, false)
                }
                if (r.first != null) { if (sb.isNotEmpty()) sb.append(' '); sb.append(r.first) }
                else if (r.second != null && firstErr == null) firstErr = r.second
            } finally { f.delete() }
        }
        return if (sb.isNotBlank()) Result(sb.toString().trim(), null) else Result(null, firstErr ?: "nie rozpoznano mowy")
    }

    private val LANG_ERRORS = setOf(12, 13)   // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE

    /** Prosi telefon o pobranie polskiego pakietu mowy do rozpoznawania offline (przyda się następnym razem). */
    private fun requestPlPack(ctx: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        onMain {
            try {
                if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return@onMain
                val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
                sr.triggerModelDownload(i)
                mainHandler.postDelayed({ try { sr.destroy() } catch (_: Throwable) {} }, 60_000)
            } catch (_: Throwable) {}
        }
    }

    /** Miesza do mono i zmniejsza próbkowanie do 16 kHz (interpolacja liniowa). */
    private fun to16k(pcm: File, rate: Int, ch: Int): ShortArray {
        val bytes = FileInputStream(pcm).use { BufferedInputStream(it).readBytes() }
        val frames = bytes.size / (2 * ch)
        val mono = ShortArray(frames)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) { var a = 0; for (c in 0 until ch) a += bb.getShort((i * ch + c) * 2).toInt(); mono[i] = (a / ch).toShort() }
        if (rate == 16000) return mono
        val n = (frames.toLong() * 16000 / rate).toInt()
        val out = ShortArray(n)
        val step = rate / 16000.0
        for (i in 0 until n) {
            val pos = i * step; val i0 = pos.toInt().coerceAtMost(frames - 1); val i1 = (i0 + 1).coerceAtMost(frames - 1); val fr = pos - i0
            out[i] = (mono[i0] * (1 - fr) + mono[i1] * fr).toInt().toShort()
        }
        return out
    }

    /** Dzieli na fragmenty do ~25 s, tnąc w najcichszym miejscu (rozpoznawanie kończy się na pauzie, więc długi ciąg byłby ucięty). */
    private fun split(s: ShortArray): List<ShortArray> {
        val max = 16000 * 25; val minLen = 16000 * 12; val win = 3200
        val res = ArrayList<ShortArray>()
        var start = 0
        while (start < s.size) {
            var end = minOf(start + max, s.size)
            if (end < s.size) {
                var best = end; var bestE = Long.MAX_VALUE
                var p = start + minLen
                while (p + win <= end) { var e = 0L; for (k in p until p + win step 8) e += abs(s[k].toInt()); if (e < bestE) { bestE = e; best = p + win / 2 }; p += win / 2 }
                end = best
            }
            res.add(s.copyOfRange(start, end)); start = end
        }
        return res
    }

    /** Rozpoznaje jeden fragment (pliku PCM 16 kHz mono). Zwraca tekst albo opis błędu. */
    private fun recognize(ctx: Context, raw: File, offline: Boolean): Triple<String?, String?, Int> {
        var text: String? = null; var err: String? = null; var code = 0
        val done = CountDownLatch(1)
        var rec: SpeechRecognizer? = null
        var pfd: ParcelFileDescriptor? = null
        onMain {
            try {
                val sr = if (offline && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                         else SpeechRecognizer.createSpeechRecognizer(ctx)
                rec = sr
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(b: Bundle?) {}
                    override fun onEvent(t: Int, b: Bundle?) {}
                    override fun onResults(b: Bundle?) { text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); done.countDown() }
                    override fun onError(e: Int) {
                        code = e
                        err = when (e) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null   // fragment bez mowy
                            12, 13 -> "telefon nie ma polskiego rozpoznawania mowy – pobierz pakiet w Ustawienia → Zarządzanie ogólne → Język → Zamiana głosu na tekst, albo wpisz klucz OpenAI w ustawieniach aplikacji"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "brak internetu dla rozpoznawania mowy"
                            else -> "rozpoznawanie mowy zwróciło błąd $e"
                        }
                        done.countDown()
                    }
                })
                pfd = ParcelFileDescriptor.open(raw, ParcelFileDescriptor.MODE_READ_ONLY)
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                if (Build.VERSION.SDK_INT >= 33) {
                    i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                    i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                }
                sr.startListening(i)
            } catch (e: Throwable) { err = "rozpoznawanie mowy: ${e.message}"; done.countDown() }
        }
        if (!done.await(60, TimeUnit.SECONDS)) err = err ?: "rozpoznawanie mowy nie odpowiada"
        onMain { try { rec?.cancel(); rec?.destroy() } catch (_: Throwable) {}; try { pfd?.close() } catch (_: Throwable) {} }
        return Triple(text, err, code)
    }
}
