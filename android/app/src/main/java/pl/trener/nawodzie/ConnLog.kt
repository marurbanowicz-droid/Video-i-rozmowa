package pl.trener.nawodzie

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.webrtc.PeerConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dziennik połączeń z zawodnikami (do diagnostyki zerwań): kto, kiedy, na jak długo, jaką drogą i przez jaką sieć. */
object ConnLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lostAt = HashMap<String, Long>()
    var app: Context? = null

    @Synchronized private fun add(s: String) {
        lines.addLast(fmt.format(Date()) + "  " + s)
        while (lines.size > 60) lines.removeFirst()
    }

    /** Sieć telefonu trenera w tej chwili. */
    private fun net(): String {
        val cm = app?.getSystemService(ConnectivityManager::class.java) ?: return "?"
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "brak sieci"
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "komórkowa"
            else -> "inna"
        }
    }

    fun lost(name: String, why: String) {
        synchronized(this) { lostAt[name] = System.currentTimeMillis() }
        add("✕ $name – utracono ($why; sieć trenera: ${net()})")
    }

    /** Połączono: odczytuje ze statystyk WebRTC, czy rozmowa idzie bezpośrednio, czy przez TURN. */
    fun online(name: String, pc: PeerConnection?) {
        val since = synchronized(this) { lostAt.remove(name) }
        val gap = if (since != null) " po ${(System.currentTimeMillis() - since) / 1000} s przerwy" else ""
        if (pc == null) { add("✓ $name – połączono$gap"); return }
        try {
            pc.getStats { r ->
                var route = "?"
                try {
                    val m = r.statsMap
                    val pair = m.values.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true }
                    val loc = pair?.let { m[it.members["localCandidateId"] as? String] }
                    val rem = pair?.let { m[it.members["remoteCandidateId"] as? String] }
                    val lt = loc?.members?.get("candidateType") as? String
                    val rt = rem?.members?.get("candidateType") as? String
                    val proto = loc?.members?.get("relayProtocol") ?: loc?.members?.get("protocol")
                    route = if (lt == "relay" || rt == "relay") "przez TURN ($proto)" else "bezpośrednio ($lt/$rt)"
                } catch (_: Throwable) {}
                add("✓ $name – połączono$gap, $route, sieć trenera: ${net()}")
            }
        } catch (_: Throwable) { add("✓ $name – połączono$gap") }
    }

    @Synchronized fun report(): String = if (lines.isEmpty()) "" else "\nPołączenia z zawodnikami:\n" + lines.joinToString("\n") + "\n"
}
