package pl.trener.nawodzie

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.media.VolumeProviderCompat

/**
 * Sterowanie przyciskami: środkowy przycisk słuchawek i klawisze głośności.
 * Akcje: mute_all (wycisz wszystko), mute_mic (wycisz mikrofon), ptt (mów), record (nagrywanie), volume (zwykła głośność).
 */
object Controls {
    private var pttDownAt = 0L
    private var lastVol = LongArray(2)
    private lateinit var app: Context

    private fun isCoach() = Engine.role == Engine.Role.COACH

    fun run(action: String, down: Boolean, holdCapable: Boolean) {
        if (Engine.role == Engine.Role.NONE) return
        when (action) {
            "mute_all" -> if (down) Engine.toggleFullMute()
            "mute_mic" -> if (down) toggleMic()
            "ptt" -> if (Prefs.talkMode != "ptt") { if (down) toggleMic() } else ptt(down, holdCapable)
            "record" -> if (down) {
                if (isCoach()) {
                    val rec = CamRec.recording != null
                    if (rec) CamRec.stopRec() else CamRec.startRec(app)
                    Notifier.beep(if (rec) "leave" else "back")
                    SessionService.refresh(app)
                } else Notifier.toast("Nagrywanie jest dostępne u trenera")
            }
        }
    }

    private fun toggleMic() {
        if (isCoach()) { val on = !Engine.micOn; Engine.setMic(on); Notifier.beep(if (on) "join" else "leave") }
        else { val m = !Engine.athMuted; Engine.setAthMuted(m); Notifier.beep(if (m) "leave" else "join") }
    }

    /** Trzymanie przycisku = mówienie. Krótkie kliknięcie (np. słuchawki Bluetooth) = przełączenie. */
    private fun ptt(down: Boolean, holdCapable: Boolean) {
        if (down) {
            if (Engine.pttActive) { Engine.setPtt(false); Notifier.beep("ptt_off") }
            else { Engine.setPtt(true); pttDownAt = SystemClock.elapsedRealtime(); Notifier.beep("ptt") }
        } else if (holdCapable && Engine.pttActive && SystemClock.elapsedRealtime() - pttDownAt > 450) {
            Engine.setPtt(false); Notifier.beep("ptt_off")
        }
    }

    private fun isHeadsetKey(code: Int) = code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE

    /** Obsługa klawiszy, gdy ekran aplikacji jest widoczny. Zwraca true, jeśli klawisz został przejęty. */
    fun dispatch(ev: KeyEvent): Boolean {
        if (Engine.role == Engine.Role.NONE) return false
        val code = ev.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val a = Prefs.volAction(code == KeyEvent.KEYCODE_VOLUME_UP, isCoach())
            if (a == "volume") return false
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) run(a, true, true)
            else if (ev.action == KeyEvent.ACTION_UP) run(a, false, true)
            return true
        }
        if (isHeadsetKey(code)) { headsetKey(ev); return true }
        return false
    }

    fun headsetKey(ev: KeyEvent) {
        if (ev.repeatCount > 0) return
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> run(Prefs.headsetAction, true, true)
            KeyEvent.ACTION_UP -> run(Prefs.headsetAction, false, true)
        }
    }

    /** Klawisze głośności przy zgaszonym ekranie / w tle (przez sesję multimediów). */
    private fun volumeFromSession(direction: Int) {
        if (direction == 0) return
        val up = direction > 0
        val i = if (up) 0 else 1
        val now = SystemClock.elapsedRealtime()
        if (now - lastVol[i] < 700) { lastVol[i] = now; return }   // przytrzymanie generuje serię zdarzeń
        lastVol[i] = now
        val a = Prefs.volAction(up, isCoach())
        if (a == "volume") app.getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        else run(a, true, false)
    }

    // ---------------- sesja multimediów ----------------
    private var session: MediaSessionCompat? = null
    private val volProvider by lazy {
        object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) { onMain { volumeFromSession(direction) } }
        }
    }

    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (session != null) return
        val s = MediaSessionCompat(app, "TrenerNaWodzie")
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val ev = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (!isHeadsetKey(ev.keyCode)) return false
                headsetKey(ev); return true
            }
        })
        s.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
            .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f).build())
        session = s
        applyVolumeMode()
        s.isActive = true
        claimMediaButtons()
    }

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun applyVolumeMode() {
        val s = session ?: return
        val coach = isCoach()
        if (Prefs.volAction(true, coach) == "volume" && Prefs.volAction(false, coach) == "volume") s.setPlaybackToLocal(AudioManager.STREAM_VOICE_CALL)
        else s.setPlaybackToRemote(volProvider)
    }

    fun stop() {
        session?.let { it.isActive = false; it.release() }
        session = null
    }

    /** Krótka cisza odtworzona jako „multimedia” – system kieruje wtedy przyciski słuchawek do tej aplikacji. */
    private fun claimMediaButtons() {
        Thread {
            try {
                val sr = 44100; val n = sr / 5
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                t.write(ShortArray(n), 0, n); t.play(); Thread.sleep(400); t.stop(); t.release()
            } catch (_: Exception) {}
        }.start()
    }

    @Suppress("unused") private val sdk = Build.VERSION.SDK_INT
}
