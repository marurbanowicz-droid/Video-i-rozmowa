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
        Backup.init(this)
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

    fun start(ctx: Context) {
        val a = ctx.getSystemService(AudioManager::class.java); am = a
        prevMode = a.mode
        a.mode = AudioManager.MODE_IN_COMMUNICATION
        // głośność rozmowy na maksimum – głos na wodzie musi być dobrze słyszalny
        try { a.setStreamVolume(AudioManager.STREAM_VOICE_CALL, a.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0) } catch (_: Exception) {}
        try {
            val f = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            a.requestAudioFocus(f); focus = f
        } catch (_: Exception) {}
        apply()
        cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { apply() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { apply() }
        }
        a.registerAudioDeviceCallback(cb, mainHandler)
    }

    fun stop() {
        val a = am ?: return
        cb?.let { a.unregisterAudioDeviceCallback(it) }; cb = null
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
                val devs = a.availableCommunicationDevices
                fun find(vararg types: Int) = devs.firstOrNull { it.type in types }
                val target = if (speaker) find(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                else find(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
                    ?: find(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
                    ?: find(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                if (target != null) a.setCommunicationDevice(target)
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
