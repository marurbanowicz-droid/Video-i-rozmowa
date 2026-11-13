package pl.trener.nawodzie

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var home: View
    private lateinit var join: View
    private lateinit var nameIn: EditText
    private lateinit var codeIn: EditText
    private lateinit var resumeBtn: View
    private lateinit var updBtn: com.google.android.material.button.MaterialButton
    private lateinit var verTxt: TextView
    private var pending: (() -> Unit)? = null

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val p = pending; pending = null
        val mic = res[Manifest.permission.RECORD_AUDIO] ?: granted(Manifest.permission.RECORD_AUDIO)
        if (!mic) { Notifier.toast("Bez dostępu do mikrofonu rozmowa nie zadziała"); return@registerForActivityResult }
        p?.invoke()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
        Updater.check { rel, _ -> if (rel != null) { updBtn.text = "⬆ Zainstaluj aktualizację ${rel.version}"; updBtn.visibility = View.VISIBLE } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    override fun onResume() {
        super.onResume()
        when (Engine.role) {
            Engine.Role.COACH -> { startActivity(Intent(this, CoachActivity::class.java)); return }
            Engine.Role.ATHLETE -> { startActivity(Intent(this, AthleteActivity::class.java)); return }
            else -> {}
        }
        val s = Prefs.session()
        resumeBtn.visibility = if (s != null) View.VISIBLE else View.GONE
        (resumeBtn as TextView).text = if (s?.optString("role") == "coach") "↻ Wznów sesję trenera (${Prefs.currentRoom().name})"
                                       else "↻ Wznów połączenie (pokój ${s?.optString("room")})"
        if (s != null && Prefs.resumeAfterUpdate) { Prefs.resumeAfterUpdate = false; resume() }
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data
        if (data != null && data.scheme == "trenernawodzie") {
            data.getQueryParameter("turn")?.takeIf { it.isNotBlank() }?.let { Prefs.sp.edit().putString("turn_url", it).apply() }
            showJoin(Prefs.sanitizeCode(data.getQueryParameter("pokoj") ?: ""))
            return
        }
        if (i?.getBooleanExtra("resume", false) == true && Prefs.session() != null && Engine.role == Engine.Role.NONE) resume()
    }

    private fun buildUi() {
        val root = column(24).apply { gravity = Gravity.CENTER }
        // ekran startowy
        val h = column(0).apply {
            addView(text("⛵ Trener na wodzie", 28f, bold = true).apply { gravity = Gravity.CENTER })
            addView(text("Rozmowa z zawodnikami i nagrywanie wideo z dźwiękiem jednocześnie.", 15f, col(R.color.mut)).apply { gravity = Gravity.CENTER }, lp(top = dp(8)))
            resumeBtn = button("↻ Wznów sesję", "primary") { resume() }
            addView(resumeBtn, lp(top = dp(24)))
            addView(button("Jestem trenerem") { askAndStartCoach() }, lp(top = dp(12)))
            addView(button("Jestem zawodnikiem") { showJoin(Prefs.lastRoom) }, lp(top = dp(12)))
            updBtn = button("⬆ Aktualizacja", "warn") { Updater.available?.let { Updater.downloadAndInstall(this@MainActivity, it) } }
            updBtn.visibility = View.GONE
            addView(updBtn, lp(top = dp(12)))
            verTxt = text("wersja ${BuildConfig.VERSION_NAME}", 12f, col(R.color.mut)).apply { gravity = Gravity.CENTER }
            addView(verTxt, lp(top = dp(20)))
        }
        home = h
        // dołączanie
        nameIn = EditText(this).apply {
            hint = "Twoje imię"; setText(Prefs.athName); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextColor(col(R.color.txt)); setHintTextColor(col(R.color.mut)); textSize = 20f
        }
        codeIn = EditText(this).apply {
            hint = "Kod pokoju"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(8)); gravity = Gravity.CENTER
            letterSpacing = 0.3f; setTextColor(col(R.color.txt)); setHintTextColor(col(R.color.mut)); textSize = 24f
        }
        val j = column(0).apply {
            addView(text("Dołącz do treningu", 26f, bold = true).apply { gravity = Gravity.CENTER })
            addView(text("Twoje imię", 14f, col(R.color.mut)), lp(top = dp(20)))
            addView(nameIn, lp())
            addView(text("Kod pokoju (od trenera)", 14f, col(R.color.mut)), lp(top = dp(12)))
            addView(codeIn, lp())
            addView(button("Połącz z trenerem", "primary") { joinClicked() }, lp(top = dp(20)))
            addView(button("Wróć") { showHome() }, lp(top = dp(12)))
        }
        join = j; j.visibility = View.GONE
        root.addView(h, lp()); root.addView(j, lp())
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun showHome() { home.visibility = View.VISIBLE; join.visibility = View.GONE }
    private fun showJoin(code: String) {
        home.visibility = View.GONE; join.visibility = View.VISIBLE
        if (code.isNotEmpty()) codeIn.setText(code)
        (if (nameIn.text.isNullOrBlank()) nameIn else codeIn).requestFocus()
    }

    private fun joinClicked() {
        val name = nameIn.text.toString().trim().take(24)
        val code = Prefs.sanitizeCode(codeIn.text.toString())
        if (name.isEmpty()) { Notifier.toast("Wpisz imię"); return }
        if (code.length < 4) { Notifier.toast("Wpisz kod pokoju"); return }
        Prefs.athName = name; Prefs.lastRoom = code
        askAndStartAthlete(code, name)
    }

    private fun resume() {
        val s = Prefs.session() ?: return
        if (s.optString("role") == "coach") askAndStartCoach()
        else askAndStartAthlete(s.optString("room"), s.optString("name"))
    }

    private fun basePerms() = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (sdkAtLeast(33)) add(Manifest.permission.POST_NOTIFICATIONS)
        if (sdkAtLeast(31)) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun askAndStartCoach() {
        pending = {
            if (!granted(Manifest.permission.CAMERA)) Notifier.toast("Bez dostępu do kamery nie będzie nagrywania")
            Prefs.saveSession("coach")
            Engine.startCoach()
            SessionService.start(this, granted(Manifest.permission.CAMERA))
            startActivity(Intent(this, CoachActivity::class.java))
        }
        perms.launch((basePerms() + Manifest.permission.CAMERA).toTypedArray())
    }

    private fun askAndStartAthlete(code: String, name: String) {
        pending = {
            Prefs.saveSession("athlete", code, name)
            Engine.startAthlete(code, name)
            SessionService.start(this, false)
            startActivity(Intent(this, AthleteActivity::class.java))
        }
        perms.launch(basePerms().toTypedArray())
    }
}
