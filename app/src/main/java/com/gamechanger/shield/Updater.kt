package com.gamechanger.shield

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the GitHub releases of this repo.
 * Every CI build publishes a release tagged "v<build number>" with GameChanger.apk.
 */
object Updater {

    data class Release(val versionCode: Int, val name: String, val notes: String, val apkUrl: String)

    private val main = Handler(Looper.getMainLooper())

    fun check(onResult: (Release?, String?) -> Unit) {
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.getString("tag_name")
                val code = tag.trimStart('v', 'V').toIntOrNull() ?: 0
                val assets = json.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                }
                val rel = if (url != null && code > BuildConfig.VERSION_CODE) {
                    Release(code, json.optString("name", tag), json.optString("body", ""), url)
                } else null
                main.post { onResult(rel, null) }
            } catch (t: Throwable) {
                main.post { onResult(null, t.message ?: "שגיאה") }
            }
        }.start()
    }

    fun downloadAndInstall(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val app = context.applicationContext
        Thread {
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "GameChanger-${release.versionCode}.apk")
                var conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                // GitHub redirects to another host; follow manually if needed.
                var hops = 0
                while (conn.responseCode in 300..399 && hops++ < 5) {
                    val next = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = URL(next).openConnection() as HttpURLConnection
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; main.post { onProgress(pct) } }
                            }
                        }
                    }
                }
                val uri: Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                main.post { app.startActivity(intent) }
            } catch (t: Throwable) {
                main.post { onError(t.message ?: "ההורדה נכשלה") }
            }
        }.start()
    }
}
