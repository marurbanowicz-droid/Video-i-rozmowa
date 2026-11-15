package pl.trener.nawodzie

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kamera (CameraX) z systemową stabilizacją Samsunga i automatycznym przełączaniem obiektywów przy zoomie.
 * Wideo nagrywane jest bez dźwięku, a dźwięk z mikrofonu rozmowy zapisywany równolegle i łączony po zakończeniu.
 *
 * Pliki robocze (rec_<czas>.mp4 / .pcm / .meta) leżą w pamięci aplikacji i są usuwane dopiero po udanym zapisie
 * w Galerii. Jeśli zapis się nie uda albo aplikacja zostanie zamknięta w trakcie, [recover] dokańcza go przy następnym uruchomieniu.
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
    private val saving = AtomicInteger(0)
    /** Trwa zapisywanie wcześniejszych nagrań w Galerii (w tle – nie blokuje kolejnego nagrania). */
    val muxing: Boolean get() = saving.get() > 0
    @Volatile var lastUri: Uri? = null; private set
    var stabSupported = false; private set
    var stabActive = false; private set
    var onChange: (() -> Unit)? = null

    private var pendingRebind = false
    private var lastZoomKey = ""
    private var sport: SportEffect? = null
    private var appCtx: Context? = null
    /** Aktualny zoom (czytany przez tryb Sport z innego wątku). */
    @Volatile var curZoom = 1f; private set
    val sportActive get() = sport != null
    /** Nazwy (bez rozszerzenia) plików roboczych, które są właśnie nagrywane lub zapisywane. */
    private val inUse: MutableSet<String> = Collections.synchronizedSet(HashSet())

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
            val vb = VideoCapture.Builder(recorder).setTargetRotation(rotation)
            if (want && !prevStabOk && vidStabOk) vb.setVideoStabilizationEnabled(true)
            if (mode == "sport") {
                // Tryb Sport sam koryguje ruch na podstawie żyroskopu. Optyczna stabilizacja obiektywu musi być wtedy
                // wyłączona – inaczej drobne drgania byłyby korygowane dwa razy (raz przez obiektyw, raz przez aplikację).
                try {
                    Camera2Interop.Extender(pb).setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    Camera2Interop.Extender(vb).setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                } catch (e: Throwable) { Log.w(TAG, "ois off", e) }
            }
            val pv = pb.build()
            pv.setSurfaceProvider(surfaceProvider)
            val vc = vb.build()
            stabSupported = prevStabOk || vidStabOk
            stabActive = want && stabSupported
            camera = if (mode == "sport") {
                try {
                    val eff = SportEffect.create(appCtx ?: (o as Context), info?.sensorRotationDegrees ?: 90)
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

    /** Orientacja telefonu. W trakcie nagrywania film zachowuje orientację z chwili startu; nowa wejdzie przy następnym nagraniu. */
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
    private fun recDir(app: Context) = File(app.filesDir, "rec").apply { mkdirs() }

    @SuppressLint("MissingPermission")
    fun startRec(ctx: Context): Boolean {
        val vc = videoCapture ?: run { Notifier.toast("Kamera nie jest gotowa"); return false }
        if (recording != null) return false
        val app = ctx.applicationContext
        val dir = recDir(app)
        val free = try { dir.usableSpace } catch (_: Exception) { Long.MAX_VALUE }
        if (free < 300L * 1024 * 1024) {
            Notifier.toast("Za mało wolnego miejsca w telefonie, żeby nagrywać (zostało ${fmtBytes(free)})"); return false
        }
        if (free < 3L * 1024 * 1024 * 1024) Notifier.toast("Mało miejsca w telefonie: ${fmtBytes(free)}. Zapis nagrania potrzebuje chwilowo dwa razy tyle miejsca, ile zajmuje film.")
        val started = System.currentTimeMillis()
        val base = "rec_$started"
        val vFile = File(dir, "$base.mp4")
        val pw = PcmWriter(File(dir, "$base.pcm"), File(dir, "$base.meta"))
        val sink: (ByteArray, Int, Int) -> Unit = { data, sr, ch -> pw.write(data, sr, ch) }
        inUse.add(base)
        try {
            recording = vc.output.prepareRecording(app, FileOutputOptions.Builder(vFile).build())
                .start(ContextCompat.getMainExecutor(app)) { ev ->
                    when (ev) {
                        is VideoRecordEvent.Start -> { recStartedAt = System.currentTimeMillis(); pw.activate(); notifyChange() }
                        is VideoRecordEvent.Finalize -> {
                            if (Engine.pcmSink === sink) Engine.pcmSink = null
                            pw.close()
                            recording = null
                            // orientacja mogła się zmienić w trakcie nagrania – następne nagranie ma mieć aktualną
                            try { videoCapture?.targetRotation = rotation } catch (_: Exception) {}
                            if (pendingRebind) { pendingRebind = false; rebind() }
                            if (ev.hasError()) Log.w(TAG, "finalize error ${ev.error}", ev.cause)
                            if (vFile.exists() && vFile.length() > 0) {
                                saving.incrementAndGet()
                                Thread { try { save(app, base, started, recovered = false) } finally { saving.decrementAndGet(); inUse.remove(base); notifyChange() } }.start()
                            } else {
                                Notifier.toast("Nagranie nie powiodło się"); cleanup(dir, base); inUse.remove(base)
                            }
                            notifyChange()
                        }
                        else -> {}
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "start", e); inUse.remove(base); pw.close(); cleanup(dir, base)
            Notifier.toast("Nie udało się rozpocząć nagrywania: ${e.message}"); return false
        }
        Engine.pcmSink = sink
        recStartedAt = System.currentTimeMillis()
        notifyChange()
        return true
    }

    fun stopRec() { try { recording?.stop() } catch (_: Exception) {} }

    private fun cleanup(dir: File, base: String) {
        for (ext in listOf("mp4", "pcm", "meta", "aac.tmp")) try { File(dir, "$base.$ext").delete() } catch (_: Exception) {}
    }

    /** Zapis nagrania w Galerii: wideo + dźwięk. Pliki robocze znikają dopiero po udanym zapisie. Wywoływać z wątku w tle. */
    private fun save(app: Context, base: String, started: Long, recovered: Boolean, dir: File = recDir(app)) {
        val vFile = File(dir, "$base.mp4"); val pcm = File(dir, "$base.pcm"); val meta = File(dir, "$base.meta")
        var sr = 0; var ch = 1
        try { meta.readText().trim().split(",").let { sr = it[0].toInt(); ch = it[1].toInt() } } catch (_: Exception) {}
        var audioMissing = false
        val uri: Uri? = try {
            Muxer.mux(app, vFile, pcm, sr, ch, Prefs.audioOffsetMs, started)
        } catch (e: Throwable) {
            Log.e(TAG, "mux", e)
            try { Muxer.saveVideoOnly(app, vFile, started).also { audioMissing = true } } catch (e2: Throwable) { Log.e(TAG, "copy", e2); null }
        }
        when {
            uri != null -> {
                cleanup(dir, base)
                lastUri = uri
                Notifier.toast(when {
                    audioMissing -> "Nie udało się dołączyć dźwięku – zapisano samo wideo"
                    recovered -> "Odzyskano nagranie z poprzedniej sesji – jest w Galerii"
                    else -> "Zapisano w Galerii (Filmy/TrenerNaWodzie)"
                })
            }
            !Muxer.isPlayable(vFile) -> {
                // nagranie przerwane w trakcie (np. zamknięcie aplikacji) – pliku nie da się odtworzyć
                cleanup(dir, base)
                Notifier.toast(if (recovered) "Przerwanego nagrania z poprzedniej sesji nie udało się odzyskać" else "Nagranie jest uszkodzone i nie zostało zapisane")
            }
            else -> Notifier.toast("Nie udało się zapisać nagrania w Galerii (brak miejsca?). Film jest bezpieczny w aplikacji – zwolnij miejsce i uruchom ją ponownie, a zapis zostanie dokończony.")
        }
    }

    /** Przy starcie aplikacji: dokańcza zapis nagrań, które zostały w plikach roboczych (przerwany zapis, brak miejsca). */
    fun recover(app: Context) {
        val dirs = listOf(recDir(app), app.cacheDir)   // cacheDir: nagrania z wersji ≤ 1.5.0
        val jobs = ArrayList<Pair<File, String>>()
        for (d in dirs) {
            val files = d.listFiles() ?: continue
            for (f in files) {
                val n = f.name
                if (!n.startsWith("rec_")) continue
                val base = n.substringBefore('.')
                if (base in inUse) continue
                if (n.endsWith(".mp4")) jobs.add(d to base)
                else if (!File(d, "$base.mp4").exists()) try { f.delete() } catch (_: Exception) {}   // osierocony dźwięk bez wideo
            }
        }
        if (jobs.isEmpty()) return
        jobs.forEach { inUse.add(it.second) }
        saving.incrementAndGet()
        Thread {
            try {
                for ((d, base) in jobs) {
                    val started = base.removePrefix("rec_").toLongOrNull() ?: System.currentTimeMillis()
                    try { save(app, base, started, recovered = true, dir = d) } catch (e: Throwable) { Log.e(TAG, "recover", e) }
                    inUse.remove(base)
                }
            } finally { saving.decrementAndGet(); notifyChange() }
        }.start()
    }

    /** Zapis surowego dźwięku (PCM 16 bit) z mikrofonu rozmowy; częstotliwość i liczba kanałów trafiają do pliku .meta. */
    class PcmWriter(val file: File, private val meta: File) {
        private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        @Volatile private var active = false
        @Volatile private var closed = false
        @Volatile var sampleRate = 0; private set
        @Volatile var channels = 1; private set
        fun activate() { active = true }
        @Synchronized fun write(data: ByteArray, sr: Int, ch: Int) {
            if (!active || closed) return
            if (sampleRate == 0) {
                sampleRate = sr; channels = ch
                try { meta.writeText("$sr,$ch") } catch (_: Exception) {}
            }
            if (sr != sampleRate || ch != channels) return
            try { out.write(data) } catch (_: Exception) {}
        }
        @Synchronized fun close() { if (closed) return; closed = true; try { out.flush(); out.close() } catch (_: Exception) {} }
    }
}
