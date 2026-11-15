package pl.trener.nawodzie

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Klient serwera sygnalizacyjnego PeerJS (0.peerjs.com) – ten sam protokół, którego używa wersja webowa,
 * dzięki temu aplikacja i strona łączą się ze sobą.
 *
 * - Ping WebSocket co [pingSec] s: zerwane po cichu połączenie (zmiana sieci, utrata zasięgu) jest wykrywane
 *   w kilkanaście sekund, a nie po wielu minutach.
 * - Stały token instalacji: po zerwaniu można od razu wrócić pod ten sam identyfikator
 *   (serwer odrzuca „ID-TAKEN” tylko połączenia z innym tokenem).
 */
class PeerSignal(val id: String, private val l: Listener, private val heartbeatSec: Long = 5, pingSec: Long = 10) {
    interface Listener {
        fun onOpen()
        fun onIdTaken()
        fun onMessage(type: String, src: String?, payload: JSONObject?)
        fun onDisconnected()
    }

    companion object {
        /** Zwykłe zapytania HTTP (aktualizacje, pobieranie) – z limitami czasu, żeby nic nie wisiało w nieskończoność. */
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        /** Krótkie zapytania wykonywane na wątku silnika (dane TURN). */
        val httpQuick: OkHttpClient = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        private val wsBase: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
        private const val TAG = "PeerSignal"
    }

    private val client: OkHttpClient = wsBase.newBuilder().pingInterval(pingSec, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var hb: ScheduledFuture<*>? = null
    @Volatile var open = false; private set
    @Volatile private var closedByUs = false
    @Volatile private var dead = false
    @Volatile private var idTaken = false
    private val token = Prefs.sigToken

    fun connect() {
        val url = "wss://0.peerjs.com:443/peerjs?key=peerjs&id=${URLEncoder.encode(id, "UTF-8")}&token=$token&version=1.5.4"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Gdy wracamy pod identyfikator, który serwer wciąż ma zarejestrowany (ten sam token),
                // serwer nie wysyła komunikatu OPEN – po chwili uznajemy połączenie za otwarte.
                Engine.exec.schedule(Runnable {
                    if (!open && !dead && !idTaken && !closedByUs) { open = true; l.onOpen() }
                }, 2500, TimeUnit.MILLISECONDS)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                val type = j.optString("type")
                Engine.exec.execute {
                    if (closedByUs) return@execute
                    when (type) {
                        "OPEN" -> if (!open) { open = true; l.onOpen() }
                        "ID-TAKEN" -> { idTaken = true; l.onIdTaken() }
                        else -> l.onMessage(type, if (j.has("src")) j.optString("src") else null, j.optJSONObject("payload"))
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}"); gone()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { gone() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        })
        hb = Engine.exec.scheduleAtFixedRate({ try { ws?.send("{\"type\":\"HEARTBEAT\"}") } catch (_: Exception) {} }, heartbeatSec, heartbeatSec, TimeUnit.SECONDS)
    }

    private fun gone() {
        if (dead) return
        dead = true
        open = false
        hb?.cancel(false); hb = null
        if (!closedByUs) Engine.exec.execute { l.onDisconnected() }
    }

    fun send(type: String, dst: String, payload: JSONObject): Boolean =
        ws?.send(JSONObject().put("type", type).put("dst", dst).put("payload", payload).toString()) ?: false

    fun close() {
        closedByUs = true
        hb?.cancel(false); hb = null
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null; open = false
    }
}
