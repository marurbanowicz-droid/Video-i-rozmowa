package pl.trener.nawodzie

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLEncoder

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.col(id: Int) = ContextCompat.getColor(this, id)

fun roundBg(color: Int, radiusPx: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

fun Context.text(s: String, sizeSp: Float = 16f, color: Int = col(R.color.txt), bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.button(label: String, style: String = "normal", onClick: (View) -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label; isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        cornerRadius = dp(14)
        minHeight = dp(58)
        val (bg, fg) = when (style) {
            "primary" -> col(R.color.acc) to Color.parseColor("#04211F")
            "danger" -> Color.parseColor("#3B1D27") to Color.parseColor("#FFB4BB")
            "warn" -> col(R.color.warn) to Color.parseColor("#2B1600")
            else -> col(R.color.card) to col(R.color.txt)
        }
        backgroundTintList = ColorStateList.valueOf(bg); setTextColor(fg)
        setOnClickListener(onClick)
    }

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
    LinearLayout.LayoutParams(w, h).apply { topMargin = top }

fun Context.column(padding: Int = 20): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun dotDrawable(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(28, 28) }

fun inviteLink(code: String): String {
    var u = Prefs.webUrl.let { if (it.contains("?")) it.substringBefore("?") else it } + "?pokoj=" + code
    if (Prefs.turnUrl.isNotEmpty()) u += "&turn=" + URLEncoder.encode(Prefs.turnUrl, "UTF-8")
    return u
}

fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.BLACK else Color.WHITE
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** Prośba o wyłączenie optymalizacji baterii – Samsung potrafi zamykać aplikacje działające w tle. */
fun Context.askBatteryOnce(force: Boolean = false) {
    val pm = getSystemService(PowerManager::class.java)
    if (pm.isIgnoringBatteryOptimizations(packageName)) { if (force) Notifier.toast("Optymalizacja baterii jest już wyłączona"); return }
    if (!force && Prefs.sp.getBoolean("battery_asked", false)) return
    Prefs.sp.edit().putBoolean("battery_asked", true).apply()
    MaterialAlertDialogBuilder(this)
        .setTitle("Praca w tle")
        .setMessage("Żeby system nie zamykał aplikacji podczas treningu (zgaszony ekran, inne aplikacje), zezwól na pracę bez ograniczeń baterii.\n\nNa Samsungu sprawdź też: Ustawienia → Bateria → Limity użycia w tle → upewnij się, że „Trener na wodzie” nie jest na liście aplikacji uśpionych.")
        .setPositiveButton("Zezwól") { _, _ ->
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        .setNegativeButton("Później", null)
        .show()
}

fun sdkAtLeast(v: Int) = Build.VERSION.SDK_INT >= v
