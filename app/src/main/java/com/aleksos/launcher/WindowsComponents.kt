package com.aleksos.launcher

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * «Компоненты Windows»: библиотеки, которых нет в Wine (MFC42, d3dx9, DirectSound…).
 * Наборы DLL собирает GitHub (.github/workflows/components.yml, Release components-N);
 * Visual C++ 2015–2022 ставится официальным установщиком Microsoft внутри Wine.
 */
object WindowsComponents {

    const val VERSION = 1
    private const val REPO = "https://github.com/kalibert801-ctrl/Launcher-Android/releases/download"
    private val MANIFEST = "$REPO/components-$VERSION/components-$VERSION.json"

    class Component(
        val id: String, val name: String, val description: String,
        val url: String, val size: Long, val sha256: String, val dlls: List<String>,
        /** Установщик Microsoft (запускается в Wine) вместо набора DLL. */
        val installerArgs: String? = null,
        /** Расширение файла установщика: exe или msi. */
        val ext: String = "exe",
    )

    /** Visual C++ 2015–2022: официальные установщики Microsoft. */
    private val VC_REDIST = listOf(
        Component("winemono", ".NET Framework (Wine Mono)", "Нужен программам на .NET: лаунчерам игр, многим утилитам",
            "https://dl.winehq.org/wine/wine-mono/8.1.0/wine-mono-8.1.0-x86.msi", 84905984L, "", emptyList(), "", "msi"),
        Component("vcrun2022_x86", "Visual C++ 2015–2022 (32-бит)", "Нужен большинству игр и программ после 2015 года",
            "https://aka.ms/vs/17/release/vc_redist.x86.exe", 14L shl 20, "", emptyList(), "/passive /norestart"),
        Component("vcrun2022_x64", "Visual C++ 2015–2022 (64-бит)", "То же для 64-битных программ",
            "https://aka.ms/vs/17/release/vc_redist.x64.exe", 25L shl 20, "", emptyList(), "/passive /norestart"),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("windows_components", Context.MODE_PRIVATE)
    fun isInstalled(context: Context, id: String): Boolean {
        // то, что ставит установщик Microsoft/Wine, проверяем по настоящим файлам
        val windows = File(WineRuntime.driveC(context), "windows")
        return when (id) {
            "winemono" -> File(windows, "mono/mono-2.0").isDirectory
            "vcrun2022_x86" -> isNative(File(windows, "syswow64/msvcp140.dll"))
            "vcrun2022_x64" -> isNative(File(windows, "system32/msvcp140.dll"))
            else -> prefs(context).getBoolean(id, false)
        }
    }

    /** Файл есть и это настоящая библиотека, а не встроенная заглушка Wine. */
    private fun isNative(dll: File): Boolean {
        if (!dll.isFile) return false
        val head = runCatching { ByteArray(0x80).also { b -> dll.inputStream().use { it.read(b) } } }.getOrNull() ?: return false
        return !String(head, Charsets.ISO_8859_1).contains("Wine builtin DLL")
    }

    private val main = Handler(Looper.getMainLooper())

    /** Показать список компонентов (из «Пуска»). */
    fun show(activity: Activity) {
        if (!EngineManager.isInstalled(activity)) {
            WindowsEngine.offerInstall(activity, "Компоненты ставятся в Windows Kernel — сначала установите его.")
            return
        }
        val wait = AosDialog(activity).title("Компоненты Windows").message("Получаю список компонентов…").show()
        thread {
            val list = runCatching { loadList() }
            main.post {
                wait.dismiss()
                if (activity.isFinishing) return@post
                list.onSuccess { showList(activity, it + VC_REDIST) }
                    .onFailure {
                        AosDialog(activity).title("Компоненты Windows")
                            .message("Не удалось получить список: ${it.message ?: it.javaClass.simpleName}\nПроверьте интернет.")
                            .button("OK", primary = true).show()
                    }
            }
        }
    }

    private fun showList(activity: Activity, list: List<Component>) {
        AosDialog(activity)
            .title("Компоненты Windows")
            .items(list.map { c ->
                val mark = if (isInstalled(activity, c.id)) "✓ " else ""
                "$mark${c.name}  ·  ${c.description}  ·  ${FileOps.formatSize(c.size)}"
            }) { confirm(activity, list[it]) }
            .show()
    }

    private fun confirm(activity: Activity, c: Component) {
        if (c.installerArgs == null && WineActivity.runningTitle != null) {
            AosDialog(activity).title(c.name)
                .message("Сначала закройте открытые Windows-программы (кнопка ✕ в их окне), потом ставьте компонент.")
                .button("OK", primary = true).show()
            return
        }
        val again = isInstalled(activity, c.id)
        AosDialog(activity).title(c.name)
            .message("${c.description}.\n\nСкачать ${FileOps.formatSize(c.size)} и ${if (again) "установить заново" else "установить"}?")
            .button("Отмена")
            .button("Установить", primary = true) { install(activity, c) }
            .show()
    }

    private fun install(activity: Activity, c: Component) {
        val text = TextView(activity).apply {
            setTextColor(Ui.TEXT); textSize = 14f
            text = "Скачиваю…"
            setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8))
        }
        val dialog = AosDialog(activity).title(c.name).view(text).show()
        dialog.setCancelable(false)
        thread {
            val result = runCatching {
                val dir = if (c.installerArgs != null) File(FileOps.programsDir, "Компоненты").apply { mkdirs() }
                          else File(activity.cacheDir, "components").apply { mkdirs() }
                val name = if (c.installerArgs != null) "${c.id}.${c.ext}" else "${c.id}.tar.gz"
                val file = File(dir, name)
                download(c.url, file) { done -> main.post { text.text = "Скачиваю… ${FileOps.formatSize(done)}" } }
                if (c.sha256.isNotEmpty() && sha256(file) != c.sha256) { file.delete(); error("Файл повреждён при скачивании, попробуйте ещё раз") }
                if (c.installerArgs == null) {
                    main.post { text.text = "Устанавливаю…" }
                    val windows = File(WineRuntime.driveC(activity), "windows")
                    GZIPInputStream(file.inputStream().buffered()).use { TarExtractor(windows) { false }.extract(it) {} }
                    file.delete()
                    setNativeOverrides(activity, c.dlls)
                    prefs(activity).edit().putBoolean(c.id, true).apply()
                }
                file
            }
            main.post {
                dialog.dismiss()
                result.onSuccess { file ->
                    if (c.installerArgs != null) {
                        // установщик Microsoft — запускаем в Windows, дальше он сам
                        prefs(activity).edit().putBoolean(c.id, true).apply()
                        WindowsEngine.runFile(activity, c.name, file, c.installerArgs)
                    } else {
                        AosDialog(activity).title(c.name).message("Компонент установлен ✔").button("OK", primary = true).show()
                    }
                }.onFailure {
                    AosDialog(activity).title(c.name)
                        .message("Не удалось установить: ${it.message ?: it.javaClass.simpleName}")
                        .button("OK", primary = true).show()
                }
            }
        }
    }

    private fun loadList(): List<Component> {
        val json = JSONObject(httpText(MANIFEST))
        val arr = json.getJSONArray("components")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val dlls = o.optJSONArray("dlls")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            Component(o.getString("id"), o.getString("name"), o.getString("description"),
                o.getString("url"), o.getLong("size"), o.getString("sha256"), dlls)
        }
    }

    private fun open(url: String): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "AleksOS-Launcher")
            }
            if (c.responseCode in 300..399) {
                u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect()
            } else {
                if (c.responseCode != 200) error("Сервер ответил ${c.responseCode}")
                return c
            }
        }
        error("Слишком много перенаправлений")
    }

    private fun httpText(url: String) = open(url).let { c -> try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() } }

    private fun download(url: String, file: File, progress: (Long) -> Unit) {
        val c = open(url)
        try {
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 300) { last = now; progress(done) }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** В реестре Wine: эти DLL брать сначала из папки Windows (native), потом встроенные. */
    private fun setNativeOverrides(context: Context, dlls: List<String>) {
        if (dlls.isEmpty()) return
        WineRuntime.setRegistryValues(context, "Software\\\\Wine\\\\DllOverrides",
            dlls.associate { "\"$it\"" to "\"native,builtin\"" })
    }
}
