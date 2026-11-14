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
        if (!sp.contains("cid")) sp.edit().putBoolean("fresh_install", true).apply()
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
    /** open (rozmowa ciągła) | ptt (naciśnij, aby mówić) */
    val talkMode: String get() = sp.getString("talk_mode", "open") ?: "open"
    val headsetAction: String get() = sp.getString("headset_action", "mute_all") ?: "mute_all"
    fun volAction(up: Boolean, coach: Boolean): String =
        sp.getString(if (up) "vol_up" else "vol_down", null) ?: if (up) "mute_all" else if (coach) "record" else "volume"
    /** Wzmocnienie odbieranego głosu (WebRTC: 0–10). */
    val gain: Double get() = (sp.getString("gain", "3") ?: "3").toDoubleOrNull() ?: 3.0
    val kbps: Int get() = (sp.getString("kbps", "32") ?: "32").toIntOrNull() ?: 32
    val turnUrl: String get() = (sp.getString("turn_url", "") ?: "").trim()
    val webUrl: String get() = (sp.getString("web_url", BuildConfig.WEB_URL) ?: BuildConfig.WEB_URL).trim().ifEmpty { BuildConfig.WEB_URL }
    val audioOffsetMs: Long get() = (sp.getString("audio_offset", "0") ?: "0").trim().toLongOrNull() ?: 0L
    val nSound get() = sp.getBoolean("n_sound", true)
    val nText get() = sp.getBoolean("n_text", true)
    val nVoice get() = sp.getBoolean("n_voice", false)
    var speaker: Boolean
        get() = sp.getBoolean("speaker", false)
        set(v) = sp.edit().putBoolean("speaker", v).apply()

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
    fun saveSession(role: String, room: String = "", name: String = "") {
        sp.edit().putString("session", JSONObject().put("role", role).put("room", room).put("name", name)
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
