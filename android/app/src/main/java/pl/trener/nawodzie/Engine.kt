package pl.trener.nawodzie

import android.content.Context
import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Serce aplikacji: połączenia głosowe z zawodnikami / trenerem (WebRTC), sygnalizacja PeerJS,
 * lista zawodników, powiadomienia, licznik danych. Cała logika działa na jednym wątku [exec].
 */
object Engine {
    enum class Role { NONE, COACH, ATHLETE }

    const val PREFIX = "trener-na-wodzie-v1-"

    /**
     * Wiadomość „pozapołączeniowa” (dzwonienie, odrzucenie). Serwer PeerJS przepuszcza tylko OFFER typu media/data
     * z poprawnie wyglądającym SDP – inne odrzuca i rozłącza nadawcę (sprawdzone doświadczalnie), więc właściwa treść
     * jedzie w polu metadata.
     */
    fun notePayload(meta: JSONObject): JSONObject = JSONObject()
        .put("sdp", JSONObject().put("type", "offer").put("sdp", "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"))
        .put("type", "data").put("connectionId", "dc_" + Prefs.genCode(12).lowercase())
        .put("label", "note").put("reliable", true).put("serialization", "json").put("metadata", meta)
    private const val TAG = "Engine"

    lateinit var app: Context
    val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    fun init(ctx: Context) { app = ctx.applicationContext }

    // ---------------- powiadamianie ekranów ----------------
    fun interface Ui { fun onEngineChanged() }
    private val uis = CopyOnWriteArraySet<Ui>()
    fun addUi(u: Ui) { uis.add(u); onMain { u.onEngineChanged() } }
    fun removeUi(u: Ui) { uis.remove(u) }
    fun changed() = onMain { uis.forEach { it.onEngineChanged() } }

    // ---------------- stan publiczny (czytany przez UI) ----------------
    @Volatile var role = Role.NONE; private set
    /** Ustawiane od razu przy starcie sesji – zanim wątek silnika zdąży zmienić [role]. */
    @Volatile private var starting = false
    val active: Boolean get() = role != Role.NONE || starting
    @Volatile var signalOpen = false; private set
    @Volatile var micOn = true; private set
    /** Wyciszenie wszystkiego: nic nie wysyłamy i nic nie słychać. */
    @Volatile var fullMute = false; private set
    /** Push-to-talk: czy właśnie mówimy. */
    @Volatile var pttActive = false; private set
    @Volatile var bytesSent = 0L; private set
    @Volatile var bytesRecv = 0L; private set
    @Volatile var sessionStart = 0L; private set
    /** Odbiornik surowego dźwięku z mikrofonu (PCM 16 bit) – używany przez nagrywanie. */
    @Volatile var pcmSink: ((ByteArray, Int, Int) -> Unit)? = null

    data class AthleteView(val cid: String, val name: String, val status: String, val muted: Boolean,
                           val sendOn: Boolean = true, val recvVol: Float = 1f, val sendVol: Float = 1f,
                           val battery: Int = -1, val rttMs: Int = -1, val lossPct: Int = -1, val app: Boolean = false,
                           val coach: Boolean = false, val cfg: Map<String, String> = emptyMap())
    /** Rozmowa z jednym zawodnikiem: gdy ustawione, głos trenera idzie tylko do niego. */
    @Volatile var soloCid: String? = null; private set
    @Volatile var athletesView: List<AthleteView> = emptyList(); private set

    data class RosterItem(val id: String, val name: String, val on: Boolean, val pid: String = "",
                          val coach: Boolean = false, val bat: Int = -1, val cfg: Map<String, String> = emptyMap())
    /** Drugi trener w pokoju: dołącza jak zawodnik (przez trenera głównego), ale ma ekran trenera (kamera, nagrywanie). */
    @Volatile var athAsCoach = false; private set
    /** Ekran trenera (wizjer) obsługuje zarówno trenera głównego, jak i drugiego trenera. */
    val coachUi: Boolean get() = role == Role.COACH || (role == Role.ATHLETE && athAsCoach)
    /** Notatka głosowa: wysyłanie głosu do zawodników jest wtedy wstrzymane, a nagranie dźwięku trwa. */
    @Volatile var noteMute = false; private set
    @Volatile var noteSink: ((ByteArray, Int, Int) -> Unit)? = null
    fun setNoteMute(on: Boolean) = exec.execute { noteMute = on; applyLocal(); changed() }
    @Volatile var athCode = ""; private set
    @Volatile var athName = ""; private set
    @Volatile var athRoomName = ""; private set
    @Volatile var athLink = false; private set
    @Volatile var athStatus = ""; private set
    @Volatile var athRoster: List<RosterItem> = emptyList(); private set
    @Volatile var athMuted = false; private set
    /** Czy trener włączył „zawodnicy słyszą się nawzajem” i z kim jest aktywne połączenie. */
    @Volatile var athCross = false; private set
    @Volatile var meshUp: Set<String> = emptySet(); private set

    // ---------------- WebRTC ----------------
    private var factoryInitDone = false
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var ice: List<PeerConnection.IceServer> = emptyList()
    private var signal: PeerSignal? = null
    private var reconnectF: ScheduledFuture<*>? = null
    private val timers = ArrayList<ScheduledFuture<*>>()

    class Link(val connId: String, val peer: String, val type: String, val metadata: JSONObject?) {
        var pc: PeerConnection? = null
        var dc: DataChannel? = null
        var remoteSet = false
        val cands = ArrayList<IceCandidate>()
        var ice: IceConnectionState = IceConnectionState.NEW
        var closed = false
        var cid: String? = null
        val bytes = LongArray(2)
        var rttMs = -1; var lossPct = -1
        var lostPrev = 0L; var recvPrev = 0L
        val created = System.currentTimeMillis()
        var mesh = false
        var remote: AudioTrack? = null
    }
    private val links = HashMap<String, Link>()
    private val closedBytes = LongArray(2)

    // ---------------- trener ----------------
    private class Athlete(val cid: String) {
        var name = ""
        var status = "connecting"   // connecting | online | reconnecting | offline
        var media: Link? = null
        var remote: AudioTrack? = null
        var muted = false
        var badSince = 0L
        var offlineSince = 0L
        var wasOnline = false
        var app = false
        var sendOn = true                       // czy trener nadaje do tego zawodnika
        var recvVol = Prefs.athRecvVol(cid)     // głośność tego zawodnika u trenera
        var sendVol = Prefs.athSendVol(cid)     // głośność trenera u tego zawodnika
        var battery = -1
        var coach = false
        var cfg: Map<String, String> = emptyMap()   // ustawienia zawodnika zgłoszone przez jego aplikację
    }
    private val athletes = LinkedHashMap<String, Athlete>()
    private val dataLinks = HashMap<String, Link>()
    private var loopA: PeerConnection? = null
    private var loopB: PeerConnection? = null

    // ---------------- zawodnik ----------------
    private var athEverLinked = false
    private var athRosterMap: Map<String, RosterItem>? = null
    private var athMedia: Link? = null
    private var athData: Link? = null
    private var athBadSince = 0L
    private var athDataT = 0L
    private var athKbps = 32
    private var athCoachVol = 1f
    private var athTick = 0
    private var athCoachMissing = 0
    private var athNextCall = 0L
    /** Drugi trener: lokalne wyciszenia / wyłączenia nadawania wybranych osób. */
    private val coMuted = HashSet<String>()
    private val coSendOff = HashSet<String>()
    /** Połączenia głosowe zawodnik ↔ zawodnik (każdy z każdym). Klucz: cid drugiego zawodnika. */
    private val mesh = HashMap<String, Link>()

    private fun now() = System.currentTimeMillis()
    private fun rnd() = Prefs.genCode(12).lowercase()

    // =====================================================================
    // START / STOP
    // =====================================================================
    fun startCoach() { starting = true; exec.execute { try { startCoachNow() } finally { starting = false } } }

    private fun startCoachNow() {
        if (role != Role.NONE) return
        StandbyService.releaseIds()   // czuwanie zwalnia identyfikatory pokoi, zanim sesja je zajmie
        role = Role.COACH; micOn = true; fullMute = false; pttActive = false; resetStats()
        try { initRtc(Prefs.coachNoise, Prefs.coachWind) } catch (e: Exception) { Log.e(TAG, "rtc", e); Notifier.toast("Błąd audio: ${e.message}") }
        startLoopback()
        ice = buildIce()
        connectSignal(PREFIX + Prefs.currentRoom().code)
        timers += exec.scheduleAtFixedRate({ safe { checkAthletes() } }, 2, 2, TimeUnit.SECONDS)
        timers += exec.scheduleAtFixedRate({ safe { broadcastRoster() } }, 10, 10, TimeUnit.SECONDS)
        timers += exec.scheduleAtFixedRate({ safe { pollStats() } }, 2, 2, TimeUnit.SECONDS)
        timers += exec.scheduleAtFixedRate({ safe { Prefs.touchSession(); Prefs.lastSession = now() } }, 60, 60, TimeUnit.SECONDS)
        changed()
    }

    fun startAthlete(code: String, name: String, asCoach: Boolean = false) {
        starting = true; exec.execute { try { startAthleteNow(code, name, asCoach) } finally { starting = false } }
    }

    private fun startAthleteNow(code: String, name: String, asCoach: Boolean) {
        if (role != Role.NONE) return
        StandbyService.releaseIds()
        athAsCoach = asCoach; micOn = true; noteMute = false; coMuted.clear(); coSendOff.clear()
        role = Role.ATHLETE; fullMute = false; pttActive = false; resetStats()
        athPeerId = "tnw-" + Prefs.cid.lowercase() + "-" + Prefs.genCode(4).lowercase()
        athCode = code; athName = if (asCoach && !name.startsWith("Trener ")) "Trener $name" else name
        athRoomName = ""; athLink = false; athEverLinked = false
        athRosterMap = null; athRoster = emptyList(); athStatus = "Łączenie z serwerem…"; athMuted = false; athCross = false
        athCoachMissing = 0; athNextCall = 0; athCoachVol = 1f
        try { if (asCoach) initRtc(Prefs.coachNoise, Prefs.coachWind) else initRtc(Prefs.athNoise, Prefs.athWind) } catch (e: Exception) { Log.e(TAG, "rtc", e); athStatus = "Błąd mikrofonu: ${e.message}" }
        ice = buildIce()
        connectSignal(athPeerId)
        timers += exec.scheduleAtFixedRate({ safe { athWatchdog() } }, 3, 3, TimeUnit.SECONDS)
        timers += exec.scheduleAtFixedRate({ safe { pollStats() } }, 2, 2, TimeUnit.SECONDS)
        timers += exec.scheduleAtFixedRate({ safe { Prefs.touchSession(); Prefs.lastSession = now() } }, 60, 60, TimeUnit.SECONDS)
        changed()
    }

    fun stop(done: (() -> Unit)? = null) = exec.execute {
        if (role == Role.ATHLETE) athData?.let { dcSend(it, JSONObject().put("t", "bye")) }
        exec.schedule(Runnable { safe { teardown() }; done?.let { onMain(it) } }, 300, TimeUnit.MILLISECONDS)
    }

    private fun teardown() {
        timers.forEach { it.cancel(false) }; timers.clear()
        reconnectF?.cancel(false); reconnectF = null
        role = Role.NONE; fullMute = false; pttActive = false; soloCid = null; athAsCoach = false; noteMute = false
        links.values.toList().forEach { closeLink(it) }
        links.clear(); athletes.clear(); dataLinks.clear(); mesh.clear(); meshUp = emptySet(); athMedia = null; athData = null
        signal?.close(); signal = null; signalOpen = false
        val la = loopA; val lb = loopB; loopA = null; loopB = null
        la?.close(); lb?.close()
        val lt = localTrack; val src = audioSource; val f = factory; val a = adm
        localTrack = null; audioSource = null; factory = null; adm = null
        exec.schedule(Runnable { safe { la?.dispose(); lb?.dispose(); lt?.dispose(); src?.dispose(); f?.dispose(); a?.release() } }, 1500, TimeUnit.MILLISECONDS)
        onMain { AudioRouter.stop() }
        snapshot(); changed()
    }

    private inline fun safe(block: () -> Unit) { try { block() } catch (e: Exception) { Log.e(TAG, "error", e) } }

    private fun onMicData(format: Int, channels: Int, rate: Int, buf: ByteBuffer) {
        if (format != android.media.AudioFormat.ENCODING_PCM_16BIT) return
        val d = buf.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        d.position(0)
        val n = d.limit()
        val sink = pcmSink; val note = noteSink
        if (sink != null || note != null) {
            val copy = ByteArray(n); d.get(copy); d.position(0)
            sink?.invoke(copy, rate, channels); note?.invoke(copy, rate, channels)
        }
        val g = if (role == Role.NONE) 1f else Prefs.micGain
        if (g == 1f) return
        val sb = d.asShortBuffer()
        for (i in 0 until sb.limit()) {
            val v = (sb.get(i) * g).toInt().coerceIn(-32768, 32767)
            sb.put(i, v.toShort())
        }
    }

    private fun initRtc(ns: Boolean, wind: Boolean) {
        if (!factoryInitDone) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
            factoryInitDone = true
        }
        val a = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(ns && role == Role.ATHLETE && !athAsCoach)
            // surowy dźwięk z mikrofonu: najpierw kopia do nagrania (bez wzmocnienia), potem wzmocnienie wysyłanego głosu
            .setAudioRecordDataCallback { format, channels, rate, buf -> onMicData(format, channels, rate, buf) }
            .createAudioDeviceModule()
        adm = a
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(a).createPeerConnectionFactory()
        makeLocalTrack(ns, wind)
        onMain { AudioRouter.start(app) }
    }

    private fun makeLocalTrack(ns: Boolean, wind: Boolean) {
        val f = factory ?: return
        val mc = MediaConstraints()
        mc.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mc.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", ns.toString()))
        mc.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        mc.mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", wind.toString()))
        val src = f.createAudioSource(mc)
        val tr = f.createAudioTrack("mic" + System.nanoTime(), src)
        tr.setEnabled(localEnabled())
        val oldT = localTrack; val oldS = audioSource
        localTrack = tr; audioSource = src
        val pcs = links.values.mapNotNull { it.pc } + listOfNotNull(loopA)
        for (pc in pcs) for (s in pc.senders) {
            val k = s.track()?.kind()
            if (k == null || k == MediaStreamTrack.AUDIO_TRACK_KIND) try { s.setTrack(tr, false) } catch (_: Exception) {}
        }
        oldT?.dispose(); oldS?.dispose()
    }

    /** Pętla lokalna: utrzymuje mikrofon aktywny także wtedy, gdy nikt nie jest połączony (potrzebne do nagrywania). */
    private fun startLoopback() {
        val f = factory ?: return
        val tr = localTrack ?: return
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val a = f.createPeerConnection(cfg, object : ObsAdapter() {
            override fun onIceCandidate(c: IceCandidate) { exec.schedule(Runnable { loopB?.addIceCandidate(c) }, 600, TimeUnit.MILLISECONDS) }
        }) ?: return
        val b = f.createPeerConnection(cfg, object : ObsAdapter() {
            override fun onIceCandidate(c: IceCandidate) { exec.schedule(Runnable { loopA?.addIceCandidate(c) }, 600, TimeUnit.MILLISECONDS) }
            override fun onTrack(transceiver: RtpTransceiver) { transceiver.receiver.track()?.setEnabled(false) }
        }) ?: return
        loopA = a; loopB = b
        a.addTrack(tr, listOf("loop"))
        createSdp(a, true) { offer ->
            setRemote(b, offer) {
                createSdp(b, false) { ans -> setRemote(a, ans) {} }
            }
        }
    }

    private fun buildIce(): List<PeerConnection.IceServer> {
        val list = mutableListOf(
            PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer()
        )
        val url = Prefs.turnUrl
        if (url.isNotEmpty()) try {
            val body = PeerSignal.httpQuick.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() } ?: ""
            val arr = if (body.trim().startsWith("[")) JSONArray(body) else JSONObject(body).optJSONArray("iceServers") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.opt("urls") ?: o.opt("url")
                val urls = when (u) { is JSONArray -> (0 until u.length()).map { u.getString(it) }; is String -> listOf(u); else -> emptyList() }
                if (urls.isEmpty()) continue
                val bld = PeerConnection.IceServer.builder(urls)
                if (o.has("username")) bld.setUsername(o.getString("username"))
                if (o.has("credential")) bld.setPassword(o.getString("credential"))
                list.add(bld.createIceServer())
            }
        } catch (e: Exception) { Log.w(TAG, "turn", e); Notifier.toast("Nie udało się pobrać danych TURN – łączę bez nich") }
        return list
    }

    // =====================================================================
    // SYGNALIZACJA
    // =====================================================================
    /** Identyfikator zawodnika na serwerze – stały przez całą sesję, żeby inni zawodnicy zawsze mogli się połączyć. */
    private var athPeerId = ""
    private var lastIdTakenToast = 0L
    private fun coachPeerId() = PREFIX + athCode

    private fun connectSignal(id: String) {
        signal?.close()
        signalOpen = false
        lateinit var s: PeerSignal
        s = PeerSignal(id, object : PeerSignal.Listener {
            override fun onOpen() {
                if (signal !== s) return
                signalOpen = true; changed()
                if (role == Role.ATHLETE) {
                    // po powrocie sygnalizacji nie zrywamy działającej rozmowy – dzwonimy tylko, gdy jej nie ma
                    val m = athMedia
                    if (m == null || !isUp(m)) athCall()
                    if (athData?.dc?.state() != DataChannel.State.OPEN) athConnectData()
                }
            }
            override fun onIdTaken() {
                if (signal !== s) return
                if (role == Role.COACH && now() - lastIdTakenToast > 30_000) {
                    lastIdTakenToast = now()
                    Notifier.toast("Ten pokój jest otwarty na innym urządzeniu (np. na stronie) – zamknij go tam. Ponawiam…")
                }
            }
            override fun onMessage(type: String, src: String?, payload: JSONObject?) {
                if (signal !== s) return
                safe { onSignal(type, src, payload) }
            }
            override fun onDisconnected() {
                if (signal !== s) return
                signalOpen = false; changed(); scheduleReconnect()
            }
        })
        signal = s
        s.connect()
    }

    private fun scheduleReconnect() {
        if (role == Role.NONE) return
        if (reconnectF?.isDone == false) return
        reconnectF = exec.schedule(Runnable {
            safe {
                if (role != Role.NONE && !signalOpen)
                    connectSignal(if (role == Role.COACH) PREFIX + Prefs.currentRoom().code else athPeerId)
            }
        }, 4, TimeUnit.SECONDS)
    }

    private fun onSignal(type: String, src: String?, p: JSONObject?) {
        when (type) {
            "OFFER" -> if (src != null && p != null) {
                val md = p.optJSONObject("metadata")
                if (md?.optBoolean("ringDecline") == true) {
                    if (role == Role.COACH) Notifier.event("leave", "${md.optString("name").ifEmpty { "Zawodnik" }} odrzuca połączenie")
                    return
                }
                if (md?.has("ring") == true) return
                if (role == Role.COACH) onOffer(src, p)
                else if (role == Role.ATHLETE && p.optJSONObject("metadata")?.optBoolean("mesh") == true) onMeshOffer(src, p)
            }
            "ANSWER" -> {
                val l = links[p?.optString("connectionId")] ?: return
                val sdp = p?.optJSONObject("sdp") ?: return
                l.pc?.let { pc -> setRemote(pc, parseSdp(sdp)) { l.remoteSet = true; flushCands(l) } }
            }
            "CANDIDATE" -> {
                val l = links[p?.optString("connectionId")] ?: return
                val c = p?.optJSONObject("candidate") ?: return
                val ic = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
                if (l.remoteSet) l.pc?.addIceCandidate(ic) else l.cands.add(ic)
            }
            "EXPIRE" -> if (role == Role.COACH && src != null && src.startsWith("tnw-a-")) {
                val n = pendingRings.remove(src) ?: "Zawodnik"
                Notifier.toast("$n – nieosiągalny (aplikacja wyłączona, czuwanie wyłączone lub brak zasięgu)")
            } else if (role == Role.ATHLETE && src != null && src != coachPeerId()) {
                mesh.entries.filter { it.value.peer == src }.map { it.key to it.value }.forEach { (k, v) -> closeLink(v); mesh.remove(k) }
                refreshMeshUp()
            } else if (role == Role.ATHLETE && src == coachPeerId()) {
                // trenera nie ma na serwerze (serwer nie kolejkuje wiadomości) – ponawiamy coraz rzadziej, do 10 s
                closeLink(athMedia); athMedia = null; closeLink(athData); athData = null
                athCoachMissing = (athCoachMissing + 1).coerceAtMost(14)
                athNextCall = now() + (3000L + 500L * athCoachMissing).coerceAtMost(10_000L)
                if (!athLink) { athStatus = "Trener jeszcze nie jest online – czekam…"; changed() }
            }
            "LEAVE" -> if (src != null) {
                if (role == Role.COACH) athletes.values.filter { it.media?.peer == src }.forEach { goOffline(it) }
                if (role == Role.ATHLETE && src == coachPeerId()) { closeLink(athMedia); athMedia = null; setLink(false) }
            }
            "ERROR" -> Log.w(TAG, "server error: $p")
        }
    }

    private fun parseSdp(o: JSONObject) = SessionDescription(SessionDescription.Type.fromCanonicalForm(o.getString("type")), o.getString("sdp"))
    private fun sdpJson(d: SessionDescription) = JSONObject().put("type", d.type.canonicalForm()).put("sdp", d.description)

    private fun flushCands(l: Link) { val pc = l.pc ?: return; l.cands.forEach { pc.addIceCandidate(it) }; l.cands.clear() }

    private fun sendCandidate(l: Link, c: IceCandidate) {
        if (l.closed) return
        signal?.send("CANDIDATE", l.peer, JSONObject()
            .put("candidate", JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))
            .put("type", l.type).put("connectionId", l.connId))
    }

    // =====================================================================
    // POŁĄCZENIA
    // =====================================================================
    open class ObsAdapter : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(p0: IceConnectionState) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        override fun onTrack(transceiver: RtpTransceiver) {}
    }

    private open class SdpCb : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) { Log.w(TAG, "sdp create: $e") }
        override fun onSetFailure(e: String?) { Log.w(TAG, "sdp set: $e") }
    }

    private fun createSdp(pc: PeerConnection, offer: Boolean, done: (SessionDescription) -> Unit) {
        val cb = object : SdpCb() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                pc.setLocalDescription(object : SdpCb() { override fun onSetSuccess() { exec.execute { safe { done(d) } } } }, d)
            }
        }
        if (offer) pc.createOffer(cb, MediaConstraints()) else pc.createAnswer(cb, MediaConstraints())
    }

    private fun setRemote(pc: PeerConnection, d: SessionDescription, done: () -> Unit) {
        pc.setRemoteDescription(object : SdpCb() { override fun onSetSuccess() { exec.execute { safe(done) } } }, d)
    }

    private fun createPc(l: Link): PeerConnection? {
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory?.createPeerConnection(cfg, object : ObsAdapter() {
            override fun onIceCandidate(c: IceCandidate) { exec.execute { safe { sendCandidate(l, c) } } }
            override fun onIceConnectionChange(p0: IceConnectionState) { exec.execute { if (!l.closed) { l.ice = p0; safe { onLinkIce(l) } } } }
            override fun onDataChannel(dc: DataChannel) { exec.execute { if (!l.closed) attachDc(l, dc) } }
            override fun onTrack(transceiver: RtpTransceiver) {
                val t = transceiver.receiver.track()
                exec.execute { if (!l.closed) safe { onRemoteTrack(l, t) } }
            }
        })
        l.pc = pc
        if (pc != null) links[l.connId] = l
        return pc
    }

    private fun attachDc(l: Link, dc: DataChannel) {
        l.dc = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() { val st = dc.state(); exec.execute { if (!l.closed) safe { onDcState(l, st) } } }
            override fun onMessage(buf: DataChannel.Buffer) {
                val b = ByteArray(buf.data.remaining()); buf.data.get(b)
                val text = String(b, Charsets.UTF_8)
                exec.execute { if (!l.closed) safe { onDcMessage(l, text) } }
            }
        })
        if (dc.state() == DataChannel.State.OPEN) onDcState(l, DataChannel.State.OPEN)
    }

    /** PeerJS (serializacja „json”) wysyła JSON jako bajty UTF-8 w ramce binarnej. */
    private fun dcSend(l: Link, o: JSONObject) {
        val dc = l.dc ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        try { dc.send(DataChannel.Buffer(ByteBuffer.wrap(o.toString().toByteArray(Charsets.UTF_8)), true)) } catch (_: Exception) {}
    }

    private fun closeLink(l: Link?) {
        if (l == null || l.closed) return
        l.closed = true
        links.remove(l.connId)
        closedBytes[0] += l.bytes[0]; closedBytes[1] += l.bytes[1]
        val dc = l.dc; val pc = l.pc
        l.dc = null; l.pc = null
        try { dc?.unregisterObserver(); dc?.close() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
        exec.schedule(Runnable { safe { dc?.dispose(); pc?.dispose() } }, 1, TimeUnit.SECONDS)
    }

    private fun applyBitrate(pc: PeerConnection?, kbps: Int) {
        pc ?: return
        for (s in pc.senders) {
            if (s.track()?.kind() != MediaStreamTrack.AUDIO_TRACK_KIND) continue
            try { val p = s.parameters; for (e in p.encodings) e.maxBitrateBps = kbps * 1000; s.setParameters(p) } catch (_: Exception) {}
        }
    }

    private fun onLinkIce(l: Link) {
        val up = l.ice == IceConnectionState.CONNECTED || l.ice == IceConnectionState.COMPLETED
        when (role) {
            Role.COACH -> {
                if (l.type == "media") {
                    val a = athletes.values.firstOrNull { it.media === l } ?: return
                    when {
                        up -> { a.badSince = 0; if (a.status != "online") setOnline(a) }
                        l.ice == IceConnectionState.FAILED || l.ice == IceConnectionState.CLOSED -> goOffline(a)
                        else -> if (a.badSince == 0L) a.badSince = now()
                    }
                } else if (l.ice == IceConnectionState.FAILED || l.ice == IceConnectionState.CLOSED) {
                    dataLinks.entries.removeAll { it.value === l }; closeLink(l)
                }
            }
            Role.ATHLETE -> {
                if (l.mesh) {
                    if (l.ice == IceConnectionState.FAILED || l.ice == IceConnectionState.CLOSED) dropMesh(l)
                    if (up) applyBitrate(l.pc, athKbps)
                    refreshMeshUp(); return
                }
                if (l === athMedia) {
                    when {
                        up -> { athBadSince = 0; setLink(true); applyBitrate(l.pc, athKbps) }
                        l.ice == IceConnectionState.FAILED -> { setLink(false); athCall() }
                        else -> {}
                    }
                }
            }
            else -> {}
        }
    }

    private fun onDcState(l: Link, st: DataChannel.State) {
        if (st == DataChannel.State.OPEN) {
            if (role == Role.COACH && l.type == "data") {
                dcSend(l, rosterMsg()); dcSend(l, JSONObject().put("t", "cfg").put("kbps", Prefs.kbps))
                athletes[l.cid]?.let { dcSend(l, JSONObject().put("t", "vol").put("v", it.sendVol.toDouble())) }
            }
            if (role == Role.ATHLETE && l === athData) sendStatus()
        } else if (st == DataChannel.State.CLOSED) {
            remoteClosed(l)
        }
    }

    /** Druga strona zamknęła połączenie (PeerJS zamyka kanał danych). */
    private fun remoteClosed(l: Link) {
        when (role) {
            Role.COACH -> if (l.type == "media") athletes.values.firstOrNull { it.media === l }?.let { goOffline(it) }
                          else { dataLinks.entries.removeAll { it.value === l }; closeLink(l) }
            Role.ATHLETE -> {
                if (l.mesh) { dropMesh(l); refreshMeshUp() }
                else if (l === athMedia) { closeLink(l); athMedia = null; setLink(false) }
                else if (l === athData) { closeLink(l); athData = null }
            }
            else -> {}
        }
    }

    private fun onDcMessage(l: Link, text: String) {
        val o = try { JSONObject(text) } catch (_: Exception) { return }
        if (o.optJSONObject("__peerData")?.optString("type") == "close") { remoteClosed(l); return }
        when (role) {
            Role.COACH -> when (o.optString("t")) {
                "bye" -> {
                    val cid = l.cid ?: return
                    athletes[cid]?.let { Notifier.event("leave", "${it.name} rozłącza się"); dropAthlete(cid); broadcastRoster() }
                }
                "st" -> athletes[l.cid]?.let {
                    it.battery = o.optInt("bat", -1)
                    val c = o.optJSONObject("cfg")
                    if (c != null) {
                        val m = HashMap<String, String>(); c.keys().forEach { k -> m[k] = c.optString(k) }
                        if (m != it.cfg) { it.cfg = m; broadcastRoster() }
                    }
                    snapshot(); changed()
                }
                // drugi trener steruje zawodnikiem przez trenera głównego
                "fwd" -> if (athletes[l.cid]?.coach == true) {
                    val tgt = o.optString("cid")
                    dataLinks[tgt]?.let { dcSend(it, JSONObject().put("t", "set").put("k", o.optString("k")).put("v", o.optString("v"))) }
                }
            }
            Role.ATHLETE -> onCoachData(o, l)
            else -> {}
        }
    }

    private fun onRemoteTrack(l: Link, t: MediaStreamTrack?) {
        if (t !is AudioTrack) return
        l.remote = t
        if (role == Role.COACH) athletes.values.firstOrNull { it.media === l }?.remote = t
        applyRemote()
    }

    // ---------------- głośność, wyciszenia, push-to-talk ----------------
    private fun localEnabled(): Boolean = !fullMute && !noteMute && when {
        Prefs.talkMode == "ptt" -> pttActive
        role == Role.ATHLETE -> !athMuted
        else -> micOn
    }
    private fun applyLocal() { localTrack?.setEnabled(localEnabled()) }
    private fun applyRemote() {
        val g = Prefs.gain
        for (l in links.values) {
            val t = l.remote ?: continue
            val a = if (role == Role.COACH) athletes.values.firstOrNull { it.media === l } else null
            val coCid = if (athAsCoach && l.mesh) l.cid else null
            val muted = a?.muted == true || (coCid != null && coCid in coMuted)
            val f = when {
                a != null -> a.recvVol
                coCid != null -> Prefs.athRecvVol(coCid)
                role == Role.ATHLETE && l === athMedia -> athCoachVol
                else -> 1f
            }
            try { t.setEnabled(!fullMute && !muted); t.setVolume((g * f).coerceIn(0.0, 10.0)) } catch (_: Exception) {}
        }
    }
    fun toggleFullMute() = exec.execute {
        fullMute = !fullMute; applyLocal(); applyRemote(); changed()
        if (role == Role.ATHLETE) sendStatus()
        Notifier.beep(if (fullMute) "leave" else "join")
        Notifier.toast(if (fullMute) "Wyciszono wszystko (mikrofon i dźwięk)" else "Dźwięk przywrócony")
    }
    fun setFullMute(on: Boolean) = exec.execute { if (fullMute != on) { fullMute = on; applyLocal(); applyRemote(); changed(); if (role == Role.ATHLETE) sendStatus() } }
    fun setPtt(on: Boolean) = exec.execute { if (pttActive != on) { pttActive = on; applyLocal(); changed() } }

    // =====================================================================
    // TRENER
    // =====================================================================
    private fun onOffer(src: String, p: JSONObject) {
        val connId = p.optString("connectionId").ifEmpty { "x_" + rnd() }
        val type = p.optString("type", "media")
        val md = p.optJSONObject("metadata")
        val cid = md?.optString("cid")?.takeIf { it.isNotEmpty() } ?: src
        val l = Link(connId, src, type, md).also { it.cid = cid }
        val pc = createPc(l) ?: return
        if (type == "media") {
            onAthleteCall(l, cid, (md?.optString("name") ?: "").ifEmpty { "Zawodnik" }.take(24))
            athletes[cid]?.let { it.app = it.app || md?.optBoolean("app") == true; if (md?.optBoolean("coach") == true) it.coach = true }
        }
        else { dataLinks[cid]?.takeIf { it !== l }?.let { closeLink(it) }; dataLinks[cid] = l }
        val sdp = p.optJSONObject("sdp") ?: return
        setRemote(pc, parseSdp(sdp)) {
            l.remoteSet = true; flushCands(l)
            if (type == "media") localTrack?.let { pc.addTrack(it, listOf("coach")) }
            createSdp(pc, false) { ans ->
                signal?.send("ANSWER", src, JSONObject().put("sdp", sdpJson(ans)).put("type", type).put("connectionId", connId))
            }
        }
    }

    private fun onAthleteCall(l: Link, cid: String, name: String) {
        var a = athletes[cid]
        if (a != null) {
            val old = a.media; a.media = null; a.remote = null; closeLink(old)
            a.name = name
            if (a.status != "offline") a.status = "reconnecting"
        } else {
            if (athletes.size >= 12) { closeLink(l); return }
            a = Athlete(cid).also { it.name = name }
            athletes[cid] = a
        }
        a.media = l; a.badSince = 0
        snapshot(); changed()
    }

    private fun setOnline(a: Athlete) {
        val prev = a.status
        a.status = "online"; a.badSince = 0
        when (prev) {
            "offline" -> Notifier.event("back", "${a.name} wraca")
            "connecting" -> Notifier.event("join", "${a.name} dołącza")
        }
        a.wasOnline = true
        if (!a.coach) Prefs.addHistory(Prefs.currentRoom().code, a.cid, a.name, a.app)
        applyBitrate(a.media?.pc, Prefs.kbps)
        applySend(a); applyRemote()
        snapshot(); changed(); broadcastRoster()
    }

    private fun goOffline(a: Athlete) {
        if (a.status == "offline") { val m = a.media; a.media = null; a.remote = null; closeLink(m); return }
        val prev = a.status
        val m = a.media; a.media = null; a.remote = null; closeLink(m)
        if (prev == "connecting" && !a.wasOnline) { dropAthlete(a.cid); return }
        a.status = "offline"; a.offlineSince = now()
        Notifier.event("leave", "Utracono połączenie: ${a.name}")
        snapshot(); changed(); broadcastRoster()
    }

    private fun dropAthlete(cid: String) {
        val a = athletes.remove(cid) ?: return
        closeLink(a.media)
        snapshot(); changed()
    }

    private fun checkAthletes() {
        if (role != Role.COACH) return
        for (a in athletes.values.toList()) {
            if (a.status == "offline") { if (now() - a.offlineSince > 10 * 60_000) { dropAthlete(a.cid); broadcastRoster() }; continue }
            val st = a.media?.ice
            if (st == IceConnectionState.CONNECTED || st == IceConnectionState.COMPLETED) { a.badSince = 0; continue }
            if (st == IceConnectionState.FAILED || st == IceConnectionState.CLOSED || a.media == null) { goOffline(a); continue }
            if (a.badSince == 0L) a.badSince = now()
            val limit = if (a.status == "online") 12_000 else 25_000
            if (now() - a.badSince > limit) goOffline(a)
        }
    }

    private fun rosterMsg(): JSONObject {
        val r = Prefs.currentRoom()
        val list = JSONArray()
        athletes.values.forEach {
            list.put(JSONObject().put("id", it.cid).put("name", it.name).put("on", it.status == "online").put("pid", it.media?.peer ?: "")
                .put("coach", it.coach).put("bat", it.battery).put("cfg", JSONObject(it.cfg as Map<*, *>)))
        }
        return JSONObject().put("t", "roster").put("room", r.name).put("code", r.code).put("cross", Prefs.crosstalk).put("list", list)
    }

    private fun broadcast(o: JSONObject) = dataLinks.values.forEach { dcSend(it, o) }
    private fun broadcastRoster() { if (role == Role.COACH) broadcast(rosterMsg()) }

    private fun snapshot() {
        if (soloCid != null && athletes[soloCid]?.status != "online") { soloCid = null; athletes.values.forEach { applySend(it) } }
        athletesView = athletes.values.map {
            AthleteView(it.cid, it.name, it.status, it.muted, it.sendOn, it.recvVol, it.sendVol, it.battery,
                it.media?.rttMs ?: -1, it.media?.lossPct ?: -1, it.app, it.coach, it.cfg)
        }
    }

    // ---- akcje trenera wywoływane z ekranu ----
    fun setMic(on: Boolean) = exec.execute {
        micOn = on; if (role == Role.ATHLETE) athMuted = !on
        applyLocal(); changed(); if (role == Role.ATHLETE) sendStatus()
    }
    fun toggleAthleteMute(cid: String) = exec.execute {
        if (role == Role.ATHLETE && athAsCoach) {
            val nowMuted = coMuted.add(cid); if (!nowMuted) coMuted.remove(cid)
            applyRemote(); snapshotCo(); changed()
            Notifier.toast(if (nowMuted) "Wyciszono u Ciebie: " + (athRosterMap?.get(cid)?.name ?: "") else "Słyszysz: " + (athRosterMap?.get(cid)?.name ?: ""))
            return@execute
        }
        val a = athletes[cid] ?: return@execute
        a.muted = !a.muted; applyRemote()
        snapshot(); changed()
        Notifier.toast(if (a.muted) "${a.name} wyciszony(a) u Ciebie" else "${a.name} słyszalny(a)")
    }
    // ---- sterowanie pojedynczym zawodnikiem (panel pokoju) ----
    /** Włącza/wyłącza nadawanie głosu trenera do danego zawodnika (z uwzględnieniem rozmowy z jedną osobą). */
    private fun applySend(a: Athlete) {
        val pc = a.media?.pc ?: return
        val on = a.sendOn && (soloCid == null || soloCid == a.cid)
        for (s in pc.senders) {
            if (s.track()?.kind() != MediaStreamTrack.AUDIO_TRACK_KIND) continue
            try { val p = s.parameters; for (e in p.encodings) e.active = on; s.setParameters(p) } catch (_: Exception) {}
        }
    }
    fun setAthleteSend(cid: String, on: Boolean) = exec.execute {
        if (role == Role.ATHLETE && athAsCoach) { if (on) coSendOff.remove(cid) else coSendOff.add(cid); applyMeshSend(); snapshotCo(); changed(); return@execute }
        val a = athletes[cid] ?: return@execute
        a.sendOn = on; applySend(a); snapshot(); changed()
    }
    fun setAthleteRecvVol(cid: String, v: Float) = exec.execute {
        if (role == Role.ATHLETE && athAsCoach) { Prefs.setAthRecvVol(cid, v.coerceIn(0f, 2f)); applyRemote(); snapshotCo(); changed(); return@execute }
        val a = athletes[cid] ?: return@execute
        a.recvVol = v.coerceIn(0f, 2f); Prefs.setAthRecvVol(cid, a.recvVol); applyRemote(); snapshot(); changed()
    }
    fun setAthleteSendVol(cid: String, v: Float) = exec.execute {
        val a = athletes[cid] ?: return@execute
        a.sendVol = v.coerceIn(0f, 2f); Prefs.setAthSendVol(cid, a.sendVol)
        dataLinks[cid]?.let { dcSend(it, JSONObject().put("t", "vol").put("v", a.sendVol.toDouble())) }
        snapshot(); changed()
    }
    /** Rozmowa tylko z jednym zawodnikiem (null = znów do wszystkich). */
    fun setSolo(cid: String?) = exec.execute {
        if (role != Role.COACH) return@execute
        val a = if (cid != null) athletes[cid] else null
        soloCid = if (a != null && a.status == "online") cid else null
        athletes.values.forEach { applySend(it) }
        snapshot(); changed()
        Notifier.beep(if (soloCid != null) "ptt" else "ptt_off")
        Notifier.toast(if (soloCid != null) "Mówisz tylko do: ${a?.name}" else "Mówisz do wszystkich")
    }

    /** Zmienia ustawienie w aplikacji zawodnika (trener główny – bezpośrednio, drugi trener – przez trenera głównego). */
    fun setAthleteSetting(cid: String, key: String, value: String) = exec.execute {
        if (role == Role.COACH) {
            dataLinks[cid]?.let { dcSend(it, JSONObject().put("t", "set").put("k", key).put("v", value)) }
            athletes[cid]?.let { it.cfg = it.cfg + (key to value); snapshot(); changed(); broadcastRoster() }
        } else if (role == Role.ATHLETE && athAsCoach) {
            athData?.let { dcSend(it, JSONObject().put("t", "fwd").put("cid", cid).put("k", key).put("v", value)) }
        }
    }

    fun switchRoom(code: String) = exec.execute {
        if (role != Role.COACH) { Prefs.setCurrentRoom(code); return@execute }
        athletes.keys.toList().forEach { dropAthlete(it) }
        dataLinks.values.toList().forEach { closeLink(it) }; dataLinks.clear()
        Prefs.setCurrentRoom(code)
        connectSignal(PREFIX + code)
        changed()
    }
    fun roomRenamed() = exec.execute { broadcastRoster(); changed() }

    // ---- „zadzwoń” do zawodników, którzy byli wcześniej w pokoju ----
    private val pendingRings = HashMap<String, String>()
    fun ring(list: List<Prefs.Seen>) = exec.execute {
        if (role != Role.COACH) return@execute
        if (!signalOpen) { Notifier.toast("Brak połączenia z serwerem – spróbuj za chwilę"); return@execute }
        val r = Prefs.currentRoom()
        for (s in list) {
            val id = "tnw-a-" + s.cid.lowercase()
            pendingRings[id] = s.name
            signal?.send("OFFER", id, notePayload(JSONObject().put("ring",
                JSONObject().put("room", r.code).put("roomName", r.name).put("turn", Prefs.turnUrl))))
        }
        Notifier.toast("Dzwonię do: " + list.joinToString { it.name })
    }

    // =====================================================================
    // ZAWODNIK
    // =====================================================================
    private fun athMeta() = JSONObject().put("name", athName).put("cid", Prefs.cid).put("app", true).apply { if (athAsCoach) put("coach", true) }

    private fun athCall() {
        if (role != Role.ATHLETE || !signalOpen) return
        closeLink(athMedia); athMedia = null
        val connId = "mc_" + rnd()
        val l = Link(connId, coachPeerId(), "media", athMeta())
        val pc = createPc(l) ?: return
        athMedia = l; athBadSince = 0
        localTrack?.let { pc.addTrack(it, listOf("ath")) }
        // PeerJS 1.5 zamyka rozmowę przez kanał danych – tworzymy go dla zgodności
        pc.createDataChannel(connId, DataChannel.Init().apply { ordered = true })?.let { attachDc(l, it) }
        createSdp(pc, true) { offer ->
            if (l.closed) return@createSdp
            signal?.send("OFFER", l.peer, JSONObject().put("sdp", sdpJson(offer)).put("type", "media")
                .put("connectionId", connId).put("metadata", athMeta()))
        }
        if (!athEverLinked && !athStatus.contains("czekam")) { athStatus = "Dzwonię do trenera…"; changed() }
    }

    private fun athConnectData() {
        if (role != Role.ATHLETE || !signalOpen) return
        closeLink(athData); athData = null
        val connId = "dc_" + rnd()
        val l = Link(connId, coachPeerId(), "data", athMeta())
        val pc = createPc(l) ?: return
        athData = l; athDataT = now()
        pc.createDataChannel(connId, DataChannel.Init().apply { ordered = true })?.let { attachDc(l, it) }
        createSdp(pc, true) { offer ->
            if (l.closed) return@createSdp
            signal?.send("OFFER", l.peer, JSONObject().put("sdp", sdpJson(offer)).put("type", "data")
                .put("connectionId", connId).put("label", connId).put("reliable", true)
                .put("serialization", "json").put("metadata", athMeta()))
        }
    }

    /** Zawodnik wysyła trenerowi poziom baterii (widoczny w panelu pokoju). */
    private fun sendStatus() {
        val l = athData ?: return
        val bat = try { app.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { -1 }
        dcSend(l, JSONObject().put("t", "st").put("bat", bat).put("cfg", currentCfg()))
    }

    fun reportSettings() = exec.execute { if (role == Role.ATHLETE) sendStatus() }

    private fun voicePct(): Int = try {
        val a = app.getSystemService(android.media.AudioManager::class.java); val st = Vol.stream(app)
        a.getStreamVolume(st) * 100 / a.getStreamMaxVolume(st).coerceAtLeast(1)
    } catch (_: Exception) { -1 }

    /** Ustawienia, którymi może sterować trener – zgłaszane trenerowi razem z baterią. */
    private fun currentCfg() = JSONObject()
        .put("speaker", Prefs.speaker.toString()).put("mute", athMuted.toString()).put("full_mute", fullMute.toString())
        .put("gain", Prefs.sp.getString("gain", "3") ?: "3").put("mic_gain", Prefs.sp.getString("mic_gain", "1") ?: "1")
        .put("talk_mode", Prefs.talkMode).put("vol_up", Prefs.volAction(true, athAsCoach)).put("vol_down", Prefs.volAction(false, athAsCoach))
        .put("headset_action", Prefs.headsetAction)
        .put("ath_noise", Prefs.athNoise.toString()).put("ath_wind", Prefs.athWind.toString()).put("voice_pct", voicePct().toString())

    private var lastRemoteToast = 0L
    /** Polecenie zmiany ustawienia od trenera. Wartości są sprawdzane – nieznane klucze i wartości są ignorowane. */
    private fun applyRemoteSetting(k: String, s: String) {
        val ed = Prefs.sp.edit()
        val actions = setOf("volume", "mute_all", "mute_mic", "ptt", "record", "note")
        when (k) {
            "speaker" -> { Prefs.speaker = s == "true"; onMain { AudioRouter.apply() } }
            "mute" -> { athMuted = s == "true"; micOn = !athMuted; applyLocal() }
            "full_mute" -> { fullMute = s == "true"; applyLocal(); applyRemote() }
            "voice_pct" -> s.toIntOrNull()?.let { pct -> onMain {
                try {
                    val a = app.getSystemService(android.media.AudioManager::class.java); val st = Vol.stream(app)
                    a.setStreamVolume(st, (pct.coerceIn(0, 100) * a.getStreamMaxVolume(st) / 100).coerceAtLeast(1), 0); Vol.remember(app)
                } catch (_: Exception) {}
            } }
            "gain" -> s.toDoubleOrNull()?.let { ed.putString("gain", it.coerceIn(0.0, 10.0).toString().removeSuffix(".0")).apply(); applyRemote() }
            "mic_gain" -> s.toFloatOrNull()?.let { ed.putString("mic_gain", it.coerceIn(0.25f, 4f).toString().removeSuffix(".0")).apply() }
            "talk_mode" -> if (s == "open" || s == "ptt") { ed.putString("talk_mode", s).apply(); pttActive = false; applyLocal() }
            "vol_up", "vol_down" -> if (s in actions && ((s != "record" && s != "note") || athAsCoach)) { ed.putString(k, s).apply(); onMain { Controls.applyVolumeMode() } }
            "headset_action" -> if (s in actions && ((s != "record" && s != "note") || athAsCoach)) ed.putString(k, s).apply()
            "ath_noise" -> { ed.putBoolean(k, s == "true").apply(); if (!athAsCoach) makeLocalTrack(Prefs.athNoise, Prefs.athWind) }
            "ath_wind" -> { ed.putBoolean(k, s == "true").apply(); if (!athAsCoach) makeLocalTrack(Prefs.athNoise, Prefs.athWind) }
            else -> return
        }
        if (now() - lastRemoteToast > 10_000) { lastRemoteToast = now(); Notifier.toast("Trener zmienił Twoje ustawienia") }
        sendStatus(); changed()
    }

    private fun athWatchdog() {
        if (role != Role.ATHLETE) return
        if (++athTick % 20 == 0) sendStatus()
        updateMesh()
        val m = athMedia
        if (signalOpen) {
            val dataOk = athData?.dc?.state() == DataChannel.State.OPEN
            val mayCall = now() >= athNextCall
            if (!dataOk && now() - athDataT > 8000 && mayCall) athConnectData()
            if (m == null) { if (mayCall) athCall(); return }
        }
        if (m == null) { setLink(false); return }
        val st = m.ice
        if (st == IceConnectionState.CONNECTED || st == IceConnectionState.COMPLETED) { athBadSince = 0; if (!athLink) setLink(true); return }
        if (st == IceConnectionState.FAILED || st == IceConnectionState.CLOSED) { setLink(false); athCall(); return }
        if (athBadSince == 0L) athBadSince = now()
        if (st == IceConnectionState.DISCONNECTED && now() - athBadSince > 4000) setLink(false)
        if (now() - athBadSince > (if (athLink) 8000 else 15000)) { setLink(false); athCall() }
    }

    private fun setLink(up: Boolean) {
        if (up == athLink) return
        athLink = up
        if (up) {
            athCoachMissing = 0; athNextCall = 0
            athStatus = "Połączono z trenerem"
            if (athEverLinked) Notifier.event("back", "Połączenie z trenerem przywrócone") else Notifier.beep("join")
            athEverLinked = true
        } else if (athEverLinked) {
            athStatus = "Utracono połączenie – łączę ponownie…"
            Notifier.event("lost", "Utracono połączenie z trenerem")
        }
        changed()
    }

    private fun onCoachData(o: JSONObject, l: Link) {
        when (o.optString("t")) {
            "set" -> if (l === athData) applyRemoteSetting(o.optString("k"), o.optString("v"))
            "cfg" -> { athKbps = o.optInt("kbps", 32); applyBitrate(athMedia?.pc, athKbps) }
            "vol" -> { athCoachVol = o.optDouble("v", 1.0).toFloat().coerceIn(0f, 3f); applyRemote() }
            "roster" -> {
                athRoomName = o.optString("room")
                athCross = o.optBoolean("cross", false)
                val arr = o.optJSONArray("list") ?: JSONArray()
                val next = LinkedHashMap<String, RosterItem>()
                for (i in 0 until arr.length()) { val x = arr.getJSONObject(i); 
                    val cj = x.optJSONObject("cfg"); val cm = HashMap<String, String>(); cj?.keys()?.forEach { k -> cm[k] = cj.optString(k) }
                    next[x.optString("id")] = RosterItem(x.optString("id"), x.optString("name"), x.optBoolean("on"), x.optString("pid"), x.optBoolean("coach"), x.optInt("bat", -1), cm) }
                val prev = athRosterMap
                if (prev != null) {
                    for ((id, x) in next) {
                        if (id == Prefs.cid) continue
                        val was = prev[id]
                        if (x.on && (was == null || !was.on)) Notifier.event(if (was != null) "back" else "join", if (was != null) "${x.name} wraca" else "${x.name} dołącza")
                        if (!x.on && was != null && was.on) Notifier.event("leave", "Utracono połączenie: ${x.name}")
                    }
                    for ((id, x) in prev) if (!next.containsKey(id) && x.on && id != Prefs.cid) Notifier.event("leave", "${x.name} rozłącza się")
                }
                athRosterMap = next; athRoster = next.values.toList()
                updateMesh(); if (athAsCoach) snapshotCo()
                changed()
            }
        }
    }

    // ---------------- zawodnik ↔ zawodnik ----------------
    private fun isUp(l: Link) = l.ice == IceConnectionState.CONNECTED || l.ice == IceConnectionState.COMPLETED

    private fun dropMesh(l: Link) {
        val k = mesh.entries.firstOrNull { it.value === l }?.key
        if (k != null) mesh.remove(k)
        closeLink(l)
    }

    private fun refreshMeshUp() {
        val n = mesh.filter { isUp(it.value) }.keys.toSet()
        if (n != meshUp) { meshUp = n; changed() }
        if (athAsCoach) { applyMeshSend(); snapshotCo() }
    }

    /** Drugi trener: wyłączone osoby nie słyszą jego głosu (osobno dla każdej z nich). */
    private fun applyMeshSend() {
        if (coSendOff.isEmpty() && mesh.isEmpty()) return
        for ((cid, l) in mesh) {
            val pc = l.pc ?: continue
            for (s in pc.senders) {
                if (s.track()?.kind() != MediaStreamTrack.AUDIO_TRACK_KIND) continue
                try { val p = s.parameters; val on = cid !in coSendOff; if (p.encodings.any { it.active != on }) { for (e in p.encodings) e.active = on; s.setParameters(p) } } catch (_: Exception) {}
            }
        }
    }

    /** Drugi trener: lista osób w pokoju zbudowana z listy otrzymanej od trenera głównego. */
    private fun snapshotCo() {
        athletesView = athRoster.filter { it.id != Prefs.cid }.map { x ->
            val l = mesh[x.id]
            AthleteView(x.id, x.name, if (x.on) "online" else "offline", x.id in coMuted, x.id !in coSendOff, Prefs.athRecvVol(x.id), 1f,
                x.bat, l?.rttMs ?: -1, l?.lossPct ?: -1, true, x.coach, x.cfg)
        }
    }

    /** Utrzymuje połączenia z pozostałymi zawodnikami. Dzwoni ten, kto ma „mniejszy” identyfikator. */
    private fun updateMesh() {
        if (role != Role.ATHLETE) return
        val roster = athRosterMap ?: emptyMap()
        val want = roster.values.filter { it.on && it.id != Prefs.cid && it.pid.isNotEmpty() && (athCross || athAsCoach || it.coach) }
        val wantIds = want.map { it.id }.toSet()
        for ((cid, l) in mesh.toMap()) {
            val stale = !isUp(l) && System.currentTimeMillis() - l.created > 20_000
            if (l.closed || cid !in wantIds || stale || l.ice == IceConnectionState.FAILED || l.ice == IceConnectionState.CLOSED) dropMesh(l)
        }
        if (signalOpen) for (x in want) {
            val ex = mesh[x.id]
            if (ex != null && ex.peer == x.pid) continue
            if (ex != null && Prefs.cid < x.id) dropMesh(ex)
            if (Prefs.cid < x.id && mesh[x.id] == null) meshCall(x)
        }
        refreshMeshUp()
    }

    private fun meshCall(x: RosterItem) {
        val connId = "mc_" + rnd()
        val md = athMeta().put("mesh", true)
        val l = Link(connId, x.pid, "media", md).also { it.cid = x.id; it.mesh = true }
        val pc = createPc(l) ?: return
        mesh[x.id] = l
        localTrack?.let { pc.addTrack(it, listOf("ath")) }
        pc.createDataChannel(connId, DataChannel.Init().apply { ordered = true })?.let { attachDc(l, it) }
        createSdp(pc, true) { offer ->
            if (l.closed) return@createSdp
            signal?.send("OFFER", l.peer, JSONObject().put("sdp", sdpJson(offer)).put("type", "media").put("connectionId", connId).put("metadata", md))
        }
    }

    private fun onMeshOffer(src: String, p: JSONObject) {
        val md = p.optJSONObject("metadata") ?: return
        val cid = md.optString("cid")
        if (cid.isEmpty() || !(athCross || athAsCoach || md.optBoolean("coach"))) return
        mesh[cid]?.let { dropMesh(it) }
        val connId = p.optString("connectionId").ifEmpty { "x_" + rnd() }
        val l = Link(connId, src, "media", md).also { it.cid = cid; it.mesh = true }
        val pc = createPc(l) ?: return
        mesh[cid] = l
        val sdp = p.optJSONObject("sdp") ?: return
        setRemote(pc, parseSdp(sdp)) {
            l.remoteSet = true; flushCands(l)
            localTrack?.let { pc.addTrack(it, listOf("ath")) }
            createSdp(pc, false) { ans ->
                signal?.send("ANSWER", src, JSONObject().put("sdp", sdpJson(ans)).put("type", "media").put("connectionId", connId))
            }
        }
    }

    fun setAthMuted(m: Boolean) = exec.execute { athMuted = m; micOn = !m; applyLocal(); changed(); sendStatus() }

    // =====================================================================
    // USTAWIENIA, STATYSTYKI
    // =====================================================================
    fun onSettingChanged(key: String) = exec.execute {
        when (key) {
            "coach_noise", "coach_wind" -> if (coachUi) makeLocalTrack(Prefs.coachNoise, Prefs.coachWind)
            "ath_noise", "ath_wind" -> if (role == Role.ATHLETE && !athAsCoach) makeLocalTrack(Prefs.athNoise, Prefs.athWind)
            "kbps" -> if (role == Role.COACH) {
                athletes.values.forEach { applyBitrate(it.media?.pc, Prefs.kbps) }
                broadcast(JSONObject().put("t", "cfg").put("kbps", Prefs.kbps))
            }
            "turn_url" -> if (role != Role.NONE) ice = buildIce()
            "crosstalk" -> broadcastRoster()
            "talk_mode" -> { pttActive = false; applyLocal(); changed() }
            "gain" -> applyRemote()
        }
        if (role == Role.ATHLETE) sendStatus()
    }

    private fun resetStats() {
        closedBytes[0] = 0; closedBytes[1] = 0; bytesSent = 0; bytesRecv = 0; sessionStart = now()
    }

    private fun num(x: Any?): Long = (x as? Number)?.toLong() ?: 0L

    private fun pollStats() {
        for (l in links.values.toList()) {
            val pc = l.pc ?: continue
            pc.getStats { rep ->
                var s = 0L; var r = 0L; var s2 = 0L; var r2 = 0L
                var rtt = -1; var lost = -1L; var recv = 0L
                for (st in rep.statsMap.values) {
                    if (st.type == "transport") { s += num(st.members["bytesSent"]); r += num(st.members["bytesReceived"]) }
                    else if (st.type == "candidate-pair" && st.members["nominated"] == true) {
                        s2 = maxOf(s2, num(st.members["bytesSent"])); r2 = maxOf(r2, num(st.members["bytesReceived"]))
                        (st.members["currentRoundTripTime"] as? Number)?.let { rtt = (it.toDouble() * 1000).toInt() }
                    } else if (st.type == "inbound-rtp" && st.members["kind"] == "audio") {
                        lost = num(st.members["packetsLost"]); recv = num(st.members["packetsReceived"])
                    }
                }
                if (s == 0L && r == 0L) { s = s2; r = r2 }
                exec.execute {
                    if (!l.closed) {
                        l.bytes[0] = maxOf(l.bytes[0], s); l.bytes[1] = maxOf(l.bytes[1], r)
                        l.rttMs = rtt
                        if (lost >= 0) {
                            val dl = lost - l.lostPrev; val dr = recv - l.recvPrev
                            if (dl + dr > 20) { l.lossPct = (dl.coerceAtLeast(0) * 100 / (dl.coerceAtLeast(0) + dr).coerceAtLeast(1)).toInt(); l.lostPrev = lost; l.recvPrev = recv }
                        }
                    }
                    bytesSent = closedBytes[0] + links.values.sumOf { it.bytes[0] }
                    bytesRecv = closedBytes[1] + links.values.sumOf { it.bytes[1] }
                }
            }
        }
        if (role == Role.COACH) snapshot() else if (athAsCoach) snapshotCo()
        changed()
    }
}
