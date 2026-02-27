package net.aurorasentient.autotechgateway.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

private const val TAG = "AutoUpdater"
private const val GITHUB_OWNER = "DRawson5570"
private const val GITHUB_REPO = "autotech_ai_android"
private const val RELEASES_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L  // 6 hours

/**
 * GitHub Release JSON model.
 */
data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("published_at") val publishedAt: String = "",
    @SerializedName("assets") val assets: List<GitHubAsset> = emptyList()
)

data class GitHubAsset(
    @SerializedName("name") val name: String = "",
    @SerializedName("browser_download_url") val downloadUrl: String = "",
    @SerializedName("size") val size: Long = 0
)

/**
 * Update check result.
 */
data class UpdateInfo(
    val available: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val apkSize: Long = 0
)

/**
 * Auto-updater that checks GitHub Releases for newer APK versions.
 *
 * Similar to the Windows gateway's auto-update mechanism:
 * - Checks on startup and every 6 hours
 * - Downloads the signed APK
 * - Launches Android's package installer
 */
class AutoUpdater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()
    private val gson = Gson()
    private var checkJob: Job? = null

    /**
     * Get the current app version from the package manager.
     */
    fun getCurrentVersion(): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    /**
     * Check GitHub Releases for a newer version.
     */
    suspend fun checkForUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentVersion()

        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API returned ${response.code}")
                return@withContext UpdateInfo(false, currentVersion, currentVersion)
            }

            val body = response.body?.string() ?: return@withContext UpdateInfo(false, currentVersion, currentVersion)
            val release = gson.fromJson(body, GitHubRelease::class.java)

            val latestTag = release.tagName.removePrefix("v")
            val isNewer = compareVersions(latestTag, currentVersion) > 0

            // Find the signed APK asset (AutotechGateway-*.apk)
            val signedApk = release.assets.find {
                it.name.startsWith("AutotechGateway") && it.name.endsWith(".apk")
            }
            // Fall back to any release APK
            val apk = signedApk ?: release.assets.find {
                it.name.endsWith(".apk") && !it.name.contains("debug")
            }

            Log.i(TAG, "Current: $currentVersion, Latest: $latestTag, Newer: $isNewer")

            UpdateInfo(
                available = isNewer && apk != null,
                currentVersion = currentVersion,
                latestVersion = latestTag,
                downloadUrl = apk?.downloadUrl ?: "",
                releaseNotes = release.name,
                apkSize = apk?.size ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}")
            UpdateInfo(false, currentVersion, currentVersion)
        }
    }

    /**
     * Download and install the update APK.
     */
    suspend fun downloadAndInstall(
        downloadUrl: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Downloading update from: $downloadUrl")
            onProgress(0f)

            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: ${response.code}")
                return@withContext false
            }

            val totalBytes = response.body?.contentLength() ?: -1
            val inputStream = response.body?.byteStream() ?: return@withContext false

            // Save to app's cache directory
            val apkFile = File(context.cacheDir, "update.apk")
            val outputStream = FileOutputStream(apkFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalBytes > 0) {
                    onProgress(totalRead.toFloat() / totalBytes)
                }
            }

            outputStream.close()
            inputStream.close()
            onProgress(1f)

            Log.i(TAG, "Download complete: ${apkFile.length()} bytes")

            // Trigger install
            installApk(apkFile)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed: ${e.message}", e)
            false
        }
    }

    /**
     * Launch Android's package installer for the downloaded APK.
     */
    private fun installApk(apkFile: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(intent)
    }

    /**
     * Start periodic update checks (every 6 hours).
     */
    fun startPeriodicChecks(
        scope: CoroutineScope,
        onUpdateAvailable: (UpdateInfo) -> Unit
    ) {
        checkJob?.cancel()
        checkJob = scope.launch {
            // Initial check after 30 seconds (let app settle)
            delay(30_000)
            val info = checkForUpdate()
            if (info.available) onUpdateAvailable(info)

            // Periodic checks
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                val update = checkForUpdate()
                if (update.available) onUpdateAvailable(update)
            }
        }
    }

    /**
     * Stop periodic checks.
     */
    fun stopPeriodicChecks() {
        checkJob?.cancel()
        checkJob = null
    }

    /**
     * Compare semantic version strings. Returns >0 if a > b.
     */
    private fun compareVersions(a: String, b: String): Int {
        val partsA = a.split(".").map { it.toIntOrNull() ?: 0 }
        val partsB = b.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(partsA.size, partsB.size)

        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va - vb
        }
        return 0
    }
}
