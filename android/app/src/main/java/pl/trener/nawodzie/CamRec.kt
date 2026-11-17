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
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraEffect
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.effects.OverlayEffect
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
    private var stabFx: List<StabEffect> = emptyList()
    /** Orientacja ekranu (podgląd) i orientacja nagrania (zamrożona na czas nagrywania) – dla trybów Horyzont. */
    @Volatile var uiRotation = Surface.ROTATION_0; private set
    @Volatile var videoRotation = Surface.ROTATION_0; private set
    /** Opóźnienie obrazu w nagraniu (Horyzont płynny) – ustawiane przez efekt stabilizacji. */
    @Volatile var videoDelayMs = 0
    private var overlay: OverlayEffect? = null
    private val overlayThread by lazy { android.os.HandlerThread("watermark").apply { start() } }
    private val stampFmt = java.text.SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", java.util.Locale.US)
    private var stampSec = 0L
    private var stampText = ""
    private val stampPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
    }
    private var appCtx: Context? = null
    /** Aktualny zoom (czytany przez tryb Sport z innego wątku). */
    @Volatile var curZoom = 1f; private set
    val sportActive get() = stabFx.isNotEmpty()
    private fun releaseStab() { stabFx.forEach { it.processor.release() }; stabFx = emptyList() }
    private fun modeSuffix(m: String) = when (m) { "level" -> "_POZ"; else -> "" }
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
        releaseStab()
        try { overlay?.close() } catch (_: Exception) {}; overlay = null
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
            releaseStab()
            try { overlay?.close() } catch (_: Exception) {}; overlay = null
            val wide = Prefs.aspect == "16:9"
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
                .setAspectRatio(if (wide) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3)
                .build()
            // podgląd w tych samych proporcjach co nagranie – na ekranie widać dokładnie to, co trafi do filmu
            val pb = Preview.Builder().setTargetRotation(rotation).setResolutionSelector(ResolutionSelector.Builder()
                .setAspectRatioStrategy(if (wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            if (want && prevStabOk) pb.setPreviewStabilizationEnabled(true)
            val vb = VideoCapture.Builder(recorder).setTargetRotation(rotation)
            if (want && !prevStabOk && vidStabOk) vb.setVideoStabilizationEnabled(true)
            val glMode = mode == "level" || mode == "sport" || mode == "horizon" || mode == "horizon2"
            // tryb Poziom zostawia optyczną stabilizację obiektywu włączoną – to ona najlepiej tłumi drgania
            if (glMode && mode != "level") {
                // Tryby Sport/Horyzont same korygują ruch na podstawie żyroskopu. Optyczna stabilizacja obiektywu musi być wtedy
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
            camera = if (glMode) {
                val ctx = appCtx ?: (o as Context)
                val sr = info?.sensorRotationDegrees ?: 90
                try {
                    // osobne efekty dla podglądu i nagrania: nagranie może być opóźnione (Horyzont płynny), podgląd nie
                    val pe = StabEffect(CameraEffect.PREVIEW, StabProcessor(ctx, mode, sr, CameraEffect.PREVIEW))
                    val ve = StabEffect(CameraEffect.VIDEO_CAPTURE, StabProcessor(ctx, mode, sr, CameraEffect.VIDEO_CAPTURE))
                    stabFx = listOf(pe, ve)
                    val group = UseCaseGroup.Builder().addUseCase(pv).addUseCase(vc).addEffect(pe).addEffect(ve).build()
                    p.bindToLifecycle(o, sel, group)
                } catch (e: Exception) {
                    Log.w(TAG, "stab x2", e)
                    releaseStab(); p.unbindAll()
                    try {
                        val both = CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE
                        val eff = StabEffect(both, StabProcessor(ctx, mode, sr, both))
                        stabFx = listOf(eff)
                        val group = UseCaseGroup.Builder().addUseCase(pv).addUseCase(vc).addEffect(eff).build()
                        p.bindToLifecycle(o, sel, group)
                    } catch (e2: Exception) {
                        Log.e(TAG, "stab", e2)
                        releaseStab(); p.unbindAll()
                        Notifier.toast("Ten tryb stabilizacji jest niedostępny na tym telefonie – używam zwykłego obrazu")
                        p.bindToLifecycle(o, sel, pv, vc)
                    }
                }
            } else if (Prefs.watermark) {
                try {
                    val eff = makeWatermark()
                    val group = UseCaseGroup.Builder().addUseCase(pv).addUseCase(vc).addEffect(eff).build()
                    p.bindToLifecycle(o, sel, group).also { overlay = eff }
                } catch (e: Exception) {
                    Log.e(TAG, "watermark", e)
                    try { overlay?.close() } catch (_: Exception) {}; overlay = null
                    p.unbindAll()
                    p.bindToLifecycle(o, sel, pv, vc)
                }
            } else p.bindToLifecycle(o, sel, pv, vc)
            applyEv()
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

    /** Data i godzina małą czcionką w prawym dolnym rogu obrazu (podgląd i nagranie). */
    private fun makeWatermark(): OverlayEffect {
        val eff = OverlayEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, 0, android.os.Handler(overlayThread.looper)) { t -> Log.e(TAG, "overlay", t) }
        eff.setOnDrawListener { frame ->
            val c = frame.overlayCanvas
            c.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            val w = frame.size.width.toFloat(); val h = frame.size.height.toFloat()
            val rot = ((frame.rotationDegrees % 360) + 360) % 360
            c.save()
            when (rot) {   // rysujemy w układzie „jak widzi widz”, niezależnie od ułożenia matrycy
                90 -> { c.translate(0f, h); c.rotate(-90f) }
                180 -> { c.translate(w, h); c.rotate(180f) }
                270 -> { c.translate(w, 0f); c.rotate(90f) }
            }
            val uw = if (rot % 180 == 0) w else h; val uh = if (rot % 180 == 0) h else w
            val now = System.currentTimeMillis()
            if (now / 1000 != stampSec) { stampSec = now / 1000; stampText = stampFmt.format(java.util.Date(now)) }
            stampPaint.textSize = minOf(uw, uh) * 0.026f
            val m = minOf(uw, uh) * 0.02f
            c.drawText(stampText, uw - m - stampPaint.measureText(stampText), uh - m, stampPaint)
            c.restore()
            true
        }
        return eff
    }

    // ---------------- ekspozycja ----------------
    /** Krok korekty ekspozycji ≈ 1/3 EV (w jednostkach aparatu). */
    private fun evStep(): Int {
        val st = camera?.cameraInfo?.exposureState ?: return 1
        val s = st.exposureCompensationStep.toFloat()
        return if (s > 0f) Math.round(0.33f / s).coerceAtLeast(1) else 1
    }
    fun evSupported(): Boolean = camera?.cameraInfo?.exposureState?.isExposureCompensationSupported == true
    /** Stała korekta jasności (bez blokowania automatyki): dir = -1 ciemniej, +1 jaśniej, 0 = zerowanie. */
    fun nudgeEv(dir: Int) {
        val st = camera?.cameraInfo?.exposureState ?: return
        val r = st.exposureCompensationRange
        Prefs.evIndex = if (dir == 0) 0 else (Prefs.evIndex + dir * evStep()).coerceIn(r.lower, r.upper)
        applyEv(); notifyChange()
    }
    private fun applyEv() {
        val cam = camera ?: return
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return
        val r = st.exposureCompensationRange
        try { cam.cameraControl.setExposureCompensationIndex(Prefs.evIndex.coerceIn(r.lower, r.upper)) } catch (_: Exception) {}
    }
    fun evLabel(): String {
        val s = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f
        val v = Prefs.evIndex * s
        return if (Prefs.evIndex == 0) "EV 0" else String.format(java.util.Locale.US, "EV %+.1f", v)
    }

    fun setSurface(sp: Preview.SurfaceProvider?) = onMain { surfaceProvider = sp; preview?.setSurfaceProvider(sp) }

    /** Orientacja telefonu. W trakcie nagrywania film zachowuje orientację z chwili startu; nowa wejdzie przy następnym nagraniu. */
    fun setRotation(r: Int) = onMain {
        rotation = r
        uiRotation = r
        if (recording == null) videoRotation = r
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
        videoRotation = rotation
        // tryb stabilizacji i opóźnienie obrazu (Horyzont płynny) – potrzebne przy zapisie, także po odzyskaniu nagrania
        try { File(dir, "$base.info").writeText("${modeSuffix(Prefs.stabMode)};$videoDelayMs") } catch (_: Exception) {}
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
                            videoRotation = rotation
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

    /** W trybie Horyzont płynny obraz w nagraniu jest opóźniony – zatrzymujemy chwilę później, żeby nic nie uciąć. */
    fun stopRec() {
        val r = recording ?: return
        val d = videoDelayMs
        if (d > 0) mainHandler.postDelayed({ try { r.stop() } catch (_: Exception) {} }, d + 150L)
        else try { r.stop() } catch (_: Exception) {}
    }

    private fun cleanup(dir: File, base: String) {
        for (ext in listOf("mp4", "pcm", "meta", "info", "aac.tmp")) try { File(dir, "$base.$ext").delete() } catch (_: Exception) {}
    }

    /** Zapis nagrania w Galerii: wideo + dźwięk. Pliki robocze znikają dopiero po udanym zapisie. Wywoływać z wątku w tle. */
    private fun save(app: Context, base: String, started: Long, recovered: Boolean, dir: File = recDir(app)) {
        val vFile = File(dir, "$base.mp4"); val pcm = File(dir, "$base.pcm"); val meta = File(dir, "$base.meta")
        var sr = 0; var ch = 1
        try { meta.readText().trim().split(",").let { sr = it[0].toInt(); ch = it[1].toInt() } } catch (_: Exception) {}
        var suffix = ""; var delayMs = 0L
        try { File(dir, "$base.info").readText().trim().split(";").let { suffix = it[0]; delayMs = it.getOrNull(1)?.toLongOrNull() ?: 0L } } catch (_: Exception) {}
        var audioMissing = false
        val uri: Uri? = try {
            // obraz opóźniony o delayMs – dźwięk przesuwamy o tyle samo
            Muxer.mux(app, vFile, pcm, sr, ch, Prefs.audioOffsetMs + delayMs, started, suffix)
        } catch (e: Throwable) {
            Log.e(TAG, "mux", e)
            try { Muxer.saveVideoOnly(app, vFile, started, suffix).also { audioMissing = true } } catch (e2: Throwable) { Log.e(TAG, "copy", e2); null }
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
