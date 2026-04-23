package com.voiceledger.lite.semantic

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AggregationRunLogger(context: Context) {
    private val logDirectory = File(context.filesDir, LOG_DIRECTORY).apply { mkdirs() }
    private val latestLogFile = File(logDirectory, LATEST_LOG_FILE)
    private var sessionLogFile: File? = null

    fun beginRun(
        isManualTrigger: Boolean,
        rebuildRequested: Boolean,
        rebuildFromEpochMs: Long?,
    ): AggregationLogSnapshot {
        val timestamp = FILE_NAME_FORMATTER.format(Instant.now())
        val mode = when {
            rebuildRequested -> "rebuild"
            isManualTrigger -> "update"
            else -> "scheduled"
        }
        sessionLogFile = File(logDirectory, "aggregation-$timestamp-$mode.log")
        latestLogFile.parentFile?.mkdirs()
        latestLogFile.writeText("")
        append("Run started ($mode)")
        if (rebuildRequested && rebuildFromEpochMs != null) {
            append("Rebuild floor: ${DISPLAY_DATE_FORMATTER.format(Instant.ofEpochMilli(rebuildFromEpochMs))}")
        }
        pruneHistory()
        return snapshot()
    }

    fun append(message: String) {
        writeLine(message.trim())
    }

    fun appendThrowable(prefix: String, throwable: Throwable) {
        writeLine(prefix.trim())
        writeRaw(expandedThrowableString(throwable))
    }

    fun snapshot(maxLines: Int = DEFAULT_TAIL_LINES): AggregationLogSnapshot {
        val path = latestLogFile.absolutePath.takeIf { latestLogFile.exists() }
        val tail = if (latestLogFile.exists()) {
            latestLogFile.readLines().takeLast(maxLines)
        } else {
            emptyList()
        }
        return AggregationLogSnapshot(
            path = path,
            tail = tail,
        )
    }

    private fun writeLine(message: String) {
        if (message.isBlank()) {
            return
        }
        writeRaw("[${LINE_TIMESTAMP_FORMATTER.format(Instant.now())}] $message\n")
    }

    private fun writeRaw(text: String) {
        latestLogFile.appendText(text)
        sessionLogFile?.appendText(text)
    }

    private fun pruneHistory() {
        logDirectory.listFiles { file ->
            file.isFile && file.name.startsWith("aggregation-") && file.name.endsWith(".log")
        }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_HISTORY_FILES)
            ?.forEach(File::delete)
    }

    private fun expandedThrowableString(throwable: Throwable): String {
        val builder = StringBuilder()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null) {
            if (depth > 0) {
                builder.append("Caused by: ")
            }
            builder.append(current::class.java.name)
            current.message?.takeIf { it.isNotBlank() }?.let {
                builder.append(": ")
                builder.append(it)
            }
            builder.append('\n')
            current.stackTrace.forEach { element ->
                builder.append("\tat ")
                builder.append(element.toString())
                builder.append('\n')
            }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return builder.toString()
    }

    companion object {
        private const val LOG_DIRECTORY = "logs/aggregation"
        private const val LATEST_LOG_FILE = "latest.log"
        private const val MAX_HISTORY_FILES = 8
        private const val DEFAULT_TAIL_LINES = 80
        private val FILE_NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
        private val LINE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        private val DISPLAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
    }
}

data class AggregationLogSnapshot(
    val path: String?,
    val tail: List<String>,
)
