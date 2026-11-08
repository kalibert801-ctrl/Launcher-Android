package com.aleksos.launcher

import com.winlator.winhandler.WinHandler
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Точка входа в ядро запуска Windows-программ (Wine + Box64 + DXVK/Turnip).
 *
 * Этап 2, шаг 1: установка ядра (скачивание и распаковка — EngineManager).
 * Шаг 2 (следующий): запуск Wine через proot + Box64 и X-сервер для окон программ.
 */
object WindowsEngine {

    /** Установлен ли ядро. */
    fun isAvailable(context: Context): Boolean = EngineManager.isInstalled(context)

    /** Модуль ядра: название, зачем нужен и установленная версия (null — не установлен). */
    class Module(val name: String, val purpose: String, val version: String?)

    /** Список модулей для экрана загрузки и BIOS. */
    fun modules(context: Context): List<Module> {
        val v = EngineManager.installedModules(context)
        return listOf(
            Module("Wine", "Windows API", v["Wine"]),
            Module("Box64", "x86-64 → ARM64", v["Box64"]),
            Module("DXVK", "DirectX 9–11 → Vulkan", v["DXVK"]),
            Module("VKD3D", "DirectX 12 → Vulkan", v["VKD3D"]),
            Module("Turnip", "Vulkan для Adreno", v["Turnip"]),
            Module("X-сервер", "окна программ", if (v.isNotEmpty()) "AleksOS" else null),
            Module("PulseAudio", "звук", null),
        )
    }

    /** Стандартная программа Wine: название и команда запуска. */
    class Standard(val name: String, val command: String, val hint: String)

    val STANDARD = listOf(
        // «start /max» — программа открывается развёрнутой на весь экран
        Standard("Блокнот", "start /max notepad", "Простой текстовый редактор"),
        Standard("WordPad", "start /max wordpad", "Редактор документов"),
        Standard("Командная строка", "start /max wineconsole cmd", "cmd.exe"),
        Standard("Проводник Windows", "start /max winefile", "Диски C:, D: (AleksOS), E: (телефон)"),
        Standard("Диспетчер задач", "taskmgr", "Запущенные Windows-программы"),
        Standard("Редактор реестра", "start /max regedit", "regedit.exe"),
        Standard("Сапёр", "winemine", "Игра"),
        Standard("Панель управления", "start /max control", "Настройки Windows"),
        Standard("Настройка Wine", "winecfg", "Версия Windows, диски, графика"),
    )

    /** Запустить Windows-программу с рабочего стола. */
    fun launch(activity: Activity, app: WindowsApp) {
        val file = resolveFile(activity, app.uri)
        if (file == null) {
            AosDialog(activity).title(app.name)
                .message("Не удалось найти файл программы. Добавьте её заново через «Этот телефон» " +
                    "(файл лучше положить в папку AleksOS → Программы).")
                .button("OK", primary = true).show()
            return
        }
        launchFile(activity, app.name, file)
    }

    /** Запустить .exe/.msi/.bat/.lnk (ярлык — по тому, куда он ведёт). */
    fun launchFile(activity: Activity, title: String, source: File) {
        var file = source
        var args = ""
        if (file.extension.equals("lnk", true)) {
            val lnk = LnkFile.read(file)
            args = lnk?.arguments.orEmpty().trim()
            val target = lnk?.targetFile(activity)
            if (target == null) {
                AosDialog(activity).title(title)
                    .message("Ярлык ведёт к программе, которой нет на телефоне. Возможно, она удалена.")
                    .button("OK", primary = true).show()
                return
            }
            file = target
        }
        val parent = file.parentFile ?: file
        val name = WineRuntime.escape(file.name)
        // start /d — запуск из папки программы (так многие игры находят свои файлы), /max — на весь экран
        var command = "start /max /d ${WineRuntime.escape(WineRuntime.toWindowsPath(parent, activity))} $name"
        val (exe, params) = WineRuntime.toExec(command)
        if (!WinHandler.fits(exe, params) && EngineManager.isInstalled(activity)) {
            // длинный путь: папке программы — короткая буква диска
            command = "start /max /d ${WineRuntime.mapShortDrive(activity, parent)}\\. $name"
        }
        if (args.isNotEmpty()) command += " " + args
        run(activity, title, command)
    }

    /** Запустить стандартную программу Wine. */
    fun launchStandard(activity: Activity, program: Standard) = run(activity, program.name, program.command)

    private fun run(activity: Activity, title: String, command: String) {
        if (!isAvailable(activity)) {
            offerInstall(activity, "Чтобы запускать «$title» и другие Windows-программы, нужен Windows Kernel.")
            return
        }
        if (EngineManager.isOutdated(activity)) {
            offerInstall(activity, "Для запуска программ нужна новая версия Windows Kernel (пакет ${EngineManager.PACKAGE}).", update = true)
            return
        }
        // Окно Windows уже открыто — запускаем программу в нём (несколько программ одновременно)
        if (WineActivity.launchInRunning(activity, title, command)) return
        WineActivity.start(activity, title, command)
    }

    /** content:// или file:// ссылка → файл на телефоне (Wine видит только настоящие пути). */
    fun resolveFile(context: Context, uriString: String): File? {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") return uri.path?.let { File(it) }?.takeIf { it.isFile }
        if (uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents") {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            val volume = docId.substringBefore(':')
            val rel = docId.substringAfter(':', "")
            val base = if (volume.equals("primary", true)) FileOps.storageRoot.path else "/storage/$volume"
            return File(base, rel).takeIf { it.isFile }
        }
        if (uri.scheme == "content" && uri.authority == "com.aleksos.launcher.files") {
            return uri.path?.removePrefix("/f")?.let { File(it) }?.takeIf { it.isFile }
        }
        return null
    }

    /** Предложить установить ядро (или показать ход установки, если она уже идёт). */
    fun offerInstall(activity: Activity, reason: String, update: Boolean = false) {
        if (EngineManager.isRunning) {
            showProgress(activity)
            return
        }
        AosDialog(activity)
            .title("Windows Kernel")
            .message(
                "$reason\n\n" +
                    "• Скачать: около 430 МБ (лучше по Wi-Fi)\n" +
                    "• Займёт на телефоне: около 1,3 ГБ\n" +
                    "• Состав: Wine 9.2, Box64, Turnip, DXVK, VKD3D\n\n" +
                    "Скачивание идёт в фоне, можно пользоваться телефоном. Если связь прервётся — продолжится с того же места."
            )
            .button("Позже")
            .button(if (update) "Обновить" else "Установить", primary = true) {
                EngineManager.startInstall(activity)
                showProgress(activity)
            }
            .show()
    }

    /** Окно с полоской установки. «Скрыть» — установка продолжится в фоне (видно в уведомлении). */
    fun showProgress(activity: Activity) {
        val bar = AosDialog.progressBar(activity)
        val text = TextView(activity).apply {
            setTextColor(Ui.TEXT)
            textSize = 13f
            setPadding(0, Ui.dp(activity, 10), 0, 0)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(text)
        }
        var dialog: Dialog? = null
        lateinit var listener: (EngineManager.State) -> Unit
        listener = { s ->
            bar.isIndeterminate = s.total <= 0
            bar.progress = s.percent
            text.text = when (s.stage) {
                EngineManager.Stage.DOWNLOADING ->
                    "${s.message}\n${FileOps.formatSize(s.done)} из ${FileOps.formatSize(s.total)} (${s.percent}%)"
                EngineManager.Stage.VERIFYING, EngineManager.Stage.EXTRACTING -> "${s.message} ${s.percent}%"
                else -> s.message
            }
            if (s.stage == EngineManager.Stage.DONE || s.stage == EngineManager.Stage.FAILED) {
                EngineManager.removeListener(listener)
                dialog?.dismiss()
                if (!activity.isFinishing) {
                    if (s.stage == EngineManager.Stage.DONE) {
                        AosDialog(activity).title("Windows Kernel")
                            .message("Windows Kernel установлен ✔\n\nВерсии модулей видны в BIOS и при загрузке AleksOS.")
                            .button("OK", primary = true).show()
                    } else {
                        AosDialog(activity).title("Установка не удалась")
                            .message(s.message)
                            .button("Закрыть")
                            .button("Повторить", primary = true) { EngineManager.startInstall(activity); showProgress(activity) }
                            .show()
                    }
                }
            }
        }
        dialog = AosDialog(activity)
            .title("Установка Windows Kernel")
            .view(box)
            .cancelable(false)
            .button("Отменить") { EngineManager.cancel(); EngineManager.removeListener(listener) }
            .button("Скрыть", primary = true) { EngineManager.removeListener(listener) }
            .show()
        EngineManager.addListener(listener)
    }
}
