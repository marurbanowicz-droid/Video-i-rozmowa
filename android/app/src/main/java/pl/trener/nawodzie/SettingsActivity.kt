package pl.trener.nawodzie

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null)
            supportFragmentManager.beginTransaction().replace(android.R.id.content, SettingsFragment()).commit()
    }
}

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
    private var coach = true
    private var updPref: Preference? = null
    private var dataPref: Preference? = null
    private var stabPref: ListPreference? = null
    private val ui = Engine.Ui { refreshDynamic() }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        coach = activity?.intent?.getBooleanExtra("coach", true) ?: true
        val ctx = preferenceManager.context
        val screen: PreferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen

        fun cat(t: String) = PreferenceCategory(ctx).apply { title = t; isIconSpaceReserved = false }.also { screen.addPreference(it) }
        fun PreferenceCategory.sw(k: String, t: String, s: String?, def: Boolean) = SwitchPreferenceCompat(ctx).apply {
            key = k; title = t; summary = s; setDefaultValue(def); isIconSpaceReserved = false
        }.also { addPreference(it) }
        fun PreferenceCategory.list(k: String, t: String, names: Array<String>, values: Array<String>, def: String) = ListPreference(ctx).apply {
            key = k; title = t; entries = names; entryValues = values; setDefaultValue(def); isIconSpaceReserved = false
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }.also { addPreference(it) }
        fun PreferenceCategory.edit(k: String, t: String, def: String, numeric: Boolean = false) = EditTextPreference(ctx).apply {
            key = k; title = t; setDefaultValue(def); isIconSpaceReserved = false
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { p -> p.text?.ifEmpty { "—" } ?: "—" }
            if (numeric) setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED }
        }.also { addPreference(it) }
        fun PreferenceCategory.action(t: String, s: String?, click: () -> Unit) = Preference(ctx).apply {
            title = t; summary = s; isIconSpaceReserved = false; setOnPreferenceClickListener { click(); true }
        }.also { addPreference(it) }

        if (coach) {
            val img = cat("Obraz i nagrywanie")
            img.list("quality", "Jakość nagrania", arrayOf("HD 720p (~45 MB/min)", "Full HD 1080p (~105 MB/min)", "4K (~300 MB/min)"), arrayOf("HD", "FHD", "UHD"), "FHD")
            img.list("aspect", "Proporcje obrazu", arrayOf("4:3", "16:9 (panoramiczne)"), arrayOf("4:3", "16:9"), "4:3")
            img.sw("watermark", "Data i godzina na nagraniu", "Mały napis w prawym dolnym rogu, jak w rejestratorach jazdy.", true)
            stabPref = img.list("stab_mode", "Stabilizacja obrazu",
                arrayOf("OIS – optyczna (obiektyw)", "EIS – elektroniczna systemowa", "SUPER – jak Super Steady", "SUPER+H – Super + poziomy horyzont"),
                arrayOf("off", "eis", "super", "superh"), Prefs.stabMode)
                .apply { summaryProvider = null }   // opis ustawiamy sami w refreshDynamic()
            img.list("stab_smooth", "SUPER: siła wygładzania", arrayOf("Słaba – szybko podąża za ruchem", "Średnia", "Mocna", "Bardzo mocna – „pływający” obraz"),
                arrayOf("0.2", "0.35", "0.6", "1.0"), "0.35")
            val cal = cat("SUPER – kalibracja (zaawansowane)")
            cal.sw("stab_log", "Zapisuj dane kalibracji", "Plik z danymi żyroskopu obok każdego nagrania SUPER (Dokumenty/TrenerNaWodzie/Kalibracja). Włącz tylko do testów.", false)
            cal.edit("stab_t_ms", "Korekta czasu żyroskopu (ms)", "0", numeric = true).apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p -> "${p.text ?: "0"} ms – zostaw 0, chyba że kalibracja wskaże inną wartość." }
            }
            cal.sw("stab_invert", "Odwróć kierunek korekty", "Tylko jeśli w trybie SUPER drgania na podglądzie są wyraźnie większe niż w trybie OIS.", false)
            cal.list("stab_rs", "Korekta odczytu matrycy („galaretka”)", arrayOf("Włączona (od góry kadru)", "Włączona (odwrócona)", "Wyłączona"), arrayOf("1", "-1", "0"), "1")
            cal.edit("stab_fscale", "Korekta ogniskowej (proc.)", "100", numeric = true).apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p -> "${p.text ?: "100"} – zostaw 100, chyba że kalibracja wskaże inną wartość." }
            }
            img.edit("audio_offset", "Korekta synchronizacji dźwięku (ms)", "0", numeric = true).apply {
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p -> "${p.text ?: "0"} ms – dodatnia: dźwięk później, ujemna: wcześniej. Sprawdź klaśnięciem w kadrze." }
            }
            img.action("Gdzie są nagrania?", "Galeria → Filmy / TrenerNaWodzie. Dźwięk jest dołączany po zatrzymaniu nagrania.") {}
        }

        val talk = cat("Rozmowa i przyciski")
        talk.list("talk_mode", "Tryb rozmowy", arrayOf("Rozmowa ciągła", "Push-to-talk (naciśnij, aby mówić)"), arrayOf("open", "ptt"), "open")
        val acts = if (coach) arrayOf("Wycisz wszystko (wysyłanie i odbiór)", "Wycisz mikrofon", "Push-to-talk", "Nagrywanie start / stop", "Notatka głosowa start / stop")
                   else arrayOf("Wycisz wszystko (wysyłanie i odbiór)", "Wycisz mikrofon", "Push-to-talk")
        val actVals = if (coach) arrayOf("mute_all", "mute_mic", "ptt", "record", "note") else arrayOf("mute_all", "mute_mic", "ptt")
        talk.list("headset_action", "Środkowy przycisk słuchawek", acts, actVals, "mute_all")
        talk.list("vol_up", "Przycisk głośności +", arrayOf("Zwykła głośność") + acts, arrayOf("volume") + actVals, "mute_all")
        talk.list("vol_down", "Przycisk głośności −", arrayOf("Zwykła głośność") + acts, arrayOf("volume") + actVals, if (coach) "record" else "volume")
        talk.action("Jak działają przyciski", "Działają też przy zgaszonym ekranie. Push-to-talk: przytrzymaj, aby mówić; w słuchawkach Bluetooth – kliknij, aby zacząć i kliknij ponownie, aby skończyć.") {}

        val snd = cat("Dźwięk")
        snd.list("mic_gain", "Wzmocnienie mikrofonu (Mic Gain)", arrayOf("0,5×", "0,75×", "1× (bez zmian)", "1,5×", "2×", "3×", "4× (może przesterowywać)"),
            arrayOf("0.5", "0.75", "1", "1.5", "2", "3", "4"), "1")
        snd.list("gain", "Głośność odbieranego głosu (wzmocnienie)", arrayOf("1× (bez wzmocnienia)", "2×", "3× (zalecane)", "4×", "6×", "8× (może przesterowywać)"), arrayOf("1", "2", "3", "4", "6", "8"), "3")
        if (coach) {
            snd.sw("coach_noise", "Redukcja szumów w rozmowie", "Dotyczy tego, co słyszą zawodnicy. Nagranie wideo dostaje dźwięk bez redukcji.", false)
            snd.sw("rec_raw", "Nagranie bez redukcji szumów systemu", "Mikrofon bez filtrów telefonu – sygnał startu nie jest tłumiony. Działa po ponownym rozpoczęciu sesji. Używaj w słuchawkach (bez głośnika), żeby nie było echa.", false)
            snd.sw("coach_wind", "Filtr niskich tonów (wiatr)", "Tłumi dudnienie wiatru i silnika.", true)
            snd.sw("crosstalk", "Zawodnicy słyszą się nawzajem", "Każdy zawodnik łączy się z każdym – działa też z zawodnikami na stronie (iPhone).", true)
        } else {
            snd.sw("ath_noise", "Redukcja szumów", null, true)
            snd.sw("ath_wind", "Filtr niskich tonów (wiatr)", "Tłumi dudnienie wiatru.", true)
        }

        if (coach) {
            val nt = cat("Notatki głosowe")
            nt.sw("note_transcribe", "Zapisuj transkrypcję obok nagrania", "Plik .txt obok .m4a w Dokumenty/TrenerNaWodzie/Notatki.", true)
            nt.edit("openai_key", "Klucz OpenAI (opcjonalnie)", "").apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    if (p.text.isNullOrBlank()) "Brak – transkrypcja w telefonie (Android 13+). Z kluczem: dokładniejszy model Whisper, wymaga internetu." else "Ustawiony – transkrypcję robi Whisper."
                }
            }
        }

        val net = cat("Połączenie")
        if (coach) {
            net.list("kbps", "Jakość rozmowy (dla wszystkich)", arrayOf("Oszczędna – 16 kb/s (słaby zasięg)", "Standardowa – 32 kb/s", "Wysoka – 64 kb/s"), arrayOf("16", "32", "64"), "32")
        }
        dataPref = net.action("Dane w tej sesji", "—") {}
        if (coach) {
            net.edit("turn_url", "Adres danych TURN (opcjonalnie)", "")
            net.edit("web_url", "Adres strony (do zaproszeń)", BuildConfig.WEB_URL)
        }

        val notif = cat("Powiadomienia o dołączeniu i rozłączeniu")
        notif.sw("n_sound", "Sygnał dźwiękowy", null, true)
        notif.sw("n_text", "Komunikat na ekranie", null, true)
        notif.sw("n_voice", "Komunikat głosowy", "Np. „Ola – utracono połączenie”.", false)

        val sb = cat("Czuwanie w tle (poza sesją)")
        sb.sw("standby_coach", "Powiadamiaj, gdy ktoś łączy się z moim pokojem",
            "Działa dla zapisanych pokoi, gdy nie prowadzisz sesji. Dotknięcie powiadomienia rozpoczyna sesję.", true)
        sb.sw("standby_ath", "Pozwól trenerowi do mnie dzwonić",
            "Trener może wywołać Cię z listy osób, które były w jego pokoju (tylko aplikacja Android).", true)
        sb.action("Czas czuwania", "Czuwanie wyłącza się samo 2 godziny po zakończeniu ostatniej sesji, żeby nie zużywać baterii. Wcześniej możesz je zatrzymać przez „Zamknij aplikację”.") {}

        val appCat = cat("Aplikacja")
        appCat.action("Praca w tle bez ograniczeń baterii", "Zalecane – system nie zamknie sesji") { requireContext().askBatteryOnce(force = true) }
        updPref = appCat.action("Wersja ${BuildConfig.VERSION_NAME}", "Dotknij, aby sprawdzić aktualizację") { checkUpdate() }
        appCat.action("Aktualizacja zachowuje dane", "Pokoje, ustawienia i nagrania zostają. Trwająca sesja wznawia się jednym dotknięciem po instalacji.") {}
        appCat.action("Zamknij aplikację", "Kończy sesję, wyłącza działanie w tle i powiadomienie – do następnego otwarcia aplikacji") {
            activity?.let { AppExit.confirm(it) }
        }
        if (Engine.role != Engine.Role.NONE) {
            appCat.action("Zakończ sesję", "Rozłącza wszystkich i zatrzymuje nagrywanie") {
                SessionService.endSession(requireContext()); activity?.finish()
            }
        }
        refreshDynamic()
    }

    private fun checkUpdate() {
        updPref?.summary = "Sprawdzam…"
        Updater.check { rel, err ->
            when {
                err != null -> updPref?.summary = "Nie udało się sprawdzić ($err)"
                rel == null -> updPref?.summary = "Masz najnowszą wersję"
                else -> {
                    updPref?.summary = "Dostępna wersja ${rel.version} – dotknij, aby zainstalować"
                    updPref?.setOnPreferenceClickListener { activity?.showUpdateDialog(rel); true }
                    activity?.showUpdateDialog(rel)
                }
            }
        }
    }

    private fun refreshDynamic() {
        val tot = Engine.bytesSent + Engine.bytesRecv
        val mins = if (Engine.sessionStart > 0) maxOf(1.0, (System.currentTimeMillis() - Engine.sessionStart) / 60000.0) else 1.0
        dataPref?.summary = if (Engine.role == Engine.Role.NONE) "Brak aktywnej sesji"
            else "Wysłane ${fmtBytes(Engine.bytesSent)} · odebrane ${fmtBytes(Engine.bytesRecv)} · razem ${fmtBytes(tot)} (${fmtBytes((tot / mins).toLong())}/min)"
        stabPref?.summary = when (Prefs.stabMode) {
            "eis" -> "Elektroniczna stabilizacja systemu telefonu (jeśli ją udostępnia) razem z optyczną."
            "super" -> "Obiektyw szeroki z dużym zapasem kadru, korekta z żyroskopu i wyprzedzeniem 0,5 s. Zoom ograniczony."
            "superh" -> "Jak SUPER, a horyzont zawsze poziomo."
            else -> "Drgania tłumi stabilizacja optyczna obiektywu, bez przycinania kadru."
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        Engine.addUi(ui)
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        Engine.removeUi(ui)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        key ?: return
        when (key) {
            "vol_up", "vol_down" -> { Controls.applyVolumeMode(); Engine.reportSettings() }
            "headset_action" -> Engine.reportSettings()
            "note_transcribe", "openai_key" -> {}
            "rec_raw" -> Notifier.toast("Zmiana zadziała od następnej sesji")
            "standby_coach", "standby_ath" -> StandbyService.ensure(requireContext())
            "sport_inv_h", "sport_inv_v", "sport_inv_roll" -> {}
            "quality", "stab_mode", "aspect", "watermark", "stab_smooth", "stab_t_ms", "stab_rs", "stab_fscale", "stab_invert" -> {
                if (CamRec.recording != null) Notifier.toast("Zmiana zadziała po zakończeniu nagrania")
                else CamRec.rebind()
            }
            else -> Engine.onSettingChanged(key)
        }
    }
}
