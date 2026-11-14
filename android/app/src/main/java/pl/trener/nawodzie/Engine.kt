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

    data class AthleteView(val cid: String, val name: String, val status: String, val muted: Boolean)
    @Volatile var athletesView: List<AthleteView> = emptyList(); private set

    data class RosterItem(val id: String, val name: String, val on: Boolean, val pid: String = "")
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
    /** Połączenia głosowe zawodnik ↔ zawodnik (każdy z każdym). Klucz: cid drugiego zawodnika. */
    private val mesh = HashMap<String, Link>()

    private fun now() = System.currentTimeMillis()
    private fun rnd() = Prefs.genCode(12).lowercase()

    // =====================================================================
    // START / STOP
    // =====================================================================
    fun startCoach() = exec.execute {
        if (role != Role.NONE) return@execute
        role = Role.COACH; micOn = true; fullMute = false; pttActive = false; resetStats()
        try { initRtc(Prefs.coachNoise, Prefs.coachWind) } catch (e: Exception) { Log.e(TAG, "rtc", e); Notifier.toast("Błąd audio: ${e.message}") }
        startLoopback()
        ice = buildIce()
        connectSignal(PREFIX + Prefs.currentRoom().code)
        timers += exec.scheduleAtFixedRate({ safe { checkAthletes() } }, 2, 2, TimeUnit.SECONDS)
        timers += exec.scheduleAtFixedRate({ safe { broadcastRoster() } }, 10, 10, TimeUnit.SECONDS)
        timers += exec.scheduleAtFixedRate({ safe { pollStats(); Prefs.touchSession() } }, 2, 2, TimeUnit.SECONDS)
        changed()
    }

    fun startAthlete(code: String, name: String) = exec.execute {
        if (role != Role.NONE) return@execute
        role = Role.ATHLETE; fullMute = false; pttActive = false; resetStats()
        athCode = code; athName = name; athRoomName = ""; athLink = false; athEverLinked = false
        athRosterMap = null; athRoster = emptyList(); athStatus = "Łączenie z serwerem…"; athMuted = false; athCross = false
        try { initRtc(Prefs.athNoise, Prefs.athWind) } catch (e: Exception) { Log.e(TAG, "rtc", e); athStatus = "Błąd mikrofonu: ${e.message}" }
        ice = buildIce()
        connectSignal(newAthId())
        timers += exec.scheduleAtFixedRate({ safe { athWatchdog() } }, 3, 3, TimeUnit.SECONDS)
        timers += exec.scheduleAtFixedRate({ safe { pollStats(); Prefs.touchSession() } }, 2, 2, TimeUnit.SECONDS)
        changed()
    }

    fun stop(done: (() -> Unit)? = null) = exec.execute {
        if (role == Role.ATHLETE) athData?.let { dcSend(it, JSONObject().put("t", "bye")) }
        exec.schedule(Runnable { safe { teardown() }; done?.let { onMain(it) } }, 300, TimeUnit.MILLISECONDS)
    }

    private fun teardown() {
        timers.forEach { it.cancel(false) }; timers.clear()
        reconnectF?.cancel(false); reconnectF = null
        role = Role.NONE; fullMute = false; pttActive = false
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

    private fun initRtc(ns: Boolean, wind: Boolean) {
        if (!factoryInitDone) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
            factoryInitDone = true
        }
        val a = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(ns)
            .setSamplesReadyCallback { s -> pcmSink?.invoke(s.data, s.sampleRate, s.channelCount) }
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
            val body = PeerSignal.http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() } ?: ""
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
    private fun newAthId() = "tnw-" + Prefs.cid.lowercase() + "-" + Prefs.genCode(4).lowercase()
    private fun coachPeerId() = PREFIX + athCode

    private fun connectSignal(id: String) {
        signal?.close()
        signalOpen = false
        lateinit var s: PeerSignal
        s = PeerSignal(id, object : PeerSignal.Listener {
            override fun onOpen() {
                if (signal !== s) return
                signalOpen = true; changed()
                if (role == Role.ATHLETE) { athCall(); athConnectData() }
            }
            override fun onIdTaken() {
                if (signal !== s) return
                if (role == Role.COACH) Notifier.toast("Pokój jeszcze zajęty przez poprzednią sesję – ponawiam…")
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
                    connectSignal(if (role == Role.COACH) PREFIX + Prefs.currentRoom().code else newAthId())
            }
        }, 4, TimeUnit.SECONDS)
    }

    private fun onSignal(type: String, src: String?, p: JSONObject?) {
        when (type) {
            "OFFER" -> if (src != null && p != null) {
                val t = p.optString("type")
                if (t == "ring-decline") { if (role == Role.COACH) Notifier.event("leave", "${p.optString("name").ifEmpty { "Zawodnik" }} odrzuca połączenie"); return }
                if (t == "ring") return
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
                closeLink(athMedia); athMedia = null; closeLink(athData); athData = null
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
            if (role == Role.COACH && l.type == "data") { dcSend(l, rosterMsg()); dcSend(l, JSONObject().put("t", "cfg").put("kbps", Prefs.kbps)) }
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
            Role.COACH -> if (o.optString("t") == "bye") {
                val cid = l.cid ?: return
                athletes[cid]?.let { Notifier.event("leave", "${it.name} rozłącza się"); dropAthlete(cid); broadcastRoster() }
            }
            Role.ATHLETE -> onCoachData(o)
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
    private fun localEnabled(): Boolean = !fullMute && when {
        Prefs.talkMode == "ptt" -> pttActive
        role == Role.ATHLETE -> !athMuted
        else -> micOn
    }
    private fun applyLocal() { localTrack?.setEnabled(localEnabled()) }
    private fun applyRemote() {
        val g = Prefs.gain
        for (l in links.values) {
            val t = l.remote ?: continue
            val muted = role == Role.COACH && athletes.values.firstOrNull { it.media === l }?.muted == true
            try { t.setEnabled(!fullMute && !muted); t.setVolume(g) } catch (_: Exception) {}
        }
    }
    fun toggleFullMute() = exec.execute {
        fullMute = !fullMute; applyLocal(); applyRemote(); changed()
        Notifier.beep(if (fullMute) "leave" else "join")
        Notifier.toast(if (fullMute) "Wyciszono wszystko (mikrofon i dźwięk)" else "Dźwięk przywrócony")
    }
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
            athletes[cid]?.let { it.app = it.app || md?.optBoolean("app") == true }
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
        Prefs.addHistory(Prefs.currentRoom().code, a.cid, a.name, a.app)
        applyBitrate(a.media?.pc, Prefs.kbps)
        snapshot(); changed(); broadcastRoster()
    }

    private fun goOffline(a: Athlete) {
        if (a.status == "offline") return
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
        athletes.values.forEach { list.put(JSONObject().put("id", it.cid).put("name", it.name).put("on", it.status == "online").put("pid", it.media?.peer ?: "")) }
        return JSONObject().put("t", "roster").put("room", r.name).put("code", r.code).put("cross", Prefs.crosstalk).put("list", list)
    }

    private fun broadcast(o: JSONObject) = dataLinks.values.forEach { dcSend(it, o) }
    private fun broadcastRoster() { if (role == Role.COACH) broadcast(rosterMsg()) }

    private fun snapshot() {
        athletesView = athletes.values.map { AthleteView(it.cid, it.name, it.status, it.muted) }
    }

    // ---- akcje trenera wywoływane z ekranu ----
    fun setMic(on: Boolean) = exec.execute { micOn = on; applyLocal(); changed() }
    fun toggleAthleteMute(cid: String) = exec.execute {
        val a = athletes[cid] ?: return@execute
        a.muted = !a.muted; applyRemote()
        snapshot(); changed()
        Notifier.toast(if (a.muted) "${a.name} wyciszony(a) u Ciebie" else "${a.name} słyszalny(a)")
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
            signal?.send("OFFER", id, JSONObject().put("type", "ring").put("connectionId", "ring_" + rnd())
                .put("room", r.code).put("roomName", r.name).put("turn", Prefs.turnUrl))
        }
        Notifier.toast("Dzwonię do: " + list.joinToString { it.name })
    }

    // =====================================================================
    // ZAWODNIK
    // =====================================================================
    private fun athMeta() = JSONObject().put("name", athName).put("cid", Prefs.cid).put("app", true)

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

    private fun athWatchdog() {
        if (role != Role.ATHLETE) return
        updateMesh()
        val m = athMedia
        if (signalOpen) {
            val dataOk = athData?.dc?.state() == DataChannel.State.OPEN
            if (!dataOk && now() - athDataT > 8000) athConnectData()
            if (m == null) { athCall(); return }
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
            athStatus = "Połączono z trenerem"
            if (athEverLinked) Notifier.event("back", "Połączenie z trenerem przywrócone") else Notifier.beep("join")
            athEverLinked = true
        } else if (athEverLinked) {
            athStatus = "Utracono połączenie – łączę ponownie…"
            Notifier.event("lost", "Utracono połączenie z trenerem")
        }
        changed()
    }

    private fun onCoachData(o: JSONObject) {
        when (o.optString("t")) {
            "cfg" -> { athKbps = o.optInt("kbps", 32); applyBitrate(athMedia?.pc, athKbps) }
            "roster" -> {
                athRoomName = o.optString("room")
                athCross = o.optBoolean("cross", false)
                val arr = o.optJSONArray("list") ?: JSONArray()
                val next = LinkedHashMap<String, RosterItem>()
                for (i in 0 until arr.length()) { val x = arr.getJSONObject(i); next[x.optString("id")] = RosterItem(x.optString("id"), x.optString("name"), x.optBoolean("on"), x.optString("pid")) }
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
                updateMesh()
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
    }

    /** Utrzymuje połączenia z pozostałymi zawodnikami. Dzwoni ten, kto ma „mniejszy” identyfikator. */
    private fun updateMesh() {
        if (role != Role.ATHLETE) return
        val roster = athRosterMap ?: emptyMap()
        val want = if (athCross) roster.values.filter { it.on && it.id != Prefs.cid && it.pid.isNotEmpty() } else emptyList()
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
        if (cid.isEmpty() || !athCross) return
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

    fun setAthMuted(m: Boolean) = exec.execute { athMuted = m; applyLocal(); changed() }

    // =====================================================================
    // USTAWIENIA, STATYSTYKI
    // =====================================================================
    fun onSettingChanged(key: String) = exec.execute {
        when (key) {
            "coach_noise", "coach_wind" -> if (role == Role.COACH) makeLocalTrack(Prefs.coachNoise, Prefs.coachWind)
            "ath_noise", "ath_wind" -> if (role == Role.ATHLETE) makeLocalTrack(Prefs.athNoise, Prefs.athWind)
            "kbps" -> if (role == Role.COACH) {
                athletes.values.forEach { applyBitrate(it.media?.pc, Prefs.kbps) }
                broadcast(JSONObject().put("t", "cfg").put("kbps", Prefs.kbps))
            }
            "turn_url" -> if (role != Role.NONE) ice = buildIce()
            "crosstalk" -> broadcastRoster()
            "talk_mode" -> { pttActive = false; applyLocal(); changed() }
            "gain" -> applyRemote()
        }
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
                for (st in rep.statsMap.values) {
                    if (st.type == "transport") { s += num(st.members["bytesSent"]); r += num(st.members["bytesReceived"]) }
                    else if (st.type == "candidate-pair" && st.members["nominated"] == true) {
                        s2 = maxOf(s2, num(st.members["bytesSent"])); r2 = maxOf(r2, num(st.members["bytesReceived"]))
                    }
                }
                if (s == 0L && r == 0L) { s = s2; r = r2 }
                exec.execute {
                    if (!l.closed) { l.bytes[0] = maxOf(l.bytes[0], s); l.bytes[1] = maxOf(l.bytes[1], r) }
                    bytesSent = closedBytes[0] + links.values.sumOf { it.bytes[0] }
                    bytesRecv = closedBytes[1] + links.values.sumOf { it.bytes[1] }
                }
            }
        }
        changed()
    }
}
