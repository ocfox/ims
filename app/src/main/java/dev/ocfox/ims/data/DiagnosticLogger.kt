package dev.ocfox.ims.data

import android.os.Build
import android.util.Log
import dev.ocfox.ims.BuildConfig
import dev.ocfox.ims.model.SimStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Stores sanitized runtime diagnostics (excluding IMSI/ICCID/phone numbers)
 * so users can inspect, copy, or share troubleshooting timelines directly.
 */
object DiagnosticLogger {
    private const val MAX_ENTRIES = 300
    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun i(tag: String, message: String) = append(Log.INFO, tag, message)

    fun w(tag: String, message: String, error: Throwable? = null) {
        val full = if (error != null) "$message (${formatError(error)})" else message
        append(Log.WARN, tag, full)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        val full = if (error != null) "$message\n${formatStack(error)}" else message
        append(Log.ERROR, tag, full)
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            _logs.value = emptyList()
        }
    }

    fun buildReport(
        shizukuStatus: String,
        sims: List<SimStatus>,
        extraSummary: String? = null,
    ): String = buildString {
        appendLine("=== Pixel IMS Diagnostic Log ===")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
        appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Shizuku: $shizukuStatus")
        if (!extraSummary.isNullOrBlank()) {
            appendLine("Note: $extraSummary")
        }
        appendLine("Active SIMs (${sims.size}):")
        if (sims.isEmpty()) {
            appendLine("  (none)")
        } else {
            for (sim in sims) {
                val carrier = sim.carrierName.ifBlank { sim.displayName }.ifBlank { "SIM ${sim.slotIndex + 1}" }
                appendLine(
                    "  - slot=${sim.slotIndex} subId=${sim.subId} carrier=$carrier plmn=${sim.plmnLabel} " +
                        "ims=${sim.imsRegState}/${sim.imsTransport} wfc=${sim.wfcSystemEnabled}(${sim.wfcSystemMode}) " +
                        "override=${sim.isOverrideActive}"
                )
            }
        }
        appendLine("--- Timeline ---")
        val snapshot = synchronized(lock) { entries.toList() }
        if (snapshot.isEmpty()) {
            appendLine("(empty)")
        } else {
            snapshot.forEach { appendLine(it) }
        }
    }

    private fun append(priority: Int, tag: String, message: String) {
        val level = when (priority) {
            Log.ERROR -> "E"
            Log.WARN -> "W"
            else -> "I"
        }
        val stamp = synchronized(lock) {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        }
        val line = "$stamp [$level/$tag] $message"
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(line)
            _logs.value = entries.toList()
        }
        runCatching { Log.println(priority, tag, message) }
    }

    private fun formatError(error: Throwable): String {
        val msg = error.message?.takeIf { it.isNotBlank() }
        return if (msg != null) "${error.javaClass.simpleName}: $msg" else error.javaClass.simpleName
    }

    private fun formatStack(error: Throwable): String {
        val causeChain = buildList {
            var cur: Throwable? = error
            while (cur != null && size < 4) {
                add(formatError(cur))
                cur = cur.cause
            }
        }
        val topFrames = error.stackTrace.take(5).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        return "${causeChain.joinToString(" -> ")}\n  at $topFrames"
    }
}
