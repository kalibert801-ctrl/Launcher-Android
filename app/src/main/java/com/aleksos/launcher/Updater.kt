package com.aleksos.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Обновление лаунчера из GitHub Releases.
 *
 * Каждая сборка на GitHub получает номер (build-N), и этот же номер записан
 * в versionCode приложения. Если на GitHub номер больше, чем у установленной
 * версии, предлагаем скачать и установить новый APK.
 */
object Updater {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/kalibert801-ctrl/Launcher-Android/releases/latest"
    private const val PREFS = "updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED_BUILD = "skipped_build"
    private const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000 // раз в 6 часов

    private class Release(val build: Int, val apkUrl: String, val notes: String)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    /** Номер установленной сборки. */
    fun currentBuild(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Тихая проверка при открытии рабочего стола: не чаще раза в 6 часов, без сообщений, если обновлений нет. */
    fun autoCheck(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < AUTO_CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        check(activity, manual = false)
    }

    /** Проверка по кнопке: всегда сообщает результат. */
    fun manualCheck(activity: Activity) = check(activity, manual = true)

    private fun check(activity: Activity, manual: Boolean) {
        if (busy) return
        busy = true
        if (manual) toast(activity, "Проверяю обновления…")
        thread(name = "update-check") {
            val result = runCatching { fetchLatest() }
            main.post {
                busy = false
                if (activity.isFinishing || activity.isDestroyed) return@post
                val release = result.getOrElse {
                    if (manual) toast(activity, "Не удалось проверить обновления. Есть ли интернет?")
                    return@post
                }
                val current = currentBuild(activity)
                val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                when {
                    release == null || release.build <= current ->
                        if (manual) toast(activity, "У вас последняя версия (сборка $current)")
                    !manual && prefs.getInt(KEY_SKIPPED_BUILD, 0) >= release.build -> {}
                    else -> offerUpdate(activity, release, current)
                }
            }
        }
    }

    private fun fetchLatest(): Release? {
        val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AleksOS-Launcher")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    return Release(build, asset.getString("browser_download_url"), json.optString("body"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun offerUpdate(activity: Activity, release: Release, current: Long) {
        val message = buildString {
            append("Доступна новая сборка ${release.build} (у вас $current).")
            if (release.notes.isNotBlank()) append("\n\n").append(release.notes.take(800))
        }
        AlertDialog.Builder(activity)
            .setTitle("Обновление AleksOS")
            .setMessage(message)
            .setPositiveButton("Обновить") { _, _ -> startInstall(activity, release) }
            .setNegativeButton("Позже", null)
            .setNeutralButton("Пропустить") { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_SKIPPED_BUILD, release.build).apply()
            }
            .show()
    }

    private fun startInstall(activity: Activity, release: Release) {
        // Android требует разрешить лаунчеру устанавливать приложения (один раз).
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Нужно разрешение")
                .setMessage(
                    "Чтобы AleksOS мог обновлять себя, включите «Разрешить установку из этого источника» " +
                        "на следующем экране, вернитесь назад и снова нажмите «Обновить»."
                )
                .setPositiveButton("Открыть настройки") { _, _ ->
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }

        @Suppress("DEPRECATION")
        val progress = android.app.ProgressDialog(activity).apply {
            setTitle("Скачиваю сборку ${release.build}")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }
        val app = activity.applicationContext
        thread(name = "update-download") {
            val result = runCatching {
                val file = File(app.cacheDir, "update.apk")
                download(release.apkUrl, file) { percent -> main.post { progress.progress = percent } }
                install(app, file)
            }
            main.post {
                runCatching { progress.dismiss() }
                result.exceptionOrNull()?.let {
                    toast(app, "Не удалось скачать обновление: ${it.message}")
                }
            }
        }
    }

    private fun download(url: String, target: File, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AleksOS-Launcher")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Передаёт APK системному установщику. Android сам покажет окно «Обновить приложение?». */
    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun toast(context: Context, text: String) =
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

/** Получает ответ системного установщика: показывает окно подтверждения или ошибку. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "неизвестная ошибка"
                Toast.makeText(context, "Обновление не установлено: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
