package pl.trener.nawodzie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Czuwanie w tle (gdy nie trwa sesja):
 *  - trener: zapisane pokoje są „na nasłuchu” – powiadomienie, gdy ktoś próbuje się połączyć,
 *  - zawodnik: czeka na wywołanie od trenera („Trener dzwoni” – Odbierz / Odrzuć).
 * Korzysta z tego samego serwera PeerJS, co rozmowy.
 */
class StandbyService : Service() {
    companion object {
        const val CH_STANDBY = "standby"
        const val CH_JOIN = "joins"
        const val CH_RING = "ring2"
        private const val ID = 21
        const val RING_ID = 31
        @Volatile var running = false; private set

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_STANDBY, "Czuwanie w tle", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_JOIN, "Ktoś łączy się z pokojem", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
            nm.createNotificationChannel(NotificationChannel(CH_RING, "Wywołania od trenera", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true); vibrationPattern = longArrayOf(0, 800, 600, 800, 600, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }

        fun wanted(): Boolean = Engine.role == Engine.Role.NONE &&
            ((Prefs.standbyCoach && Prefs.wasCoach) || (Prefs.standbyAth && Prefs.wasAthlete))

        /** Uruchamia lub zatrzymuje czuwanie zależnie od ustawień i stanu sesji. */
        fun ensure(ctx: Context) {
            val app = ctx.applicationContext
            if (wanted()) {
                try { ContextCompat.startForegroundService(app, Intent(app, StandbyService::class.java)) } catch (_: Exception) {}
            } else stop(app)
        }

        fun stop(ctx: Context) { if (running) ctx.applicationContext.stopService(Intent(ctx.applicationContext, StandbyService::class.java)) }
    }

    private val signals = HashMap<String, PeerSignal>()
    private val retries = HashMap<String, ScheduledFuture<*>>()
    private val backoff = HashMap<String, Long>()
    private val lastJoinNotif = HashMap<String, Long>()
    private var wl: PowerManager.WakeLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        channels(this)
        netCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { Engine.exec.execute { reconnectNow() } }
        }
        try { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb!!) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (!wanted()) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            "DECLINE" -> {
                val room = intent.getStringExtra("room") ?: ""
                Engine.exec.execute { sendDecline(room) }
                getSystemService(NotificationManager::class.java).cancel(RING_ID)
            }
        }
        // trzyma procesor w gotowości, żeby połączenie z serwerem nie wygasło przy zgaszonym ekranie
        if (wl == null) wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrenerNaWodzie:standby").apply { setReferenceCounted(false); acquire() }
        Engine.exec.execute { sync() }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val parts = ArrayList<String>()
        if (Prefs.standbyCoach && Prefs.wasCoach) parts.add("powiadomię, gdy ktoś połączy się z Twoim pokojem")
        if (Prefs.standbyAth && Prefs.wasAthlete) parts.add("trener może do Ciebie zadzwonić")
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_STANDBY)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Trener na wodzie – czuwanie")
            .setContentText(parts.joinToString("; ").replaceFirstChar { it.uppercase() })
            .setOngoing(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_MIN).build()
    }

    // ---------------- połączenia z serwerem ----------------
    private fun desiredIds(): Map<String, String> {   // id -> rola ("room:KOD" albo "ath")
        val m = LinkedHashMap<String, String>()
        if (Prefs.standbyCoach && Prefs.wasCoach) Prefs.loadRooms().forEach { m[Engine.PREFIX + it.code] = "room:" + it.code }
        if (Prefs.standbyAth && Prefs.wasAthlete) m[Prefs.standbyId()] = "ath"
        return m
    }

    private fun sync() {
        if (!running) return
        val want = desiredIds()
        for (id in signals.keys.toList()) if (id !in want) { signals.remove(id)?.close(); retries.remove(id)?.cancel(false) }
        for ((id, kind) in want) if (signals[id] == null && retries[id] == null) connect(id, kind)
    }

    private fun connect(id: String, kind: String) {
        retries.remove(id)
        lateinit var s: PeerSignal
        s = PeerSignal(id, object : PeerSignal.Listener {
            override fun onOpen() { backoff[id] = 0 }
            override fun onIdTaken() { /* pokój zajęty np. przez inną sesję – spróbujemy później */ }
            override fun onMessage(type: String, src: String?, payload: JSONObject?) {
                if (signals[id] !== s || src == null || payload == null) return
                if (type == "OFFER") onOffer(kind, src, payload)
            }
            override fun onDisconnected() {
                if (signals[id] !== s) return
                signals.remove(id); scheduleRetry(id, kind)
            }
        }, heartbeatSec = 25)
        signals[id] = s
        s.connect()
    }

    private fun scheduleRetry(id: String, kind: String) {
        if (!running || retries[id] != null) return
        val b = ((backoff[id] ?: 0L) * 2).coerceIn(10L, 120L)
        backoff[id] = b
        retries[id] = Engine.exec.schedule(Runnable { retries.remove(id); if (running && desiredIds().containsKey(id) && signals[id] == null) connect(id, kind) }, b, TimeUnit.SECONDS)
    }

    private fun reconnectNow() {
        if (!running) return
        for ((id, f) in retries.toMap()) { f.cancel(false); retries.remove(id); backoff[id] = 0 }
        sync()
    }

    // ---------------- zdarzenia ----------------
    private fun onOffer(kind: String, src: String, p: JSONObject) {
        if (kind.startsWith("room:")) {
            val code = kind.removePrefix("room:")
            val md = p.optJSONObject("metadata")
            val name = md?.optString("name")?.ifEmpty { null } ?: "Zawodnik"
            if (md?.optBoolean("mesh") == true) return
            val key = code + "|" + (md?.optString("cid") ?: src)
            val now = System.currentTimeMillis()
            if (now - (lastJoinNotif[key] ?: 0L) < 120_000) return
            lastJoinNotif[key] = now
            val room = Prefs.loadRooms().firstOrNull { it.code == code }
            val pi = PendingIntent.getActivity(this, code.hashCode(), Intent(this, MainActivity::class.java)
                .putExtra("start_coach_room", code).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, CH_JOIN)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("$name chce się połączyć")
                .setContentText("Pokój ${room?.name ?: code} – dotknij, aby rozpocząć sesję")
                .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true).setContentIntent(pi).setTimeoutAfter(15 * 60_000L).build()
            notify(100 + (key.hashCode() and 0xfff), n)
        } else if (kind == "ath" && p.optString("type") == "ring") {
            val code = Prefs.sanitizeCode(p.optString("room"))
            if (code.length < 4) return
            val roomName = p.optString("roomName").ifEmpty { code }
            p.optString("turn").takeIf { it.isNotBlank() }?.let { Prefs.sp.edit().putString("turn_url", it).apply() }
            val answer = PendingIntent.getActivity(this, 50, Intent(this, MainActivity::class.java)
                .putExtra("join_room", code).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val decline = PendingIntent.getService(this, 51, Intent(this, StandbyService::class.java)
                .setAction("DECLINE").putExtra("room", code), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, CH_RING)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Trener dzwoni")
                .setContentText("Pokój: $roomName – odbierz, aby dołączyć do rozmowy")
                .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(answer, true).setContentIntent(answer)
                .addAction(0, "Odrzuć", decline).addAction(0, "Odbierz", answer)
                .setAutoCancel(true).setTimeoutAfter(45_000).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
            n.flags = n.flags or Notification.FLAG_INSISTENT
            notify(RING_ID, n)
        }
    }

    private fun notify(id: Int, n: Notification) {
        try { getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) {}
    }

    private fun sendDecline(room: String) {
        val s = signals[Prefs.standbyId()] ?: return
        if (!s.open) return
        s.send("OFFER", Engine.PREFIX + room, JSONObject().put("type", "ring-decline").put("connectionId", "ring_" + Prefs.genCode(8))
            .put("name", Prefs.athName.ifEmpty { "Zawodnik" }).put("cid", Prefs.cid))
    }

    override fun onDestroy() {
        running = false
        Engine.exec.execute { signals.values.forEach { it.close() }; signals.clear(); retries.values.forEach { it.cancel(false) }; retries.clear() }
        netCb?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        try { wl?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}

/** Po uruchomieniu telefonu lub aktualizacji – wznawia czuwanie. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) StandbyService.ensure(ctx)
    }
}
