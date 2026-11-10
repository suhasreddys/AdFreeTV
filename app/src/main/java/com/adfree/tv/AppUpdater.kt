package com.adfree.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object AppUpdater {

    private const val GITHUB_REPO = "suhasreddys/AdFreeTV"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    /**
     * Checks GitHub for a newer release on app startup.
     */
    fun checkForUpdates(activity: Activity) {
        thread {
            try {
                val url = URL(RELEASES_API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "AdFreeTV-Updater")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val tagName = json.optString("tag_name", "").removePrefix("v")
                    val assets = json.optJSONArray("assets")

                    var downloadUrl: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                downloadUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }

                    if (downloadUrl != null && isNewerVersion(activity, tagName)) {
                        Handler(Looper.getMainLooper()).post {
                            promptUserToUpdate(activity, tagName, downloadUrl)
                        }
                    }
                }
            } catch (e: Exception) {
                // Silently ignore network failures during background update checks
            }
        }
    }

    /**
     * Compare semantic version strings (e.g. 1.0.3 vs 1.0.0).
     */
    private fun isNewerVersion(context: Context, remoteVersion: String): Boolean {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val localVersion = packageInfo.versionName ?: "1.0.0"

            val localParts = localVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val remoteParts = remoteVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val length = maxOf(localParts.size, remoteParts.size)

            for (i in 0 until length) {
                val local = localParts.getOrElse(i) { 0 }
                val remote = remoteParts.getOrElse(i) { 0 }
                if (remote > local) return true
                if (remote < local) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun promptUserToUpdate(activity: Activity, newVersion: String, downloadUrl: String) {
        if (activity.isFinishing || activity.isDestroyed) return

        AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setMessage("A new version of AdFreeTV (v$newVersion) is available on GitHub. Would you like to update now?")
            .setPositiveButton("Update") { _, _ ->
                downloadAndInstall(activity, downloadUrl)
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, downloadUrl: String) {
        Toast.makeText(activity, "Downloading update in background...", Toast.LENGTH_LONG).show()

        thread {
            try {
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000

                val updateDir = File(activity.cacheDir, "updates")
                if (!updateDir.exists()) updateDir.mkdirs()
                val apkFile = File(updateDir, "update.apk")

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    installApk(activity, apkFile)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(activity, "Failed to download update: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(activity, "Please allow permission to install updates", Toast.LENGTH_LONG).show()
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.provider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Installation error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
