package com.aleksos.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Windows Kernel: где лежит, установлен ли, какие версии модулей.
 *
 * Ядро скачивается один раз пакетом engine-N из GitHub Releases и распаковывается
 * в личную папку приложения files/engine (запускать программы из общей памяти Android запрещает):
 *   engine/rootfs     — Linux-окружение с Wine и Box64
 *   engine/container  — заготовка «диска C» (папка .wine)
 *   engine/dxwrapper  — DXVK и VKD3D
 *   engine/engine.json — версии модулей
 */
object EngineManager {

    /** Какой пакет ядра нужен этой версии AleksOS. */
    /** Номер пакета ядра, который нужен этой версии AleksOS (engine/VERSION). */
    const val PACKAGE = 2
    private const val MANIFEST_URL =
        "https://github.com/kalibert801-ctrl/Launcher-Android/releases/download/engine-$PACKAGE/engine-$PACKAGE.json"

    fun engineDir(context: Context) = File(context.filesDir, "engine")
    fun rootfsDir(context: Context) = File(engineDir(context), "rootfs")
    private fun installedManifest(context: Context) = File(engineDir(context), "engine.json")

    fun isInstalled(context: Context): Boolean =
        installedManifest(context).isFile && File(rootfsDir(context), "opt/wine/bin").isDirectory

    /** Номер установленного пакета (0 — не установлен). */
    fun installedPackage(context: Context): Int = runCatching {
        JSONObject(installedManifest(context).readText()).optInt("package", 0)
    }.getOrDefault(0)

    /** Установлен старый пакет: нужно обновить ядро. */
    fun isOutdated(context: Context): Boolean = isInstalled(context) && installedPackage(context) < PACKAGE

    /** Версии установленных модулей (имя → версия). */
    fun installedModules(context: Context): Map<String, String> = runCatching {
        val modules = JSONObject(installedManifest(context).readText()).getJSONObject("modules")
        modules.keys().asSequence().associateWith { modules.getString(it) }
    }.getOrDefault(emptyMap())

    // ---------- Состояние установки (для окна с прогрессом) ----------

    enum class Stage { IDLE, CHECKING, DOWNLOADING, VERIFYING, EXTRACTING, DONE, FAILED }

    class State(val stage: Stage, val done: Long = 0, val total: Long = 0, val message: String = "") {
        val percent: Int get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
    }

    @Volatile var state = State(Stage.IDLE)
        private set
    private val listeners = ArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelRequested = false

    val isRunning get() = state.stage in setOf(Stage.CHECKING, Stage.DOWNLOADING, Stage.VERIFYING, Stage.EXTRACTING)

    fun addListener(l: (State) -> Unit) { synchronized(listeners) { listeners += l }; l(state) }
    fun removeListener(l: (State) -> Unit) { synchronized(listeners) { listeners -= l } }

    private fun publish(s: State) {
        state = s
        main.post { synchronized(listeners) { listeners.toList() }.forEach { it(s) } }
    }

    fun cancel() { cancelRequested = true }

    /** Начать установку (в фоновом сервисе с уведомлением). */
    fun startInstall(context: Context) {
        if (isRunning) return
        cancelRequested = false
        val intent = Intent(context, EngineInstallService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    /** Сама установка. Вызывается из сервиса в отдельном потоке. */
    internal fun runInstall(context: Context) {
        val download = File(context.cacheDir, "aleksos-engine-$PACKAGE.tar.gz")
        try {
            publish(State(Stage.CHECKING, message = "Получаю сведения о Windows Kernel…"))
            // для проверки на эмуляторе: адрес пакета можно подменить файлом (пишется только через adb)
            val override = File(context.filesDir, "engine-manifest-url.txt")
            val manifestUrl = if (override.isFile) override.readText().trim() else MANIFEST_URL
            val manifest = JSONObject(httpGet(manifestUrl))
            val url = manifest.getString("url")
            val size = manifest.getLong("size")
            val unpacked = manifest.optLong("unpacked", size * 3)
            val sha256 = manifest.optString("sha256")

            // Хватит ли места: архив + распакованный ядро + запас 300 МБ
            val free = StatFs(context.filesDir.path).availableBytes
            val need = (size - download.length().coerceAtMost(size)) + unpacked + 300_000_000L
            if (free < need) {
                error("Мало места: нужно ${FileOps.formatSize(need)}, свободно ${FileOps.formatSize(free)}")
            }

            // 1. Скачивание (с докачкой, если прошлый раз прервался)
            downloadWithResume(url, download, size)

            // 2. Проверка целостности
            if (sha256.isNotEmpty()) {
                publish(State(Stage.VERIFYING, 0, size, "Проверяю архив…"))
                val actual = sha256Of(download) { done -> publish(State(Stage.VERIFYING, done, size, "Проверяю архив…")) }
                if (!actual.equals(sha256, ignoreCase = true)) {
                    download.delete()
                    error("Архив повреждён при скачивании. Попробуйте ещё раз.")
                }
            }

            // 3. Распаковка во временную папку, потом подмена
            val target = engineDir(context)
            val staging = File(context.filesDir, "engine.new")
            FileOps.deleteRecursive(staging)
            staging.mkdirs()
            val counting = CountingInputStream(download.inputStream().buffered(1 shl 16))
            TarExtractor(staging) { cancelRequested }.extract(GZIPInputStream(counting, 1 shl 16)) {
                publish(State(Stage.EXTRACTING, counting.count, size, "Распаковываю Windows Kernel…"))
            }
            if (cancelRequested) error("Установка отменена")
            FileOps.deleteRecursive(target)
            if (!staging.renameTo(target)) error("Не удалось завершить установку")
            download.delete()
            publish(State(Stage.DONE, size, size, "Windows Kernel установлен"))
        } catch (e: Throwable) {
            FileOps.deleteRecursive(File(context.filesDir, "engine.new"))
            publish(State(Stage.FAILED, message = e.message ?: e.javaClass.simpleName))
        }
    }

    private fun downloadWithResume(url: String, file: File, total: Long) {
        if (file.length() > total) file.delete()
        var attempt = 0
        while (file.length() < total) {
            if (cancelRequested) error("Установка отменена")
            val from = file.length()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AleksOS-Launcher")
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            try {
                val code = conn.responseCode
                val append = code == 206
                if (code != 200 && code != 206) error("Сервер ответил $code")
                if (!append && from > 0) file.delete()   // сервер не умеет докачку — начинаем заново
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(file, append).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            if (cancelRequested) error("Установка отменена")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            val now = System.currentTimeMillis()
                            if (now - last > 250) {
                                last = now
                                publish(State(Stage.DOWNLOADING, file.length(), total, "Скачиваю Windows Kernel…"))
                            }
                        }
                    }
                }
                attempt = 0
            } catch (e: java.io.IOException) {
                // обрыв связи — пробуем докачать ещё несколько раз
                if (++attempt > 5) throw java.io.IOException("Связь прервалась. Нажмите «Установить» ещё раз — скачивание продолжится.")
                Thread.sleep(3000)
            } finally {
                conn.disconnect()
            }
        }
        publish(State(Stage.DOWNLOADING, total, total, "Скачано"))
    }

    private fun httpGet(url: String): String {
        if (url.startsWith("file:")) return File(url.removePrefix("file:")).readText()   // только для проверки
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AleksOS-Launcher")
        }
        try {
            if (conn.responseCode == 404) error("Пакет Windows Kernel ещё не опубликован на GitHub")
            if (conn.responseCode != 200) error("Сервер ответил ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256Of(file: File, progress: (Long) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var last = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                if (done - last > 8_000_000) { last = done; progress(done) }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        @Volatile var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}

/**
 * Распаковщик tar (форматы ustar/GNU/PAX): файлы, папки, символические и жёсткие ссылки, права доступа.
 * Свой, чтобы не тянуть сторонние библиотеки.
 */
class TarExtractor(private val dest: File, private val cancelled: () -> Boolean) {

    fun extract(input: InputStream, onProgress: () -> Unit) {
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var lastProgress = 0L
        while (true) {
            if (cancelled()) return
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break   // конец архива
            val type = header[156].toInt().toChar()
            val size = parseOctal(header, 124, 12)
            var name = longName ?: paxPath ?: (str(header, 345, 155).let { if (it.isNotEmpty()) "$it/" else "" } + str(header, 0, 100))
            val link = longLink ?: paxLink ?: str(header, 157, 100)
            val mode = parseOctal(header, 100, 8).toInt()

            when (type) {
                'L' -> { longName = readString(input, size); continue }
                'K' -> { longLink = readString(input, size); continue }
                'x' -> {
                    val pax = readString(input, size)
                    paxPath = paxValue(pax, "path"); paxLink = paxValue(pax, "linkpath")
                    continue
                }
                'g' -> { skip(input, padded(size)); continue }
            }
            longName = null; longLink = null; paxPath = null; paxLink = null

            name = name.removePrefix("./").trimEnd('/')
            if (name.isEmpty() || name == ".") { skip(input, padded(size)); continue }
            // защита: ничего не писать за пределы папки ядра
            if (name.startsWith("/") || name.split('/').any { it == ".." }) { skip(input, padded(size)); continue }
            val out = File(dest, name)

            when (type) {
                '5' -> { out.mkdirs(); chmod(out, mode or 0b111_000_000) }
                '2' -> {
                    out.parentFile?.mkdirs()
                    out.delete()
                    Os.symlink(link, out.path)
                }
                '1' -> {
                    out.parentFile?.mkdirs()
                    out.delete()
                    val src = File(dest, link.removePrefix("./"))
                    runCatching { Os.link(src.path, out.path) }.onFailure { if (src.isFile) src.copyTo(out, true) }
                }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    if (out.exists() || isSymlink(out)) out.delete()
                    out.outputStream().buffered(1 shl 16).use { copy(input, it, size) }
                    skip(input, padded(size) - size)
                    chmod(out, mode)
                }
                else -> skip(input, padded(size))   // устройства и прочее не нужны
            }
            val now = System.currentTimeMillis()
            if (now - lastProgress > 250) { lastProgress = now; onProgress() }
        }
        onProgress()
    }

    private fun chmod(f: File, mode: Int) {
        runCatching { Os.chmod(f.path, (mode and 0xFFF).let { if (it == 0) 0x1A4 else it }) }
    }

    private fun isSymlink(f: File) = runCatching { android.system.Os.lstat(f.path) }.isSuccess && !f.exists()

    private fun padded(size: Long) = (size + 511) / 512 * 512

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw java.io.EOFException("Архив обрезан")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw java.io.EOFException("Архив обрезан")
            left -= n
        }
    }

    private fun readString(input: InputStream, size: Long): String {
        val data = ByteArray(size.toInt())
        readFully(input, data)
        skip(input, padded(size) - size)
        return String(data, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun paxValue(pax: String, key: String): String? =
        pax.lineSequence().mapNotNull { line ->
            val rec = line.substringAfter(' ', "")
            if (rec.startsWith("$key=")) rec.removePrefix("$key=") else null
        }.firstOrNull()

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        // большие размеры в GNU tar кодируются двоичным числом (старший бит = 1)
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        val s = str(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }
}

/** Фоновый сервис установки: держит процесс живым, пока качается и распаковывается ядро. */
class EngineInstallService : Service() {

    private val channelId = "engine_install"
    @Volatile private var finished = false
    private val listener: (EngineManager.State) -> Unit = { if (!finished) updateNotification(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Установка Windows Kernel", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startForeground(1, build("Подготовка…", 0, true))
        EngineManager.addListener(listener)
        thread(name = "engine-install") {
            EngineManager.runInstall(applicationContext)
            finished = true
            EngineManager.removeListener(listener)
            stopForeground(true)
            // убрать полоску установки: последнее обновление могло прийти уже после stopForeground
            getSystemService(NotificationManager::class.java).cancel(1)
            val s = EngineManager.state
            getSystemService(NotificationManager::class.java).notify(2, build(
                if (s.stage == EngineManager.Stage.DONE) "Windows Kernel установлен" else "Установка не удалась: ${s.message}",
                -1, false
            ))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(s: EngineManager.State) {
        val text = when (s.stage) {
            EngineManager.Stage.DOWNLOADING -> "${s.message} ${FileOps.formatSize(s.done)} из ${FileOps.formatSize(s.total)}"
            else -> s.message
        }
        getSystemService(NotificationManager::class.java).notify(1, build(text, s.percent, s.total <= 0))
    }

    private fun build(text: String, percent: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channelId)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Windows Kernel")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (percent >= 0) b.setProgress(100, percent, indeterminate).setOngoing(true)
        else b.setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true)
        return b.build()
    }
}
