package io.github.thunderfun.vendroid.utils

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object VDELog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    private data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String
    )

    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_BYTES = 256 * 1024L   // 256 KB
    private const val TRUNCATE_TARGET = 128 * 1024L   // keep newest 128 KB

    private val buffer = ArrayDeque<LogEntry>(64)
    // DateTimeFormatter is immutable and thread-safe (unlike SimpleDateFormat).
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val zone = ZoneId.systemDefault()

    private var handler: Handler? = null
    private var logFile: File? = null
    // Kept open on the handler thread. Flushed per line (see writeLineLocked)
    // so recovery mode always sees the current session. Handler-thread confined.
    private var writer: BufferedWriter? = null
    // Mirrors logFile.length() without a stat() per line.
    private var currentFileBytes = 0L

    private val initialized = AtomicBoolean(false)

    // Bounds the file writes queued on the handler thread. The in-memory ring
    // buffer is capped independently; under a flood (e.g. the report-only CSP
    // reporter) the ring buffer keeps the last N entries even if the disk sink
    // is deliberately throttled.
    private val pendingWrites = AtomicInteger(0)
    private const val MAX_PENDING_WRITES = 2000

    fun init(context: Context, persistToFile: Boolean) {
        if (!initialized.compareAndSet(false, true)) return
        // logFile is always set so reader-only processes (RecoveryActivity in
        // the main process) can read the file the :web process wrote.
        logFile = File(context.filesDir, "vde_logs.txt")
        if (!persistToFile) {
            // Reader-only process: never create the writer handler, so this
            // process never rotates/truncates/appends to the :web-managed file.
            // The in-memory ring buffer still works, and getLogFileContents()
            // can read :web's on-disk file. Eliminates the cross-process
            // rotation race (both processes used to rotate vde_logs.txt).
            return
        }
        val thread = HandlerThread("VDELog-writer", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        handler = Handler(thread.looper)
        // Rotate the previous session's file aside (rather than truncating it)
        // so a crash that triggered recovery still has logs available via
        // vde_logs.prev.txt, then write the new session header.
        handler?.post {
            try {
                val f = logFile
                if (f != null && f.exists() && f.length() > 0) {
                    val prev = File(f.parentFile, "vde_logs.prev.txt")
                    prev.delete()
                    if (f.renameTo(prev)) {
                        // Rename succeeded. Safe to start a fresh (truncated) file.
                        openWriterLocked(append = false)
                    } else {
                        // Rename failed; preserve the previous session's log by
                        // appending instead of truncating it away.
                        VDELog.d("VDELog", "rotate rename failed; appending to existing log")
                        openWriterLocked(append = true)
                    }
                } else {
                    openWriterLocked(append = false)
                }
                val header = "--- Session: ${dateFmt.format(Instant.now().atZone(zone))} (PID=${Process.myPid()}) ---\n"
                writeLineLocked(header)
            } catch (_: Exception) {
                // Disk init failed for this session; the in-memory ring buffer still works.
            }
        }
    }

    fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, redactIds(message))
        synchronized(buffer) {
            buffer.addLast(entry)
            if (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        // Only touch pendingWrites when the post will actually run. If handler
        // is null (pre-init or post-shutdown), skip the disk write; the ring
        // buffer still retains the entry. Otherwise the increment here would
        // be unbalanced (no task posted to decrement it) and would ratchet
        // pendingWrites up until every subsequent disk write was dropped
        // forever.
        val h = handler
        if (h == null) return
        if (pendingWrites.incrementAndGet() > MAX_PENDING_WRITES) {
            // Drop the disk write under a flood; the ring buffer keeps it.
            pendingWrites.decrementAndGet()
            return
        }
        h.post {
            try { writeToFile(entry, throwable) } finally { pendingWrites.decrementAndGet() }
        }
    }

    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)
    fun e(tag: String, message: String, throwable: Throwable) = log(Level.ERROR, tag, message, throwable)
    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)

    // Snowflake redaction. log() is the single chokepoint, so every sink
    // (ring buffer, disk file, log viewer / share output) passes through it.

    /** Snowflakes are 17-20 digit epoch-based IDs. Epoch-ms timestamps are
     *  13 digits and hashes are hex, so a pure-digit run of this length is
     *  practically never a false positive. */
    private const val SNOWFLAKE_MIN_DIGITS = 17
    private const val SNOWFLAKE_MAX_DIGITS = 20
    private val SNOWFLAKE_REGEX =
        Regex("(?<!\\d)\\d{$SNOWFLAKE_MIN_DIGITS,$SNOWFLAKE_MAX_DIGITS}(?!\\d)")

    // Bounded so hostile console spam of distinct large numbers cannot grow it.
    private const val MAX_SNOWFLAKE_ALIASES = 128
    private val snowflakeAliases = ConcurrentHashMap<String, String>()
    private val snowflakeCounter = AtomicInteger(0)

    /**
     * Replaces Discord snowflakes with stable aliases ("[sn1]", "[sn2]",
     * ...) so references stay correlatable across lines while real values
     * never reach logs or shares. Aliases live in memory only and reset
     * each session.
     */
    fun redactIds(raw: String): String {
        if (!raw.any { it.isDigit() }) return raw
        return SNOWFLAKE_REGEX.replace(raw) { m ->
            val id = m.value
            snowflakeAliases[id] ?: run {
                if (snowflakeAliases.size >= MAX_SNOWFLAKE_ALIASES) {
                    "[sn]"
                } else {
                    // Losing racer adopts the winner's alias, so every ID
                    // maps to exactly one stable alias.
                    val alias = "[sn${snowflakeCounter.incrementAndGet()}]"
                    snowflakeAliases.putIfAbsent(id, alias) ?: alias
                }
            }
        }
    }

    fun getRecentLogs(count: Int = 500): String {
        val entries: List<LogEntry>
        synchronized(buffer) {
            entries = buffer.toList().takeLast(count.coerceIn(1, MAX_ENTRIES))
        }
        if (entries.isEmpty()) return "No logs available."
        val sb = StringBuilder(entries.size * 120)
        for (entry in entries) {
            sb.append('[')
            sb.append(timeFmt.format(Instant.ofEpochMilli(entry.timestamp).atZone(zone)))
            sb.append("] [")
            sb.append(entry.level.name)
            sb.append("] [")
            sb.append(entry.tag)
            sb.append("] ")
            // Truncate individual entries to prevent bloating the JS bridge return value.
            val msg = if (entry.message.length > 2000) entry.message.substring(0, 2000) + "…" else entry.message
            // Escape newlines so each entry is one line for parsing.
            sb.append(msg.replace("\n", "\\n"))
            sb.append('\n')
        }
        return sb.toString()
    }

    fun clearLogs() {
        synchronized(buffer) { buffer.clear() }
        handler?.post {
            try {
                openWriterLocked(append = false)
            } catch (_: Exception) {
                // Reopen failure leaves the previous session's file in place.
            }
        }
    }

    fun shutdown() {
        val h = handler
        if (h != null) {
            // Flush and close before quitting the looper so buffered bytes survive.
            h.post {
                try {
                    writer?.flush()
                    writer?.close()
                } catch (_: Exception) {
                    // Already shutting down; the unwritten tail is lost either way.
                }
                writer = null
            }
            h.looper.quitSafely()
        }
        handler = null
    }

    fun getLogFileContents(): String {
        // Flush on the handler thread so the on-disk file is current.
        val h = handler
        if (h != null) {
            val latch = CountDownLatch(1)
            h.post {
                // Flush failed; the read below returns whatever reached disk.
                try { writer?.flush() } catch (_: Exception) {}
                latch.countDown()
            }
            // Interrupted: proceed rather than hang; the file may be one line stale.
            try { latch.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }
        return try {
            val f = logFile ?: return "No log file."
            if (f.exists()) f.readText() else "No log file."
        } catch (_: Exception) {
            "Failed to read log file."
        }
    }

    private fun writeToFile(entry: LogEntry, throwable: Throwable?) {
        try {
            if (writer == null) openWriterLocked(append = true)
            val line = buildString(160) {
                append('[')
                append(dateFmt.format(Instant.ofEpochMilli(entry.timestamp).atZone(zone)))
                append("] [")
                append(entry.level.name)
                append("] [")
                append(entry.tag)
                append("] ")
                append(entry.message.replace("\n", "\\n"))
                if (throwable != null) {
                    append(" | ")
                    append(redactIds(throwable.stackTraceToString()))
                }
                append('\n')
            }
            writeLineLocked(line)
            rotateIfNeededLocked()
        } catch (_: Exception) {
            // Writer may be in a bad state; force a reopen on the next line.
            // close() failure changes nothing; writer is nulled below.
            try { writer?.close() } catch (_: Exception) {}
            writer = null
        }
    }

    /** Writes [line] to the writer and advances [currentFileBytes].
     *  Handler-thread confined. */
    private fun writeLineLocked(line: String) {
        val w = writer ?: return
        val bytes = line.toByteArray(Charsets.UTF_8)
        w.write(line, 0, line.length)
        // Flush per line so the current session is always on disk.
        // RecoveryActivity reads the file from a different process and
        // cannot flush this writer.
        w.flush()
        currentFileBytes += bytes.size
    }

    /** Opens or reopens the file writer. When [append] is false the file is
     *  truncated and [currentFileBytes] reset; otherwise appended to and seeded
     *  from the existing file length. Handler-thread confined. */
    private fun openWriterLocked(append: Boolean) {
        try {
            writer?.close()
        } catch (_: Exception) {
            // Nothing to salvage from a writer this call is about to replace.
        }
        writer = null
        val f = logFile ?: return
        if (!append) {
            // Pre-truncate is best-effort; the writer below reopens without append, which truncates.
            try { f.writeText("") } catch (_: Exception) {}
            currentFileBytes = 0
        } else {
            currentFileBytes = if (f.exists()) f.length() else 0
        }
        writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(f, append), Charsets.UTF_8),
            8192
        )
    }

    /** Truncates the file to [TRUNCATE_TARGET] newest bytes when it exceeds
     *  [MAX_FILE_BYTES], using [currentFileBytes] instead of stat(). */
    private fun rotateIfNeededLocked() {
        if (currentFileBytes <= MAX_FILE_BYTES) return
        try {
            writer?.flush()
            writer?.close()
            writer = null
            val f = logFile ?: return
            val bytes = f.readBytes()
            val keep = bytes.copyOfRange(
                (bytes.size - TRUNCATE_TARGET.toInt()).coerceAtLeast(0),
                bytes.size
            )
            f.writeBytes(keep)
            currentFileBytes = keep.size.toLong()
            writer = BufferedWriter(
                OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8),
                8192
            )
        } catch (_: Exception) {
            // If rotation failed, reopen in append mode so logging continues.
            try {
                openWriterLocked(append = true)
            } catch (_: Exception) {
                // Recovery failed too; logging stays down until the next write reopens it.
            }
        }
    }
}
