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
    private lateinit var asCoachBox: android.widget.CheckBox
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
        Prefs.standbyPaused = false   // użytkownik otworzył aplikację – czuwanie może wrócić
        buildUi()
        handleIntent(intent)
        Prefs.sp.getString("last_crash", null)?.let { crash ->
            Prefs.sp.edit().remove("last_crash").apply()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Aplikacja została nieoczekiwanie zamknięta")
                .setMessage("Skopiuj opis błędu i wyślij go, żeby można było to naprawić.\n\n" + crash.take(1500))
                .setPositiveButton("Kopiuj opis") { _, _ ->
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("błąd", crash)); Notifier.toast("Skopiowano")
                }
                .setNegativeButton("Zamknij", null).show()
        }
        Updater.check { rel, _ -> if (rel != null) { updBtn.text = "⬆ Zainstaluj aktualizację ${rel.version}"; updBtn.visibility = View.VISIBLE } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    override fun onResume() {
        super.onResume()
        when (Engine.role) {
            Engine.Role.COACH -> { startActivity(Intent(this, CoachActivity::class.java)); return }
            Engine.Role.ATHLETE -> { startActivity(Intent(this, if (Engine.athAsCoach) CoachActivity::class.java else AthleteActivity::class.java)); return }
            else -> {}
        }
        StandbyService.ensure(this)
        val s = Prefs.session()
        resumeBtn.visibility = if (s != null) View.VISIBLE else View.GONE
        (resumeBtn as TextView).text = if (s?.optString("role") == "coach") "↻ Wznów sesję trenera (${Prefs.currentRoom().name})"
                                       else if (s?.optBoolean("asCoach") == true) "↻ Wznów jako trener (pokój ${s.optString("room")})"
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
        i?.getStringExtra("start_coach_room")?.let { code ->
            i?.removeExtra("start_coach_room")
            if (Engine.role == Engine.Role.NONE && Prefs.loadRooms().any { it.code == code }) { Prefs.setCurrentRoom(code); askAndStartCoach() }
            return
        }
        i?.getStringExtra("join_room")?.let { code ->
            i?.removeExtra("join_room")
            getSystemService(android.app.NotificationManager::class.java).cancel(StandbyService.RING_ID)
            if (Engine.role != Engine.Role.NONE) return
            if (Prefs.athName.isBlank()) showJoin(code) else { Prefs.lastRoom = code; askAndStartAthlete(code, Prefs.athName) }
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
            addView(button("Jestem zawodnikiem") { asCoachBox.isChecked = false; showJoin(Prefs.lastRoom) }, lp(top = dp(12)))
            addView(button("Dołączam jako trener") { asCoachBox.isChecked = true; showJoin(Prefs.lastRoom) }, lp(top = dp(12)))
            updBtn = button("⬆ Aktualizacja", "warn") { Updater.available?.let { showUpdateDialog(it) } }
            updBtn.visibility = View.GONE
            addView(updBtn, lp(top = dp(12)))
            addView(button("Zamknij aplikację", "danger") { AppExit.confirm(this@MainActivity) }, lp(top = dp(24)))
            addView(button("Sprawdź aktualizacje") { forceUpdateCheck() }, lp(top = dp(12)))
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
        asCoachBox = android.widget.CheckBox(this).apply {
            text = "Dołączam jako trener (kamera, nagrywanie, sterowanie zawodnikami)"; setTextColor(col(R.color.txt)); textSize = 15f
        }
        val j = column(0).apply {
            addView(text("Dołącz do treningu", 26f, bold = true).apply { gravity = Gravity.CENTER })
            addView(text("Twoje imię", 14f, col(R.color.mut)), lp(top = dp(20)))
            addView(nameIn, lp())
            addView(text("Kod pokoju (od trenera)", 14f, col(R.color.mut)), lp(top = dp(12)))
            addView(codeIn, lp())
            addView(asCoachBox, lp(top = dp(12)))
            addView(button("Połącz z trenerem", "primary") { joinClicked() }, lp(top = dp(20)))
            addView(button("Wróć") { showHome() }, lp(top = dp(12)))
        }
        join = j; j.visibility = View.GONE
        root.addView(h, lp()); root.addView(j, lp())
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    /** Wymuszone sprawdzenie – zawsze pokazuje wynik. */
    private fun forceUpdateCheck() {
        Notifier.toast("Sprawdzam aktualizacje…")
        Updater.check { rel, err ->
            when {
                err != null -> com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Nie udało się sprawdzić").setMessage("Sprawdź połączenie z internetem.\n\n($err)").setPositiveButton("OK", null).show()
                rel == null -> com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Masz najnowszą wersję").setMessage("Zainstalowana: ${BuildConfig.VERSION_NAME}\nNajnowsza opublikowana: ${Updater.latestSeen ?: "—"}\n\nJeśli nowa wersja jest scalona, a nie widać jej tutaj, poczekaj kilka minut – publikacja trwa po scaleniu.")
                    .setPositiveButton("OK", null).show()
                else -> { updBtn.text = "⬆ Zainstaluj aktualizację ${rel.version}"; updBtn.visibility = View.VISIBLE; showUpdateDialog(rel) }
            }
        }
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
        askAndStartAthlete(code, name, asCoachBox.isChecked)
    }

    private fun resume() {
        val s = Prefs.session() ?: return
        if (s.optString("role") == "coach") askAndStartCoach()
        else askAndStartAthlete(s.optString("room"), s.optString("name"), s.optBoolean("asCoach"))
    }

    private fun basePerms() = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (sdkAtLeast(33)) add(Manifest.permission.POST_NOTIFICATIONS)
        if (sdkAtLeast(31)) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun askAndStartCoach() {
        pending = {
            if (!granted(Manifest.permission.CAMERA)) Notifier.toast("Bez dostępu do kamery nie będzie nagrywania")
            Prefs.wasCoach = true
            StandbyService.stop(this)
            Prefs.saveSession("coach")
            Engine.startCoach()
            SessionService.start(this, coach = true, camera = granted(Manifest.permission.CAMERA))
            startActivity(Intent(this, CoachActivity::class.java))
        }
        perms.launch((basePerms() + Manifest.permission.CAMERA).toTypedArray())
    }

    private fun askAndStartAthlete(code: String, name: String, asCoach: Boolean = false) {
        pending = {
            if (!asCoach) Prefs.wasAthlete = true
            StandbyService.stop(this)
            Prefs.saveSession("athlete", code, name, asCoach)
            Engine.startAthlete(code, name, asCoach)
            val cam = asCoach && granted(Manifest.permission.CAMERA)
            if (asCoach && !cam) Notifier.toast("Bez dostępu do kamery nie będzie nagrywania")
            SessionService.start(this, coach = asCoach, camera = cam)
            startActivity(Intent(this, if (asCoach) CoachActivity::class.java else AthleteActivity::class.java))
        }
        perms.launch((if (asCoach) basePerms() + Manifest.permission.CAMERA else basePerms()).toTypedArray())
    }
}
