package pl.trener.nawodzie

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Kamera (CameraX) z systemową stabilizacją Samsunga i automatycznym przełączaniem obiektywów przy zoomie.
 * Wideo nagrywane jest bez dźwięku, a dźwięk z mikrofonu rozmowy zapisywany równolegle i łączony po zakończeniu.
 */
object CamRec {
    private const val TAG = "CamRec"
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    var camera: Camera? = null; private set
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var rotation = Surface.ROTATION_0

    @Volatile var recording: Recording? = null; private set
    @Volatile var recStartedAt = 0L; private set
    @Volatile var muxing = false; private set
    @Volatile var lastUri: Uri? = null; private set
    var stabSupported = false; private set
    var stabActive = false; private set
    var onChange: (() -> Unit)? = null

    private var pcm: PcmWriter? = null
    private var pendingRebind = false
    private var lastZoomKey = ""
    private var sport: SportEffect? = null
    private var appCtx: android.content.Context? = null
    /** Aktualny zoom (czytany przez tryb Sport z innego wątku). */
    @Volatile var curZoom = 1f; private set
    val sportActive get() = sport != null

    fun bind(ctx: Context, lifecycleOwner: LifecycleOwner) {
        owner = lifecycleOwner
        appCtx = ctx.applicationContext
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            try { provider = f.get(); rebind() } catch (e: Exception) { Log.e(TAG, "camera", e); Notifier.toast("Nie udało się uruchomić kamery") }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun unbind() {
        try { recording?.stop() } catch (_: Exception) {}
        provider?.unbindAll()
        sport?.processor?.release(); sport = null
        camera = null; preview = null; videoCapture = null; owner = null
        notifyChange()
    }

    /** Ponowne podpięcie kamery – po zmianie jakości lub stabilizacji (nie w trakcie nagrywania). */
    fun rebind() = onMain {
        if (recording != null) { pendingRebind = true; return@onMain }
        val p = provider ?: return@onMain
        val o = owner ?: return@onMain
        try {
            p.unbindAll()
            sport?.processor?.release(); sport = null
            val sel = CameraSelector.DEFAULT_BACK_CAMERA
            val info = try { p.getCameraInfo(sel) } catch (_: Exception) { null }
            val prevStabOk = info != null && Preview.getPreviewCapabilities(info).isStabilizationSupported
            val vidStabOk = info != null && Recorder.getVideoCapabilities(info).isStabilizationSupported
            val mode = Prefs.stabMode
            val want = mode == "std"
            val (q, br) = when (Prefs.quality) {
                "HD" -> Quality.HD to 6_000_000
                "UHD" -> Quality.UHD to 40_000_000
                else -> Quality.FHD to 14_000_000
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setTargetVideoEncodingBitRate(br)
                .build()
            val pb = Preview.Builder().setTargetRotation(rotation)
            if (want && prevStabOk) pb.setPreviewStabilizationEnabled(true)
            val pv = pb.build()
            pv.setSurfaceProvider(surfaceProvider)
            val vb = VideoCapture.Builder(recorder).setTargetRotation(rotation)
            if (want && !prevStabOk && vidStabOk) vb.setVideoStabilizationEnabled(true)
            val vc = vb.build()
            stabSupported = prevStabOk || vidStabOk
            stabActive = want && stabSupported
            camera = if (mode == "sport") {
                try {
                    val eff = SportEffect.create(appCtx ?: (o as android.content.Context), info?.sensorRotationDegrees ?: 90)
                    val group = UseCaseGroup.Builder().addUseCase(pv).addUseCase(vc).addEffect(eff).build()
                    p.bindToLifecycle(o, sel, group).also { sport = eff }
                } catch (e: Exception) {
                    Log.e(TAG, "sport", e)
                    sport?.processor?.release(); sport = null
                    Notifier.toast("Tryb Sport niedostępny na tym telefonie – używam zwykłego obrazu")
                    p.unbindAll()
                    p.bindToLifecycle(o, sel, pv, vc)
                }
            } else p.bindToLifecycle(o, sel, pv, vc)
            lastZoomKey = ""
            camera?.cameraInfo?.zoomState?.observe(o) { zs ->
                curZoom = zs.zoomRatio
                val k = "${zs.minZoomRatio}-${zs.maxZoomRatio}"
                if (k != lastZoomKey) { lastZoomKey = k; notifyChange() }
            }
            preview = pv; videoCapture = vc
        } catch (e: Exception) {
            Log.e(TAG, "bind", e); Notifier.toast("Błąd kamery: ${e.message}")
        }
        notifyChange()
    }

    fun setSurface(sp: Preview.SurfaceProvider?) = onMain { surfaceProvider = sp; preview?.setSurfaceProvider(sp) }

    fun setRotation(r: Int) = onMain {
        rotation = r
        preview?.targetRotation = r
        if (recording == null) videoCapture?.targetRotation = r
    }

    fun setZoom(ratio: Float) { camera?.cameraControl?.setZoomRatio(ratio) }
    fun zoomRange(): Pair<Float, Float> {
        val z = camera?.cameraInfo?.zoomState?.value ?: return 1f to 1f
        return z.minZoomRatio to z.maxZoomRatio
    }
    fun zoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    private fun notifyChange() = onMain { onChange?.invoke(); Engine.changed() }

    // ---------------- nagrywanie ----------------
    @SuppressLint("MissingPermission")
    fun startRec(ctx: Context): Boolean {
        val vc = videoCapture ?: run { Notifier.toast("Kamera nie jest gotowa"); return false }
        if (recording != null || muxing) return false
        val app = ctx.applicationContext
        val base = "rec_" + System.currentTimeMillis()
        val vFile = File(app.cacheDir, "$base.mp4")
        val pw = PcmWriter(File(app.cacheDir, "$base.pcm"))
        pcm = pw
        Engine.pcmSink = { data, sr, ch -> pw.write(data, sr, ch) }
        recording = vc.output.prepareRecording(app, FileOutputOptions.Builder(vFile).build())
            .start(ContextCompat.getMainExecutor(app)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Start -> { recStartedAt = System.currentTimeMillis(); pw.activate(); notifyChange() }
                    is VideoRecordEvent.Finalize -> {
                        Engine.pcmSink = null
                        pw.close()
                        recording = null
                        if (pendingRebind) { pendingRebind = false; rebind() }
                        if (ev.hasError()) Log.w(TAG, "finalize error ${ev.error}", ev.cause)
                        if (vFile.exists() && vFile.length() > 0) finish(app, vFile, pw)
                        else { Notifier.toast("Nagranie nie powiodło się"); vFile.delete(); pw.file.delete() }
                        notifyChange()
                    }
                    else -> {}
                }
            }
        recStartedAt = System.currentTimeMillis()
        notifyChange()
        return true
    }

    fun stopRec() { try { recording?.stop() } catch (_: Exception) {} }

    private fun finish(app: Context, vFile: File, pw: PcmWriter) {
        muxing = true; notifyChange()
        Thread {
            val uri = try {
                Muxer.mux(app, vFile, pw.file, pw.sampleRate, pw.channels, Prefs.audioOffsetMs)
            } catch (e: Exception) {
                Log.e(TAG, "mux", e)
                try { Muxer.saveVideoOnly(app, vFile) } catch (e2: Exception) { Log.e(TAG, "copy", e2); null }
                    .also { Notifier.toast("Nie udało się dołączyć dźwięku – zapisano samo wideo") }
            }
            vFile.delete(); pw.file.delete()
            muxing = false
            if (uri != null) { lastUri = uri; Notifier.toast("Zapisano w Galerii (Filmy/TrenerNaWodzie)") }
            notifyChange()
        }.start()
    }

    /** Zapis surowego dźwięku (PCM 16 bit) z mikrofonu rozmowy. */
    class PcmWriter(val file: File) {
        private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        @Volatile private var active = false
        @Volatile private var closed = false
        @Volatile var sampleRate = 0; private set
        @Volatile var channels = 1; private set
        fun activate() { active = true }
        @Synchronized fun write(data: ByteArray, sr: Int, ch: Int) {
            if (!active || closed) return
            if (sampleRate == 0) { sampleRate = sr; channels = ch }
            if (sr != sampleRate || ch != channels) return
            try { out.write(data) } catch (_: Exception) {}
        }
        @Synchronized fun close() { if (closed) return; closed = true; try { out.flush(); out.close() } catch (_: Exception) {} }
    }
}
