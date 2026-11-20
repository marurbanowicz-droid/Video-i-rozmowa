package pl.trener.nawodzie

import android.os.SystemClock
import java.io.File

/**
 * Diagnostyka zużycia procesora: czas pracy każdego wątku aplikacji odczytany z /proc/self/task.
 * Pozwala sprawdzić, co zużywa baterię (rozmowa, kamera, stabilizacja, sieć…).
 */
object CpuDiag {
    private const val HZ = 100.0   // USER_HZ na Androidzie
    private var base: Map<String, Long>? = null
    private var baseTotal = 0L
    private var baseAt = 0L

    private fun fields(stat: String): List<String> = stat.substring(stat.lastIndexOf(')') + 2).split(' ')

    // Co 5 s zapamiętujemy czas każdego wątku (po numerze), żeby po zakończeniu wątku wiedzieć, jak się nazywał.
    private val seen = HashMap<Int, Pair<String, Long>>()
    private var sampler: Thread? = null

    private fun scan(): Map<Int, Pair<String, Long>> {
        val m = HashMap<Int, Pair<String, Long>>()
        for (t in File("/proc/self/task").listFiles() ?: emptyArray()) try {
            val st = File(t, "stat").readText()
            val name = st.substring(st.indexOf('(') + 1, st.lastIndexOf(')'))
                .replace(Regex("[0-9]+"), "#").trim()
            val f = fields(st)
            m[t.name.toInt()] = name to (f[11].toLong() + f[12].toLong())
        } catch (_: Exception) {}
        return m
    }

    @Synchronized private fun sample() { seen.putAll(scan()) }

    fun startSampling() {
        if (sampler != null) return
        sampler = Thread {
            while (true) { try { sample(); Thread.sleep(5000) } catch (_: InterruptedException) { return@Thread } catch (_: Exception) {} }
        }.apply { isDaemon = true; name = "cpu-diag"; start() }
    }

    /** Suma czasu po nazwie – także wątków już zakończonych (ostatni odczyt sprzed ≤5 s). */
    @Synchronized private fun threads(): Map<String, Long> {
        seen.putAll(scan())
        val m = HashMap<String, Long>()
        for ((name, v) in seen.values) m[name] = (m[name] ?: 0L) + v
        return m
    }

    private fun procTotal(): Pair<Long, Long> = try {
        val f = fields(File("/proc/self/stat").readText())
        val cpu = f[11].toLong() + f[12].toLong()
        val ageTicks = (SystemClock.elapsedRealtime() / 1000.0 * HZ).toLong() - f[19].toLong()
        cpu to ageTicks
    } catch (_: Exception) { 0L to 0L }

    private fun fmt(ticks: Long): String {
        val s = (ticks / HZ).toLong()
        return if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }

    fun reset() {
        startSampling()
        base = threads(); baseTotal = procTotal().first; baseAt = SystemClock.elapsedRealtime()
    }

    fun report(): String {
        val (total0, age0) = procTotal()
        val now = threads()
        val b = base
        val total = total0 - (if (b != null) baseTotal else 0L)
        val age = if (b != null) ((SystemClock.elapsedRealtime() - baseAt) / 1000.0 * HZ).toLong() else age0
        val per = now.mapValues { (k, v) -> v - (b?.get(k) ?: 0L) }.filter { it.value > 0 }
        val sb = StringBuilder()
        sb.append(if (b != null) "Od wyzerowania: " else "Od uruchomienia aplikacji: ").append(fmt(age)).append('\n')
        sb.append("Procesor łącznie: ").append(fmt(total))
        if (age > 0) sb.append(" (").append(Math.round(total * 100.0 / age)).append("% jednego rdzenia)")
        sb.append("\nSesja: ").append(Engine.role.name.lowercase()).append(", kamera: ")
            .append(if (CamRec.sleeping) "uśpiona" else if (CamRec.recording != null) "nagrywa" else "włączona")
            .append(", tryb: ").append(Prefs.stabMode).append("\n\n")
        val live = per.values.sum()
        for ((k, v) in per.entries.sortedByDescending { it.value }.take(18)) {
            sb.append(fmt(v)).append(" – ").append(k)
            if (age > 0) sb.append(" (").append(Math.round(v * 100.0 / age)).append("%)")
            sb.append('\n')
        }
        if (total - live > HZ) sb.append(fmt(total - live)).append(" – wątki już zakończone\n")
        return sb.toString()
    }
}
