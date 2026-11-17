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
 * Stabilizacja obrazu liczona przez aplikację (tryby Sport, Horyzont, Horyzont płynny).
 *
 *  - [Motion]: orientacja telefonu z żyroskopu (200 Hz) korygowana powoli grawitacją z akcelerometru.
 *    Korekta jest bardzo słaba (stała czasowa ok. 12 s) i wyłączana przy szarpnięciach, więc przyspieszenia łodzi
 *    (zakręty, uderzenia o falę) prawie nie przekrzywiają wyznaczonego „pionu”.
 *  - [StabProcessor]: efekt CameraX w OpenGL. Dla każdej klatki wyznacza „wirtualną kamerę” (wypoziomowaną,
 *    wygładzoną) i przelicza obraz tak, jakby nagrywała go ta wirtualna kamera – pełny obrót 3D (homografia),
 *    a nie tylko przesunięcie kadru.
 *
 * Tryby:
 *  sport    – dotychczasowy: wygładzanie drgań w każdej osi, bez odniesienia do horyzontu.
 *  horizon  – przechył zablokowany względem grawitacji, wysokość horyzontu trzymana stale (powoli podąża za
 *             świadomym pochyleniem), panoramowanie wygładzane adaptacyjnie.
 *  horizon2 – jak horizon, ale nagranie jest opóźnione o ok. 0,33 s: tor kamery liczony jest „z wyprzedzeniem”
 *             (płynniejsze panoramy, mniej szarpnięć przy krawędziach zapasu kadru). Podgląd bez opóźnienia.
 */

// ======================================================================================
// Orientacja telefonu (wspólna dla podglądu i nagrania)
// ======================================================================================
object Motion : SensorEventListener {
    private const val N = 1024                 // ok. 5 s historii przy 200 Hz
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
    private var head = 0; private var count = 0

    fun acquire(ctx: Context) {
        synchronized(lock) {
            users++
            if (users > 1) return
            ready = false; hasAcc = false; lastTs = 0; count = 0; head = 0; th.fill(0.0)
            qw = 1.0; qx = 0.0; qy = 0.0; qz = 0.0
            val t = HandlerThread("motion").apply { start() }; thread = t
            val m = ctx.applicationContext.getSystemService(SensorManager::class.java); sm = m
            val h = Handler(t.looper)
            try { m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { m.registerListener(this, it, 10_000, h) } } catch (e: Exception) { Log.e(TAG, "acc", e) }
            // 200 Hz – najwyższa częstotliwość dostępna bez dodatkowych uprawnień (Android 12+)
            try { m.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { m.registerListener(this, it, 5_000, h) } } catch (e: Exception) { Log.e(TAG, "gyro", e) }
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
            if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                if (!hasAcc) { for (i in 0..2) acc[i] = e.values[i].toDouble(); hasAcc = true }
                else for (i in 0..2) acc[i] += (e.values[i] - acc[i]) * 0.2   // tłumi drgania silnika
                if (!ready) initFromAcc(e.timestamp)
                return
            }
            if (e.sensor.type != Sensor.TYPE_GYROSCOPE || !ready) return
            val ts = e.timestamp
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
            // całkowanie: q = q ⊗ Δq (prędkości kątowe w układzie telefonu)
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
                    val a = hQ[j]; val b = hQ[i]
                    val sg = if (a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3] < 0) -1.0 else 1.0
                    for (c in 0..3) outQ[c] = a[c] * (1 - f) + b[c] * sg * f
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
// Efekt CameraX
// ======================================================================================
class StabEffect(targets: Int, val processor: StabProcessor) :
    CameraEffect(targets, processor.executor, processor,
        Consumer<Throwable> { t -> Log.e("StabEffect", "error", t); Notifier.toast("Stabilizacja: błąd (${t.message})") })

class StabProcessor(ctx: Context, private val mode: String, sensorRotation: Int, private val targets: Int) : SurfaceProcessor {
    companion object {
        private const val TAG = "StabProcessor"
        private const val F_NORM = 0.667            // ogniskowa / dłuższy bok kadru dla obiektywu ~24 mm
        private const val LOOKAHEAD_NS = 330_000_000L
        const val LOOKAHEAD_MS = 330
        private fun fb(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
        private val QUAD = fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val QUAD_TEX = fb(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        private val I4 = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        private val I3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        private const val VS = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uPre;
uniform mat3 uH;
varying vec3 vH;
void main() {
    gl_Position = aPosition;
    vec4 c = uPre * aTexCoord;
    vH = uH * vec3(c.xy, 1.0);
}"""
        private const val FS_BODY = """
uniform mat4 uPost;
varying vec3 vH;
void main() {
    vec2 q = vH.xy / vH.z;
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

    private val legacy = mode == "sport"
    private val videoOnly = targets == CameraEffect.VIDEO_CAPTURE
    private val wantDelay = mode == "horizon2" && videoOnly
    private val crop = Prefs.sportCrop.coerceIn(1.1f, 2.2f).toDouble()
    private val sensorRot = sensorRotation
    private val B = deviceToBuf(sensorRotation)
    private val BT = mT(B)
    private val tauYaw = if (mode == "horizon2") 1.0 else 0.7
    private val tauPitch = if (mode == "horizon2") 3.0 else 2.5
    private val invert = Prefs.stabInvert
    private val watermark = Prefs.watermark
    private val offsetNs = Prefs.stabOffsetMs * 1_000_000L

    // ---- GL ----
    private class Prog(val id: Int, val aPos: Int, val aTex: Int, val uPre: Int, val uH: Int, val uPost: Int)
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctxEgl: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var progOes: Prog? = null
    private var prog2d: Prog? = null
    private var progTxt = 0; private var txtPos = 0; private var txtTex = 0
    private var texOes = 0
    private var st: SurfaceTexture? = null
    private var inW = 1920; private var inH = 1080
    private class Out(val es: EGLSurface) { val outM = FloatArray(16); val pre = FloatArray(16) }
    private val outputs = LinkedHashMap<SurfaceOutput, Out>()
    private val stM = FloatArray(16); private val stInv = FloatArray(16)
    private var released = false
    private var lastFrame = 0L
    private var lastPts = 0L

    // ---- bufor opóźnienia (Horyzont płynny, tylko nagranie) ----
    private var ringTex = IntArray(0)
    private var ringSt = LongArray(0)          // czas czujników klatki
    private var ringStart = 0; private var ringCount = 0
    private var ringFbo = 0
    private var ringOk = false
    private var ringTried = false

    // ---- napis z datą ----
    private var textTex = 0; private var textSec = -1L; private var textAspect = 8f
    private val textFmt = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)
    private val txtBuf = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // ---- tor wirtualnej kamery ----
    private val q = DoubleArray(4); private val thv = DoubleArray(3)
    private var pInit = false; private var pYaw = 0.0; private var pElev = 0.0; private var pLam = 0.0
    private var dLam = 0.0
    private val hist = ArrayDeque<DoubleArray>()   // [czas, kierunek, wysokość] – dla toru z wyprzedzeniem
    private val legLp = DoubleArray(3); private var legInit = false

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
        if (wantDelay) prog2d = makeProg(FS_2D)
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
            GLES20.glGetUniformLocation(p, "uPre"), GLES20.glGetUniformLocation(p, "uH"), GLES20.glGetUniformLocation(p, "uPost"))
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

    private fun onFrame() {
        val tex = st ?: return
        if (released) return
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctxEgl)
        try { tex.updateTexImage() } catch (e: Exception) { return }
        tex.getTransformMatrix(stM)
        Matrix.invertM(stInv, 0, stM, 0)
        val ts = tex.timestamp
        val tS = sensorTime(ts) + offsetNs
        val dt = if (lastFrame == 0L) 1.0 / 30 else ((tS - lastFrame) / 1e9).coerceIn(0.001, 0.1)
        lastFrame = tS
        if (outputs.isEmpty()) return
        val prog = progOes ?: return
        // macierze wyjść: współrzędne wyjścia → współrzędne obrazu z matrycy (bez odwrócenia SurfaceTexture)
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

        if (legacy) {
            val post = legacyMatrix(tS, dt)
            for ((so, o) in outputs) {
                begin(o.es, so)
                drawWarp(prog, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes, o.outM, I3, post)
                drawText(so, o, rot)
                finish(o.es, ts)
            }
            return
        }

        val A = if (Motion.at(tS, q, null)) qToM(q) else null
        val V = if (A != null) step(A, dt, rot, corners) else null

        if (wantDelay && !ringTried) allocRing()
        if (ringOk) {
            if (A != null && V != null) {
                val f = mV(V, EF)
                hist.addLast(doubleArrayOf(tS.toDouble(), atan2(f[1], f[0]), asin(f[2].coerceIn(-1.0, 1.0))))
                while (hist.size > 2 && tS - hist.first()[0] > 2.5e9) hist.removeFirst()
            }
            pushRing(tS)
            emitDelayed(tS, ts, dt, rot, corners)
            return
        }

        val h = if (A != null && V != null) homography(A, V) else homography(I3D, I3D)
        val hf = toGl3(h)
        for ((so, o) in outputs) {
            begin(o.es, so)
            drawWarp(prog, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes, o.pre, hf, stM)
            drawText(so, o, rot)
            finish(o.es, ts)
        }
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

    private fun drawWarp(p: Prog, target: Int, tex: Int, pre: FloatArray, h: FloatArray, post: FloatArray) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniformMatrix4fv(p.uPre, 1, false, pre, 0)
        GLES20.glUniformMatrix3fv(p.uH, 1, false, h, 0)
        GLES20.glUniformMatrix4fv(p.uPost, 1, false, post, 0)
        GLES20.glEnableVertexAttribArray(p.aPos); GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glEnableVertexAttribArray(p.aTex); GLES20.glVertexAttribPointer(p.aTex, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // =========================================================================
    // Wirtualna kamera
    // =========================================================================
    private val I3D = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    private fun fPx(): Double = F_NORM * max(inW, inH) * CamRec.curZoom.coerceAtLeast(0.5f)

    /** Przekształcenie obrazu: punkt obrazu wirtualnej kamery → punkt obrazu z matrycy (współrzędne 0…1, t w górę). */
    private fun homography(A: DoubleArray, V: DoubleArray): DoubleArray {
        val rDev = if (invert) mMul(mT(V), A) else mMul(mT(A), V)
        val rc = mMul(mMul(B, rDev), BT)
        val W = inW.toDouble(); val H = inH.toDouble(); val f = fPx(); val k = crop
        val mv = doubleArrayOf(W / k, 0.0, -0.5 * W / k, 0.0, -H / k, 0.5 * H / k, 0.0, 0.0, f)
        val p = doubleArrayOf(f / W, 0.0, 0.5, 0.0, -f / H, 0.5, 0.0, 0.0, 1.0)
        return mMul(mMul(p, rc), mv)
    }

    private fun toGl3(h: DoubleArray) = floatArrayOf(
        h[0].toFloat(), h[3].toFloat(), h[6].toFloat(), h[1].toFloat(), h[4].toFloat(), h[7].toFloat(), h[2].toFloat(), h[5].toFloat(), h[8].toFloat())

    private fun fits(A: DoubleArray, V: DoubleArray, corners: List<DoubleArray>): Boolean {
        val h = homography(A, V)
        for (c in corners) {
            val z = h[6] * c[0] + h[7] * c[1] + h[8]
            if (z <= 1e-6) return false
            val x = (h[0] * c[0] + h[1] * c[1] + h[2]) / z
            val y = (h[3] * c[0] + h[4] * c[1] + h[5]) / z
            if (x < 0.001 || x > 0.999 || y < 0.001 || y > 0.999) return false
        }
        return true
    }

    /** Najmniejsze „przyciągnięcie” wirtualnej kamery do rzeczywistej, przy którym kadr mieści się w obrazie. */
    private fun needLam(A: DoubleArray, V: DoubleArray, corners: List<DoubleArray>): Double {
        if (fits(A, V, corners)) return 0.0
        var lo = 0.0; var hi = 1.0
        repeat(8) { val m = (lo + hi) / 2; if (fits(A, mBlend(V, A, m), corners)) hi = m else lo = m }
        return hi
    }

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

    /** Zapas kadru (kąt) w poziomie i w pionie obrazu. */
    private fun margins(rot: Int): Pair<Double, Double> {
        val br = mV(B, rightAxis(rot))
        val mx = max(inW, inH).toDouble()
        val horAlongW = abs(br[0]) > 0.5
        val halfHor = 0.5 * (if (horAlongW) inW else inH) / mx
        val halfVer = 0.5 * (if (horAlongW) inH else inW) / mx
        val f = F_NORM * CamRec.curZoom.coerceAtLeast(0.5f)
        val k = crop
        return (atan(halfHor / f) - atan(halfHor / (f * k))) to (atan(halfVer / f) - atan(halfVer / (f * k)))
    }

    /** Jeden krok toru wirtualnej kamery (na bieżących danych), z ograniczeniem do zapasu kadru. */
    private fun step(A: DoubleArray, dt: Double, rot: Int, corners: List<DoubleArray>): DoubleArray {
        val Fa = mV(A, EF)
        if (abs(Fa[2]) > 0.92) { pInit = false; return A }   // aparat skierowany pionowo – brak horyzontu
        val yawA = atan2(Fa[1], Fa[0]); val elA = asin(Fa[2].coerceIn(-1.0, 1.0))
        if (!pInit) { pYaw = yawA; pElev = elA; pLam = 0.0; pInit = true }
        val (mY, mE) = margins(rot)
        val dy = wrapPi(yawA - pYaw); val de = elA - pElev
        // wygładzanie adaptacyjne: drobne wahania tłumione mocno, duże ruchy (panorama) śledzone szybciej
        val ty = tauYaw / (1 + (abs(dy) / (0.35 * mY + 1e-6)).let { it * it })
        val tp = tauPitch / (1 + (abs(de) / (0.5 * mE + 1e-6)).let { it * it })
        pYaw = wrapPi(pYaw + dy * dt / (ty + dt))
        pElev += de * dt / (tp + dt)
        var V = level(pYaw, pElev, rightAxis(rot), upAxis(rot))
        val need = needLam(A, V, corners)
        // szybkie przyciąganie, gdy kadr wychodzi poza zapas; łagodne puszczanie
        pLam = max(need, pLam * exp(-dt / 0.35))
        if (pLam > 1e-4) {
            V = mBlend(V, A, pLam)
            val F = mV(V, EF)
            pYaw = atan2(F[1], F[0]); pElev = asin(F[2].coerceIn(-1.0, 1.0))
        }
        return V
    }

    /** Tor dla klatki opóźnionej: średnia toru z chwili klatki i następnych ~0,33 s („wyprzedzenie”). */
    private fun delayedV(tf: Long, A: DoubleArray, dt: Double, rot: Int, corners: List<DoubleArray>): DoubleArray {
        val Fa = mV(A, EF)
        if (abs(Fa[2]) > 0.92 || hist.isEmpty()) return A
        var n = 0; var sy = 0.0; var se = 0.0; var ref = Double.NaN
        for (e in hist) {
            if (e[0] < tf - 1e7 || e[0] > tf + LOOKAHEAD_NS) continue
            if (ref.isNaN()) ref = e[1]
            sy += ref + wrapPi(e[1] - ref); se += e[2]; n++
        }
        if (n == 0) { val e = hist.last(); sy = e[1]; se = e[2]; n = 1 }
        var V = level(wrapPi(sy / n), se / n, rightAxis(rot), upAxis(rot))
        val need = needLam(A, V, corners)
        dLam = max(need, dLam * exp(-dt / 0.35))
        if (dLam > 1e-4) V = mBlend(V, A, dLam)
        return V
    }

    // ---- tryb Sport (dotychczasowy algorytm) ----
    private val legM = FloatArray(16)
    private fun legacyMatrix(tS: Long, dt: Double): FloatArray {
        val s = crop.toFloat()
        val j = FloatArray(3)
        val fz = (F_NORM * CamRec.curZoom.coerceAtLeast(0.5f)).toFloat()
        val aspect = inW.toFloat() / inH
        if (Motion.at(tS, q, thv)) {
            if (!legInit) { thv.copyInto(legLp); legInit = true }
            val a = dt / (0.55 + dt)
            val m = (1f - 1f / s) / 2f
            val lim = doubleArrayOf((m / fz).toDouble(), (m / (fz * aspect)).toDouble(), 0.14)
            for (i in 0..2) {
                legLp[i] += (thv[i] - legLp[i]) * a
                var d = thv[i] - legLp[i]
                if (abs(d) > lim[i]) { legLp[i] = thv[i] - sign(d) * lim[i]; d = thv[i] - legLp[i] }
                j[i] = d.toFloat()
            }
        }
        val dxScreen = fz * j[1]; val dyScreen = fz * j[0]
        var du: Float; var dv: Float
        when (((sensorRot % 360) + 360) % 360) {
            90 -> { du = dyScreen; dv = -dxScreen * aspect }
            270 -> { du = -dyScreen; dv = dxScreen * aspect }
            180 -> { du = -dxScreen; dv = -dyScreen * aspect }
            else -> { du = dxScreen; dv = dyScreen * aspect }
        }
        if (Prefs.sportInvV) du = -du
        if (Prefs.sportInvH) dv = -dv
        val m = (1f - 1f / s) / 2f
        du = du.coerceIn(-m, m); dv = dv.coerceIn(-m, m)
        var deg = Math.toDegrees(j[2].toDouble()).toFloat()
        if (Prefs.sportInvRoll) deg = -deg
        Matrix.setIdentityM(legM, 0)
        Matrix.translateM(legM, 0, 0.5f + du, 0.5f + dv, 0f)
        Matrix.scaleM(legM, 0, 1f / s, 1f / s, 1f)
        Matrix.scaleM(legM, 0, 1f / inW, 1f / inH, 1f)
        Matrix.rotateM(legM, 0, deg, 0f, 0f, 1f)
        Matrix.scaleM(legM, 0, inW.toFloat(), inH.toFloat(), 1f)
        Matrix.translateM(legM, 0, -0.5f, -0.5f, 0f)
        return legM
    }

    // =========================================================================
    // Bufor opóźnienia
    // =========================================================================
    private fun allocRing() {
        ringTried = true
        val n = 14
        val bytes = inW.toLong() * inH * 4 * n
        if (bytes > 220L * 1024 * 1024) {
            Log.w(TAG, "ring too large: $bytes"); Notifier.toast("Horyzont płynny: przy tej jakości nagrywam bez wyprzedzenia")
            return
        }
        val t = IntArray(n); GLES20.glGenTextures(n, t, 0)
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { }
        for (id in t) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            texParams(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, inW, inH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        }
        val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            GLES20.glDeleteTextures(n, t, 0); GLES20.glDeleteFramebuffers(1, f, 0)
            Notifier.toast("Horyzont płynny: za mało pamięci – nagrywam bez wyprzedzenia")
            return
        }
        ringTex = t; ringSt = LongArray(n); ringFbo = f[0]; ringStart = 0; ringCount = 0
        ringOk = true
        CamRec.videoDelayMs = LOOKAHEAD_MS
    }

    /** Kopia bieżącej klatki do bufora (w układzie obrazu z matrycy). */
    private fun pushRing(tS: Long) {
        val n = ringTex.size
        if (ringCount == n) { ringStart = (ringStart + 1) % n; ringCount-- }
        val slot = (ringStart + ringCount) % n
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ringFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ringTex[slot], 0)
        GLES20.glViewport(0, 0, inW, inH)
        drawWarp(progOes!!, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes, I4, I3, stM)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        ringSt[slot] = tS
        ringCount++
    }

    private fun emitDelayed(tS: Long, ts: Long, dt: Double, rot: Int, corners: List<DoubleArray>) {
        val n = ringTex.size
        var pick = -1
        for (k in 0 until ringCount) if (tS - ringSt[(ringStart + k) % n] >= LOOKAHEAD_NS) pick = k
        if (pick < 0) return
        val idx = (ringStart + pick) % n
        val tf = ringSt[idx]
        val A = if (Motion.at(tf, q, null)) qToM(q) else null
        val h = if (A != null) homography(A, delayedV(tf, A, dt, rot, corners)) else homography(I3D, I3D)
        val hf = toGl3(h)
        val p = prog2d ?: return
        for ((so, o) in outputs) {
            begin(o.es, so)
            drawWarp(p, GLES20.GL_TEXTURE_2D, ringTex[idx], o.pre, hf, I4)
            drawText(so, o, rot)
            // znacznik czasu bieżący (nie z chwili zdjęcia) – nagranie jest równo przesunięte, a dźwięk wyrównany przy zapisie
            finish(o.es, ts)
        }
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
        // kierunki „w prawo” i „w górę” obrazu w układzie wyjścia
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
