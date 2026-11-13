package pl.trener.nawodzie

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

/**
 * Usługa pierwszoplanowa: trzyma sesję przy życiu w tle i przy zgaszonym ekranie
 * (mikrofon, rozmowa, a u trenera także kamera i nagrywanie).
 */
class SessionService : LifecycleService() {
    companion object {
        const val CHANNEL = "session"
        private const val ID = 7
        @Volatile var running = false; private set

        fun start(ctx: Context, coach: Boolean) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, SessionService::class.java).putExtra("coach", coach))

        fun refresh(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, SessionService::class.java).setAction("REFRESH"))
        }

        /** Kończy sesję: zatrzymuje nagrywanie, rozłącza, zamyka usługę. */
        fun endSession(ctx: Context) {
            val app = ctx.applicationContext
            val wasRec = CamRec.recording != null
            CamRec.stopRec()
            Prefs.clearSession()
            Engine.stop()
            mainHandler.postDelayed({ app.stopService(Intent(app, SessionService::class.java)) }, if (wasRec) 2500 else 600)
        }
    }

    private var wl: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var coach = false
    private val ui = Engine.Ui { updateNotification() }

    override fun onCreate() {
        super.onCreate()
        running = true
        Engine.addUi(ui)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            "STOP" -> { endSession(this); return START_NOT_STICKY }
            "REC" -> { if (CamRec.recording != null) CamRec.stopRec() else CamRec.startRec(this); return START_NOT_STICKY }
            "REFRESH" -> { updateNotification(); return START_NOT_STICKY }
        }
        coach = intent?.getBooleanExtra("coach", false) ?: false
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            val type = if (coach) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                       else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(ID, n, type)
        } else startForeground(ID, n)
        acquireLocks()
        if (coach) CamRec.bind(this, this)
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        if (wl == null) wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrenerNaWodzie:session").apply { setReferenceCounted(false); acquire(10 * 60 * 60 * 1000L) }
        if (wifi == null) try {
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TrenerNaWodzie:wifi").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, SessionService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, if (coach) CoachActivity::class.java else AthleteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (coach) {
            val on = Engine.athletesView.count { it.status == "online" }
            (if (CamRec.recording != null) "● Nagrywanie · " else "") + "Zawodnicy online: $on · ${fmtBytes(Engine.bytesSent + Engine.bytesRecv)}"
        } else Engine.athStatus.ifEmpty { "Połączenie z trenerem" }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (coach) "Sesja trenera – ${Prefs.currentRoom().name}" else "Trener na wodzie")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (coach) b.addAction(0, if (CamRec.recording != null) "⏹ Stop nagrywania" else "⏺ Nagrywaj", pi("REC", 1))
        b.addAction(0, "Zakończ", pi("STOP", 2))
        return b.build()
    }

    private var lastText = ""
    private fun updateNotification() {
        if (!running || Engine.role == Engine.Role.NONE) return
        val n = buildNotification()
        val key = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString() + n.actions?.size
        if (key == lastText) return
        lastText = key
        getSystemService(android.app.NotificationManager::class.java).notify(ID, n)
    }

    override fun onDestroy() {
        Engine.removeUi(ui)
        running = false
        if (coach) CamRec.unbind()
        try { wl?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        wl = null; wifi = null
        super.onDestroy()
    }
}
