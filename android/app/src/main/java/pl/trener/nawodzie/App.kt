package pl.trener.nawodzie

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                Prefs.sp.edit().putString("last_crash", "Wersja ${BuildConfig.VERSION_NAME}, wątek ${t.name}\n" + sw.toString().take(6000)).commit()
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
        Engine.init(this)
        Notifier.init(this)
        Controls.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(SessionService.CHANNEL, "Sesja treningowa", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(UpdateReceiver.CHANNEL, "Aktualizacje", NotificationManager.IMPORTANCE_DEFAULT))
        StandbyService.channels(this)
        // dokończenie zapisu nagrań przerwanych w poprzedniej sesji (zamknięcie aplikacji, brak miejsca)
        mainHandler.postDelayed({ try { CamRec.recover(this) } catch (_: Exception) {} }, 1500)
    }
}

val mainHandler = Handler(Looper.getMainLooper())
fun onMain(r: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) r() else mainHandler.post(r) }

fun fmtBytes(b: Long): String =
    if (b >= 1L shl 30) String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    else String.format(Locale.US, "%.1f MB", b / 1048576.0)

fun cmpVer(a: String, b: String): Int {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until 3) { val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 }); if (d != 0) return d }
    return 0
}

/** Powiadomienia o dołączeniu / rozłączeniu: dźwięk, tekst, głos. */
object Notifier {
    private lateinit var app: Context
    private var tone: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(app) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pl", "PL")
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                ttsReady = true
            }
        }
    }

    fun beep(kind: String) = onMain {
        try {
            val g = tone ?: ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90).also { tone = it }
            // tryb ponton: te same dźwięki co mikrofon, ale powtórzone – włączenie 3× niski, wyłączenie 2× podwójny wysoki
            if (kind == "ponton_on" || kind == "ponton_off") {
                val on = kind == "ponton_on"
                val t = if (on) ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_ACK
                val n = if (on) 3 else 2; val gap = if (on) 450L else 600L
                for (i in 0 until n) mainHandler.postDelayed({ try { g.startTone(t, 400) } catch (_: Exception) {} }, i * gap)
                return@onMain
            }
            val t = when (kind) {
                "join" -> ToneGenerator.TONE_PROP_ACK
                "back" -> ToneGenerator.TONE_PROP_BEEP2
                "leave" -> ToneGenerator.TONE_PROP_NACK
                "lost" -> ToneGenerator.TONE_SUP_ERROR
                "ptt" -> ToneGenerator.TONE_PROP_BEEP
                "ptt_off" -> ToneGenerator.TONE_PROP_PROMPT
                else -> ToneGenerator.TONE_PROP_BEEP
            }
            g.startTone(t, if (kind.startsWith("ptt")) 120 else 450)
        } catch (_: Exception) {}
    }

    /** Start / stop nagrywania: dźwięk i wibracja (start – jeden dłuższy, stop – dwa krótkie). */
    fun recCue(start: Boolean) = onMain {
        try {
            val g = tone ?: ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90).also { tone = it }
            g.startTone(if (start) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2, if (start) 300 else 450)
        } catch (_: Exception) {}
        try {
            val v: android.os.Vibrator? = if (android.os.Build.VERSION.SDK_INT >= 31)
                app.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") app.getSystemService(android.os.Vibrator::class.java)
            val p = if (start) longArrayOf(0, 250) else longArrayOf(0, 90, 120, 90)
            v?.vibrate(android.os.VibrationEffect.createWaveform(p, -1))
        } catch (_: Exception) {}
    }

    fun say(text: String) = onMain {
        ensureTts()
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "n" + System.nanoTime())
        else mainHandler.postDelayed({ if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "n") }, 1500)
    }

    fun toast(text: String) = onMain { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }

    fun event(kind: String, text: String) {
        if (Prefs.nSound) beep(kind)
        if (Prefs.nText) toast(text)
        if (Prefs.nVoice) say(text)
    }
}

/** Kierowanie dźwięku rozmowy: słuchawki (przewodowe/Bluetooth) albo głośnik. */
object AudioRouter {
    private var am: AudioManager? = null
    private var cb: AudioDeviceCallback? = null
    private var prevMode = AudioManager.MODE_NORMAL
    private var focus: android.media.AudioFocusRequest? = null
    private var modeListener: Any? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { ch ->
        // po zwykłej rozmowie telefonicznej system oddaje nam dźwięk – przywracamy tryb rozmowy i słuchawki
        if (ch == AudioManager.AUDIOFOCUS_GAIN) mainHandler.postDelayed({ reassert() }, 700)
        // trwała utrata (np. asystent głosowy uruchomiony przyciskiem słuchawek) – po chwili odbieramy dźwięk z powrotem
        if (ch == AudioManager.AUDIOFOCUS_LOSS) mainHandler.postDelayed({
            val a = am; val f = focus
            if (a != null && f != null) { try { a.requestAudioFocus(f) } catch (_: Exception) {}; reassert() }
        }, 2500)
    }

    // Słuchawki Bluetooth potrafią same rozłączyć kanał rozmowy (np. środkowym przyciskiem – „zakończ połączenie”).
    // Sprawdzamy co kilka sekund i po każdej zmianie, czy dźwięk idzie tam, gdzie powinien, i w razie potrzeby przywracamy.
    private var devListener: Any? = null
    private var lastFix = 0L
    private val checkR = object : Runnable { override fun run() { verify(); mainHandler.postDelayed(this, 4000) } }

    private fun target(a: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 31) return null
        val devs = a.availableCommunicationDevices
        fun find(vararg types: Int) = devs.firstOrNull { it.type in types }
        return if (Prefs.speaker) find(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        else find(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
            ?: find(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            ?: find(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }

    private fun verify() {
        val a = am ?: return
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val mode = a.mode
            if (mode != AudioManager.MODE_IN_COMMUNICATION && mode != AudioManager.MODE_NORMAL) return   // trwa zwykła rozmowa telefoniczna
            val want = target(a) ?: return
            val cur = a.communicationDevice
            if (mode == AudioManager.MODE_NORMAL || cur == null || cur.id != want.id) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastFix < 1500) return
                lastFix = now
                if (mode == AudioManager.MODE_NORMAL) a.mode = AudioManager.MODE_IN_COMMUNICATION
                a.setCommunicationDevice(want)
                Controls.rebase(); Controls.reclaim()
            }
        } catch (_: Exception) {}
    }

    private fun reassert() {
        val a = am ?: return
        try { if (a.mode == AudioManager.MODE_NORMAL) a.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
        apply()
    }

    fun start(ctx: Context) {
        val a = ctx.getSystemService(AudioManager::class.java); am = a
        prevMode = a.mode
        a.mode = AudioManager.MODE_IN_COMMUNICATION
        // głośność rozmowy: ostatnio ustawiona przez użytkownika; przy pierwszym użyciu maksimum (na wodzie musi być słychać)
        try {
            val max = a.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val saved = Prefs.voiceVol
            a.setStreamVolume(AudioManager.STREAM_VOICE_CALL, if (saved in 1..max) saved else max, 0)
        } catch (_: Exception) {}
        try {
            val f = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener, mainHandler).build()
            a.requestAudioFocus(f); focus = f
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 31) try {
            val l = AudioManager.OnModeChangedListener { m -> if (m == AudioManager.MODE_NORMAL) mainHandler.postDelayed({ reassert() }, 1000) }
            a.addOnModeChangedListener(androidx.core.content.ContextCompat.getMainExecutor(ctx), l); modeListener = l
        } catch (_: Exception) {}
        apply()
        cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { apply() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { apply() }
        }
        a.registerAudioDeviceCallback(cb, mainHandler)
        if (Build.VERSION.SDK_INT >= 31) try {
            val l = AudioManager.OnCommunicationDeviceChangedListener { mainHandler.postDelayed({ verify() }, 1500) }
            a.addOnCommunicationDeviceChangedListener(androidx.core.content.ContextCompat.getMainExecutor(ctx), l); devListener = l
        } catch (_: Exception) {}
        mainHandler.removeCallbacks(checkR); mainHandler.postDelayed(checkR, 4000)
    }

    fun stop() {
        val a = am ?: return
        cb?.let { a.unregisterAudioDeviceCallback(it) }; cb = null
        mainHandler.removeCallbacks(checkR)
        if (Build.VERSION.SDK_INT >= 31) try { (devListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let { a.removeOnCommunicationDeviceChangedListener(it) } } catch (_: Exception) {}
        devListener = null
        if (Build.VERSION.SDK_INT >= 31) try { (modeListener as? AudioManager.OnModeChangedListener)?.let { a.removeOnModeChangedListener(it) } } catch (_: Exception) {}
        modeListener = null
        try { val v = a.getStreamVolume(AudioManager.STREAM_VOICE_CALL); if (v > 0) Prefs.voiceVol = v } catch (_: Exception) {}
        focus?.let { try { a.abandonAudioFocusRequest(it) } catch (_: Exception) {} }; focus = null
        try {
            if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice()
            else { @Suppress("DEPRECATION") a.isSpeakerphoneOn = false; @Suppress("DEPRECATION") a.stopBluetoothSco() }
        } catch (_: Exception) {}
        a.mode = prevMode
        am = null
    }

    fun apply() {
        val a = am ?: return
        val speaker = Prefs.speaker
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                target(a)?.let { a.setCommunicationDevice(it) }
            } else {
                val outs = a.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val bt = outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                val wired = outs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                @Suppress("DEPRECATION")
                if (!speaker && bt) { a.isSpeakerphoneOn = false; a.startBluetoothSco(); a.isBluetoothScoOn = true }
                else { a.stopBluetoothSco(); a.isBluetoothScoOn = false; a.isSpeakerphoneOn = speaker || (!wired && !bt) }
            }
        } catch (_: Exception) {}
    }
}

/**
 * Trwałe zamknięcie aplikacji: kończy sesję (zapisuje nagranie), wyłącza czuwanie i wszystkie powiadomienia.
 * Czuwanie wróci dopiero, gdy użytkownik sam otworzy aplikację.
 */
object AppExit {
    fun quit(ctx: android.content.Context, activity: android.app.Activity? = null) {
        val app = ctx.applicationContext
        Prefs.standbyPaused = true
        val wasRec = CamRec.recording != null
        if (Engine.role != Engine.Role.NONE) SessionService.endSession(app)
        StandbyService.stop(app)
        activity?.finishAndRemoveTask()
        Notifier.toast(if (wasRec) "Zapisuję nagranie i zamykam aplikację…" else "Aplikacja zamknięta")
        val start = System.currentTimeMillis()
        // czekamy, aż nagranie zostanie zapisane w Galerii (maks. 5 min), dopiero potem zamykamy proces
        val finish = object : Runnable {
            override fun run() {
                if ((CamRec.muxing || CamRec.recording != null) && System.currentTimeMillis() - start < 300_000) { mainHandler.postDelayed(this, 1000); return }
                try { app.getSystemService(android.app.NotificationManager::class.java).cancelAll() } catch (_: Exception) {}
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        mainHandler.postDelayed(finish, if (wasRec) 4000 else 2500)
    }

    /** Okno z pytaniem przed zamknięciem. */
    fun confirm(activity: android.app.Activity) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("Zamknąć aplikację?")
            .setMessage((if (Engine.role != Engine.Role.NONE) "Sesja zostanie zakończona" + (if (CamRec.recording != null) " (nagranie zostanie zapisane)" else "") + ". " else "") +
                "Aplikacja przestanie działać w tle i zniknie jej powiadomienie. Powiadomienia o łączeniu i wywołania od trenera nie będą docierać, dopóki znów jej nie otworzysz.")
            .setPositiveButton("Zamknij") { _, _ -> quit(activity, activity) }
            .setNegativeButton("Anuluj", null).show()
    }
}
