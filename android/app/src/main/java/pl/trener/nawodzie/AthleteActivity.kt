package pl.trener.nawodzie

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AthleteActivity : AppCompatActivity(), Engine.Ui {
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var roster: ChipGroup
    private lateinit var muteBtn: MaterialButton
    private lateinit var spkBtn: MaterialButton
    private lateinit var dataTxt: TextView
    private lateinit var hint: TextView
    private lateinit var muteAll: MaterialButton

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = Controls.dispatch(event) || super.dispatchKeyEvent(event)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = column(24).apply { gravity = Gravity.CENTER }
        title = text("Pokój", 26f, bold = true).apply { gravity = Gravity.CENTER }
        status = text("Łączenie…", 19f, bold = true).apply {
            gravity = Gravity.CENTER; background = roundBg(col(R.color.card), dp(18).toFloat()); setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        roster = ChipGroup(this)
        muteBtn = button("Mikrofon włączony", "primary") { if (Prefs.talkMode != "ptt") Engine.setAthMuted(!Engine.athMuted) }
        muteBtn.minHeight = dp(84)
        muteBtn.setOnTouchListener { v, e ->
            if (Prefs.talkMode != "ptt") return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { Engine.setPtt(true); Notifier.beep("ptt"); v.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { Engine.setPtt(false); Notifier.beep("ptt_off"); v.isPressed = false }
            }
            true
        }
        muteAll = button("Wycisz wszystko") { Engine.toggleFullMute() }
        spkBtn = button("") { Prefs.speaker = !Prefs.speaker; AudioRouter.apply(); refresh() }
        dataTxt = text("", 13f, col(R.color.mut)).apply { gravity = Gravity.CENTER }
        root.addView(title, lp())
        root.addView(status, lp(top = dp(16)))
        root.addView(roster, lp(top = dp(12)))
        hint = text("", 13f, col(R.color.mut)).apply { gravity = Gravity.CENTER }
        root.addView(hint, lp(top = dp(6)))
        root.addView(muteBtn, lp(top = dp(16)))
        root.addView(spkBtn, lp(top = dp(10)))
        root.addView(muteAll, lp(top = dp(10)))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("⚙ Ustawienia") { startActivity(Intent(this@AthleteActivity, SettingsActivity::class.java).putExtra("coach", false)) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(button("Rozłącz", "danger") { leave() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        }, lp(top = dp(10)))
        root.addView(dataTxt, lp(top = dp(12)))
        root.addView(text("Aplikacja działa też przy zgaszonym ekranie i w tle.", 13f, col(R.color.mut)).apply { gravity = Gravity.CENTER }, lp(top = dp(8)))
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true); Notifier.toast("Połączenie działa w tle") }
        })
        askBatteryOnce()
    }

    override fun onStart() { super.onStart(); Engine.addUi(this) }
    override fun onStop() { Engine.removeUi(this); super.onStop() }

    override fun onEngineChanged() {
        if (Engine.role == Engine.Role.NONE) { finish(); return }
        refresh()
    }

    private fun refresh() {
        title.text = (Engine.athRoomName.ifEmpty { "Pokój" }) + " · " + Engine.athCode
        status.text = (if (Engine.athLink) "🟢 " else if (Engine.athStatus.contains("Utracono")) "🔴 " else "🟠 ") + Engine.athStatus
        roster.removeAllViews()
        fun chip(name: String, on: Boolean) = Chip(this).apply {
            text = (if (on) "🟢 " else "🔴 ") + name; isClickable = false; alpha = if (on) 1f else 0.6f
        }
        roster.addView(chip("Trener", Engine.athLink))
        for (x in Engine.athRoster) {
            val label = when {
                x.id == Prefs.cid -> "${x.name} (Ty)"
                Engine.athCross && x.id in Engine.meshUp -> "${x.name} 🔊"
                else -> x.name
            }
            roster.addView(chip(label, x.on))
        }
        hint.text = if (Engine.athCross) "🔊 Słyszycie się nawzajem – ikona przy imieniu oznacza aktywne połączenie." else "Słyszysz tylko trenera."
        val ptt = Prefs.talkMode == "ptt"
        val talking = !Engine.fullMute && (if (ptt) Engine.pttActive else !Engine.athMuted)
        muteBtn.setIconResource(if (talking) R.drawable.ic_mic else R.drawable.ic_mic_off)
        muteBtn.text = when {
            Engine.fullMute -> "Wyciszono wszystko"
            ptt -> if (Engine.pttActive) "MÓWISZ…" else "Przytrzymaj, aby mówić"
            Engine.athMuted -> "Mikrofon wyciszony"
            else -> "Mikrofon włączony"
        }
        muteBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (talking) col(R.color.acc) else col(R.color.card))
        muteBtn.setTextColor(if (talking) Color.parseColor("#04211F") else col(R.color.txt))
        muteBtn.iconTint = android.content.res.ColorStateList.valueOf(if (talking) Color.parseColor("#04211F") else col(R.color.txt))
        muteAll.text = if (Engine.fullMute) "Przywróć dźwięk" else "Wycisz wszystko (mikrofon i dźwięk)"
        muteAll.setIconResource(if (Engine.fullMute) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
        muteAll.backgroundTintList = android.content.res.ColorStateList.valueOf(if (Engine.fullMute) Color.parseColor("#E6FF3B30") else col(R.color.card))
        spkBtn.text = if (Prefs.speaker) "Dźwięk: głośnik" else "Dźwięk: słuchawki"
        spkBtn.setIconResource(if (Prefs.speaker) R.drawable.ic_volume_up else R.drawable.ic_headset)
        val tot = Engine.bytesSent + Engine.bytesRecv
        dataTxt.text = "Dane w tej sesji: ${fmtBytes(tot)} (wysłane ${fmtBytes(Engine.bytesSent)}, odebrane ${fmtBytes(Engine.bytesRecv)})"
        dataTxt.visibility = View.VISIBLE
    }

    private fun leave() {
        MaterialAlertDialogBuilder(this).setMessage("Rozłączyć się z trenerem?")
            .setPositiveButton("Rozłącz") { _, _ -> SessionService.endSession(this) }
            .setNegativeButton("Anuluj", null).show()
    }
}
