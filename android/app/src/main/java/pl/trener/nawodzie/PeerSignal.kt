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
 */
class PeerSignal(val id: String, private val l: Listener) {
    interface Listener {
        fun onOpen()
        fun onIdTaken()
        fun onMessage(type: String, src: String?, payload: JSONObject?)
        fun onDisconnected()
    }

    companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        private const val TAG = "PeerSignal"
    }

    private var ws: WebSocket? = null
    private var hb: ScheduledFuture<*>? = null
    @Volatile var open = false; private set
    @Volatile private var closedByUs = false
    private val token = Prefs.genCode(12).lowercase()

    fun connect() {
        val url = "wss://0.peerjs.com:443/peerjs?key=peerjs&id=${URLEncoder.encode(id, "UTF-8")}&token=$token&version=1.5.4"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = try { JSONObject(text) } catch (e: Exception) { return }
                val type = j.optString("type")
                Engine.exec.execute {
                    when (type) {
                        "OPEN" -> { open = true; l.onOpen() }
                        "ID-TAKEN" -> l.onIdTaken()
                        else -> l.onMessage(type, if (j.has("src")) j.optString("src") else null, j.optJSONObject("payload"))
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t); gone()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { gone() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        })
        hb = Engine.exec.scheduleAtFixedRate({ ws?.send("{\"type\":\"HEARTBEAT\"}") }, 5, 5, TimeUnit.SECONDS)
    }

    private fun gone() {
        val wasOpen = open
        open = false
        hb?.cancel(false); hb = null
        if (!closedByUs) Engine.exec.execute { l.onDisconnected() }
        else if (wasOpen) Log.d(TAG, "closed")
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
