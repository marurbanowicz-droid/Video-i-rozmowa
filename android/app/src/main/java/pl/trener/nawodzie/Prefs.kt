package pl.trener.nawodzie

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

data class Room(var code: String, var name: String)

/** Wszystkie ustawienia i zapisane dane aplikacji (przetrwają aktualizację). */
object Prefs {
    lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (!sp.contains("cid")) sp.edit().putString("cid", genCode(10)).apply()
        if (!sp.contains("web_url")) sp.edit().putString("web_url", BuildConfig.WEB_URL).apply()
        loadRooms()
    }

    private const val ALPH = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val rnd = SecureRandom()
    fun genCode(n: Int = 4) = (1..n).map { ALPH[rnd.nextInt(ALPH.length)] }.joinToString("")
    fun sanitizeCode(s: String) = s.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(8)

    // ---------- ustawienia ----------
    val cid: String get() = sp.getString("cid", "") ?: ""
    /** Stały token tej instalacji dla serwera sygnalizacyjnego – pozwala wrócić pod własny identyfikator po zerwaniu sieci. */
    val sigToken: String get() = sp.getString("sig_token", null) ?: genCode(16).lowercase().also { sp.edit().putString("sig_token", it).apply() }
    /** Ostatnio ustawiona głośność rozmowy (-1 = jeszcze nie ustawiano). */
    var voiceVol: Int
        get() = sp.getInt("voice_vol", -1)
        set(v) = sp.edit().putInt("voice_vol", v).apply()
    var athName: String
        get() = sp.getString("ath_name", "") ?: ""
        set(v) = sp.edit().putString("ath_name", v).apply()
    var lastRoom: String
        get() = sp.getString("last_room", "") ?: ""
        set(v) = sp.edit().putString("last_room", v).apply()

    val coachNoise get() = sp.getBoolean("coach_noise", false)
    val coachWind get() = sp.getBoolean("coach_wind", true)
    val athNoise get() = sp.getBoolean("ath_noise", true)
    val athWind get() = sp.getBoolean("ath_wind", true)
    val crosstalk get() = sp.getBoolean("crosstalk", true)
    val quality: String get() = sp.getString("quality", "FHD") ?: "FHD"
    /** off | std (systemowa) | sport (duże kadrowanie + żyroskop) */
    val stabMode: String get() = sp.getString("stab_mode", null) ?: if (sp.getBoolean("stab", true)) "std" else "off"
    val sportCrop: Float get() = (sp.getString("sport_crop", "1.6") ?: "1.6").toFloatOrNull() ?: 1.6f
    val sportInvH get() = sp.getBoolean("sport_inv_h", false)
    val sportInvV get() = sp.getBoolean("sport_inv_v", false)
    val sportInvRoll get() = sp.getBoolean("sport_inv_roll", false)
    /** Tryby Horyzont: przesunięcie czasu żyroskopu względem klatek (ms) i odwrócenie kierunku korekty. */
    val stabOffsetMs: Long get() = (sp.getString("stab_offset", "10") ?: "10").trim().toLongOrNull()?.coerceIn(-200, 200) ?: 10L
    val stabInvert get() = sp.getBoolean("stab_invert", false)
    /** open (rozmowa ciągła) | ptt (naciśnij, aby mówić) */
    val talkMode: String get() = sp.getString("talk_mode", "open") ?: "open"
    val headsetAction: String get() = sp.getString("headset_action", "mute_all") ?: "mute_all"
    fun volAction(up: Boolean, coach: Boolean): String =
        sp.getString(if (up) "vol_up" else "vol_down", null) ?: if (!coach) "volume" else if (up) "mute_all" else "record"
    /** Wzmocnienie mikrofonu (wysyłanego głosu). Nagranie zawsze dostaje surowy dźwięk. */
    val micGain: Float get() = (sp.getString("mic_gain", "1") ?: "1").toFloatOrNull() ?: 1f
    /** Wzmocnienie odbieranego głosu (WebRTC: 0–10). */
    val gain: Double get() = (sp.getString("gain", "3") ?: "3").toDoubleOrNull() ?: 3.0
    val kbps: Int get() = (sp.getString("kbps", "32") ?: "32").toIntOrNull() ?: 32
    val turnUrl: String get() = (sp.getString("turn_url", "") ?: "").trim()
    val webUrl: String get() = (sp.getString("web_url", BuildConfig.WEB_URL) ?: BuildConfig.WEB_URL).trim().ifEmpty { BuildConfig.WEB_URL }
    val audioOffsetMs: Long get() = (sp.getString("audio_offset", "0") ?: "0").trim().toLongOrNull() ?: 0L
    /** Notatki głosowe: transkrypcja i opcjonalny klucz OpenAI (Whisper). */
    val noteTranscribe get() = sp.getBoolean("note_transcribe", true)
    val openaiKey: String get() = (sp.getString("openai_key", "") ?: "").trim()
    val nSound get() = sp.getBoolean("n_sound", true)
    val nText get() = sp.getBoolean("n_text", true)
    val nVoice get() = sp.getBoolean("n_voice", false)
    var speaker: Boolean
        get() = sp.getBoolean("speaker", false)
        set(v) = sp.edit().putBoolean("speaker", v).apply()

    // ---------- kamera ----------
    val aspect: String get() = sp.getString("aspect", "4:3") ?: "4:3"
    val watermark get() = sp.getBoolean("watermark", true)
    var evIndex: Int
        get() = sp.getInt("ev_index", 0)
        set(v) = sp.edit().putInt("ev_index", v).apply()

    // ---------- głośności ustawione dla konkretnego zawodnika (1.0 = bez zmian) ----------
    fun athRecvVol(cid: String) = sp.getFloat("rv_$cid", 1f)
    fun athSendVol(cid: String) = sp.getFloat("sv_$cid", 1f)
    fun setAthRecvVol(cid: String, v: Float) = sp.edit().putFloat("rv_$cid", v).apply()
    fun setAthSendVol(cid: String, v: Float) = sp.edit().putFloat("sv_$cid", v).apply()

    // ---------- czuwanie w tle ----------
    const val STANDBY_MS = 2 * 3600_000L
    /** Koniec (lub początek) ostatniej sesji – czuwanie działa przez 2 godziny od tej chwili. */
    var lastSession: Long
        get() = sp.getLong("last_session", 0L)
        set(v) = sp.edit().putLong("last_session", v).apply()
    val standbyUntil: Long get() = lastSession + STANDBY_MS
    val standbyCoach get() = sp.getBoolean("standby_coach", true)
    val standbyAth get() = sp.getBoolean("standby_ath", true)
    /** Ustawiane przez „Zamknij aplikację” – czuwanie nie startuje, dopóki użytkownik nie otworzy aplikacji. */
    var standbyPaused: Boolean
        get() = sp.getBoolean("standby_paused", false)
        set(v) = sp.edit().putBoolean("standby_paused", v).commit().let { }
    var wasCoach: Boolean
        get() = sp.getBoolean("was_coach", false)
        set(v) = sp.edit().putBoolean("was_coach", v).apply()
    var wasAthlete: Boolean
        get() = sp.getBoolean("was_athlete", false)
        set(v) = sp.edit().putBoolean("was_athlete", v).apply()
    fun standbyId() = "tnw-a-" + cid.lowercase()

    // ---------- historia pokoju (kto już był) ----------
    data class Seen(val cid: String, val name: String, val last: Long, val app: Boolean)
    fun history(code: String): List<Seen> {
        val raw = sp.getString("hist_$code", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map { Seen(it.getString("cid"), it.optString("name"), it.optLong("last"), it.optBoolean("app")) }
        } catch (_: Exception) { emptyList() }
    }
    fun addHistory(code: String, cid: String, name: String, app: Boolean) {
        val list = history(code).filter { it.cid != cid }.toMutableList()
        list.add(0, Seen(cid, name, System.currentTimeMillis(), app))
        val a = JSONArray(); list.take(30).forEach { a.put(JSONObject().put("cid", it.cid).put("name", it.name).put("last", it.last).put("app", it.app)) }
        sp.edit().putString("hist_$code", a.toString()).apply()
    }
    fun moveHistory(from: String, to: String) {
        val raw = sp.getString("hist_$from", null) ?: return
        sp.edit().putString("hist_$to", raw).remove("hist_$from").apply()
    }
    fun dropHistory(code: String) = sp.edit().remove("hist_$code").apply()
    fun removeHistory(code: String, cid: String) {
        val a = JSONArray(); history(code).filter { it.cid != cid }.forEach { a.put(JSONObject().put("cid", it.cid).put("name", it.name).put("last", it.last).put("app", it.app)) }
        sp.edit().putString("hist_$code", a.toString()).apply()
    }

    // ---------- pokoje ----------
    fun loadRooms(): MutableList<Room> {
        val raw = sp.getString("rooms", null)
        val list = mutableListOf<Room>()
        if (raw != null) try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); list.add(Room(o.getString("code"), o.optString("name", "Pokój"))) }
        } catch (_: Exception) {}
        if (list.isEmpty()) {
            list.add(Room(genCode(4), "Mój pokój"))
            saveRooms(list); sp.edit().putString("current_room", list[0].code).apply()
        }
        return list
    }
    fun saveRooms(list: List<Room>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("code", it.code).put("name", it.name)) }
        sp.edit().putString("rooms", a.toString()).apply()
    }
    fun currentRoom(): Room {
        val rs = loadRooms(); val c = sp.getString("current_room", rs[0].code)
        return rs.firstOrNull { it.code == c } ?: rs[0]
    }
    fun setCurrentRoom(code: String) = sp.edit().putString("current_room", code).apply()

    // ---------- sesja (do wznowienia po zamknięciu / aktualizacji) ----------
    fun saveSession(role: String, room: String = "", name: String = "", asCoach: Boolean = false) {
        sp.edit().putString("session", JSONObject().put("role", role).put("room", room).put("name", name).put("asCoach", asCoach)
            .put("ts", System.currentTimeMillis()).toString()).apply()
    }
    fun touchSession() {
        val s = session() ?: return
        sp.edit().putString("session", s.put("ts", System.currentTimeMillis()).toString()).apply()
    }
    fun session(): JSONObject? {
        val raw = sp.getString("session", null) ?: return null
        return try {
            val o = JSONObject(raw)
            if (System.currentTimeMillis() - o.optLong("ts") > 12 * 3600_000L) null else o
        } catch (_: Exception) { null }
    }
    fun clearSession() = sp.edit().remove("session").remove("resume_after_update").apply()
    var resumeAfterUpdate: Boolean
        get() = sp.getBoolean("resume_after_update", false)
        set(v) = sp.edit().putBoolean("resume_after_update", v).apply()
}
