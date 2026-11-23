package pl.trener.nawodzie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

private const val TAG = "TimeSync"

/** Znacznik czasu klatki z aparatu → czas zegara SystemClock.elapsedRealtimeNanos (ten sam co w GPS i czujnikach). */
fun camToElapsedNs(ts: Long): Long {
    val now = SystemClock.elapsedRealtimeNanos()
    if (abs(ts - now) < 5_000_000_000L) return ts
    val mono = System.nanoTime()
    if (abs(ts - mono) < 5_000_000_000L) return ts + (now - mono)
    return ts
}

/**
 * Czas GPS. Z każdego odczytu GPS liczymy różnicę między czasem satelitów (UTC) a zegarem telefonu
 * od uruchomienia (elapsedRealtime) – obie wartości opisują tę samą chwilę pomiaru. Różnica (mediana
 * z ostatnich odczytów) zostaje po utracie sygnału, bo zegar telefonu dryfuje tylko o ułamki ms na minutę.
 */
object GpsClock : LocationListener {
    private val offs = ArrayDeque<Double>()
    @Volatile var offsetMs = Double.NaN; private set
    @Volatile var fixes = 0; private set
    private var lm: LocationManager? = null

    val gps: Boolean get() = !offsetMs.isNaN()

    fun start(ctx: Context) {
        if (lm != null) return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val m = ctx.getSystemService(LocationManager::class.java) ?: return
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            lm = m
        } catch (e: Exception) { Log.w(TAG, "gps", e) }
    }

    fun stop() {
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        lm = null
    }

    override fun onLocationChanged(l: Location) {
        if (l.provider != LocationManager.GPS_PROVIDER || l.time <= 0L) return
        val o = l.time - l.elapsedRealtimeNanos / 1e6
        // odrzuć odczyty sprzeczne z zegarem telefonu o więcej niż 10 min (błąd odbiornika)
        val sys = (System.currentTimeMillis() - SystemClock.elapsedRealtime()).toDouble()
        if (abs(o - sys) > 600_000.0) return
        synchronized(offs) {
            offs.addLast(o)
            while (offs.size > 15) offs.removeFirst()
            val s = offs.sorted()
            offsetMs = s[s.size / 2]
        }
        fixes++
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("API < 29") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    /** UTC (ms) chwili o podanym czasie elapsedRealtime (ns): z GPS albo, bez niego, z zegara systemu. */
    fun utcMs(elapsedNs: Long): Long {
        val o = offsetMs
        return if (!o.isNaN()) Math.round(elapsedNs / 1e6 + o)
        else elapsedNs / 1_000_000 + (System.currentTimeMillis() - SystemClock.elapsedRealtime())
    }

    fun nowUtc(): Long = utcMs(SystemClock.elapsedRealtimeNanos())
}

/** Odliczanie ostatniej minuty przed startem (wciśnięte na sygnał 1 minuty) i czas od startu przez 15 minut. */
object LastMinute {
    const val HOLD_MS = 60 * 60_000L      // czas od startu pokazywany przez godzinę
    @Volatile var startUtc = 0L; private set      // 0 – wyłączone
    @Volatile var pressedUtc = 0L; private set
    /** Synchronizacje na sygnał 30 s / 15 s: (czas UTC wciśnięcia, sekund do startu). */
    val syncs = CopyOnWriteArrayList<Pair<Long, Int>>()
    var onChange: (() -> Unit)? = null

    fun press() {
        pressedUtc = GpsClock.nowUtc(); startUtc = pressedUtc + 60_000L; syncs.clear(); onChange?.invoke()
    }
    /** Sygnał „30 s” albo „15 s” do startu: start = teraz + [sec]. */
    fun sync(sec: Int) {
        if (startUtc == 0L) return
        val now = GpsClock.nowUtc()
        startUtc = now + sec * 1000L; syncs.add(now to sec); onChange?.invoke()
    }
    fun cancel() { startUtc = 0L; syncs.clear(); onChange?.invoke() }

    /** Czy coś jest pokazywane w chwili [utc]. */
    fun shownAt(utc: Long): Boolean { val s = startUtc; return s != 0L && utc - s <= HOLD_MS }

    /** „0:42” przed startem, „0:09.37” w ostatnich 10 s, „+0:03.21” w pierwszych 10 s po starcie, potem „+0:15”; null gdy nic do pokazania. */
    fun text(utc: Long): String? {
        val s = startUtc
        if (s == 0L) return null
        val d = s - utc
        if (-d > HOLD_MS) return null
        if (d <= 0) {
            val a = -d
            if (a < 10_000) { val cs = a / 10; return "+0:0" + (cs / 100) + "." + two((cs % 100).toInt()) }
            return "+" + mmss(a / 1000)
        }
        if (d < 10_000) { val cs = d / 10; return "0:0" + (cs / 100) + "." + two((cs % 100).toInt()) }
        return mmss((d + 999) / 1000)   // pełne sekundy zaokrąglone w górę: 0:11 → 0:10 → 0:09.99
    }
    private fun two(v: Int) = if (v < 10) "0$v" else v.toString()
    private fun mmss(sec: Long) = (sec / 60).toString() + ":" + two((sec % 60).toInt())
}

/**
 * Napisy wpalane w obraz: czas (lewy górny róg) i odliczanie (prawy górny róg).
 * Bitmapy mają stały rozmiar i są rysowane na nowo co klatkę – bez tworzenia nowych obiektów.
 * Każdy wątek rysujący ma własny egzemplarz.
 */
class Stamp {
    companion object { const val GREEN = 0xFF2E7D4F.toInt() }
    private val tP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL); textSize = 44f; color = Color.WHITE }
    private val tagP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 26f }
    private val bigP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 96f; color = Color.WHITE }
    private val smallP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 56f; color = Color.WHITE }
    private val labP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 28f; color = 0xE6FFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val bgP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private val tW = tP.measureText("00:00:00.000")
    private var tzMin = -1L; private var tzOff = 0L
    private val tChars = "00:00:00.000".toCharArray()
    val timeBmp: Bitmap = Bitmap.createBitmap((14 + tW + 12 + tagP.measureText("GPS") + 14).toInt(), 64, Bitmap.Config.ARGB_8888)
    private val tC = Canvas(timeBmp)
    private val bigW = bigP.measureText("+00:00"); private val smW = smallP.measureText(".00")
    val cdBmp: Bitmap = Bitmap.createBitmap((28 + bigW + smW + 28).toInt(), 150, Bitmap.Config.ARGB_8888)
    private val cC = Canvas(cdBmp)

    val timeAspect get() = timeBmp.width.toFloat() / timeBmp.height
    val cdAspect get() = cdBmp.width.toFloat() / cdBmp.height

    fun drawTime(utc: Long, gps: Boolean) {
        timeBmp.eraseColor(Color.TRANSPARENT)
        bgP.color = 0x8C000000.toInt()
        r.set(0f, 0f, timeBmp.width.toFloat(), timeBmp.height.toFloat())
        tC.drawRoundRect(r, 10f, 10f, bgP)
        if (utc / 60_000 != tzMin) { tzMin = utc / 60_000; tzOff = TimeZone.getDefault().getOffset(utc).toLong() }
        val local = utc + tzOff
        val ms = Math.floorMod(local, 86_400_000L)
        val c = tChars; var v = ms
        val msPart = (v % 1000).toInt(); v /= 1000
        val sec = (v % 60).toInt(); v /= 60
        val min = (v % 60).toInt(); val hr = (v / 60).toInt()
        c[0] = '0' + hr / 10; c[1] = '0' + hr % 10; c[3] = '0' + min / 10; c[4] = '0' + min % 10
        c[6] = '0' + sec / 10; c[7] = '0' + sec % 10
        c[9] = '0' + msPart / 100; c[10] = '0' + (msPart / 10) % 10; c[11] = '0' + msPart % 10
        tC.drawText(c, 0, 12, 14f, 47f, tP)
        tagP.color = if (gps) 0xFF9FE3A8.toInt() else 0xFFFFD60A.toInt()
        tC.drawText(if (gps) "GPS" else "SYS", 14f + tW + 12f, 45f, tagP)
    }

    fun drawCountdown(text: String) {
        cdBmp.eraseColor(Color.TRANSPARENT)
        bgP.color = if (text.startsWith("+")) GREEN else 0xFFA2543F.toInt()   // ceglasty przed startem, zielony po
        r.set(0f, 0f, cdBmp.width.toFloat(), cdBmp.height.toFloat())
        cC.drawRoundRect(r, 16f, 16f, bgP)
        val dot = text.indexOf('.')
        val main = if (dot >= 0) text.substring(0, dot) else text
        val frac = if (dot >= 0) text.substring(dot) else ""
        val w = bigP.measureText(main) + smallP.measureText(frac)
        val x = (cdBmp.width - w) / 2
        cC.drawText(main, x, 98f, bigP)
        cC.drawText(frac, x + bigP.measureText(main), 98f, smallP)
        cC.drawText(if (text.startsWith("+")) "po starcie" else "do startu", cdBmp.width / 2f, 136f, labP)
    }
}

/** Znaczniki klatek oddanych do nagrania: czas prezentacji (trafia do pliku) i czas zrobienia klatki (elapsedRealtime). */
object FrameLog {
    private var pts = LongArray(8192); private var cap = LongArray(8192); private var n = 0
    @Volatile var on = false; private set
    private const val MAX = 400_000

    @Synchronized fun start() { n = 0; on = true }
    @Synchronized fun add(p: Long, c: Long) {
        if (!on || n >= MAX) return
        if (n > 0 && pts[n - 1] == p) return
        if (n == pts.size) { pts = pts.copyOf(n * 2); cap = cap.copyOf(n * 2) }
        pts[n] = p; cap[n] = c; n++
    }
    @Synchronized fun stop(): Pair<LongArray, LongArray> { on = false; return pts.copyOf(n) to cap.copyOf(n) }
}

/** Wszystko, co trzeba wiedzieć o czasie jednego nagrania; zapisywane po zakończeniu, liczone przy zapisie pliku. */
class RecTime(
    val logPts: LongArray, val logCap: LongArray,
    val startHintNs: Long,           // elapsedRealtime chwili, gdy nagrywanie się zaczęło
    val delayMs: Int,                // opóźnienie obrazu (wyprzedzenie stabilizacji)
    val lmPressed: Long, val lmStart: Long, val lmSyncs: List<Pair<Long, Int>>,
    val stab: String,
) {
    var firstUtc = 0L; private set
    var json: String? = null; private set

    /** Pierwsze znaczniki klatek w pliku (µs, rosnąco) i czas trwania; ustala czas pierwszej klatki i tekst JSON. */
    fun build(fileName: String, filePts: LongArray, durUs: Long, frames: Int) {
        val k = match(filePts)
        val gps = GpsClock.gps
        val capNs = when {
            k != null -> logCap[k]
            logCap.isNotEmpty() -> logCap.firstOrNull { it >= startHintNs - delayMs * 1_000_000L } ?: logCap[0]
            else -> startHintNs - delayMs * 1_000_000L
        }
        firstUtc = GpsClock.utcMs(capNs)
        val fps = if (filePts.size >= 2 && filePts.last() > filePts[0]) (filePts.size - 1) * 1e6 / (filePts.last() - filePts[0]) else 0.0
        val o = JSONObject()
        o.put("format", "trener-na-wodzie/time/1")
        o.put("app_version", BuildConfig.VERSION_NAME)
        o.put("file", fileName)
        o.put("first_frame_utc", iso(firstUtc))
        o.put("first_frame_unix_ms", firstUtc)
        o.put("time_source", if (gps) "gps" else "system")
        o.put("first_frame_match", if (k != null) "frames" else "approx")
        o.put("fps", Math.round(fps * 100) / 100.0)
        o.put("frames", frames)
        o.put("duration_s", Math.round(durUs / 1000.0) / 1000.0)
        o.put("utc_offset_min", TimeZone.getDefault().getOffset(firstUtc) / 60_000)
        o.put("stabilization", stab)
        if (lmStart != 0L) o.put("last_minute", JSONObject().apply {
            put("pressed_utc", iso(lmPressed))
            put("syncs", JSONArray().apply { lmSyncs.forEach { (t, sec) -> put(JSONObject().put("at_utc", iso(t)).put("to_start_s", sec)) } })
            put("start_utc", iso(lmStart))
            put("start_unix_ms", lmStart)
        })
        json = o.toString(2)
    }

    /** Dopasowuje odstępy między klatkami w pliku do zapisanych znaczników; zwraca indeks pierwszej klatki pliku. */
    private fun match(filePts: LongArray): Int? {
        val m = minOf(filePts.size, 90)
        if (m < 5 || logPts.size < m) return null
        val rel = LongArray(m) { (filePts[it] - filePts[0]) * 1000 }
        val errs = LongArray(logPts.size) { Long.MAX_VALUE }
        var best = Long.MAX_VALUE
        for (k in logPts.indices) {
            if (logPts[k] - logPts[0] > 8_000_000_000L) break
            if (logPts[k] + rel[m - 1] > logPts.last() + 20_000_000L) break
            var e = 0L
            for (i in 1 until m) {
                val t = logPts[k] + rel[i]
                var j = java.util.Arrays.binarySearch(logPts, t)
                if (j < 0) {
                    val ins = -j - 1
                    j = if (ins >= logPts.size) logPts.size - 1 else if (ins == 0) 0 else if (t - logPts[ins - 1] < logPts[ins] - t) ins - 1 else ins
                }
                e += abs(logPts[j] - t)
                if (e > best + m * 300_000L) break
            }
            errs[k] = e
            if (e < best) best = e
        }
        if (best == Long.MAX_VALUE || best / (m - 1) > 3_000_000L) return null
        // przy bardzo równych odstępach kilka miejsc pasuje tak samo – wybierz najbliższe chwili startu nagrania
        var pick = -1; var dist = Long.MAX_VALUE
        for (k in errs.indices) if (errs[k] <= best + (m - 1) * 2_000L) {
            val d = abs(logCap[k] - (startHintNs - delayMs * 1_000_000L))
            if (d < dist) { dist = d; pick = k }
        }
        return if (pick >= 0) pick else null
    }

    companion object {
        fun iso(utc: Long): String {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(java.util.Date(utc))
        }

        /** Pierwsze znaczniki klatek pliku (µs, rosnąco), czas trwania (µs) i liczba klatek. */
        fun filePts(video: File): Triple<LongArray, Long, Int> {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(video.absolutePath)
                var tr = -1; var dur = 0L
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        tr = i; if (f.containsKey(MediaFormat.KEY_DURATION)) dur = f.getLong(MediaFormat.KEY_DURATION); break
                    }
                }
                if (tr < 0) return Triple(LongArray(0), 0L, 0)
                ex.selectTrack(tr)
                val first = ArrayList<Long>(160); var n = 0
                while (true) {
                    val t = ex.sampleTime
                    if (t < 0) break
                    if (first.size < 160) first.add(t)
                    n++
                    if (!ex.advance()) break
                }
                return Triple(first.sorted().take(120).toLongArray(), dur, n)
            } finally { try { ex.release() } catch (_: Throwable) {} }
        }
    }
}

/**
 * Metadane w pliku MP4: czas utworzenia (mvhd, pełne sekundy UTC) = czas pierwszej klatki
 * oraz na końcu pliku dodatkowy blok 'uuid' z tekstem JSON (odtwarzacze go pomijają).
 * Typ bloku: 'uuid', identyfikator 16 bajtów ASCII „TrenerNaWodzie01”, dalej JSON w UTF-8.
 */
object Mp4Stamp {
    private val UUID = "TrenerNaWodzie01".toByteArray(Charsets.US_ASCII)
    private const val EPOCH_1904_S = 2_082_844_800L

    fun apply(fd: FileDescriptor, utcMs: Long, json: String?) {
        val inS = FileInputStream(fd); val outS = FileOutputStream(fd)
        val rc = inS.channel; val wc = outS.channel
        val size = rc.size()
        val h = ByteBuffer.allocate(16)
        fun header(pos: Long): Triple<Long, String, Int>? {
            h.clear(); h.limit(8)
            if (rc.read(h, pos) < 8) return null
            h.flip()
            var sz = h.int.toLong() and 0xFFFFFFFFL
            val type = String(byteArrayOf(h.get(), h.get(), h.get(), h.get()), Charsets.US_ASCII)
            var hl = 8
            if (sz == 1L) { h.clear(); h.limit(8); rc.read(h, pos + 8); h.flip(); sz = h.long; hl = 16 }
            else if (sz == 0L) sz = size - pos
            if (sz < hl) return null
            return Triple(sz, type, hl)
        }
        val secs = utcMs / 1000 + EPOCH_1904_S
        var pos = 0L
        while (pos + 8 <= size) {
            val (sz, type, hl) = header(pos) ?: break
            if (type == "moov") {
                var c = pos + hl
                while (c + 8 <= pos + sz) {
                    val (csz, ctype, _) = header(c) ?: break
                    if (ctype == "mvhd") {
                        h.clear(); h.limit(1); rc.read(h, c + 8); h.flip()
                        val v = h.get().toInt()
                        val b = ByteBuffer.allocate(16)
                        if (v == 1) { b.putLong(secs); b.putLong(secs); b.flip(); wc.write(b, c + 12) }
                        else { b.putInt(secs.toInt()); b.putInt(secs.toInt()); b.flip(); b.limit(8); wc.write(b, c + 12) }
                    }
                    c += csz
                }
            }
            pos += sz
        }
        if (json != null) {
            val body = json.toByteArray(Charsets.UTF_8)
            val box = ByteBuffer.allocate(8 + 16 + body.size)
            box.putInt(box.capacity()); box.put("uuid".toByteArray(Charsets.US_ASCII)); box.put(UUID); box.put(body); box.flip()
            wc.write(box, size)
        }
        wc.force(true)
    }
}
