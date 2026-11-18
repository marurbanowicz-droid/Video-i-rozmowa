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
    private lateinit var evTxt: TextView
    private lateinit var evBox: LinearLayout
    private lateinit var qualityPill: TextView
    private lateinit var stabPill: TextView
    private lateinit var noiseBtn: ImageButton
    private lateinit var recInfo: TextView
    private lateinit var notePill: TextView
    private lateinit var muteBanner: TextView
    private lateinit var athList: LinearLayout
    private lateinit var presets: LinearLayout
    private lateinit var dial: ZoomDial
    private lateinit var micBtn: ImageButton
    private lateinit var spkBtn: ImageButton
    private lateinit var shutter: ShutterButton
    private var rangeKey = ""
    private var landscape = false
    private var listSig = ""
    private var builtAspect = ""
    private val camCb: () -> Unit = { syncZoom(); refresh() }
    /** Obrót o 180° (poziomo w lewo ↔ w prawo) nie zmienia konfiguracji ekranu – wykrywamy go tutaj, żeby film nie był do góry nogami. */
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { CamRec.setRotation(rot()) }
    }
    private val ticker = object : Runnable { override fun run() { updateRecTime(); mainHandler.postDelayed(this, 500) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true); Notifier.toast("Sesja działa w tle. Zakończysz ją w ustawieniach lub w powiadomieniu.") }
        })
        CamRec.onChange = camCb
        askBatteryOnce()
    }

    override fun onDestroy() {
        if (CamRec.onChange === camCb) CamRec.onChange = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (builtAspect != Prefs.aspect) { buildUi(); rangeKey = "" }   // zmiana proporcji w ustawieniach – nowy układ
        Engine.addUi(this)
        CamRec.setSurface(preview.surfaceProvider)
        CamRec.setRotation(rot())
        try { getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(displayListener, mainHandler) } catch (_: Exception) {}
        mainHandler.post(ticker)
        syncZoom()
    }

    override fun onStop() {
        Engine.removeUi(this)
        try { getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(displayListener) } catch (_: Exception) {}
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
        listSig = ""; builtAspect = Prefs.aspect
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        if (!::preview.isInitialized) preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // płynny obrót bez czarnej klatki
        } else (preview.parent as? ViewGroup)?.removeView(preview)

        // Obraz ma proporcje 4:3 (lub 16:9), ekran jest dużo dłuższy – elementy sterujące układamy
        // w wolnych pasach obok obrazu, żeby niczego nie zasłaniały.
        val dm = resources.displayMetrics
        val sw = dm.widthPixels; val sh = dm.heightPixels
        val ratio = if (Prefs.aspect == "16:9") 16f / 9f else 4f / 3f
        val band: Int   // pas na górze (pionowo) albo po lewej (poziomo)
        if (!landscape) {
            val imgH = (sw * ratio).toInt()
            val avail = sh - imgH
            band = dp(112) + maxOf(0, avail - dp(204) - dp(112)) / 2
            root.addView(preview, FrameLayout.LayoutParams(sw, minOf(imgH, sh - band)).apply { topMargin = band })
        } else {
            val imgW = (sh * ratio).toInt()
            val avail = sw - imgW
            band = dp(108) + maxOf(0, avail - dp(214) - dp(108)) / 2
            root.addView(preview, FrameLayout.LayoutParams(minOf(imgW, sw - band), sh).apply { leftMargin = band })
        }
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean { applyZoom(dial.zoom * det.scaleFactor); return true }
        })
        preview.setOnTouchListener { _, e -> scale.onTouchEvent(e); true }

        // ---- elementy pasa górnego / lewego ----
        val settingsBtn = iconBtn(R.drawable.ic_settings, 38) { openSettings() }
        val endBtn = iconBtn(R.drawable.ic_power, 38) { confirmEnd() }.apply {
            imageTintList = ColorStateList.valueOf(Color.parseColor("#FF6B63")); contentDescription = "Zakończ sesję"
        }
        qualityPill = pillText("FHD").apply { setOnClickListener { qualityMenu(it) } }
        stabPill = pillText("STAB").apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_stab, 0, 0, 0); compoundDrawablePadding = dp(6)
            setOnClickListener { stabMenu(it) }
        }
        noiseBtn = iconBtn(R.drawable.ic_noise, 38) { toggleNoise() }
        statusPill = pillText("").apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_group, 0, 0, 0); compoundDrawablePadding = dp(6)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { roomPanel() }
        }
        // korekta ekspozycji: − ciemniej, + jaśniej, dotknięcie wartości zeruje
        evTxt = text("EV 0", 12f, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(2), dp(7), dp(2), dp(7)); setOnClickListener { CamRec.nudgeEv(0) } }
        fun evBtn(s: String, dir: Int) = text(s, 18f, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), dp(3), dp(10), dp(3)); setOnClickListener { CamRec.nudgeEv(dir) }
        }
        evBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundBg(glass, dp(16).toFloat())
            addView(evBtn("−", -1)); addView(evTxt, LinearLayout.LayoutParams(dp(46), -2)); addView(evBtn("+", 1))
        }
        recInfo = text("", 13f, bold = true).apply {
            background = roundBg(Color.parseColor("#E6FF3B30"), dp(14).toFloat()); setPadding(dp(8), dp(4), dp(8), dp(4)); visibility = View.GONE; maxLines = 1
        }
        notePill = pillText("🎙 Notatka").apply { setOnClickListener { VoiceNote.toggle(this@CoachActivity); updateNote() } }
        athList = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        if (!landscape) {
            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(settingsBtn); addView(gap(6)); addView(endBtn)
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(qualityPill); addView(gap(6)); addView(stabPill); addView(gap(6)); addView(noiseBtn)
                // pole na pokój zajmuje resztę wiersza; długa nazwa jest skracana „…”, a nie wypycha ikony poza ekran
                addView(FrameLayout(context).apply {
                    addView(statusPill, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
            }
            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(evBox)
                addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(athList) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
                addView(recInfo)
                addView(notePill, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM
                setPadding(dp(8), dp(28), dp(8), dp(4))
                addView(row1, lp()); addView(row2, lp(top = dp(4)))
            }
            root.addView(top, FrameLayout.LayoutParams(-1, band, Gravity.TOP))
        } else {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(8), dp(4), dp(8))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                    addView(settingsBtn); addView(gap(8)); addView(noiseBtn)
                })
                addView(endBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                addView(qualityPill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                addView(stabPill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                addView(evBox, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                addView(statusPill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                addView(recInfo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                addView(notePill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                addView(athList, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            }
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col, FrameLayout.LayoutParams(-1, -2)) },
                FrameLayout.LayoutParams(band, -1, Gravity.START))
        }

        // komunikat o wyciszeniu wszystkiego / rozmowie z jedną osobą – jedyny element nad obrazem, tylko w tych stanach
        muteBanner = text("", 14f, bold = true).apply {
            background = roundBg(Color.parseColor("#E6FF3B30"), dp(14).toFloat()); setPadding(dp(14), dp(8), dp(14), dp(8)); visibility = View.GONE
            setOnClickListener { if (Engine.fullMute) Engine.toggleFullMute() else Engine.setSolo(null) }
        }
        root.addView(muteBanner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (if (landscape) 0 else band) + dp(10) })

        // ---- zoom i przyciski ----
        presets = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        dial = ZoomDial(this).apply { vertical = landscape; onZoom = { z -> CamRec.setZoom(z); highlightPreset() } }
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
        spkBtn.setOnLongClickListener { volumeDialog(); true }
        shutter = ShutterButton(this).apply { setOnClickListener { toggleRec() } }
        val gallery = iconBtn(R.drawable.ic_gallery, 46) { openGallery() }
        val invite = iconBtn(R.drawable.ic_person_add, 46) { inviteDialog() }

        if (!landscape) {
            val ctrls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(cell(gallery), LinearLayout.LayoutParams(0, -2, 1f))
                addView(cell(micBtn), LinearLayout.LayoutParams(0, -2, 1f))
                addView(shutter, LinearLayout.LayoutParams(dp(76), dp(76)))
                addView(cell(spkBtn), LinearLayout.LayoutParams(0, -2, 1f))
                addView(cell(invite), LinearLayout.LayoutParams(0, -2, 1f))
            }
            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(16))
                addView(presets, lp(h = dp(36)))
                addView(dial, lp(h = dp(64), top = dp(2)))
                addView(ctrls, lp(top = dp(4)))
            }
            root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        } else {
            // poziomo: przyciski w kolumnie po prawej (jak w aparacie), zoom tuż obok spustu
            val side = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
                fun sp() = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) }
                addView(invite); addView(sp()); addView(spkBtn); addView(sp())
                addView(shutter, LinearLayout.LayoutParams(dp(76), dp(76))); addView(sp())
                addView(micBtn); addView(sp()); addView(gallery)
            }
            root.addView(side, FrameLayout.LayoutParams(dp(96), -1, Gravity.END))
            val zoomCol = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(presets, LinearLayout.LayoutParams(dp(44), -2))
                addView(dial, LinearLayout.LayoutParams(dp(70), -1))
            }
            root.addView(zoomCol, FrameLayout.LayoutParams(dp(118), -1, Gravity.END).apply { rightMargin = dp(96); topMargin = dp(8); bottomMargin = dp(8) })
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
                presets.addView(t, LinearLayout.LayoutParams(dp(38), dp(38)).apply { if (landscape) { topMargin = dp(4); bottomMargin = dp(4) } else { marginStart = dp(4); marginEnd = dp(4) } })
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
        val opts = listOf("off" to "OIS – optyczna (obiektyw)", "eis" to "EIS – elektroniczna systemowa",
            "super" to "SUPER – jak Super Steady", "superh" to "SUPER+H – Super + poziomy horyzont")
        opts.forEachIndexed { i, (_, label) -> pm.menu.add(0, i, i, label).isCheckable = true }
        pm.menu.setGroupCheckable(0, true, true)
        pm.menu.getItem(opts.indexOfFirst { it.first == Prefs.stabMode }.coerceAtLeast(0)).isChecked = true
        pm.setOnMenuItemClickListener { item ->
            val m = opts[item.itemId].first
            Prefs.sp.edit().putString("stab_mode", m).apply()
            CamRec.rebind()
            mainHandler.postDelayed({
                Notifier.toast(when (m) {
                    "super", "superh" -> "SUPER: ${CamRec.superInfo}. Zoom ograniczony do zakresu, w którym działa stabilizacja. Nagranie jest płynniejsze niż podgląd."
                    "eis" -> "Stabilizacja elektroniczna systemu (z optyczną)"
                    else -> "Optyczna stabilizacja obiektywu"
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
        Notifier.toast(if (on) "Redukcja szumów w rozmowie włączona (nagranie bez zmian)" else "Redukcja szumów wyłączona")
        refresh()
    }

    private fun updateNote() {
        val rec = VoiceNote.recording
        if (rec) { val s = (System.currentTimeMillis() - VoiceNote.startedAt) / 1000; notePill.text = String.format(Locale.US, "●  %d:%02d  ■", s / 60, s % 60) }
        else notePill.text = if (VoiceNote.processing) "Zapis…" else "🎙 Notatka"
        notePill.background = roundBg(if (rec) Color.parseColor("#E6FF3B30") else glass, dp(16).toFloat())
    }

    private fun updateRecTime() {
        updateNote()
        val r = CamRec.recording != null
        recInfo.visibility = if (r || CamRec.muxing) View.VISIBLE else View.GONE
        if (r) { val s = (System.currentTimeMillis() - CamRec.recStartedAt) / 1000; recInfo.text = String.format(Locale.US, "●  %02d:%02d", s / 60, s % 60) }
        else if (CamRec.muxing) recInfo.text = "Zapis…"
    }

    override fun onEngineChanged() {
        if (!Engine.active) { finish(); return }
        refresh()
    }

    private val isHost get() = Engine.role == Engine.Role.COACH
    private fun roomName() = if (isHost) Prefs.currentRoom().name else Engine.athRoomName.ifEmpty { "Pokój" }
    private fun roomCode() = if (isHost) Prefs.currentRoom().code else Engine.athCode

    private fun refresh() {
        val all = Engine.athletesView; val on = all.count { it.status == "online" }
        statusPill.text = "$on" + (if (all.size > on) "/${all.size}" else "") + (if (landscape) "" else "  ·  ${roomName().take(10)}")
        statusPill.setTextColor(if (Engine.signalOpen && (isHost || Engine.athLink)) Color.WHITE else ACCENT)
        evTxt.text = CamRec.evLabel()
        evTxt.setTextColor(if (Prefs.evIndex != 0) ACCENT else Color.WHITE)
        evBox.visibility = if (CamRec.camera == null || CamRec.evSupported()) View.VISIBLE else View.GONE
        qualityPill.text = when (Prefs.quality) { "HD" -> "HD"; "UHD" -> "4K"; else -> "FHD" }
        val sm = Prefs.stabMode
        stabPill.text = when (sm) { "eis" -> "EIS"; "super" -> "SUPER"; "superh" -> "SUPER+H"; else -> "OIS" }
        val stabOn = sm != "off"
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
        val soloName = Engine.soloCid?.let { id -> all.firstOrNull { it.cid == id }?.name }
        muteBanner.visibility = if (Engine.fullMute || soloName != null) View.VISIBLE else View.GONE
        muteBanner.text = if (Engine.fullMute) "WYCISZONO WSZYSTKO – dotknij, aby przywrócić" else "Mówisz tylko do: $soloName – dotknij, aby mówić do wszystkich"
        spkBtn.setImageResource(if (Engine.fullMute) R.drawable.ic_volume_off else if (Prefs.speaker) R.drawable.ic_volume_up else R.drawable.ic_headset)
        shutter.recording = CamRec.recording != null
        updateRecTime()
        // zawodnicy – każdy z osobnym wyciszeniem. Listę przebudowujemy tylko, gdy coś się w niej zmieniło:
        // odświeżanie co 2 s (licznik danych) podmieniałoby przyciski pod palcem i gubiło dotknięcia.
        val sig = roomCode() + "#" + Engine.soloCid + "#" + all.joinToString("|") { "${it.cid}:${it.name}:${it.status}:${it.muted}:${it.sendOn}" }
        if (sig == listSig) return
        listSig = sig
        athList.removeAllViews()
        for (a in all) {
            val solo = Engine.soloCid == a.cid
            val dotColor = when (a.status) { "online" -> 0xFF30D158.toInt(); "offline" -> 0xFFFF453A.toInt(); else -> ACCENT }
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = roundBg(if (solo) 0x66FFD60A else glass, dp(16).toFloat()); setPadding(dp(9), dp(5), dp(7), dp(5))
                alpha = if (a.status == "offline") 0.6f else 1f
                contentDescription = "${a.name}: dotknij, aby wyciszyć; przytrzymaj, aby mówić tylko do tej osoby"
                setOnClickListener { Engine.toggleAthleteMute(a.cid) }
                setOnLongClickListener { Engine.setSolo(if (solo) null else a.cid); true }
            }
            chip.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) } },
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
            chip.addView(text(a.name, 13f, bold = true).apply { maxLines = 1 })
            if (a.muted || !a.sendOn) chip.addView(ImageView(this).apply {
                setImageResource(if (a.muted) R.drawable.ic_volume_off else R.drawable.ic_mic_off)
                imageTintList = ColorStateList.valueOf(0xFFFF453A.toInt())
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) })
            athList.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { if (landscape) bottomMargin = dp(5) else marginEnd = dp(5) })
        }
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

    /** Głośność rozmowy i wzmocnienie mikrofonu (przytrzymaj przycisk głośnika). */
    private fun volumeDialog() {
        val vol = text(Vol.label(this), 16f, bold = true).apply { gravity = Gravity.CENTER }
        val mic = text(Vol.micLabel(), 16f, bold = true).apply { gravity = Gravity.CENTER }
        fun row(lbl: TextView, minus: () -> Unit, plus: () -> Unit, update: () -> String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(button("−") { minus(); lbl.text = update() }, LinearLayout.LayoutParams(dp(64), -2))
            addView(lbl, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("+") { plus(); lbl.text = update() }, LinearLayout.LayoutParams(dp(64), -2))
        }
        val box = column(16).apply {
            addView(text("Słyszę zawodników", 13f, col(R.color.mut)), lp())
            addView(row(vol, { Vol.down(this@CoachActivity) }, { Vol.up(this@CoachActivity) }) { Vol.label(this@CoachActivity) }, lp(top = dp(4)))
            addView(text("Zawodnicy słyszą mnie (nagranie bez zmian)", 13f, col(R.color.mut)), lp(top = dp(16)))
            addView(row(mic, { Vol.micDown() }, { Vol.micUp() }) { Vol.micLabel() }, lp(top = dp(4)))
        }
        MaterialAlertDialogBuilder(this).setTitle("Głośność").setView(box).setPositiveButton("OK", null).show()
    }

    // ---------------- panel zarządzania pokojem ----------------
    private fun roomPanel() {
        val r = Room(roomCode(), roomName())
        val head = text("", 13f, col(R.color.mut))
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val box = column(16).apply {
            gravity = Gravity.START
            addView(head, lp())
            addView(button("Kto kogo słyszy…") { hearDialog() }.apply { minHeight = dp(44); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f) }, lp(top = dp(8)))
            if (isHost) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("Zadzwoń…") { callDialog() }.apply { minHeight = dp(44); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                addView(button("Pokoje…") { roomsDialog() }.apply { minHeight = dp(44); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            }, lp(top = dp(8)))
            addView(listBox, lp(top = dp(8)))
        }
        class Row(val info: TextView, val send: ImageButton, val recv: ImageButton, val solo: TextView)
        val rows = HashMap<String, Row>()
        var builtFor = ""

        fun toggleBtn(icon: Int, onClick: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col(R.color.card)) }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)); setOnClickListener { onClick() }
        }
        fun slider(value: Float, onSet: (Float) -> Unit): View {
            val pct = text("${Math.round(value * 100)}%", 12f, col(R.color.mut)).apply { minWidth = dp(44); gravity = Gravity.END }
            val sb = android.widget.SeekBar(this).apply {
                max = 20; progress = Math.round(value * 10)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) { pct.text = "${p * 10}%"; if (fromUser) onSet(p / 10f) }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
                })
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(sb, LinearLayout.LayoutParams(0, -2, 1f)); addView(pct)
            }
        }
        fun build(all: List<Engine.AthleteView>) {
            listBox.removeAllViews(); rows.clear()
            if (all.isEmpty()) listBox.addView(text(if (isHost) "Nikt nie jest połączony. Zaproś zawodników albo zadzwoń do osób, które już tu były." else "Nikogo jeszcze nie ma na liście od trenera głównego.", 14f, col(R.color.mut)), lp(top = dp(8)))
            for (a in all) {
                val name = text(a.name, 16f, bold = true)
                val info = text("", 12f, col(R.color.mut))
                val send = toggleBtn(R.drawable.ic_mic) { Engine.setAthleteSend(a.cid, !(Engine.athletesView.firstOrNull { it.cid == a.cid }?.sendOn ?: true)) }
                val recv = toggleBtn(R.drawable.ic_volume_up) { Engine.toggleAthleteMute(a.cid) }
                val solo = text("Mów tylko do tej osoby", 13f, bold = true).apply {
                    gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8))
                    setOnClickListener { Engine.setSolo(if (Engine.soloCid == a.cid) null else a.cid) }
                }
                fun line(btn: View, label: String, sl: View) = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(btn); addView(text(label, 12f, col(R.color.mut)).apply { minWidth = dp(64); setPadding(dp(8), 0, 0, 0) })
                    addView(sl, LinearLayout.LayoutParams(0, -2, 1f))
                }
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; background = roundBg(col(R.color.card2), dp(14).toFloat()); setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(name); addView(info)
                    addView(line(send, "Nadaję", slider(a.sendVol) { v -> Engine.setAthleteSendVol(a.cid, v) }), lp(top = dp(6)))
                    addView(line(recv, "Odbieram", slider(a.recvVol) { v -> Engine.setAthleteRecvVol(a.cid, v) }), lp(top = dp(4)))
                    addView(solo, lp(top = dp(6)))
                    if (a.app) addView(text("⚙ Ustawienia zawodnika…", 13f, bold = true).apply {
                        gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8)); background = roundBg(col(R.color.card), dp(12).toFloat())
                        setOnClickListener { athleteSettings(a.cid) }
                    }, lp(top = dp(6)))
                }
                listBox.addView(card, lp(top = dp(8)))
                rows[a.cid] = Row(info, send, recv, solo)
            }
        }
        fun update() {
            val all = Engine.athletesView
            val on = all.count { it.status == "online" }
            head.text = "Kod ${roomCode()} · połączonych $on z ${all.size} · dane ${fmtBytes(Engine.bytesSent + Engine.bytesRecv)}" +
                if (!Engine.signalOpen) " · łączenie z serwerem…" else ""
            val key = all.joinToString("|") { it.cid + it.name + it.app }
            if (key != builtFor) { builtFor = key; build(all) }
            for (a in all) {
                val row = rows[a.cid] ?: continue
                val st = when (a.status) { "online" -> "połączony"; "offline" -> "brak połączenia – ponawianie"; else -> "łączenie…" }
                val q = if (a.status == "online" && a.rttMs >= 0) " · opóźnienie ${a.rttMs} ms" + (if (a.lossPct >= 0) ", straty ${a.lossPct}%" else "") else ""
                val bat = if (a.battery >= 0) " · bateria ${a.battery}%" else ""
                row.info.text = st + q + bat + if (a.app) "" else " · strona"
                row.info.setTextColor(if (a.status == "online" && (a.lossPct > 8 || a.rttMs > 600 || a.battery in 0..15)) ACCENT else col(R.color.mut))
                row.send.setImageResource(if (a.sendOn) R.drawable.ic_mic else R.drawable.ic_mic_off)
                row.send.imageTintList = ColorStateList.valueOf(if (a.sendOn) Color.WHITE else 0xFFFF453A.toInt())
                row.recv.setImageResource(if (a.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                row.recv.imageTintList = ColorStateList.valueOf(if (a.muted) 0xFFFF453A.toInt() else Color.WHITE)
                val solo = Engine.soloCid == a.cid
                row.solo.text = if (solo) "Mówisz tylko do tej osoby – dotknij, aby wrócić do wszystkich" else "Mów tylko do tej osoby"
                row.solo.background = roundBg(if (solo) ACCENT else col(R.color.card), dp(12).toFloat())
                row.solo.setTextColor(if (solo) Color.BLACK else col(R.color.txt))
            }
        }
        val ui = Engine.Ui { update() }
        val dlg = MaterialAlertDialogBuilder(this).setTitle(r.name)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Zamknij", null).create()
        dlg.setOnShowListener { Engine.addUi(ui) }
        dlg.setOnDismissListener { Engine.removeUi(ui) }
        dlg.show()
    }

    // ---------------- kto kogo słyszy ----------------
    private class Person(val id: String, val label: String, val coach: Boolean)

    private fun hearDialog() {
        val ps = ArrayList<Person>()
        ps.add(Person(Engine.HOST, if (isHost) "Ty" else "Trener główny", true))
        if (!isHost) ps.add(Person(Prefs.cid, "Ty", true))
        Engine.athletesView.forEach { ps.add(Person(it.cid, it.name, it.coach)) }
        val cross = if (isHost) Prefs.crosstalk else Engine.athCross
        val box = column(16).apply { gravity = Gravity.START }
        box.addView(text("Włącz u każdej osoby tych, których ma słyszeć. Zmiana działa od razu.", 12f, col(R.color.mut)), lp())
        if (!cross) box.addView(text("Zawodnicy nie słyszą się nawzajem (wyłączone w ustawieniach) – tych par tu nie ma.", 12f, col(R.color.mut)), lp(top = dp(4)))
        for (l in ps) {
            val rows = ps.filter { it.id != l.id && (cross || l.coach || it.coach) }
            if (rows.isEmpty()) continue
            box.addView(text("${l.label} słyszy:", 15f, bold = true), lp(top = dp(16)))
            for (s in rows) {
                val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
                    text = s.label; setTextColor(col(R.color.txt)); textSize = 14f
                    isChecked = Engine.hears(l.id, s.id)
                    setOnCheckedChangeListener { _, on -> Engine.setHears(l.id, s.id, on) }
                }
                box.addView(sw, lp(top = dp(4)))
            }
        }
        MaterialAlertDialogBuilder(this).setTitle("Kto kogo słyszy").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Zamknij", null).show()
    }

    // ---------------- zdalne ustawienia zawodnika ----------------
    private val GAINS = listOf("1", "2", "3", "4", "6", "8")
    private val MICS = listOf("0.5", "0.75", "1", "1.5", "2", "3", "4")
    private val ACT_NAMES = mapOf("volume" to "Zwykła głośność", "mute_all" to "Wycisz wszystko", "mute_mic" to "Wycisz mikrofon",
        "ptt" to "Push-to-talk", "record" to "Nagrywanie", "note" to "Notatka głosowa")

    /** Okno sterowania ustawieniami aplikacji wybranego zawodnika (zmiany działają od razu u niego). */
    private fun athleteSettings(cid: String) {
        val v = Engine.athletesView.firstOrNull { it.cid == cid } ?: return
        val st = HashMap(v.cfg)
        val known = v.cfg.isNotEmpty()
        fun put(k: String, x: String) { st[k] = x; Engine.setAthleteSetting(cid, k, x) }
        val box = column(16).apply { gravity = Gravity.START }
        if (!known) box.addView(text("Aplikacja zawodnika jeszcze nie zgłosiła swoich ustawień – pokazane są wartości domyślne.", 12f, col(R.color.mut)), lp())
        fun sw(label: String, key: String, def: Boolean) {
            val c = androidx.appcompat.widget.SwitchCompat(this).apply {
                text = label; setTextColor(col(R.color.txt)); textSize = 15f
                isChecked = (st[key] ?: def.toString()) == "true"
                setOnCheckedChangeListener { _, on -> put(key, on.toString()) }
            }
            box.addView(c, lp(top = dp(10)))
        }
        fun seek(label: String, max: Int, start: Int, fmt: (Int) -> String, done: (Int) -> Unit) {
            val t = text(label + ": " + fmt(start), 13f, col(R.color.mut))
            val sb = android.widget.SeekBar(this).apply {
                this.max = max; progress = start.coerceIn(0, max)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar, p: Int, u: Boolean) { t.text = label + ": " + fmt(p) }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar) { done(s.progress) }
                })
            }
            box.addView(t, lp(top = dp(12))); box.addView(sb, lp())
        }
        fun choice(label: String, key: String, values: List<String>, names: (String) -> String, def: String) {
            val b = button("") {}
            fun show() { b.text = label + ": " + names(st[key] ?: def) }
            b.setOnClickListener {
                val i = values.indexOf(st[key] ?: def)
                put(key, values[(i + 1) % values.size]); show()
            }
            show(); box.addView(b, lp(top = dp(8)))
        }
        sw("Dźwięk z głośnika (wyłączone = słuchawki)", "speaker", false)
        sw("Mikrofon wyciszony", "mute", false)
        sw("Wyciszone wszystko (mikrofon i dźwięk)", "full_mute", false)
        seek("Głośność rozmowy", 10, (st["voice_pct"]?.toIntOrNull() ?: 80) / 10, { "${it * 10}%" }) { put("voice_pct", (it * 10).toString()) }
        seek("Wzmocnienie odbieranego głosu", GAINS.size - 1, GAINS.indexOf(st["gain"] ?: "3").coerceAtLeast(0), { GAINS[it] + "×" }) { put("gain", GAINS[it]) }
        seek("Wzmocnienie mikrofonu", MICS.size - 1, MICS.indexOf(st["mic_gain"] ?: "1").coerceAtLeast(0), { MICS[it] + "×" }) { put("mic_gain", MICS[it]) }
        choice("Tryb rozmowy", "talk_mode", listOf("open", "ptt"), { if (it == "ptt") "naciśnij, aby mówić" else "ciągła" }, "open")
        val acts = if (v.coach) listOf("volume", "mute_all", "mute_mic", "ptt", "record", "note") else listOf("volume", "mute_all", "mute_mic", "ptt")
        choice("Przycisk głośności +", "vol_up", acts, { ACT_NAMES[it] ?: it }, if (v.coach) "mute_all" else "volume")
        choice("Przycisk głośności −", "vol_down", acts, { ACT_NAMES[it] ?: it }, if (v.coach) "record" else "volume")
        choice("Środkowy przycisk słuchawek", "headset_action", acts.drop(1), { ACT_NAMES[it] ?: it }, "mute_all")
        if (!v.coach) {
            sw("Redukcja szumów", "ath_noise", true)
            sw("Filtr niskich tonów (wiatr)", "ath_wind", true)
        }
        MaterialAlertDialogBuilder(this).setTitle(v.name).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Zamknij", null).show()
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
                list.filterIndexed { i, _ -> checked[i] }.forEach { Prefs.removeHistory(r.code, it.cid) }; listSig = ""; refresh()
            }
            .setNegativeButton("Anuluj", null).show()
    }

    private fun confirmEnd() {
        val rec = CamRec.recording != null
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setMessage(if (rec) "Zakończyć sesję? Trwające nagranie zostanie zapisane, a wszyscy zostaną rozłączeni." else "Zakończyć sesję i rozłączyć wszystkich?")
            .setPositiveButton("Zakończ") { _, _ -> SessionService.endSession(this); finish() }
            .setNegativeButton("Anuluj", null).show()
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java).putExtra("coach", true))

    // ---------------- zaproszenie ----------------
    private fun inviteDialog() {
        val r = Room(roomCode(), roomName())
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
                        c == r.code -> {}
                        c.length < 4 -> Notifier.toast("Kod musi mieć co najmniej 4 znaki")
                        rs.any { it.code == c && it.code != r.code } -> Notifier.toast("Taki pokój już istnieje")
                        else -> confirmDrop { rs.first { it.code == r.code }.code = c; Prefs.saveRooms(rs); Prefs.moveHistory(r.code, c); Engine.switchRoom(c) }
                    }
                }
                4 -> {
                    val rs = Prefs.loadRooms()
                    if (rs.size < 2) Notifier.toast("To jedyny pokój – najpierw utwórz inny")
                    else MaterialAlertDialogBuilder(this).setMessage("Usunąć pokój „${r.name}” (${r.code})?")
                        .setPositiveButton("Usuń") { _, _ -> confirmDrop { val left = rs.filter { it.code != r.code }; Prefs.saveRooms(left); Prefs.dropHistory(r.code); Engine.switchRoom(left[0].code) } }
                        .setNegativeButton("Anuluj", null).show()
                }
            }
        }.show()
    }
}
