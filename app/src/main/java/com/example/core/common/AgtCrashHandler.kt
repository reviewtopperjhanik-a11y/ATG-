package com.example.core.common

import android.content.Context
import com.example.core.security.SecretRedactor
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

data class CrashReportSummary(
    val timestampMs: Long,
    val threadName: String,
    val exceptionClass: String,
    val message: String,
    val stackExcerpt: String,
)

/**
 * Intercepts uncaught crashes to persist a bounded, strictly secret-redacted diagnostic summary
 * inside private app storage (`files/diagnostics/last_crash.json`) before delegating to the OS handler.
 */
object AgtCrashHandler {
    private const val DIAGNOSTICS_DIR = "diagnostics"
    private const val CRASH_FILE_NAME = "last_crash.json"
    private const val MAX_MESSAGE_CHARS = 320
    private const val MAX_STACK_LINES = 20
    private const val MAX_STACK_CHARS = 1800

    @Volatile
    private var installedForProcess = false

    fun install(
        context: Context,
        knownSecretsProvider: () -> Collection<String> = { emptyList() },
    ) {
        if (installedForProcess) return
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (previousHandler is AgtUncaughtHandler) {
            installedForProcess = true
            return
        }
        Thread.setDefaultUncaughtExceptionHandler(
            AgtUncaughtHandler(
                appContext = appContext,
                previous = previousHandler,
                knownSecretsProvider = knownSecretsProvider,
            )
        )
        installedForProcess = true
    }

    fun recordCrash(
        context: Context,
        throwable: Throwable,
        threadName: String = Thread.currentThread().name,
        knownSecrets: Collection<String> = emptyList(),
        timestampMs: Long = System.currentTimeMillis(),
    ): CrashReportSummary {
        val rawMsg = throwable.message?.ifBlank { null } ?: throwable.javaClass.simpleName
        val safeMsg = SecretRedactor.redact(rawMsg, knownSecrets).take(MAX_MESSAGE_CHARS)

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val limitedStack = sw.toString()
            .lineSequence()
            .take(MAX_STACK_LINES)
            .joinToString("\n")
        val safeStack = SecretRedactor.redact(limitedStack, knownSecrets).take(MAX_STACK_CHARS)

        val summary = CrashReportSummary(
            timestampMs = timestampMs,
            threadName = threadName.take(64),
            exceptionClass = throwable.javaClass.name,
            message = safeMsg,
            stackExcerpt = safeStack,
        )

        val file = crashFile(context)
        file.parentFile?.mkdirs()
        val json = JSONObject()
            .put("timestampMs", summary.timestampMs)
            .put("threadName", summary.threadName)
            .put("exceptionClass", summary.exceptionClass)
            .put("message", summary.message)
            .put("stackExcerpt", summary.stackExcerpt)
            .toString()

        val tempFile = File(file.parentFile, "$CRASH_FILE_NAME.tmp")
        tempFile.writeText(json)
        if (file.exists()) file.delete()
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
        return summary
    }

    fun readLastCrash(context: Context): CrashReportSummary? {
        val file = crashFile(context)
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val obj = JSONObject(file.readText())
            CrashReportSummary(
                timestampMs = obj.optLong("timestampMs", 0L),
                threadName = obj.optString("threadName", "main"),
                exceptionClass = obj.optString("exceptionClass", "Throwable"),
                message = obj.optString("message", ""),
                stackExcerpt = obj.optString("stackExcerpt", ""),
            )
        }.getOrNull()
    }

    fun clearLastCrash(context: Context) {
        val file = crashFile(context)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun crashFile(context: Context): File =
        File(File(context.applicationContext.filesDir, DIAGNOSTICS_DIR), CRASH_FILE_NAME)

    private class AgtUncaughtHandler(
        private val appContext: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
        private val knownSecretsProvider: () -> Collection<String>,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val secrets = runCatching { knownSecretsProvider() }.getOrDefault(emptyList())
                recordCrash(
                    context = appContext,
                    throwable = e,
                    threadName = t.name,
                    knownSecrets = secrets,
                )
            }
            previous?.uncaughtException(t, e)
        }
    }
}
