package pl.trener.nawodzie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Stabilizacja obrazu liczona przez aplikację (tryby SUPER i SUPER+H).
 *
 *  - [Motion]: orientacja telefonu z żyroskopu (200 Hz) korygowana powoli grawitacją z akcelerometru.
 *  - [FrameMeta]: dla każdej klatki czas naświetlania, czas odczytu matrycy i rzeczywisty zoom (z aparatu).
 *  - [StabProcessor]: efekt CameraX w OpenGL – wirtualna kamera z dużym zapasem kadru (obiektyw szeroki),
 *    obrót 3D osobno dla 9 pasów klatki (korekta „galaretki”), tor wygładzany z wyprzedzeniem w nagraniu.
 *  - [StabLog]: opcjonalny zapis danych do kalibracji.
 */

// ======================================================================================
// Orientacja telefonu (wspólna dla podglądu i nagrania)
// ======================================================================================
object Motion : SensorEventListener {
    private const val N = 4096                 // kilka sekund historii także przy 500 Hz
    private const val TAG = "Motion"
    private val lock = Any()
    private var users = 0
    private var thread: HandlerThread? = null
    private var sm: SensorManager? = null

    // kwaternion urządzenie → świat (oś z świata skierowana w górę)
    private var qw = 1.0; private var qx = 0.0; private var qy = 0.0; private var qz = 0.0
    private var ready = false
    private var lastTs = 0L
    private var startTs = 0L
    private val acc = DoubleArray(3); private var hasAcc = false
    private val th = DoubleArray(3)            // scałkowany surowy żyroskop (tryb Sport)

    private val hTs = LongArray(N); private val hQ = Array(N) { DoubleArray(4) }; private val hTh = Array(N) { DoubleArray(3) }
    private val hW = Array(N) { DoubleArray(3) }  // prędkość kątowa (z korektą grawitacji) w chwili próbki
    private val pw = DoubleArray(3); private var hasPw = false
    // rzeczywista częstotliwość żyroskopu (do zapisu danych)
    @Volatile var gyroHz = 0.0; private set
    private var rateN = 0; private var rateT0 = 0L
    private var head = 0; private var count = 0

    fun acquire(ctx: Context) {
        synchronized(lock) {
            users++
            if (users > 1) return
            ready = false; hasAcc = false; hasPw = false; lastTs = 0; count = 0; head = 0; th.fill(0.0)
            qw = 1.0; qx = 0.0; qy = 0.0; qz = 0.0
            val t = HandlerThread("motion").apply { start() }; thread = t
            val m = ctx.applicationContext.getSystemService(SensorManager::class.java); sm = m
            val h = Handler(t.looper)
            try { m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { m.registerListener(this, it, 10_000, h) } } catch (e: Exception) { Log.e(TAG, "acc", e) }
            // w czasie nagrywania najwyższa częstotliwość żyroskopu (uprawnienie HIGH_SAMPLING_RATE_SENSORS), poza nim 50 Hz
            try { m.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { m.registerListener(this, it, gyroDelay(), h) } } catch (e: Exception) { Log.e(TAG, "gyro", e) }
        }
    }

    @Volatile private var fast = false
    private fun gyroDelay() = if (fast) SensorManager.SENSOR_DELAY_FASTEST else 20_000
    /** Pełna częstotliwość żyroskopu tylko w czasie nagrywania. */
    fun setFast(on: Boolean) {
        synchronized(lock) {
            if (fast == on) return
            fast = on
            val m = sm ?: return; val t = thread ?: return
            try {
                val g = m.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
                m.unregisterListener(this, g)
                m.registerListener(this, g, gyroDelay(), Handler(t.looper))
                rateN = 0
            } catch (e: Exception) { Log.e(TAG, "gyro rate", e) }
        }
    }

    fun release() {
        synchronized(lock) {
            users--
            if (users > 0) return
            users = 0
            try { sm?.unregisterListener(this) } catch (_: Exception) {}
            thread?.quitSafely(); thread = null; sm = null; ready = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        synchronized(lock) {
            if (StabLog.active) StabLog.line((if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) "a," else "g,") + "${e.timestamp},${e.values[0]},${e.values[1]},${e.values[2]}\n")
            if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                if (!hasAcc) { for (i in 0..2) acc[i] = e.values[i].toDouble(); hasAcc = true }
                else for (i in 0..2) acc[i] += (e.values[i] - acc[i]) * 0.2   // tłumi drgania silnika
                if (!ready) initFromAcc(e.timestamp)
                return
            }
            if (e.sensor.type != Sensor.TYPE_GYROSCOPE || !ready) return
            val ts = e.timestamp
            if (rateN == 0) rateT0 = ts
            if (++rateN >= 200) { if (ts > rateT0) gyroHz = (rateN - 1) * 1e9 / (ts - rateT0); rateN = 0 }
            if (lastTs == 0L) { lastTs = ts; return }
            val dt = ((ts - lastTs) / 1e9).coerceIn(0.0, 0.05); lastTs = ts
            if (dt <= 0.0) return
            var wx = e.values[0].toDouble(); var wy = e.values[1].toDouble(); var wz = e.values[2].toDouble()
            th[0] += wx * dt; th[1] += wy * dt; th[2] += wz * dt
            // powolna korekta „pionu” grawitacją – tylko gdy telefon nie jest szarpany ani mocno przyspieszany
            val an = sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2])
            val rate = sqrt(wx * wx + wy * wy + wz * wz)
            if (hasAcc && an > 1.0 && abs(an / SensorManager.GRAVITY_EARTH - 1.0) < 0.12 && rate < 2.0) {
                val ax = acc[0] / an; val ay = acc[1] / an; val az = acc[2] / an
                // przewidywany kierunek „w górę” w układzie telefonu = trzeci wiersz macierzy obrotu
                val vx = 2 * (qx * qz - qw * qy); val vy = 2 * (qy * qz + qw * qx); val vz = 1 - 2 * (qx * qx + qy * qy)
                val ex = ay * vz - az * vy; val ey = az * vx - ax * vz; val ez = ax * vy - ay * vx
                val kp = if (ts - startTs < 2_000_000_000L) 2.0 else 0.08
                wx += kp * ex; wy += kp * ey; wz += kp * ez
            }
            // całkowanie metodą trapezów: q = q ⊗ Δq ze średniej prędkości z początku i końca przedziału
            // (wcześniej brana była wartość z końca – orientacja wyprzedzała rzeczywistą o pół okresu próbkowania)
            val cx = wx; val cy = wy; val cz = wz
            if (hasPw) { wx = (wx + pw[0]) / 2; wy = (wy + pw[1]) / 2; wz = (wz + pw[2]) / 2 }
            pw[0] = cx; pw[1] = cy; pw[2] = cz; hasPw = true
            val w = sqrt(wx * wx + wy * wy + wz * wz)
            val half = w * dt / 2
            val s = if (w > 1e-9) sin(half) / w else dt / 2
            val dw = cos(half); val dx = wx * s; val dy = wy * s; val dz = wz * s
            val nw = qw * dw - qx * dx - qy * dy - qz * dz
            val nx = qw * dx + qx * dw + qy * dz - qz * dy
            val ny = qw * dy - qx * dz + qy * dw + qz * dx
            val nz = qw * dz + qx * dy - qy * dx + qz * dw
            val n = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
            qw = nw / n; qx = nx / n; qy = ny / n; qz = nz / n
            hTs[head] = ts
            hQ[head][0] = qw; hQ[head][1] = qx; hQ[head][2] = qy; hQ[head][3] = qz
            hTh[head][0] = th[0]; hTh[head][1] = th[1]; hTh[head][2] = th[2]
            hW[head][0] = cx; hW[head][1] = cy; hW[head][2] = cz
            head = (head + 1) % N; if (count < N) count++
        }
    }

    private fun initFromAcc(ts: Long) {
        val an = sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2])
        if (an < 1.0) return
        val ax = acc[0] / an; val ay = acc[1] / an; val az = acc[2] / an
        // najkrótszy obrót przenoszący „górę” telefonu na oś z świata
        if (az < -0.999) { qw = 0.0; qx = 1.0; qy = 0.0; qz = 0.0 }
        else {
            val w = 1 + az; val x = ay; val y = -ax; val z = 0.0
            val n = sqrt(w * w + x * x + y * y + z * z)
            qw = w / n; qx = x / n; qy = y / n; qz = z / n
        }
        ready = true; startTs = ts; lastTs = 0
    }

    /** Orientacja w chwili [ts] (czas czujników). Zwraca false, gdy brak danych. */
    fun at(ts: Long, outQ: DoubleArray, outTh: DoubleArray?): Boolean {
        synchronized(lock) {
            if (count == 0) return false
            val latest = (head - 1 + N) % N
            if (ts >= hTs[latest]) { copy(latest, outQ, outTh); return true }
            var i = latest
            for (k in 1 until count) {
                val j = (latest - k + N) % N
                if (hTs[j] <= ts) {
                    val span = (hTs[i] - hTs[j]).toDouble()
                    val f = if (span > 0) (ts - hTs[j]) / span else 0.0
                    // między próbkami: prędkość zmienia się liniowo → kąt rośnie kwadratowo (dokładniej niż prosta interpolacja)
                    val tau = (ts - hTs[j]) / 1e9; val dsp = span / 1e9
                    val wa = hW[j]; val wb = hW[i]
                    val c2 = if (dsp > 0) tau * tau / (2 * dsp) else 0.0
                    val px = wa[0] * tau + (wb[0] - wa[0]) * c2
                    val py = wa[1] * tau + (wb[1] - wa[1]) * c2
                    val pz = wa[2] * tau + (wb[2] - wa[2]) * c2
                    val an = sqrt(px * px + py * py + pz * pz)
                    val sh = if (an > 1e-12) sin(an / 2) / an else 0.5
                    val dw = cos(an / 2); val dx = px * sh; val dy = py * sh; val dz = pz * sh
                    val a = hQ[j]
                    outQ[0] = a[0] * dw - a[1] * dx - a[2] * dy - a[3] * dz
                    outQ[1] = a[0] * dx + a[1] * dw + a[2] * dz - a[3] * dy
                    outQ[2] = a[0] * dy - a[1] * dz + a[2] * dw + a[3] * dx
                    outQ[3] = a[0] * dz + a[1] * dy - a[2] * dx + a[3] * dw
                    qNorm(outQ)
                    if (outTh != null) for (c in 0..2) outTh[c] = hTh[j][c] * (1 - f) + hTh[i][c] * f
                    return true
                }
                i = j
            }
            copy(i, outQ, outTh); return true
        }
    }

    private fun copy(i: Int, outQ: DoubleArray, outTh: DoubleArray?) {
        for (c in 0..3) outQ[c] = hQ[i][c]
        if (outTh != null) for (c in 0..2) outTh[c] = hTh[i][c]
    }
}

// ======================================================================================
// Drobna matematyka (macierze 3×3 w kolejności wierszowej, kwaterniony w, x, y, z)
// ======================================================================================
private fun qNorm(q: DoubleArray) { val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]); for (i in 0..3) q[i] /= n }

private fun qToM(q: DoubleArray): DoubleArray {
    val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
    return doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
        2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
        2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y))
}

private fun mToQ(m: DoubleArray): DoubleArray {
    val tr = m[0] + m[4] + m[8]
    val q = DoubleArray(4)
    if (tr > 0) {
        val s = sqrt(tr + 1) * 2
        q[0] = 0.25 * s; q[1] = (m[7] - m[5]) / s; q[2] = (m[2] - m[6]) / s; q[3] = (m[3] - m[1]) / s
    } else if (m[0] > m[4] && m[0] > m[8]) {
        val s = sqrt(1 + m[0] - m[4] - m[8]) * 2
        q[0] = (m[7] - m[5]) / s; q[1] = 0.25 * s; q[2] = (m[1] + m[3]) / s; q[3] = (m[2] + m[6]) / s
    } else if (m[4] > m[8]) {
        val s = sqrt(1 + m[4] - m[0] - m[8]) * 2
        q[0] = (m[2] - m[6]) / s; q[1] = (m[1] + m[3]) / s; q[2] = 0.25 * s; q[3] = (m[5] + m[7]) / s
    } else {
        val s = sqrt(1 + m[8] - m[0] - m[4]) * 2
        q[0] = (m[3] - m[1]) / s; q[1] = (m[2] + m[6]) / s; q[2] = (m[5] + m[7]) / s; q[3] = 0.25 * s
    }
    qNorm(q); return q
}

private fun mMul(a: DoubleArray, b: DoubleArray): DoubleArray {
    val r = DoubleArray(9)
    for (i in 0..2) for (j in 0..2) r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
    return r
}

private fun mT(a: DoubleArray) = doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

private fun mV(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
    m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2])

/** Płynne przejście między orientacjami (t = 0 → a, t = 1 → b). */
private fun mBlend(a: DoubleArray, b: DoubleArray, t: Double): DoubleArray {
    val qa = mToQ(a); val qb = mToQ(b)
    val sg = if (qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3] < 0) -1.0 else 1.0
    val q = DoubleArray(4) { qa[it] * (1 - t) + qb[it] * sg * t }
    qNorm(q); return qToM(q)
}

private fun wrapPi(a: Double): Double { var x = a; while (x > PI) x -= 2 * PI; while (x < -PI) x += 2 * PI; return x }

private val EF = doubleArrayOf(0.0, 0.0, -1.0)      // oś optyczna tylnego aparatu w układzie telefonu

/** Osie „w prawo” i „w górę” obrazu w układzie telefonu – zależnie od tego, jak trzymany jest telefon. */
private fun rightAxis(r: Int) = when (r) {
    Surface.ROTATION_90 -> doubleArrayOf(0.0, -1.0, 0.0); Surface.ROTATION_180 -> doubleArrayOf(-1.0, 0.0, 0.0)
    Surface.ROTATION_270 -> doubleArrayOf(0.0, 1.0, 0.0); else -> doubleArrayOf(1.0, 0.0, 0.0)
}
private fun upAxis(r: Int) = when (r) {
    Surface.ROTATION_90 -> doubleArrayOf(1.0, 0.0, 0.0); Surface.ROTATION_180 -> doubleArrayOf(0.0, -1.0, 0.0)
    Surface.ROTATION_270 -> doubleArrayOf(-1.0, 0.0, 0.0); else -> doubleArrayOf(0.0, 1.0, 0.0)
}

/** Macierz: układ telefonu → układ obrazu z matrycy (x w prawo, y w dół, z w głąb sceny). */
private fun deviceToBuf(sensorRotation: Int): DoubleArray {
    val k = ((((sensorRotation % 360) + 360) % 360) / 90)
    fun f(d: DoubleArray): DoubleArray {
        var x = d[0]; var y = -d[1]; val z = -d[2]          // obraz „pionowy” (jak na ekranie w pozycji naturalnej)
        repeat(k) { val nx = y; val ny = -x; x = nx; y = ny }   // cofnięcie obrotu matrycy
        return doubleArrayOf(x, y, z)
    }
    val c0 = f(doubleArrayOf(1.0, 0.0, 0.0)); val c1 = f(doubleArrayOf(0.0, 1.0, 0.0)); val c2 = f(doubleArrayOf(0.0, 0.0, 1.0))
    return doubleArrayOf(c0[0], c1[0], c2[0], c0[1], c1[1], c2[1], c0[2], c1[2], c2[2])
}

// ======================================================================================
// Dane klatek z aparatu: czas naświetlania, czas odczytu matrycy, zoom (z CaptureResult)
// ======================================================================================
object FrameMeta {
    private const val N = 64
    private val lock = Any()
    private val ts = LongArray(N); private val exp = LongArray(N); private val skew = LongArray(N)
    private val zoom = FloatArray(N); private val phys = arrayOfNulls<String>(N)
    private var head = 0
    class M(val exp: Long, val skew: Long, val zoom: Float, val phys: String?)
    @Volatile private var last: M? = null

    val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            val t = res.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val k = res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L
            val z = if (Build.VERSION.SDK_INT >= 30) (res.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 0f) else 0f
            val p = if (Build.VERSION.SDK_INT >= 29) res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
            synchronized(lock) { ts[head] = t; exp[head] = e; skew[head] = k; zoom[head] = z; phys[head] = p; head = (head + 1) % N }
            last = M(e, k, z, p)
        }
    }

    /** Dane klatki o danym czasie (dokładnie tej samej); gdy jeszcze nie dotarły – ostatnie znane. */
    fun find(t: Long): M? {
        synchronized(lock) {
            var best = -1; var bd = 1_500_000L
            for (i in 0 until N) { if (ts[i] == 0L) continue; val d = abs(ts[i] - t); if (d < bd) { bd = d; best = i } }
            if (best >= 0) return M(exp[best], skew[best], zoom[best], phys[best])
        }
        return last
    }
}

// ======================================================================================
// Zapis danych kalibracji (opcjonalny): żyroskop, akcelerometr i czasy klatek
// ======================================================================================
object StabLog {
    private val lock = Any()
    private var w: java.io.Writer? = null
    @Volatile var active = false; private set
    @Volatile var session = 0; private set

    fun start(f: java.io.File, header: List<String>) {
        synchronized(lock) {
            close()
            try {
                val o = java.io.BufferedWriter(java.io.FileWriter(f), 1 shl 16)
                header.forEach { o.write("# $it\n") }
                o.write("# g,t_ns,wx,wy,wz (rad/s) | a,t_ns,ax,ay,az (m/s2) | f,src_ts,out_pts,exp_ns,skew_ns,zoom,user_zoom,crop,phys\n")
                w = o; session++; active = true
            } catch (e: Exception) { Log.w("StabLog", "start", e) }
        }
    }
    fun line(s: String) { if (!active) return; synchronized(lock) { try { w?.write(s) } catch (_: Exception) {} } }
    fun stop() { synchronized(lock) { close() } }
    private fun close() { active = false; try { w?.flush(); w?.close() } catch (_: Exception) {}; w = null }
}

// ======================================================================================
// Efekt CameraX
// ======================================================================================
class StabEffect(targets: Int, val processor: StabProcessor) :
    CameraEffect(targets, processor.executor, processor,
        Consumer<Throwable> { t -> Log.e("StabEffect", "error", t); Notifier.toast("Stabilizacja: błąd (${t.message})") })

/**
 * Stabilizacja SUPER (odpowiednik Super Steady): wirtualna kamera wycinająca kadr z dużym zapasem,
 * obrót 3D liczony z żyroskopu osobno dla 9 pasów klatki (korekta odczytu matrycy linia po linii),
 * tor kamery wygładzany z wyprzedzeniem (nagranie opóźnione o ok. 0,5 s, podgląd bez opóźnienia).
 *
 * [fMain] – ogniskowa obiektywu głównego / dłuższy bok kadru (zoom 1×),
 * [lensR] – ogniskowa używanego aparatu względem głównego (1 dla aparatu logicznego, ok. 0,6 dla osobnego szerokiego),
 * [czMin] – najmniejszy zoom aparatu (0,6 = obiektyw szeroki w aparacie logicznym).
 */
class StabProcessor(ctx: Context, mode: String, sensorRotation: Int, targets: Int,
                    private val fMain: Double, private val lensR: Double, private val czMin: Float) : SurfaceProcessor {
    companion object {
        private const val TAG = "StabProcessor"
        private const val NB = 9
        private const val MEM_BUDGET = 200L * 1024 * 1024
        private fun fb(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
        private val QUAD = fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val QUAD_TEX = fb(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        private val I4 = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private val I3D = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        private const val VS = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uPre;
varying vec2 vC;
void main() {
    gl_Position = aPosition;
    vC = (uPre * aTexCoord).xy;
}"""
        private const val FS_BODY = """
uniform mat4 uPost;
uniform mat3 uH[9];
uniform float uRs;
uniform vec3 uRv;
varying vec2 vC;
vec2 pr(mat3 h, vec3 c) { vec3 v = h * c; return v.xy / v.z; }
void main() {
    vec3 c = vec3(vC, 1.0);
    vec2 q = pr(uH[4], c);
    if (uRs != 0.0) {
        float fr = dot(vec3(q, 1.0), uRv);
        float x = clamp(fr, 0.0, 1.0) * 8.0;
        vec2 r = q;
        for (int k = 0; k < 8; k++) {
            float a = x - float(k);
            if (a >= 0.0 && a <= 1.0) { r = mix(pr(uH[k], c), pr(uH[k + 1], c), a); }
        }
        q = r;
    }
    float inb = step(-0.002, q.x) * step(q.x, 1.002) * step(-0.002, q.y) * step(q.y, 1.002);
    gl_FragColor = vec4(texture2D(sTex, (uPost * vec4(q, 0.0, 1.0)).xy).rgb * inb, 1.0);
}"""
        private const val PREC = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""
        private const val FS_OES = "#extension GL_OES_EGL_image_external : require\n$PREC\nuniform samplerExternalOES sTex;\n$FS_BODY"
        private const val FS_2D = "$PREC\nuniform sampler2D sTex;\n$FS_BODY"
        private const val VS_COPY = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uPost;
varying vec2 vT;
void main() { gl_Position = aPosition; vT = (uPost * aTexCoord).xy; }"""
        private const val FS_COPY = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nuniform samplerExternalOES sTex;\nvarying vec2 vT;\nvoid main() { gl_FragColor = texture2D(sTex, vT); }"
        private const val VS_TXT = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vT;
void main() { gl_Position = aPosition; vT = aTexCoord; }"""
        private const val FS_TXT = """
precision mediump float;
uniform sampler2D sTex;
varying vec2 vT;
void main() { gl_FragColor = texture2D(sTex, vT); }"""
    }

    private val app = ctx.applicationContext
    private val thread = HandlerThread("stab-gl").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    private val horizon = mode == "superh"
    // tryb OIS: bez korekty, tylko zoom cyfrowy powyżej zakresu aparatu (do 20×) i napis z datą
    private val pass = mode == "off"
    private val videoOnly = targets == CameraEffect.VIDEO_CAPTURE
    private val wantDelay = videoOnly && !pass
    private val sensorRot = sensorRotation
    // Układ obrazu wejściowego. Macierz SurfaceTexture może już zawierać obrót matrycy (wtedy obraz jest „pionowy”
    // jak telefon w pozycji naturalnej) – wykrywamy to z macierzy przy pierwszej klatce.
    private var swap = false
    private var geoInit = false
    private var B = deviceToBuf(sensorRotation)
    private var BT = mT(B)
    private var imgW = 1440; private var imgH = 1080
    private val rawCal = Prefs.stabRawCal                      // kalibracja: nagranie bez korekty (ten sam obiektyw i kadr)
    private val sigma = Prefs.stabSmooth                       // s – siła wygładzania toru
    // kalibracja z nagrania 18.11 (Galaxy S24 FE, obiektyw szeroki): ogniskowa ×1,05; przesunięcie czasu +2,5 ms zmierzone
    // przy dawnym całkowaniu żyroskopu (125 Hz). Przy 500 Hz i całkowaniu trapezami pomiar z nagrań z wody 20.11
    // (dwa ujęcia, zgodnie w obu osiach): obraz był korygowany ok. 5,4 ms za późno względem 6,5 ms; nagrania kalibracyjne 20.11
    // (500 Hz, ląd i woda, obiektyw szeroki): 1–3,5 ms, ogniskowa ×1,05–1,06 → +2,0 ms
    private val calDev = android.os.Build.MODEL.startsWith("SM-S721")
    private val offsetNs = Prefs.stabTimeMs * 1_000_000L + (if (calDev) 2_000_000L else 0L)
    private val wideCalF = if (calDev) 1.05 else 1.0
    private val rsDir = Prefs.stabRs
    private val invert = Prefs.stabInvert
    private val watermark = Prefs.watermark

    // ---- GL ----
    private class Prog(val id: Int, val aPos: Int, val aTex: Int, val uPre: Int, val uH: Int, val uPost: Int, val uRs: Int, val uRv: Int)
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctxEgl: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var progOes: Prog? = null
    private var prog2d: Prog? = null
    private var progCopy = 0; private var cpPos = 0; private var cpTex = 0; private var cpPost = 0
    private var progTxt = 0; private var txtPos = 0; private var txtTex = 0
    private var texOes = 0
    private var st: SurfaceTexture? = null
    private var inW = 1920; private var inH = 1080
    private class Out(val es: EGLSurface) { val outM = FloatArray(16); val pre = FloatArray(16) }
    private val outputs = LinkedHashMap<SurfaceOutput, Out>()
    private val stM = FloatArray(16); private val stInv = FloatArray(16)
    private var released = false
    private var lastPts = 0L
    private var lastTc = 0L

    // ---- bufor opóźnienia (tylko nagranie) ----
    private var ringTex = IntArray(0)
    private var ringTs = LongArray(0)          // czas klatki z aparatu (surowy)
    private var ringTc = LongArray(0)          // ten sam czas w skali czujników
    private var ringE = FloatArray(0)          // zoom wirtualny w chwili klatki
    private var ringStart = 0; private var ringCount = 0
    private var ringFbo = 0
    private var ringOk = false
    private var ringTried = false
    private var lookNs = 0L

    // ---- tor kamery ----
    private class Smp(val t: Long, val q: DoubleArray)
    private val track = ArrayDeque<Smp>()
    private val qTmp = DoubleArray(4)
    private var pLam = 0.0
    private var lastLam = 0.0
    private var logSession = -1

    // ---- napis z datą ----
    private var textTex = 0; private var textSec = -1L; private var textAspect = 8f
    private val textFmt = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)
    private val txtBuf = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        Motion.acquire(app)
        handler.post { try { initGl() } catch (e: Exception) { Log.e(TAG, "gl", e) } }
    }

    // =========================================================================
    // EGL / GL
    // =========================================================================
    private fun initGl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2); EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE)
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0)
        cfg = cfgs[0]
        ctxEgl = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctxEgl)
        progOes = makeProg(FS_OES)
        if (wantDelay) {
            prog2d = makeProg(FS_2D)
            progCopy = link(compile(GLES20.GL_VERTEX_SHADER, VS_COPY), compile(GLES20.GL_FRAGMENT_SHADER, FS_COPY))
            cpPos = GLES20.glGetAttribLocation(progCopy, "aPosition"); cpTex = GLES20.glGetAttribLocation(progCopy, "aTexCoord")
            cpPost = GLES20.glGetUniformLocation(progCopy, "uPost")
        }
        if (watermark) {
            progTxt = link(compile(GLES20.GL_VERTEX_SHADER, VS_TXT), compile(GLES20.GL_FRAGMENT_SHADER, FS_TXT))
            txtPos = GLES20.glGetAttribLocation(progTxt, "aPosition"); txtTex = GLES20.glGetAttribLocation(progTxt, "aTexCoord")
        }
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); texOes = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes)
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
    }

    private fun texParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun makeProg(fs: String): Prog {
        val p = link(compile(GLES20.GL_VERTEX_SHADER, VS), compile(GLES20.GL_FRAGMENT_SHADER, fs))
        return Prog(p, GLES20.glGetAttribLocation(p, "aPosition"), GLES20.glGetAttribLocation(p, "aTexCoord"),
            GLES20.glGetUniformLocation(p, "uPre"), GLES20.glGetUniformLocation(p, "uH"),
            GLES20.glGetUniformLocation(p, "uPost"), GLES20.glGetUniformLocation(p, "uRs"), GLES20.glGetUniformLocation(p, "uRv"))
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src.trim()); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }
    private fun link(v: Int, f: Int): Int {
        val p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    // =========================================================================
    // CameraX
    // =========================================================================
    override fun onInputSurface(request: SurfaceRequest) {
        if (released) { request.willNotProvideSurface(); return }
        val size = request.resolution
        inW = size.width; inH = size.height
        val tex = SurfaceTexture(texOes)
        tex.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(tex)
        tex.setOnFrameAvailableListener({ onFrame() }, handler)
        st = tex
        request.provideSurface(surface, executor) { surface.release(); tex.release(); if (st === tex) st = null }
    }

    override fun onOutputSurface(out: SurfaceOutput) {
        if (released) { out.close(); return }
        val surface = out.getSurface(executor) { ev ->
            if (ev.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                outputs.remove(out)?.let { EGL14.eglDestroySurface(dpy, it.es) }
                out.close()
            }
        }
        val es = EGL14.eglCreateWindowSurface(dpy, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (es == null || es == EGL14.EGL_NO_SURFACE) { Log.e(TAG, "eglCreateWindowSurface"); return }
        outputs[out] = Out(es)
    }

    /** Czas klatki w skali zegara czujników (część telefonów podaje czas klatek w innej skali). */
    private fun sensorTime(ts: Long): Long {
        val now = SystemClock.elapsedRealtimeNanos()
        if (abs(ts - now) < 2_000_000_000L) return ts
        val mono = System.nanoTime()
        if (abs(ts - mono) < 2_000_000_000L) return ts + (now - mono)
        return ts
    }

    private fun rotationFor(): Int = if (videoOnly) CamRec.videoRotation else CamRec.uiRotation

    // =========================================================================
    // Dane klatki: czasy pasów (odczyt linia po linii), ogniskowa, przycięcie
    // =========================================================================
    private class Fd(val tb: LongArray, val tMid: Long, val f: Double, val k: Double,
                     val exp: Long, val skew: Long, val cz: Double, val e: Float, val phys: String?)

    private fun frameData(ts: Long, tC: Long, e: Float, m: FrameMeta.M?): Fd {
        val exp = m?.exp?.takeIf { it in 1..200_000_000L } ?: 8_000_000L
        val skew = m?.skew?.takeIf { it in 1..80_000_000L } ?: 22_000_000L
        val cz = (m?.zoom?.takeIf { it > 0.1f } ?: CamRec.curZoom).toDouble().coerceAtLeast(0.3)
        // jaka część wysokości matrycy trafia do kadru (zoom cyfrowy i proporcje 16:9 obcinają linie)
        val native = if (lensR < 0.999) 1.0 else if (cz < 0.999) czMin.toDouble() else 1.0
        var c = (native / cz).coerceIn(0.05, 1.0)
        val lo = min(inW, inH).toDouble() / max(inW, inH)
        if (lo < 0.7) c *= lo * 4.0 / 3.0
        val tMid = tC + exp / 2 + skew / 2
        val tb = LongArray(NB) { i -> tMid + (skew * c * (i.toDouble() / (NB - 1) - 0.5)).toLong() }
        val f = fMain * lensR * cz * max(inW, inH) * (if (lensR > 0.999 && cz < 0.999) wideCalF else 1.0)
        val k = max(1.0, e / (lensR * cz))
        return Fd(tb, tMid, f, k, exp, skew, cz, e, m?.phys)
    }

    private fun bands(fd: Fd): Array<DoubleArray>? {
        val out = arrayOfNulls<DoubleArray>(NB)
        for (i in 0 until NB) { if (!Motion.at(fd.tb[i], qTmp, null)) return null; out[i] = qToM(qTmp) }
        return Array(NB) { out[it]!! }
    }

    /** Punkt kadru wirtualnej kamery → punkt obrazu z matrycy (0…1, t w górę) dla orientacji telefonu [a]. */
    private fun hom(a: DoubleArray, v: DoubleArray, f: Double, k: Double): DoubleArray {
        val rc = mMul(mMul(B, if (invert) mMul(mT(v), a) else mMul(mT(a), v)), BT)
        val w = imgW.toDouble(); val h = imgH.toDouble()
        val mv = doubleArrayOf(w / k, 0.0, -0.5 * w / k, 0.0, -h / k, 0.5 * h / k, 0.0, 0.0, f)
        val p = doubleArrayOf(f / w, 0.0, 0.5, 0.0, -f / h, 0.5, 0.0, 0.0, 1.0)
        return mMul(mMul(p, rc), mv)
    }

    private fun inside(h: DoubleArray, corners: List<DoubleArray>): Boolean {
        for (c in corners) {
            val z = h[6] * c[0] + h[7] * c[1] + h[8]
            if (z <= 1e-6) return false
            val x = (h[0] * c[0] + h[1] * c[1] + h[2]) / z
            val y = (h[3] * c[0] + h[4] * c[1] + h[5]) / z
            if (x < 0.001 || x > 0.999 || y < 0.001 || y > 0.999) return false
        }
        return true
    }

    /** Czy kadr wirtualnej kamery mieści się w obrazie z matrycy (pierwszy, środkowy i ostatni pas). */
    private fun fits(As: Array<DoubleArray>, v: DoubleArray, fd: Fd, corners: List<DoubleArray>): Boolean =
        inside(hom(As[0], v, fd.f, fd.k), corners) && inside(hom(As[NB / 2], v, fd.f, fd.k), corners) && inside(hom(As[NB - 1], v, fd.f, fd.k), corners)

    /** Najmniejsze przyciągnięcie wirtualnej kamery do rzeczywistej, przy którym kadr się mieści. */
    private fun needLam(As: Array<DoubleArray>, v: DoubleArray, fd: Fd, corners: List<DoubleArray>): Double {
        if (fits(As, v, fd, corners)) return 0.0
        val a = As[NB / 2]
        var lo = 0.0; var hi = 1.0
        repeat(9) { val m = (lo + hi) / 2; if (fits(As, mBlend(v, a, m), fd, corners)) hi = m else lo = m }
        return hi
    }
    private fun needLam1(a: DoubleArray, v: DoubleArray, fd: Fd, corners: List<DoubleArray>): Double {
        if (inside(hom(a, v, fd.f, fd.k), corners)) return 0.0
        var lo = 0.0; var hi = 1.0
        repeat(7) { val m = (lo + hi) / 2; if (inside(hom(a, mBlend(v, a, m), fd.f, fd.k), corners)) hi = m else lo = m }
        return hi
    }

    // =========================================================================
    // Tor wirtualnej kamery
    // =========================================================================
    /** Wypoziomowana orientacja kamery patrzącej w kierunku (yaw, elev). */
    private fun level(yaw: Double, elev: Double, er: DoubleArray, eu: DoubleArray): DoubleArray {
        val F = doubleArrayOf(cos(elev) * cos(yaw), cos(elev) * sin(yaw), sin(elev))
        var hx = F[1]; var hy = -F[0]
        val hn = sqrt(hx * hx + hy * hy); hx /= hn; hy /= hn
        val h = doubleArrayOf(hx, hy, 0.0)
        val u = doubleArrayOf(h[1] * F[2] - h[2] * F[1], h[2] * F[0] - h[0] * F[2], h[0] * F[1] - h[1] * F[0])
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = h[i] * er[j] + u[i] * eu[j] + F[i] * EF[j]
        return r
    }

    private fun levelM(m: DoubleArray, rot: Int): DoubleArray? {
        val f = mV(m, EF)
        if (abs(f[2]) > 0.85) return null   // aparat skierowany w górę / w dół – brak horyzontu
        return level(atan2(f[1], f[0]), asin(f[2].coerceIn(-1.0, 1.0)), rightAxis(rot), upAxis(rot))
    }

    /** Średnia ważona (Gauss) orientacji z toru wokół chwili [center]; w trybie SUPER+H – z wypoziomowanym horyzontem. */
    private fun smoothV(center: Long, back: Long, fwd: Long, sig: Double, rot: Int): DoubleArray? {
        var ref: DoubleArray? = null; var bd = Long.MAX_VALUE
        for (s in track) { val d = abs(s.t - center); if (d < bd) { bd = d; ref = s.q } }
        if (ref == null) return null
        val lvl = horizon && levelM(qToM(ref), rot) != null
        val refQ = if (lvl) mToQ(levelM(qToM(ref), rot)!!) else ref
        val acc = DoubleArray(4)
        val sNs = sig * 1e9
        for (s in track) {
            val dt = (s.t - center).toDouble()
            if (dt < -back || dt > fwd) continue
            val w = exp(-0.5 * (dt / sNs) * (dt / sNs))
            val q = if (lvl) (levelM(qToM(s.q), rot)?.let { mToQ(it) } ?: continue) else s.q
            val sg = if (q[0] * refQ[0] + q[1] * refQ[1] + q[2] * refQ[2] + q[3] * refQ[3] < 0) -w else w
            for (i in 0..3) acc[i] += q[i] * sg
        }
        val n = sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2] + acc[3] * acc[3])
        if (n < 1e-9) return qToM(refQ)
        for (i in 0..3) acc[i] /= n
        val m = qToM(acc)
        return if (lvl) levelM(m, rot) ?: m else m
    }

    /** Nagranie: wygładzanie symetryczne (z wyprzedzeniem) i łagodne „przygotowanie się” na duże wychylenia. */
    private fun videoV(fd: Fd, As: Array<DoubleArray>, rot: Int, corners: List<DoubleArray>): DoubleArray {
        val sNs = (sigma * 1e9).toLong()
        val newest = track.lastOrNull()?.t ?: fd.tMid
        val fwd = min((2.5 * sNs).toLong(), max(0L, newest - fd.tMid))
        val v0 = smoothV(fd.tMid, (2.5 * sNs).toLong(), fwd, sigma, rot) ?: return As[NB / 2]
        var lam = needLam(As, v0, fd, corners)
        val win = max(lookNs, 200_000_000L).toDouble()
        for (s in track) {
            val d = abs(s.t - fd.tMid).toDouble()
            if (d < 1e6 || d > win) continue
            val w = 1 - d / win
            if (w <= lam) continue
            lam = max(lam, needLam1(qToM(s.q), v0, fd, corners) * w)
        }
        var v = if (lam > 1e-4) mBlend(v0, As[NB / 2], lam) else v0
        var extra = 0.0
        if (!fits(As, v, fd, corners)) { extra = needLam(As, v, fd, corners); v = mBlend(v, As[NB / 2], extra) }
        lastLam = 1 - (1 - lam) * (1 - extra)   // do zapisu: ile ruchu telefonu przeszło do kadru (0 = pełna stabilizacja)
        return v
    }

    /** Podgląd: tylko przeszłość (bez opóźnienia), krótsze wygładzanie. */
    private fun previewV(fd: Fd, As: Array<DoubleArray>, rot: Int, corners: List<DoubleArray>, dt: Double): DoubleArray {
        val sig = min(sigma, 0.15)
        val v0 = smoothV(fd.tMid, (2.5 * sig * 1e9).toLong(), 0L, sig, rot) ?: return As[NB / 2]
        pLam = max(needLam(As, v0, fd, corners), pLam * exp(-dt / 0.3))
        return if (pLam > 1e-4) mBlend(v0, As[NB / 2], pLam) else v0
    }

    private fun homs(As: Array<DoubleArray>?, v: DoubleArray?, fd: Fd): FloatArray {
        val out = FloatArray(9 * NB)
        for (i in 0 until NB) {
            val h = if (As != null && v != null) hom(As[i], v, fd.f, fd.k) else hom(I3D, I3D, fd.f, fd.k)
            val g = floatArrayOf(h[0].toFloat(), h[3].toFloat(), h[6].toFloat(), h[1].toFloat(), h[4].toFloat(), h[7].toFloat(), h[2].toFloat(), h[5].toFloat(), h[8].toFloat())
            System.arraycopy(g, 0, out, i * 9, 9)
        }
        return out
    }

    // =========================================================================
    // Klatka
    // =========================================================================
    private fun onFrame() {
        val tex = st ?: return
        if (released) return
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctxEgl)
        try { tex.updateTexImage() } catch (e: Exception) { return }
        tex.getTransformMatrix(stM)
        Matrix.invertM(stInv, 0, stM, 0)
        val sw = abs(stM[1]) > abs(stM[0])
        if (!geoInit || sw != swap) {
            swap = sw; geoInit = true
            B = deviceToBuf(if (sw) 0 else sensorRot); BT = mT(B)
        }
        imgW = if (swap) inH else inW; imgH = if (swap) inW else inH
        val ts = tex.timestamp
        val tC = sensorTime(ts) + offsetNs
        val dt = if (lastTc == 0L) 1.0 / 30 else ((tC - lastTc) / 1e9).coerceIn(0.001, 0.1)
        lastTc = tC
        if (outputs.isEmpty()) return
        val corners = ArrayList<DoubleArray>(8)
        for ((so, o) in outputs) {
            so.updateTransformMatrix(o.outM, stM)
            Matrix.multiplyMM(o.pre, 0, stInv, 0, o.outM, 0)
            for (c in arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(1f, 1f))) {
                val v = FloatArray(4); Matrix.multiplyMV(v, 0, o.pre, 0, floatArrayOf(c[0], c[1], 0f, 1f), 0)
                corners.add(doubleArrayOf(v[0].toDouble(), v[1].toDouble()))
            }
        }
        val rot = rotationFor()
        if (watermark) updateText()
        val e = CamRec.userZoom
        val m = FrameMeta.find(ts)
        val fdNow = frameData(ts, tC, e, m)
        if (Motion.at(fdNow.tMid, qTmp, null)) {
            track.addLast(Smp(fdNow.tMid, qTmp.copyOf()))
            while (track.size > 2 && fdNow.tMid - track.first().t > 3_000_000_000L) track.removeFirst()
        }

        if (wantDelay && !ringTried) allocRing()
        if (ringOk && ringCount > 0) {
            // klatki zostałe w buforze z poprzedniego nagrania (strumień stoi między nagraniami) – nie mogą trafić do nowego filmu
            val newest = ringTc[(ringStart + ringCount - 1) % ringTex.size]
            if (tC - newest > 300_000_000L || tC < newest) { ringStart = 0; ringCount = 0 }
        }
        if (ringOk) { pushRing(ts, tC, e); emitDelayed(ts, rot, corners); return }

        // podgląd bez nagrywania: bez liczenia stabilizacji (sam kadr) – mniej pracy procesora
        val idle = pass || (!videoOnly && CamRec.recording == null)
        val As = if (idle) null else bands(fdNow)
        val v = if (As != null) (if (videoOnly) (if (rawCal) null else videoV(fdNow, As, rot, corners)) else previewV(fdNow, As, rot, corners, dt)) else null
        val hs = homs(As, v, fdNow)
        val prog = progOes ?: return
        for ((so, o) in outputs) {
            begin(o.es, so)
            drawWarp(prog, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes, o.pre, hs, stM)
            drawText(so, o, rot)
            finish(o.es, ts)
        }
        if (videoOnly) logFrame(ts, lastPts, fdNow)
    }

    private fun logFrame(src: Long, pts: Long, fd: Fd) {
        if (!StabLog.active) return
        if (logSession != StabLog.session) {
            logSession = StabLog.session
            StabLog.line("# stream=${inW}x$inH swap=$swap st=${stM.take(6).joinToString(";")} rotation=${rotationFor()} lookahead_ms=${lookNs / 1_000_000} fMain=$fMain lensR=$lensR czMin=$czMin smooth_s=$sigma time_offset_us=${offsetNs / 1_000} rs=$rsDir gyro_hz=${"%.0f".format(java.util.Locale.US, Motion.gyroHz)}\n")
        }
        StabLog.line("f,$src,$pts,${fd.exp},${fd.skew},${fd.cz},${fd.e},${fd.k},${fd.phys ?: ""},${"%.4f".format(java.util.Locale.US, lastLam)}\n")
    }

    private fun begin(es: EGLSurface, so: SurfaceOutput) {
        EGL14.eglMakeCurrent(dpy, es, es, ctxEgl)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, so.size.width, so.size.height)
    }

    private fun finish(es: EGLSurface, ts: Long) {
        val pts = if (ts <= lastPts) lastPts + 1000 else ts
        lastPts = pts
        EGLExt.eglPresentationTimeANDROID(dpy, es, pts)
        EGL14.eglSwapBuffers(dpy, es)
    }

    private fun drawWarp(p: Prog, target: Int, tex: Int, pre: FloatArray, hs: FloatArray, post: FloatArray) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniformMatrix4fv(p.uPre, 1, false, pre, 0)
        GLES20.glUniformMatrix3fv(p.uH, NB, false, hs, 0)
        GLES20.glUniformMatrix4fv(p.uPost, 1, false, post, 0)
        GLES20.glUniform1f(p.uRs, rsDir.toFloat())
        // ułamek odczytu matrycy dla punktu obrazu: linie matrycy biegną wzdłuż jej dłuższego boku
        if (swap) { if (rsDir >= 0) GLES20.glUniform3f(p.uRv, -1f, 0f, 1f) else GLES20.glUniform3f(p.uRv, 1f, 0f, 0f) }
        else { if (rsDir >= 0) GLES20.glUniform3f(p.uRv, 0f, -1f, 1f) else GLES20.glUniform3f(p.uRv, 0f, 1f, 0f) }
        GLES20.glEnableVertexAttribArray(p.aPos); GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glEnableVertexAttribArray(p.aTex); GLES20.glVertexAttribPointer(p.aTex, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // =========================================================================
    // Bufor opóźnienia (wyprzedzenie toru kamery w nagraniu)
    // =========================================================================
    private fun allocRing() {
        ringTried = true
        val per = inW.toLong() * inH * 4
        val n = min(18L, MEM_BUDGET / per).toInt()
        if (n < 5) { Notifier.toast("SUPER: przy tej jakości nagrywam bez wyprzedzenia"); return }
        val t = IntArray(n); GLES20.glGenTextures(n, t, 0)
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { }
        for (id in t) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            texParams(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, imgW, imgH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        }
        val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            GLES20.glDeleteTextures(n, t, 0); GLES20.glDeleteFramebuffers(1, f, 0)
            Notifier.toast("SUPER: za mało pamięci – nagrywam bez wyprzedzenia")
            return
        }
        ringTex = t; ringTs = LongArray(n); ringTc = LongArray(n); ringE = FloatArray(n); ringFbo = f[0]; ringStart = 0; ringCount = 0
        lookNs = min(500_000_000L, (n - 2) * 33_333_333L)
        ringOk = true
        CamRec.videoDelayMs = (lookNs / 1_000_000).toInt()
    }

    private fun pushRing(ts: Long, tC: Long, e: Float) {
        val n = ringTex.size
        if (ringCount == n) { ringStart = (ringStart + 1) % n; ringCount-- }
        val slot = (ringStart + ringCount) % n
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ringFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ringTex[slot], 0)
        GLES20.glViewport(0, 0, imgW, imgH)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(progCopy)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes)
        GLES20.glUniformMatrix4fv(cpPost, 1, false, stM, 0)
        GLES20.glEnableVertexAttribArray(cpPos); GLES20.glVertexAttribPointer(cpPos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glEnableVertexAttribArray(cpTex); GLES20.glVertexAttribPointer(cpTex, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        ringTs[slot] = ts; ringTc[slot] = tC; ringE[slot] = e
        ringCount++
    }

    private fun emitDelayed(ts: Long, rot: Int, corners: List<DoubleArray>) {
        val n = ringTex.size
        val newest = ringTc[(ringStart + ringCount - 1 + n) % n]
        var pick = -1
        for (k in 0 until ringCount) if (newest - ringTc[(ringStart + k) % n] >= lookNs) pick = k
        if (pick < 0) return
        val idx = (ringStart + pick) % n
        val fd = frameData(ringTs[idx], ringTc[idx], ringE[idx], FrameMeta.find(ringTs[idx]))
        val As = bands(fd)
        val v = if (As != null && !rawCal) videoV(fd, As, rot, corners) else null
        val hs = homs(As, v, fd)
        val p = prog2d ?: return
        for ((so, o) in outputs) {
            begin(o.es, so)
            drawWarp(p, GLES20.GL_TEXTURE_2D, ringTex[idx], o.pre, hs, I4)
            drawText(so, o, rot)
            // znacznik czasu bieżący – nagranie jest równo przesunięte, a dźwięk wyrównany przy zapisie
            finish(o.es, ts)
        }
        logFrame(ringTs[idx], lastPts, fd)
        ringStart = (idx + 1) % n; ringCount -= pick + 1
    }

    // =========================================================================
    // Data i godzina na obrazie
    // =========================================================================
    private fun updateText() {
        val now = System.currentTimeMillis()
        if (now / 1000 == textSec && textTex != 0) return
        textSec = now / 1000
        val s = textFmt.format(Date(now))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 48f; typeface = Typeface.MONOSPACE; setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }
        val w = (p.measureText(s) + 12).toInt(); val h = 64
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(s, 6f, 50f, p)
        if (textTex == 0) {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); textTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex); texParams(GLES20.GL_TEXTURE_2D)
        } else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        textAspect = w.toFloat() / h
        bmp.recycle()
    }

    /** Napis w prawym dolnym rogu obrazu „tak, jak go widzi widz” – niezależnie od ułożenia bufora wyjścia. */
    private fun drawText(so: SurfaceOutput, o: Out, rot: Int) {
        if (!watermark || textTex == 0 || progTxt == 0) return
        val ow = so.size.width.toFloat(); val oh = so.size.height.toFloat()
        val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, o.pre, 0)) return
        fun outDir(e: DoubleArray): FloatArray {
            val b = mV(B, e)
            val v = FloatArray(4); Matrix.multiplyMV(v, 0, inv, 0, floatArrayOf(b[0].toFloat(), (-b[1]).toFloat(), 0f, 0f), 0)
            val px = v[0] * ow; val py = v[1] * oh
            return if (abs(px) >= abs(py)) floatArrayOf(sign(px), 0f) else floatArrayOf(0f, sign(py))
        }
        val r = outDir(rightAxis(rot)); val u = outDir(upAxis(rot))
        val halfR = if (r[0] != 0f) ow / 2 else oh / 2
        val halfU = if (u[0] != 0f) ow / 2 else oh / 2
        val cx = ow / 2 + r[0] * halfR - u[0] * halfU
        val cy = oh / 2 + r[1] * halfR - u[1] * halfU
        val th = 0.026f * min(ow, oh); val tw = th * textAspect; val m = 0.02f * min(ow, oh)
        val brx = cx - r[0] * m + u[0] * m; val bry = cy - r[1] * m + u[1] * m
        val blx = brx - r[0] * tw; val bly = bry - r[1] * tw
        val trx = brx + u[0] * th; val tr_y = bry + u[1] * th
        val tlx = blx + u[0] * th; val tly = bly + u[1] * th
        fun nx(x: Float) = x / ow * 2 - 1
        fun ny(y: Float) = y / oh * 2 - 1
        txtBuf.clear()
        txtBuf.put(floatArrayOf(
            nx(blx), ny(bly), 0f, 1f,
            nx(brx), ny(bry), 1f, 1f,
            nx(tlx), ny(tly), 0f, 0f,
            nx(trx), ny(tr_y), 1f, 0f))
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(progTxt)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex)
        txtBuf.position(0)
        GLES20.glEnableVertexAttribArray(txtPos); GLES20.glVertexAttribPointer(txtPos, 2, GLES20.GL_FLOAT, false, 16, txtBuf)
        txtBuf.position(2)
        GLES20.glEnableVertexAttribArray(txtTex); GLES20.glVertexAttribPointer(txtTex, 2, GLES20.GL_FLOAT, false, 16, txtBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        handler.post {
            if (released) return@post
            released = true
            if (ringOk) CamRec.videoDelayMs = 0
            Motion.release()
            for ((so, o) in outputs) { EGL14.eglDestroySurface(dpy, o.es); try { so.close() } catch (_: Exception) {} }
            outputs.clear()
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbuf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuf)
                EGL14.eglDestroyContext(dpy, ctxEgl)
                EGL14.eglTerminate(dpy)
            }
            thread.quitSafely()
        }
    }
}
