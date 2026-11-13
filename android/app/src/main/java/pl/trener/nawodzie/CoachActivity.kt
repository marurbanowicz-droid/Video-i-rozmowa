package pl.trener.nawodzie

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class CoachActivity : AppCompatActivity(), Engine.Ui {
    private lateinit var preview: PreviewView
    private lateinit var roomPill: TextView
    private lateinit var countPill: TextView
    private lateinit var dataPill: TextView
    private lateinit var recInfo: TextView
    private lateinit var athList: LinearLayout
    private lateinit var zoomBar: SeekBar
    private lateinit var zoomLbl: TextView
    private lateinit var micBtn: MaterialButton
    private lateinit var spkBtn: MaterialButton
    private lateinit var recBtn: MaterialButton
    private val ticker = object : Runnable { override fun run() { updateRecTime(); mainHandler.postDelayed(this, 500) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true); Notifier.toast("Sesja działa w tle. Zakończysz ją w ustawieniach lub w powiadomieniu.") }
        })
        CamRec.onChange = { setupZoom(); refresh() }
        askBatteryOnce()
    }

    override fun onStart() {
        super.onStart()
        Engine.addUi(this)
        CamRec.setSurface(preview.surfaceProvider)
        CamRec.setRotation(rot())
        mainHandler.post(ticker)
    }

    override fun onStop() {
        Engine.removeUi(this)
        CamRec.setSurface(null)
        mainHandler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CamRec.setRotation(rot())
    }

    @Suppress("DEPRECATION")
    private fun rot(): Int = windowManager.defaultDisplay.rotation

    // ---------------- interfejs ----------------
    private fun pill(): TextView = text("", 14f, bold = true).apply {
        background = roundBg(Color.parseColor("#99000000"), dp(20).toFloat())
        setPadding(dp(12), dp(8), dp(12), dp(8)); maxLines = 1
    }

    private fun round(label: String, onClick: (View) -> Unit) = button(label) { onClick(it) }.apply {
        cornerRadius = dp(32); minWidth = dp(64); minHeight = dp(64); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
        setPadding(0, 0, 0, 0)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#44FFFFFF"))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val (mn, mx) = CamRec.zoomRange()
                CamRec.setZoom((CamRec.zoomRatio() * d.scaleFactor).coerceIn(mn, mx)); syncZoomBar(); return true
            }
        })
        preview.setOnTouchListener { _, e -> scale.onTouchEvent(e); true }

        // górny pasek
        roomPill = pill().apply { setOnClickListener { roomsDialog() } }
        countPill = pill(); dataPill = pill()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            fun gap() = View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) }
            addView(roomPill); addView(gap()); addView(countPill); addView(gap()); addView(dataPill); addView(gap())
            addView(pill().apply { text = "Zaproś"; setOnClickListener { inviteDialog() } }); addView(gap())
            addView(pill().apply { text = "⚙"; setOnClickListener { openSettings() } })
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(top) },
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(24) })

        recInfo = text("● 00:00", 16f, bold = true).apply {
            background = roundBg(col(R.color.red), dp(20).toFloat()); setPadding(dp(14), dp(6), dp(14), dp(6)); visibility = View.GONE
        }
        root.addView(recInfo, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(84) })

        athList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(athList) },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(84); leftMargin = dp(8) })

        // dolny pasek
        zoomBar = SeekBar(this).apply { max = 1000 }
        zoomLbl = text("1.0×", 15f, bold = true).apply { minWidth = dp(56); gravity = Gravity.END }
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val (mn, mx) = CamRec.zoomRange()
                val z = mn + (mx - mn) * (p / 1000f) * (p / 1000f)   // nieliniowo – precyzyjniej przy małym zoomie
                CamRec.setZoom(z); zoomLbl.text = String.format(Locale.US, "%.1f×", z)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text("🔍", 18f)); addView(zoomBar, LinearLayout.LayoutParams(0, -2, 1f)); addView(zoomLbl)
        }
        micBtn = round("🎙") { Engine.setMic(!Engine.micOn); Notifier.toast(if (!Engine.micOn) "Zawodnicy Cię słyszą" else "Mikrofon do zawodników wyciszony (nagranie nadal ma dźwięk)") }
        spkBtn = round("🔈") { Prefs.speaker = !Prefs.speaker; AudioRouter.apply(); refresh(); Notifier.toast(if (Prefs.speaker) "Dźwięk z głośnika" else "Dźwięk w słuchawkach (jeśli podłączone)") }
        recBtn = round("⏺") { toggleRec() }.apply { minWidth = dp(84); minHeight = dp(84); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 30f) }
        val galBtn = round("📁") { openGallery() }
        val ctrls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            listOf(micBtn, spkBtn, recBtn, galBtn).forEach { b -> addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6); marginEnd = dp(6) }) }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(20))
            setBackgroundColor(Color.parseColor("#66000000"))
            addView(zoomRow); addView(ctrls, lp(top = dp(6)))
        }
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun setupZoom() { syncZoomBar() }
    private fun syncZoomBar() {
        val (mn, mx) = CamRec.zoomRange(); val z = CamRec.zoomRatio()
        val f = if (mx > mn) ((z - mn) / (mx - mn)).coerceIn(0f, 1f) else 0f
        zoomBar.progress = (Math.sqrt(f.toDouble()) * 1000).toInt()
        zoomLbl.text = String.format(Locale.US, "%.1f×", z)
    }

    private fun updateRecTime() {
        val r = CamRec.recording != null
        recInfo.visibility = if (r || CamRec.muxing) View.VISIBLE else View.GONE
        if (r) { val s = (System.currentTimeMillis() - CamRec.recStartedAt) / 1000; recInfo.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60) }
        else if (CamRec.muxing) recInfo.text = "Zapisuję nagranie…"
    }

    override fun onEngineChanged() {
        if (Engine.role == Engine.Role.NONE) { finish(); return }
        refresh()
    }

    private fun refresh() {
        val r = Prefs.currentRoom()
        roomPill.text = "${if (Engine.signalOpen) "🟢" else "🟠"} ${r.name} · ${r.code}"
        val all = Engine.athletesView; val on = all.count { it.status == "online" }
        countPill.text = "👥 $on" + if (all.size > on) "/${all.size}" else ""
        dataPill.text = "⇅ " + fmtBytes(Engine.bytesSent + Engine.bytesRecv)
        micBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (Engine.micOn) Color.parseColor("#44FFFFFF") else col(R.color.red))
        spkBtn.text = if (Prefs.speaker) "🔊" else "🎧"
        val rec = CamRec.recording != null
        recBtn.text = if (rec) "⏹" else "⏺"
        recBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (rec) col(R.color.red) else Color.parseColor("#44FFFFFF"))
        // lista zawodników
        athList.removeAllViews()
        for (a in all) {
            val dot = when (a.status) { "online" -> "🟢"; "offline" -> "🔴"; else -> "🟠" }
            val t = text("$dot ${a.name}" + (if (a.muted) " 🔇" else "") + (if (a.status == "offline") "  – ponawianie…" else ""), 14f).apply {
                background = roundBg(Color.parseColor("#AA000000"), dp(10).toFloat())
                setPadding(dp(10), dp(6), dp(10), dp(6)); alpha = if (a.status == "offline") 0.6f else 1f
                setOnClickListener { Engine.toggleAthleteMute(a.cid) }
            }
            athList.addView(t, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
        }
        updateRecTime()
    }

    private fun toggleRec() {
        if (CamRec.recording != null) CamRec.stopRec() else CamRec.startRec(this)
        SessionService.refresh(this)
        refresh()
    }

    private fun openGallery() {
        val u = CamRec.lastUri
        try {
            if (u != null) startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            else startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
        } catch (_: Exception) { Notifier.toast("Nagrania są w Galerii: Filmy/TrenerNaWodzie") }
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java).putExtra("coach", true))

    // ---------------- zaproszenie ----------------
    private fun inviteDialog() {
        val r = Prefs.currentRoom()
        val link = inviteLink(r.code)
        val box = column(16).apply {
            addView(ImageView(context).apply { setImageBitmap(qrBitmap(link, 600)); adjustViewBounds = true }, LinearLayout.LayoutParams(dp(260), dp(260)))
            addView(text("Kod pokoju: ${r.code}", 20f, bold = true).apply { gravity = Gravity.CENTER }, lp(top = dp(10)))
            addView(text(link, 12f, col(R.color.mut)).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) }, lp(top = dp(6)))
            addView(text("iPhone: otwiera stronę. Android z aplikacją: na stronie „Otwórz w aplikacji” albo wpisz kod w aplikacji.", 12f, col(R.color.mut)).apply { gravity = Gravity.CENTER }, lp(top = dp(8)))
        }
        MaterialAlertDialogBuilder(this).setTitle("Zaproś do: ${r.name}").setView(box)
            .setPositiveButton("Udostępnij") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Dołącz do łączności treningowej (${r.name}): $link"), "Udostępnij link"))
            }
            .setNeutralButton("Kopiuj") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", link)); Notifier.toast("Skopiowano link")
            }
            .setNegativeButton("Zamknij", null).show()
    }

    // ---------------- pokoje ----------------
    private fun confirmDrop(then: () -> Unit) {
        if (Engine.athletesView.none { it.status == "online" }) { then(); return }
        MaterialAlertDialogBuilder(this).setMessage("Zawodnicy zostaną rozłączeni. Kontynuować?")
            .setPositiveButton("Tak") { _, _ -> then() }.setNegativeButton("Anuluj", null).show()
    }

    private fun input(title: String, value: String, code: Boolean, ok: (String) -> Unit) {
        val e = EditText(this).apply {
            setText(value); setSelection(value.length); setTextColor(col(R.color.txt))
            if (code) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(8)) }
        }
        val wrap = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(wrap)
            .setPositiveButton("OK") { _, _ -> ok(e.text.toString()) }.setNegativeButton("Anuluj", null).show()
    }

    private fun roomsDialog() {
        val r = Prefs.currentRoom()
        val items = arrayOf("Zmień nazwę pokoju", "Przełącz na inny pokój", "Nowy pokój", "Zmień kod pokoju", "Usuń ten pokój")
        MaterialAlertDialogBuilder(this).setTitle("${r.name} (${r.code})").setItems(items) { _, which ->
            when (which) {
                0 -> input("Nazwa pokoju", r.name, false) { v ->
                    val rs = Prefs.loadRooms(); rs.first { it.code == r.code }.name = v.trim().take(40).ifEmpty { "Pokój" }
                    Prefs.saveRooms(rs); Engine.roomRenamed(); SessionService.refresh(this)
                }
                1 -> {
                    val rs = Prefs.loadRooms()
                    MaterialAlertDialogBuilder(this).setTitle("Wybierz pokój")
                        .setSingleChoiceItems(rs.map { "${it.name} (${it.code})" }.toTypedArray(), rs.indexOfFirst { it.code == r.code }) { d, i ->
                            d.dismiss(); if (rs[i].code != r.code) confirmDrop { Engine.switchRoom(rs[i].code) }
                        }.show()
                }
                2 -> input("Nazwa nowego pokoju", "Nowy pokój", false) { v ->
                    confirmDrop {
                        val rs = Prefs.loadRooms(); var c: String
                        do c = Prefs.genCode(4) while (rs.any { it.code == c })
                        rs.add(Room(c, v.trim().take(40).ifEmpty { "Pokój" })); Prefs.saveRooms(rs)
                        Engine.switchRoom(c); Notifier.toast("Utworzono pokój $c")
                    }
                }
                3 -> input("Nowy kod (4–8 znaków). Stary link przestanie działać.", r.code, true) { v ->
                    val c = Prefs.sanitizeCode(v)
                    val rs = Prefs.loadRooms()
                    when {
                        c.length < 4 -> Notifier.toast("Kod musi mieć co najmniej 4 znaki")
                        rs.any { it.code == c && it.code != r.code } -> Notifier.toast("Taki pokój już istnieje")
                        else -> confirmDrop { rs.first { it.code == r.code }.code = c; Prefs.saveRooms(rs); Engine.switchRoom(c) }
                    }
                }
                4 -> {
                    val rs = Prefs.loadRooms()
                    if (rs.size < 2) Notifier.toast("To jedyny pokój – najpierw utwórz inny")
                    else MaterialAlertDialogBuilder(this).setMessage("Usunąć pokój „${r.name}” (${r.code})?")
                        .setPositiveButton("Usuń") { _, _ -> confirmDrop { val left = rs.filter { it.code != r.code }; Prefs.saveRooms(left); Engine.switchRoom(left[0].code) } }
                        .setNegativeButton("Anuluj", null).show()
                }
            }
        }.show()
    }
}
