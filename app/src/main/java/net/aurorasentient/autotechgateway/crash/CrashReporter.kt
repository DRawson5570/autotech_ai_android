package net.aurorasentient.autotechgateway.crash

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

private const val TAG = "CrashReporter"

/**
 * Catches uncaught exceptions and sends crash reports to the server.
 *
 * Reports are:
 * 1. Saved to local files (crash_reports/) as a fallback
 * 2. POSTed to the server endpoint when connectivity is available
 * 3. Any unsent reports are retried on next app launch
 */
class CrashReporter private constructor(
    private val context: Context,
    private val serverUrl: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val crashDir = File(context.filesDir, "crash_reports")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        @Volatile
        private var instance: CrashReporter? = null

        /**
         * Initialize the crash reporter. Call from Application.onCreate().
         */
        fun init(context: Context, serverUrl: String = "https://automotive.aurora-sentient.net") {
            if (instance != null) return
            instance = CrashReporter(context.applicationContext, serverUrl).apply {
                install()
            }
            Log.i(TAG, "CrashReporter initialized, server=$serverUrl")
        }

        /**
         * Send a non-fatal error report (e.g., caught exceptions worth logging).
         */
        fun reportNonFatal(throwable: Throwable, context: Map<String, String> = emptyMap()) {
            instance?.sendReport(throwable, fatal = false, extra = context)
        }

        /**
         * Send a log message (not an exception) to the server for debugging.
         */
        fun reportLog(level: String, message: String, context: Map<String, String> = emptyMap()) {
            instance?.sendLogReport(level, message, context)
        }
    }

    private fun install() {
        crashDir.mkdirs()

        // Chain with any existing handler (e.g., Android's default crash dialog)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL: Uncaught exception on thread ${thread.name}", throwable)

            // Build and save the crash report synchronously (we're about to die)
            val report = buildReport(throwable, fatal = true)
            saveToDisk(report)

            // Try to send it (best effort, short timeout)
            try {
                sendReportSync(report)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send crash report immediately, saved for retry", e)
            }

            // Call the original handler (shows crash dialog, kills process)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Retry any unsent reports from previous crashes
        retrySavedReports()
    }

    private fun buildReport(
        throwable: Throwable,
        fatal: Boolean,
        extra: Map<String, String> = emptyMap()
    ): CrashReport {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        return CrashReport(
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()),
            appVersion = getAppVersion(),
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            fatal = fatal,
            exceptionType = throwable.javaClass.name,
            message = throwable.message ?: "",
            stackTrace = sw.toString(),
            threadName = Thread.currentThread().name,
            extra = extra
        )
    }

    private fun buildLogReport(
        level: String,
        message: String,
        extra: Map<String, String> = emptyMap()
    ): CrashReport {
        return CrashReport(
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()),
            appVersion = getAppVersion(),
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            fatal = false,
            exceptionType = "LOG:$level",
            message = message,
            stackTrace = "",
            threadName = Thread.currentThread().name,
            extra = extra
        )
    }

    private fun sendReport(throwable: Throwable, fatal: Boolean, extra: Map<String, String> = emptyMap()) {
        scope.launch {
            try {
                val report = buildReport(throwable, fatal, extra)
                sendReportSync(report)
                Log.d(TAG, "Non-fatal report sent: ${throwable.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send non-fatal report", e)
                // Save for retry
                saveToDisk(buildReport(throwable, fatal, extra))
            }
        }
    }

    private fun sendLogReport(level: String, message: String, extra: Map<String, String> = emptyMap()) {
        scope.launch {
            try {
                val report = buildLogReport(level, message, extra)
                sendReportSync(report)
                Log.d(TAG, "Log report sent: $message")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send log report", e)
            }
        }
    }

    private fun sendReportSync(report: CrashReport) {
        val json = gson.toJson(report)
        val body = json.toRequestBody("application/json".toMediaType())
        val url = "${serverUrl.trimEnd('/')}/api/scan_tool/gateway/crash-report"

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Server returned ${response.code}: ${response.body?.string()}")
            }
        }
    }

    private fun saveToDisk(report: CrashReport) {
        try {
            val filename = "crash_${System.currentTimeMillis()}.json"
            val file = File(crashDir, filename)
            file.writeText(gson.toJson(report))
            Log.d(TAG, "Crash report saved: $filename")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash report to disk", e)
        }
    }

    private fun retrySavedReports() {
        scope.launch {
            try {
                val files = crashDir.listFiles { f -> f.extension == "json" } ?: return@launch
                if (files.isEmpty()) return@launch

                Log.d(TAG, "Retrying ${files.size} saved crash reports")
                for (file in files) {
                    try {
                        val report = gson.fromJson(file.readText(), CrashReport::class.java)
                        sendReportSync(report)
                        file.delete()
                        Log.d(TAG, "Retried and sent: ${file.name}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Retry failed for ${file.name}, will try later", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to retry saved reports", e)
            }
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }
}

/**
 * Data class for crash report payload — sent as JSON to the server.
 */
data class CrashReport(
    val timestamp: String,
    val appVersion: String,
    val androidVersion: String,
    val sdkInt: Int,
    val device: String,
    val fatal: Boolean,
    val exceptionType: String,
    val message: String,
    val stackTrace: String,
    val threadName: String,
    val extra: Map<String, String> = emptyMap(),
    val platform: String = "android"
)
