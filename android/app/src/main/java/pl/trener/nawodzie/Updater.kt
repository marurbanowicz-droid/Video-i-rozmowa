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
    /** Najnowsza wersja znaleziona przy ostatnim sprawdzeniu (także gdy nie jest nowsza od zainstalowanej). */
    @Volatile var latestSeen: String? = null; private set

    fun check(cb: (Release?, String?) -> Unit) {
        Thread {
            try {
                val req = Request.Builder().url("https://api.github.com/repos/${BuildConfig.GH_REPO}/releases?per_page=15")
                    .header("Accept", "application/vnd.github+json").header("Cache-Control", "no-cache").build()
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
                latestSeen = best?.version
                val newer = best?.takeIf { cmpVer(it.version, BuildConfig.VERSION_NAME) > 0 }
                available = newer
                onMain { cb(newer, null) }
            } catch (e: Exception) {
                onMain { cb(null, e.message ?: "błąd") }
            }
        }.start()
    }

    // ---------------- postęp aktualizacji (widoczny w oknie) ----------------
    data class Progress(val step: Int, val title: String, val detail: String, val pct: Int, val error: Boolean = false, val finished: Boolean = false)
    @Volatile var progress: Progress? = null; private set
    @Volatile private var cancelled = false
    @Volatile var busy = false; private set
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Progress) -> Unit>()
    fun listen(l: (Progress) -> Unit) { listeners.add(l); progress?.let { p -> onMain { l(p) } } }
    fun unlisten(l: (Progress) -> Unit) { listeners.remove(l) }
    internal fun emit(p: Progress) { progress = p; onMain { listeners.forEach { it(p) } } }
    fun cancel() { cancelled = true }

    private fun mb(b: Long) = String.format(java.util.Locale.US, "%.1f", b / 1048576.0).replace('.', ',')
    private fun secs(s: Long) = if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"

    /** Pobiera APK (z postępem), sprawdza go i przekazuje do instalatora systemowego. */
    fun downloadAndInstall(ctx: Context, rel: Release) {
        if (CamRec.recording != null) { emit(Progress(0, "Najpierw zakończ nagrywanie", "Aktualizacja w trakcie nagrywania przerwałaby film.", 0, error = true)); return }
        if (busy) return
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            emit(Progress(0, "Potrzebne pozwolenie", "Otwieram ustawienia: włącz „Zezwalaj z tego źródła”, wróć do aplikacji i dotknij „Aktualizuj” jeszcze raz.", 0, error = true))
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        busy = true; cancelled = false
        val sizeTxt = if (rel.size > 0) "${mb(rel.size)} MB" else "ok. 48 MB"
        emit(Progress(1, "Krok 1/4 · Łączenie z serwerem", "Wersja ${rel.version}, rozmiar $sizeTxt", -1))
        Thread {
            val nm = app.getSystemService(android.app.NotificationManager::class.java)
            try {
                val dir = app.getExternalFilesDir(null) ?: app.filesDir
                dir.listFiles()?.filter { it.name.startsWith("aktualizacja-") }?.forEach { it.delete() }
                val f = File(dir, "aktualizacja-${rel.version}.apk")
                PeerSignal.http.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("serwer odpowiedział błędem ${r.code}")
                    val total = r.body!!.contentLength().takeIf { it > 0 } ?: rel.size
                    val t0 = System.currentTimeMillis()
                    f.outputStream().use { out ->
                        val inp = r.body!!.byteStream(); val buf = ByteArray(64 * 1024); var done = 0L; var lastEmit = 0L
                        while (true) {
                            if (cancelled) throw InterruptedException()
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 300) {
                                lastEmit = now
                                val el = (now - t0).coerceAtLeast(1) / 1000.0
                                val speed = done / el
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                val left = if (total > 0 && speed > 0) ((total - done) / speed).toLong() else -1
                                val det = "${mb(done)} z ${if (total > 0) mb(total) else "?"} MB · ${mb(speed.toLong())} MB/s" +
                                    if (left >= 0) " · zostało ok. ${secs(left)}" else ""
                                emit(Progress(1, "Krok 1/4 · Pobieranie $pct%", det, pct))
                                try {
                                    nm.notify(12, NotificationCompat.Builder(app, UpdateReceiver.CHANNEL).setSmallIcon(R.drawable.ic_notif)
                                        .setContentTitle("Pobieranie wersji ${rel.version} – $pct%").setContentText(det)
                                        .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, pct.coerceAtLeast(0), pct < 0).build())
                                } catch (_: SecurityException) {}
                            }
                        }
                    }
                }
                nm.cancel(12)
                emit(Progress(2, "Krok 2/4 · Sprawdzanie pliku", "Czy plik pobrał się w całości…", -1))
                if (rel.size > 0 && f.length() != rel.size) throw IllegalStateException("plik niepełny (${mb(f.length())} z ${mb(rel.size)} MB) – sprawdź zasięg i spróbuj ponownie")
                emit(Progress(3, "Krok 3/4 · Przygotowanie instalacji", "Przekazuję plik do instalatora systemu…", -1))
                if (Prefs.session() != null) Prefs.resumeAfterUpdate = true
                installSession(app, f)
                emit(Progress(4, "Krok 4/4 · Potwierdź instalację", "W oknie systemu dotknij „Aktualizuj”. Aplikacja zamknie się na chwilę i wróci w nowej wersji – sesję wznowisz jednym dotknięciem.", 100))
            } catch (e: InterruptedException) {
                nm.cancel(12); emit(Progress(0, "Anulowano", "Pobieranie przerwane.", 0, error = true))
            } catch (e: Exception) {
                nm.cancel(12)
                emit(Progress(0, "Aktualizacja nieudana", (e.message ?: "nieznany błąd") + "\n\nMożesz też pobrać plik ręcznie ze strony GitHub (Releases).", 0, error = true))
            } finally { busy = false }
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
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // zapasowo powiadomienie – gdyby system nie pozwolił otworzyć okna instalacji automatycznie
                try {
                    val pi = PendingIntent.getActivity(ctx, 13, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    ctx.getSystemService(android.app.NotificationManager::class.java).notify(13,
                        NotificationCompat.Builder(ctx, UpdateReceiver.CHANNEL).setSmallIcon(R.drawable.ic_notif)
                            .setContentTitle("Aktualizacja gotowa").setContentText("Dotknij, aby zainstalować")
                            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build())
                } catch (_: Exception) {}
                try { ctx.startActivity(confirm) } catch (_: Exception) {}
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                Prefs.resumeAfterUpdate = false
                val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "kod $st"
                Updater.emit(Updater.Progress(0, "Instalacja nieudana", "$msg\n\nPobierz plik ręcznie ze strony GitHub (Releases).", 0, error = true))
                Notifier.toast("Aktualizacja nieudana: $msg")
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
