package pl.trener.nawodzie

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlin.math.abs

class CoachActivity : AppCompatActivity(), Engine.Ui {
    private lateinit var preview: PreviewView
    private lateinit var statusPill: TextView
    private lateinit var dataTxt: TextView
    private lateinit var qualityPill: TextView
    private lateinit var stabPill: TextView
    private lateinit var noiseBtn: ImageButton
    private lateinit var recInfo: TextView
    private lateinit var muteBanner: TextView
    private lateinit var athList: LinearLayout
    private lateinit var presets: LinearLayout
    private lateinit var dial: ZoomDial
    private lateinit var micBtn: ImageButton
    private lateinit var spkBtn: ImageButton
    private lateinit var shutter: ShutterButton
    private var rangeKey = ""
    private var landscape = false
    private val ticker = object : Runnable { override fun run() { updateRecTime(); mainHandler.postDelayed(this, 500) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true); Notifier.toast("Sesja działa w tle. Zakończysz ją w ustawieniach lub w powiadomieniu.") }
        })
        CamRec.onChange = { syncZoom(); refresh() }
        askBatteryOnce()
    }

    override fun onStart() {
        super.onStart()
        Engine.addUi(this)
        CamRec.setSurface(preview.surfaceProvider)
        CamRec.setRotation(rot())
        mainHandler.post(ticker)
        syncZoom()
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
        val land = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land != landscape) {
            landscape = land
            buildUi(); rangeKey = ""
            CamRec.setSurface(preview.surfaceProvider)
            syncZoom(); refresh()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = Controls.dispatch(event) || super.dispatchKeyEvent(event)

    @Suppress("DEPRECATION")
    private fun rot(): Int = windowManager.defaultDisplay.rotation

    // ---------------- elementy wizjera ----------------
    private val glass = Color.parseColor("#59000000")

    private fun iconBtn(icon: Int, sizeDp: Int = 48, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(glass) }
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener { onClick() }
    }

    private fun ImageButton.state(on: Boolean, warn: Boolean = false) {
        imageTintList = ColorStateList.valueOf(when { warn -> Color.WHITE; on -> ACCENT; else -> Color.WHITE })
        (background as GradientDrawable).setColor(if (warn) Color.parseColor("#E6FF3B30") else glass)
        alpha = if (on || warn) 1f else 0.75f
    }

    private fun pillText(t: String) = text(t, 13f, bold = true).apply {
        background = roundBg(glass, dp(16).toFloat()); setPadding(dp(12), dp(7), dp(12), dp(7)); maxLines = 1
    }

    private fun gap(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(w), dp(w)) }
    private fun cell(v: View) = FrameLayout(this).apply {
        (v.parent as? ViewGroup)?.removeView(v)
        addView(v, FrameLayout.LayoutParams(v.layoutParams?.width ?: -2, v.layoutParams?.height ?: -2, Gravity.CENTER))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean { applyZoom(dial.zoom * det.scaleFactor); return true }
        })
        preview.setOnTouchListener { _, e -> scale.onTouchEvent(e); true }

        // ---- górny pasek ----
        qualityPill = pillText("FHD").apply { setOnClickListener { qualityMenu(it) } }
        stabPill = pillText("STAB").apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_stab, 0, 0, 0); compoundDrawablePadding = dp(6)
            setOnClickListener { stabMenu(it) }
        }
        noiseBtn = iconBtn(R.drawable.ic_noise, 38) { toggleNoise() }
        statusPill = pillText("").apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_group, 0, 0, 0); compoundDrawablePadding = dp(6)
            setOnClickListener { roomsDialog() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(if (landscape) 10 else 30), dp(if (landscape) 110 else 10), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#AA000000"), Color.TRANSPARENT))
            addView(iconBtn(R.drawable.ic_settings, 38) { openSettings() })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(qualityPill); addView(gap(8)); addView(stabPill); addView(gap(8)); addView(noiseBtn)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(statusPill)
        }
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val topH = if (landscape) 62 else 84

        recInfo = text("", 15f, bold = true).apply {
            background = roundBg(Color.parseColor("#E6FF3B30"), dp(14).toFloat()); setPadding(dp(12), dp(4), dp(12), dp(4)); visibility = View.GONE
        }
        root.addView(recInfo, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(topH) })
        muteBanner = text("WYCISZONO WSZYSTKO – dotknij, aby przywrócić", 14f, bold = true).apply {
            background = roundBg(Color.parseColor("#E6FF3B30"), dp(14).toFloat()); setPadding(dp(14), dp(8), dp(14), dp(8)); visibility = View.GONE
            setOnClickListener { Engine.toggleFullMute() }
        }
        root.addView(muteBanner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(topH + 34) })
        dataTxt = text("", 11f, Color.parseColor("#CCFFFFFF")).apply { setShadowLayer(4f, 0f, 1f, Color.BLACK) }
        root.addView(dataTxt, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(topH - 4); rightMargin = dp(if (landscape) 110 else 14) })

        athList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(athList) },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(topH); leftMargin = dp(10); bottomMargin = dp(if (landscape) 120 else 260)
            })

        // ---- zoom i przyciski ----
        presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        dial = ZoomDial(this).apply { onZoom = { z -> CamRec.setZoom(z); highlightPreset() } }
        micBtn = iconBtn(R.drawable.ic_mic, 52) { if (Prefs.talkMode != "ptt") Controls.run("mute_mic", true, true) }
        micBtn.setOnTouchListener { v, e ->
            if (Prefs.talkMode != "ptt") return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { Engine.setPtt(true); Notifier.beep("ptt"); v.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { Engine.setPtt(false); Notifier.beep("ptt_off"); v.isPressed = false }
            }
            true
        }
        micBtn.setOnLongClickListener { if (Prefs.talkMode != "ptt") { Engine.toggleFullMute(); true } else false }
        spkBtn = iconBtn(R.drawable.ic_volume_up, 52) { Prefs.speaker = !Prefs.speaker; AudioRouter.apply(); refresh(); Notifier.toast(if (Prefs.speaker) "Dźwięk z głośnika" else "Dźwięk w słuchawkach (jeśli podłączone)") }
        shutter = ShutterButton(this).apply { setOnClickListener { toggleRec() } }
        val gallery = iconBtn(R.drawable.ic_gallery, 46) { openGallery() }
        val invite = iconBtn(R.drawable.ic_person_add, 46) { inviteDialog() }

        if (!landscape) {
            val ctrls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(cell(gallery), LinearLayout.LayoutParams(0, -2, 1f))
                addView(cell(micBtn), LinearLayout.LayoutParams(0, -2, 1f))
                addView(shutter, LinearLayout.LayoutParams(dp(80), dp(80)))
                addView(cell(spkBtn), LinearLayout.LayoutParams(0, -2, 1f))
                addView(cell(invite), LinearLayout.LayoutParams(0, -2, 1f))
            }
            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(24), dp(8), dp(22))
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT))
                addView(presets, lp())
                addView(dial, lp(h = dp(78), top = dp(4)))
                addView(ctrls, lp(top = dp(6)))
            }
            root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        } else {
            // poziomo: przyciski w kolumnie po prawej (jak w aparacie), zoom w wąskim pasku na dole
            val side = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT))
                fun sp() = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) }
                addView(invite); addView(sp()); addView(spkBtn); addView(sp())
                addView(shutter, LinearLayout.LayoutParams(dp(76), dp(76))); addView(sp())
                addView(micBtn); addView(sp()); addView(gallery)
            }
            root.addView(side, FrameLayout.LayoutParams(dp(100), -1, Gravity.END))
            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.parseColor("#AA000000"), Color.TRANSPARENT))
                addView(presets, LinearLayout.LayoutParams(-2, -2))
                addView(dial, LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginStart = dp(8) })
            }
            root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { rightMargin = dp(100) })
        }
        setContentView(root)
    }

    // ---------------- zoom ----------------
    private fun applyZoom(z: Float) {
        val nz = z.coerceIn(dial.minZ, dial.maxZ)
        CamRec.setZoom(nz); dial.setZoomValue(nz); highlightPreset()
    }

    private fun syncZoom() {
        val (mn, mx) = CamRec.zoomRange()
        val key = "$mn-$mx"
        if (key != rangeKey) {
            rangeKey = key
            dial.setRange(mn, mx)
            presets.removeAllViews()
            val vals = (listOf(mn) + listOf(1f, 2f, 3f, 5f, 10f, 30f).filter { it > mn + 0.05f && it <= mx + 0.01f }).distinct()
            for (v in vals) {
                val t = text(fmtZ(v), 12f, bold = true).apply {
                    gravity = Gravity.CENTER; tag = v
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(glass) }
                    setOnClickListener { applyZoom(v) }
                }
                presets.addView(t, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(4); marginEnd = dp(4) })
            }
        }
        dial.setZoomValue(CamRec.zoomRatio())
        highlightPreset()
    }

    private fun fmtZ(v: Float) = if (v < 1f) String.format(Locale.US, ".%d", Math.round(v * 10)) else "${Math.round(v)}×"

    private fun highlightPreset() {
        val z = dial.zoom
        for (i in 0 until presets.childCount) {
            val t = presets.getChildAt(i) as TextView
            val v = t.tag as Float
            val on = abs(v - z) < 0.05f * v
            t.setTextColor(if (on) ACCENT else Color.WHITE)
            t.text = if (on && v >= 1f) String.format(Locale.US, "%.1f×", z).replace(".0×", "×") else fmtZ(v)
        }
    }

    // ---------------- szybkie ustawienia ----------------
    private fun qualityMenu(anchor: View) {
        if (CamRec.recording != null) { Notifier.toast("Zatrzymaj nagrywanie, aby zmienić jakość"); return }
        val pm = PopupMenu(this, anchor)
        val opts = listOf("HD" to "HD 720p", "FHD" to "Full HD 1080p", "UHD" to "4K")
        opts.forEachIndexed { i, (_, label) -> pm.menu.add(0, i, i, label).isCheckable = true }
        pm.menu.setGroupCheckable(0, true, true)
        pm.menu.getItem(opts.indexOfFirst { it.first == Prefs.quality }.coerceAtLeast(0)).isChecked = true
        pm.setOnMenuItemClickListener { item ->
            Prefs.sp.edit().putString("quality", opts[item.itemId].first).apply()
            CamRec.rebind(); refresh(); true
        }
        pm.show()
    }

    private fun stabMenu(anchor: View) {
        if (CamRec.recording != null) { Notifier.toast("Zatrzymaj nagrywanie, aby zmienić stabilizację"); return }
        val pm = PopupMenu(this, anchor)
        val opts = listOf("off" to "Wyłączona", "std" to "Standardowa", "sport" to "Sport – duże kadrowanie")
        opts.forEachIndexed { i, (_, label) -> pm.menu.add(0, i, i, label).isCheckable = true }
        pm.menu.setGroupCheckable(0, true, true)
        pm.menu.getItem(opts.indexOfFirst { it.first == Prefs.stabMode }.coerceAtLeast(0)).isChecked = true
        pm.setOnMenuItemClickListener { item ->
            val m = opts[item.itemId].first
            Prefs.sp.edit().putString("stab_mode", m).apply()
            CamRec.rebind()
            mainHandler.postDelayed({
                Notifier.toast(when {
                    m == "off" -> "Stabilizacja wyłączona"
                    m == "sport" -> "Tryb Sport: obraz przycięty, mocna korekta wahań"
                    CamRec.stabSupported -> "Stabilizacja standardowa włączona"
                    else -> "Ten telefon nie udostępnia standardowej stabilizacji – spróbuj trybu Sport"
                }); refresh()
            }, 800)
            true
        }
        pm.show()
    }

    private fun toggleNoise() {
        val on = !Prefs.coachNoise
        Prefs.sp.edit().putBoolean("coach_noise", on).apply()
        Engine.onSettingChanged("coach_noise")
        Notifier.toast(if (on) "Redukcja szumów włączona (może przytłumić sygnał startu)" else "Redukcja szumów wyłączona")
        refresh()
    }

    private fun updateRecTime() {
        val r = CamRec.recording != null
        recInfo.visibility = if (r || CamRec.muxing) View.VISIBLE else View.GONE
        if (r) { val s = (System.currentTimeMillis() - CamRec.recStartedAt) / 1000; recInfo.text = String.format(Locale.US, "●  %02d:%02d", s / 60, s % 60) }
        else if (CamRec.muxing) recInfo.text = "Zapisuję nagranie…"
    }

    override fun onEngineChanged() {
        if (Engine.role == Engine.Role.NONE) { finish(); return }
        refresh()
    }

    private fun refresh() {
        val r = Prefs.currentRoom()
        val all = Engine.athletesView; val on = all.count { it.status == "online" }
        statusPill.text = "$on" + (if (all.size > on) "/${all.size}" else "") + "  ·  ${r.name}"
        statusPill.setTextColor(if (Engine.signalOpen) Color.WHITE else ACCENT)
        dataTxt.text = "${r.code} · ${fmtBytes(Engine.bytesSent + Engine.bytesRecv)}" + if (!Engine.signalOpen) " · łączenie…" else ""
        qualityPill.text = when (Prefs.quality) { "HD" -> "HD"; "UHD" -> "4K"; else -> "FHD" }
        val sm = Prefs.stabMode
        stabPill.text = when (sm) { "sport" -> "SPORT"; "std" -> "STAB"; else -> "OFF" }
        val stabOn = sm == "sport" || (sm == "std" && (CamRec.camera == null || CamRec.stabSupported))
        stabPill.setTextColor(if (stabOn) ACCENT else Color.WHITE)
        stabPill.compoundDrawablesRelative[0]?.mutate()?.setTint(if (stabOn) ACCENT else Color.WHITE)
        noiseBtn.state(Prefs.coachNoise)
        val ptt = Prefs.talkMode == "ptt"
        val talking = if (ptt) Engine.pttActive else Engine.micOn
        micBtn.setImageResource(if (talking && !Engine.fullMute) R.drawable.ic_mic else R.drawable.ic_mic_off)
        when {
            Engine.fullMute -> micBtn.state(true, warn = true)
            ptt -> { micBtn.state(Engine.pttActive); if (!Engine.pttActive) micBtn.alpha = 1f }
            else -> { micBtn.state(true, warn = !Engine.micOn); if (Engine.micOn) micBtn.imageTintList = ColorStateList.valueOf(Color.WHITE) }
        }
        muteBanner.visibility = if (Engine.fullMute) View.VISIBLE else View.GONE
        spkBtn.setImageResource(if (Engine.fullMute) R.drawable.ic_volume_off else if (Prefs.speaker) R.drawable.ic_volume_up else R.drawable.ic_headset)
        shutter.recording = CamRec.recording != null
        // zawodnicy – każdy z osobnym wyciszeniem
        athList.removeAllViews()
        val onlineIds = all.filter { it.status == "online" }.map { it.cid }.toSet()
        if (Prefs.history(r.code).any { it.cid !in onlineIds }) {
            val call = pillText("Zadzwoń").apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_call, 0, 0, 0); compoundDrawablePadding = dp(6)
                setOnClickListener { callDialog() }
            }
            athList.addView(call, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        }
        for (a in all) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = roundBg(glass, dp(20).toFloat()); setPadding(dp(12), dp(2), dp(2), dp(2))
                alpha = if (a.status == "offline") 0.6f else 1f
            }
            val dotColor = when (a.status) { "online" -> 0xFF30D158.toInt(); "offline" -> 0xFFFF453A.toInt(); else -> ACCENT }
            row.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) } },
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            row.addView(text(a.name + if (a.status == "offline") " · ponawianie" else "", 14f, bold = true))
            val mute = ImageButton(this).apply {
                setImageResource(if (a.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                imageTintList = ColorStateList.valueOf(if (a.muted) 0xFFFF453A.toInt() else Color.WHITE)
                background = null; setPadding(dp(8), dp(6), dp(8), dp(6))
                contentDescription = if (a.muted) "Włącz dźwięk ${a.name}" else "Wycisz ${a.name}"
                setOnClickListener { Engine.toggleAthleteMute(a.cid) }
            }
            row.addView(mute)
            athList.addView(row, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
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

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60000
        return when { m < 60 -> "${maxOf(1, m)} min temu"; m < 48 * 60 -> "${m / 60} godz. temu"; else -> "${m / 1440} dni temu" }
    }

    /** Lista osób, które były wcześniej w tym pokoju – wybierz, do kogo zadzwonić. */
    private fun callDialog() {
        val r = Prefs.currentRoom()
        val online = Engine.athletesView.filter { it.status == "online" }.map { it.cid }.toSet()
        val list = Prefs.history(r.code).filter { it.cid !in online }
        if (list.isEmpty()) { Notifier.toast("Wszyscy z tego pokoju są już połączeni"); return }
        val labels = list.map { it.name + " · " + ago(it.last) + if (!it.app) " · strona (nie odbierze)" else "" }.toTypedArray()
        val checked = list.map { it.app }.toBooleanArray()
        MaterialAlertDialogBuilder(this).setTitle("Zadzwoń – ${r.name}")
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Zadzwoń") { _, _ ->
                val sel = list.filterIndexed { i, _ -> checked[i] }
                if (sel.isEmpty()) Notifier.toast("Nikogo nie wybrano") else Engine.ring(sel)
            }
            .setNeutralButton("Usuń zaznaczone z listy") { _, _ ->
                list.filterIndexed { i, _ -> checked[i] }.forEach { Prefs.removeHistory(r.code, it.cid) }; refresh()
            }
            .setNegativeButton("Anuluj", null).show()
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
