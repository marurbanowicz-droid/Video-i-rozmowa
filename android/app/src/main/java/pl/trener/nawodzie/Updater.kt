package pl.trener.nawodzie

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import okhttp3.Request
import org.json.JSONArray
import java.io.File

/** Sprawdzanie i instalacja aktualizacji z GitHub Releases (tag android-vX.Y.Z). */
object Updater {
    data class Release(val version: String, val apkUrl: String, val page: String, val size: Long = -1)

    @Volatile var available: Release? = null; private set

    fun check(cb: (Release?, String?) -> Unit) {
        Thread {
            try {
                val req = Request.Builder().url("https://api.github.com/repos/${BuildConfig.GH_REPO}/releases?per_page=15")
                    .header("Accept", "application/vnd.github+json").build()
                val body = PeerSignal.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    r.body?.string() ?: "[]"
                }
                val arr = JSONArray(body)
                var best: Release? = null
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
                    val tag = o.optString("tag_name")
                    if (!tag.startsWith("android-v")) continue
                    val v = tag.removePrefix("android-v")
                    val assets = o.optJSONArray("assets") ?: JSONArray()
                    var apk: String? = null; var size = -1L
                    for (j in 0 until assets.length()) {
                        val a = assets.getJSONObject(j)
                        if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); size = a.optLong("size", -1); break }
                    }
                    if (apk == null) continue
                    if (best == null || cmpVer(v, best.version) > 0) best = Release(v, apk, o.optString("html_url"), size)
                }
                val newer = best?.takeIf { cmpVer(it.version, BuildConfig.VERSION_NAME) > 0 }
                available = newer
                onMain { cb(newer, null) }
            } catch (e: Exception) {
                onMain { cb(null, e.message ?: "błąd") }
            }
        }.start()
    }

    /** Pobiera APK i otwiera instalator. Sesja zostanie wznowiona po aktualizacji. */
    fun downloadAndInstall(ctx: Context, rel: Release) {
        if (CamRec.recording != null) { Notifier.toast("Zakończ nagrywanie przed aktualizacją"); return }
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            Notifier.toast("Zezwól aplikacji na instalowanie aktualizacji, potem spróbuj ponownie")
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        Notifier.toast("Pobieram wersję ${rel.version}…")
        Thread {
            try {
                val dir = app.getExternalFilesDir(null) ?: app.filesDir
                val f = File(dir, "aktualizacja-${rel.version}.apk")
                PeerSignal.http.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    f.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                }
                if (rel.size > 0 && f.length() != rel.size) throw IllegalStateException("pobrano ${f.length()} z ${rel.size} bajtów – spróbuj ponownie")
                if (Prefs.session() != null) Prefs.resumeAfterUpdate = true
                installSession(app, f)
            } catch (e: Exception) {
                Notifier.toast("Nie udało się pobrać aktualizacji: ${e.message}")
            }
        }.start()
    }
}

/** Instalacja przez PackageInstaller – system zwraca dokładną przyczynę ewentualnego błędu. */
private fun installSession(app: Context, f: File) {
    val pi = app.packageManager.packageInstaller
    val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    params.setAppPackageName(app.packageName)
    val id = pi.createSession(params)
    pi.openSession(id).use { s ->
        s.openWrite("update.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(app, id, Intent(app, InstallReceiver::class.java), flags)
        s.commit(pending.intentSender)
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val st = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
        when (st) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                Prefs.resumeAfterUpdate = false
                val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "kod $st"
                Notifier.toast("Aktualizacja nieudana: $msg. Pobierz plik ze strony GitHub (Releases).")
            }
        }
    }
}

/** Po aktualizacji: powiadomienie, które jednym dotknięciem wznawia sesję. */
class UpdateReceiver : BroadcastReceiver() {
    companion object { const val CHANNEL = "updates" }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val resume = Prefs.session() != null
        val pi = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java).putExtra("resume", resume)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Zaktualizowano do wersji ${BuildConfig.VERSION_NAME}")
            .setContentText(if (resume) "Dotknij, aby wznowić sesję" else "Dotknij, aby otworzyć aplikację")
            .setContentIntent(pi).setAutoCancel(true).build()
        try { ctx.getSystemService(android.app.NotificationManager::class.java).notify(11, n) } catch (_: SecurityException) {}
    }
}
