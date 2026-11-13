package pl.trener.nawodzie

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

const val ACCENT = 0xFFFFD60A.toInt()   // żółty jak w aparatach

/** Pokrętło zoomu w stylu aparatu: łuk z podziałką, przeciąganie w bok obraca pokrętło (skala logarytmiczna). */
class ZoomDial(ctx: Context) : View(ctx) {
    var minZ = 1f; private set
    var maxZ = 1f; private set
    var zoom = 1f; private set
    var onZoom: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val tickP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 1.5f * d; strokeCap = Paint.Cap.ROUND }
    private val majorP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val valueP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 14f * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val accentP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val tri = Path()
    private var lastX = 0f
    private var lastMajor = -1f
    private var ticks: List<Pair<Float, Boolean>> = emptyList()

    fun setRange(min: Float, max: Float) {
        minZ = min; maxZ = maxOf(min, max)
        zoom = zoom.coerceIn(minZ, maxZ)
        ticks = buildTicks()
        invalidate()
    }

    fun setZoomValue(z: Float) { zoom = z.coerceIn(minZ, maxZ); invalidate() }

    private fun buildTicks(): List<Pair<Float, Boolean>> {
        val majors = listOf(0.5f, 0.6f, 1f, 2f, 3f, 5f, 10f, 20f, 30f, 50f, 100f)
        val out = ArrayList<Pair<Float, Boolean>>()
        var v = (minZ * 10).roundToInt() / 10f
        while (v <= maxZ + 1e-3f) {
            out.add(v to majors.any { abs(it - v) < 1e-3f })
            v += when { v < 2f -> 0.1f; v < 5f -> 0.25f; v < 10f -> 0.5f; v < 30f -> 1f; else -> 5f }
            v = (v * 100).roundToInt() / 100f
        }
        return out
    }

    private val pxPerLog get() = width * 0.42f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; lastX = e.x
                val nz = exp(ln(zoom) - dx / pxPerLog).coerceIn(minZ, maxZ)
                if (nz != zoom) {
                    zoom = nz
                    val near = ticks.firstOrNull { it.second && abs(it.first - nz) < 0.03f * it.first }?.first ?: -1f
                    if (near > 0 && near != lastMajor) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    lastMajor = near
                    onZoom?.invoke(nz); invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        val cx = width / 2f
        val r = width * 0.95f
        val apex = 34f * d
        val cy = r + apex
        val lz = ln(zoom)
        for ((v, major) in ticks) {
            val th = (ln(v) - lz) * pxPerLog / r
            if (abs(th) > 0.62f) continue
            val a = (1f - abs(th) / 0.62f).coerceIn(0f, 1f)
            val s = sin(th); val co = cos(th)
            val len = if (major) 13f * d else 7f * d
            val p = if (major) majorP else tickP
            p.alpha = (255 * a).toInt()
            c.drawLine(cx + r * s, cy - r * co, cx + (r - len) * s, cy - (r - len) * co, p)
            if (major) {
                labelP.alpha = (255 * a).toInt()
                val lr = r - 28f * d
                c.drawText(fmt(v), cx + lr * s, cy - lr * co + 4f * d, labelP)
            }
        }
        // wskaźnik i aktualna wartość
        tri.reset(); tri.moveTo(cx - 6 * d, apex - 8 * d); tri.lineTo(cx + 6 * d, apex - 8 * d); tri.lineTo(cx, apex - 1 * d); tri.close()
        c.drawPath(tri, accentP)
        val txt = String.format(Locale.US, "%.1f×", zoom)
        val w = valueP.measureText(txt) + 16 * d
        c.drawRoundRect(RectF(cx - w / 2, 2 * d, cx + w / 2, apex - 11 * d), 10 * d, 10 * d, accentP)
        c.drawText(txt, cx, apex - 16 * d, valueP)
    }

    private fun fmt(v: Float) = if (v < 1f) String.format(Locale.US, ".%d", (v * 10).roundToInt()) else "${v.roundToInt()}"
}

/** Przycisk nagrywania: biały pierścień, czerwone koło → zaokrąglony kwadrat podczas nagrywania. */
class ShutterButton(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
    private var p = 0f
    private var anim: ValueAnimator? = null
    var recording = false
        set(v) {
            if (field == v) return
            field = v
            anim?.cancel()
            anim = ValueAnimator.ofFloat(p, if (v) 1f else 0f).setDuration(220).apply { addUpdateListener { p = it.animatedValue as Float; invalidate() }; start() }
        }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f - 3f * d
        c.drawCircle(cx, cy, r, ring)
        val inner = r - 7f * d
        val half = inner * (1f - 0.45f * p)
        val corner = half * (1f - 0.75f * p)
        c.drawRoundRect(RectF(cx - half, cy - half, cx + half, cy + half), corner, corner, fill)
    }
}
