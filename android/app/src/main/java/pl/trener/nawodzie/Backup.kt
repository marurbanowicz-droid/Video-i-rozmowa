package pl.trener.nawodzie

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject

/**
 * Kopia ustawień (pokoje, kody, imię, wszystkie opcje) w pliku w folderze Pobrane/TrenerNaWodzie.
 * Zapisywana automatycznie po każdej zmianie – przetrwa odinstalowanie aplikacji.
 * Przy zwykłej aktualizacji dane i tak zostają w aplikacji; kopia jest na wypadek ponownej instalacji.
 */
object Backup {
    private const val TAG = "Backup"
    const val FILE_NAME = "ustawienia-trener-na-wodzie.json"
    private val SKIP = setOf("session", "resume_after_update", "last_crash", "battery_asked", "backup_uri", "fresh_install")
    private lateinit var app: Context
    private val save = Runnable { Thread { try { writeAuto() } catch (e: Exception) { Log.w(TAG, "auto", e) } }.start() }
    // trzymamy silną referencję – SharedPreferences przechowuje słuchaczy jako słabe referencje
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key !in SKIP) { mainHandler.removeCallbacks(save); mainHandler.postDelayed(save, 3000) }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        Prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        mainHandler.postDelayed(save, 5000)
    }

    fun toJson(): String {
        val p = JSONObject()
        for ((k, v) in Prefs.sp.all) {
            if (k in SKIP || v == null) continue
            val e = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is String -> JSONObject().put("t", "s").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                else -> null
            } ?: continue
            p.put(k, e)
        }
        return JSONObject().put("app", "trener-na-wodzie").put("version", BuildConfig.VERSION_NAME)
            .put("saved", System.currentTimeMillis()).put("prefs", p).toString(2)
    }

    /** Przywraca ustawienia z pliku kopii. Zwraca liczbę przywróconych pozycji. */
    fun restore(json: String): Int {
        val o = JSONObject(json)
        if (o.optString("app") != "trener-na-wodzie") throw IllegalArgumentException("to nie jest kopia ustawień tej aplikacji")
        val p = o.getJSONObject("prefs")
        val ed = Prefs.sp.edit()
        var n = 0
        for (k in p.keys()) {
            if (k in SKIP) continue
            val e = p.getJSONObject(k)
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "s" -> ed.putString(k, e.getString("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                else -> continue
            }
            n++
        }
        ed.putBoolean("fresh_install", false)
        ed.commit()
        return n
    }

    fun restoreFrom(ctx: Context, uri: Uri): Int {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw IllegalStateException("nie można odczytać pliku")
        return restore(text)
    }

    /** Zapis do Pobrane/TrenerNaWodzie – nadpisuje własny plik kopii. */
    @Synchronized
    fun writeAuto(): Boolean {
        val data = toJson().toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= 29) {
            val r = app.contentResolver
            var uri = Prefs.sp.getString("backup_uri", null)?.let { Uri.parse(it) }
            if (uri != null) {
                val ok = try { r.openOutputStream(uri, "wt")?.use { it.write(data) } != null } catch (_: Exception) { false }
                if (ok) return true
            }
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TrenerNaWodzie")
            }
            uri = r.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv) ?: return false
            r.openOutputStream(uri, "wt")?.use { it.write(data) } ?: return false
            Prefs.sp.edit().putString("backup_uri", uri.toString()).apply()
            return true
        } else {
            return try {
                @Suppress("DEPRECATION")
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TrenerNaWodzie").apply { mkdirs() }
                java.io.File(dir, FILE_NAME).writeBytes(data); true
            } catch (_: Exception) { false }
        }
    }
}
