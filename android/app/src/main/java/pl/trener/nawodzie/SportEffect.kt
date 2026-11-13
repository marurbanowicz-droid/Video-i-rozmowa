package pl.trener.nawodzie

import android.content.Context
import android.graphics.SurfaceTexture
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
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
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
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.sign

/**
 * Tryb Sport: efekt CameraX (OpenGL), który kadruje obraz i przesuwa / obraca kadr na podstawie żyroskopu.
 * Działa jednocześnie na podglądzie i nagraniu. Duży zapas kadru pozwala wyrównać mocne wahania (np. motorówka).
 */
class SportEffect private constructor(val processor: SportProcessor) :
    CameraEffect(PREVIEW or VIDEO_CAPTURE, processor.executor, processor,
        Consumer<Throwable> { t -> Log.e("SportEffect", "error", t); Notifier.toast("Tryb Sport: błąd (${t.message})") }) {
    companion object {
        fun create(ctx: Context, sensorRotation: Int) = SportEffect(SportProcessor(ctx, sensorRotation))
    }
}

class SportProcessor(ctx: Context, private val sensorRotation: Int) : SurfaceProcessor, SensorEventListener {
    private val thread = HandlerThread("sport-gl").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctxEgl: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPos = 0; private var aTex = 0; private var uTexM = 0
    private var texId = 0
    private var st: SurfaceTexture? = null
    private var inW = 1920; private var inH = 1080
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val stM = FloatArray(16); private val outM = FloatArray(16); private val stabM = FloatArray(16); private val texM = FloatArray(16)
    private var released = false

    private val sm = ctx.applicationContext.getSystemService(SensorManager::class.java)

    // stan stabilizacji (wszystko na wątku [handler])
    private val th = DoubleArray(3); private val lp = DoubleArray(3)
    private var lastGyroTs = 0L
    private val histTs = LongArray(256); private val histJ = Array(256) { FloatArray(3) }
    private var histN = 0; private var histHead = 0

    private val quad: FloatBuffer = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex: FloatBuffer = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    init {
        handler.post {
            try { initGl() } catch (e: Exception) { Log.e(TAG, "gl", e) }
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
        }
    }

    companion object {
        private const val TAG = "SportProcessor"
        private const val F_NORM = 0.667f          // ogniskowa / dłuższy bok kadru dla obiektywu ~24 mm
        private fun floatBuf(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
        private const val VS = """
            attribute vec4 aPosition; attribute vec4 aTexCoord; uniform mat4 uTexMatrix; varying vec2 vTex;
            void main() { gl_Position = aPosition; vTex = (uTexMatrix * aTexCoord).xy; }"""
        private const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; uniform samplerExternalOES sTex; varying vec2 vTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }"""
    }

    // ---------------- EGL / GL ----------------
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
        program = link(compile(GLES20.GL_VERTEX_SHADER, VS), compile(GLES20.GL_FRAGMENT_SHADER, FS))
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexM = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }
    private fun link(v: Int, f: Int): Int {
        val p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        return p
    }

    // ---------------- CameraX ----------------
    override fun onInputSurface(request: SurfaceRequest) {
        if (released) { request.willNotProvideSurface(); return }
        val size = request.resolution
        inW = size.width; inH = size.height
        val tex = SurfaceTexture(texId)
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
                outputs.remove(out)?.let { EGL14.eglDestroySurface(dpy, it) }
                out.close()
            }
        }
        val es = EGL14.eglCreateWindowSurface(dpy, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (es == null || es == EGL14.EGL_NO_SURFACE) { Log.e(TAG, "eglCreateWindowSurface"); return }
        outputs[out] = es
    }

    private fun onFrame() {
        val tex = st ?: return
        if (released) return
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctxEgl)
        try { tex.updateTexImage() } catch (e: Exception) { return }
        tex.getTransformMatrix(stM)
        val ts = tex.timestamp
        buildStabMatrix(ts)
        for ((out, es) in outputs) {
            EGL14.eglMakeCurrent(dpy, es, es, ctxEgl)
            out.updateTransformMatrix(outM, stM)
            Matrix.multiplyMM(texM, 0, stabM, 0, outM, 0)
            GLES20.glViewport(0, 0, out.size.width, out.size.height)
            draw()
            EGLExt.eglPresentationTimeANDROID(dpy, es, ts)
            EGL14.eglSwapBuffers(dpy, es)
        }
    }

    private fun draw() {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(uTexM, 1, false, texM, 0)
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aTex); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ---------------- żyroskop i korekta ----------------
    private fun scale() = Prefs.sportCrop.coerceIn(1.1f, 2.2f)
    private fun fz() = F_NORM * CamRec.curZoom.coerceAtLeast(0.5f)

    override fun onSensorChanged(e: SensorEvent) {
        val ts = e.timestamp
        if (lastGyroTs == 0L) { lastGyroTs = ts; return }
        val dt = ((ts - lastGyroTs) / 1e9).coerceIn(0.0, 0.1); lastGyroTs = ts
        if (dt <= 0) return
        val tau = 0.55
        val a = dt / (tau + dt)
        // maksymalny kąt korekty mieszczący się w zapasie kadru
        val m = (1f - 1f / scale()) / 2f
        val aspect = inW.toFloat() / inH
        val lim = doubleArrayOf((m / fz()).toDouble(), (m / (fz() * aspect)).toDouble(), 0.14)
        val j = histJ[histHead]
        for (i in 0..2) {
            th[i] += e.values[i] * dt
            lp[i] += (th[i] - lp[i]) * a
            var d = th[i] - lp[i]
            if (abs(d) > lim[i]) { lp[i] = th[i] - sign(d) * lim[i]; d = th[i] - lp[i] }
            j[i] = d.toFloat()
        }
        histTs[histHead] = ts
        histHead = (histHead + 1) % histTs.size; if (histN < histTs.size) histN++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun jitterAt(ts: Long): FloatArray {
        if (histN == 0) return floatArrayOf(0f, 0f, 0f)
        val latest = (histHead - 1 + histTs.size) % histTs.size
        if (abs(histTs[latest] - ts) > 1_000_000_000L) return histJ[latest]   // inne zegary – bierzemy najnowszą próbkę
        var idx = latest
        for (k in 0 until histN) {
            val i = (latest - k + histTs.size) % histTs.size
            idx = i
            if (histTs[i] <= ts) break
        }
        return histJ[idx]
    }

    /** Macierz w przestrzeni tekstury: kadrowanie (1/s) + przesunięcie + obrót wokół środka. */
    private fun buildStabMatrix(ts: Long) {
        val s = scale()
        val j = jitterAt(ts)
        val f = fz()
        val aspect = inW.toFloat() / inH
        // przesunięcie obrazu w układzie ekranu (pion telefonu): obrót w bok → x, kiwanie → y
        val dxScreen = f * j[1]             // względem krótszego boku zostanie przeskalowane niżej
        val dyScreen = f * j[0]
        // obrót do układu matrycy (zwykle 90°)
        var du: Float; var dv: Float
        when (((sensorRotation % 360) + 360) % 360) {
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
        Matrix.setIdentityM(stabM, 0)
        Matrix.translateM(stabM, 0, 0.5f + du, 0.5f + dv, 0f)
        Matrix.scaleM(stabM, 0, 1f / s, 1f / s, 1f)
        Matrix.scaleM(stabM, 0, 1f / inW, 1f / inH, 1f)
        Matrix.rotateM(stabM, 0, deg, 0f, 0f, 1f)
        Matrix.scaleM(stabM, 0, inW.toFloat(), inH.toFloat(), 1f)
        Matrix.translateM(stabM, 0, -0.5f, -0.5f, 0f)
    }

    fun release() {
        handler.post {
            released = true
            try { sm.unregisterListener(this) } catch (_: Exception) {}
            for ((out, es) in outputs) { EGL14.eglDestroySurface(dpy, es); try { out.close() } catch (_: Exception) {} }
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
