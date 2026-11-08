package com.aleksos.launcher

import android.content.Context
import android.system.Os
import com.winlator.core.EnvVars
import com.winlator.core.ProcessHelper
import java.io.File
import java.io.FileWriter

/**
 * Подготовка окружения Wine перед запуском программы:
 * диски C:/D:/Z:, переменные окружения, журнал ядра.
 */
object WineRuntime {

    const val WINEPREFIX = "/home/xuser/.wine"

    /** Журнал последнего запуска (вывод Wine/Box64/proot). */
    fun logFile(context: Context) = File(context.filesDir, "engine-log.txt")

    @Volatile private var logWriter: FileWriter? = null
    @Volatile private var logSize = 0L
    private const val LOG_LIMIT = 2L * 1024 * 1024

    init {
        // Вывод программ всегда читается (иначе Wine может «застрять» на полном буфере вывода)
        ProcessHelper.addDebugCallback { line -> writeLog(line) }
    }

    fun startLog(context: Context, header: String) {
        synchronized(this) {
            runCatching { logWriter?.close() }
            logSize = 0
            logWriter = runCatching { FileWriter(logFile(context), false) }.getOrNull()
        }
        writeLog("=== AleksOS: $header ===")
    }

    fun writeLog(line: String) {
        synchronized(this) {
            val w = logWriter ?: return
            if (logSize > LOG_LIMIT) return
            runCatching {
                w.write(line); w.write("\n"); w.flush()
                logSize += line.length + 1
            }
        }
    }

    fun readLogTail(context: Context, lines: Int = 80): String = runCatching {
        val all = logFile(context).readLines()
        all.takeLast(lines).joinToString("\n")
    }.getOrDefault("Журнал пуст")

    /**
     * Диски Windows: C: — системный (внутри ядра), D: — папка AleksOS, E: — вся память телефона,
     * Z: — Linux-корень ядра (нужен самому Wine). «Документы» и «Рабочий стол» Windows —
     * это папки AleksOS, чтобы файлы из Windows-программ сразу были видны на телефоне.
     */
    fun prepareDrives(context: Context) {
        val rootfs = EngineManager.rootfsDir(context)
        val dosdevices = File(rootfs, "home/xuser/.wine/dosdevices")
        dosdevices.mkdirs()
        dosdevices.listFiles()?.forEach { if (it.name.matches(Regex("[cdez]:"))) it.delete() }
        link("../drive_c", File(dosdevices, "c:"))
        link(FileOps.aleksosDir.absolutePath, File(dosdevices, "d:"))
        link(FileOps.storageRoot.absolutePath, File(dosdevices, "e:"))
        link("/", File(dosdevices, "z:"))

        if (FileOps.aleksosDir.isDirectory) {
            val profile = File(rootfs, "home/xuser/.wine/drive_c/users/xuser")
            linkFolder(File(profile, "Documents"), FileOps.documentsDir)
            linkFolder(File(profile, "Desktop"), FileOps.desktopDir)
        }

        installRussianLocale(context, rootfs)

        File(rootfs, "tmp").mkdirs()
        File(rootfs, "tmp").listFiles()?.forEach { FileOps.deleteRecursive(it) }
        File(rootfs, "home/xuser/.wine/drive_c/windows/temp").listFiles()?.forEach { FileOps.deleteRecursive(it) }

        // На случай пакета ядра без готового wine (пакет 1): берём 64-битный WoW64
        val bin = File(rootfs, "opt/wine/bin")
        copyIfMissing(File(bin, "wine-wow64"), File(bin, "wine"))
        copyIfMissing(File(bin, "wine-preloader-wow64"), File(bin, "wine-preloader"))
        for (f in listOf(File(bin, "wine"), File(bin, "wine-preloader"), File(bin, "wineserver"),
            File(rootfs, "usr/local/bin/box64"))) {
            if (f.isFile) runCatching { Os.chmod(f.path, 493) }   // 0755
        }
    }

    private fun link(target: String, linkFile: File) {
        runCatching { linkFile.delete(); Os.symlink(target, linkFile.path) }
    }

    private fun isLink(f: File) = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    /** Папку профиля Windows заменить ссылкой на папку AleksOS (только если она пустая). */
    private fun linkFolder(winDir: File, target: File) {
        target.mkdirs()
        if (isLink(winDir)) {
            if (runCatching { Os.readlink(winDir.path) }.getOrNull() == target.absolutePath) return
            winDir.delete()
        } else if (winDir.isDirectory) {
            if (!winDir.list().isNullOrEmpty()) return   // там уже что-то есть — не трогаем
            winDir.delete()
        }
        winDir.parentFile?.mkdirs()
        link(target.absolutePath, winDir)
    }

    /** Русская локаль Linux (собирается на GitHub и лежит в APK) — для русского языка Wine. */
    private const val RU_LOCALE = "ru_RU.utf8"

    private fun installRussianLocale(context: Context, rootfs: File) {
        val dst = File(rootfs, "usr/lib/locale/$RU_LOCALE")
        if (File(dst, "LC_CTYPE").isFile) return
        val assets = context.assets
        val files = runCatching { assets.list("locale/$RU_LOCALE") }.getOrNull() ?: return
        if (files.isEmpty()) return
        fun copy(assetPath: String, out: File) {
            val children = assets.list(assetPath).orEmpty()
            if (children.isNotEmpty()) {
                out.mkdirs()
                children.forEach { copy("$assetPath/$it", File(out, it)) }
            } else {
                out.parentFile?.mkdirs()
                assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        runCatching { copy("locale/$RU_LOCALE", dst) }
            .onFailure { writeLog("Не удалось установить русскую локаль: $it") }
    }

    fun hasRussianLocale(context: Context) =
        File(EngineManager.rootfsDir(context), "usr/lib/locale/$RU_LOCALE/LC_CTYPE").isFile

    private fun copyIfMissing(src: File, dst: File) {
        if (!dst.exists() && src.isFile) runCatching { src.copyTo(dst) }
    }

    /** Переменные окружения Wine (графика, синхронизация, отладка). */
    fun envVars(context: Context): EnvVars {
        val env = EnvVars()
        env.put("WINEPREFIX", WINEPREFIX)
        env.put("WINEDEBUG", "fixme-all")
        // Русский язык и кодировка Windows-1251 (если русская локаль установлена)
        val locale = if (hasRussianLocale(context)) RU_LOCALE else "en_US.utf8"
        env.put("LC_ALL", locale)
        env.put("LANG", locale)
        env.put("WINEESYNC", "1")
        env.put("MESA_DEBUG", "silent")
        env.put("MESA_NO_ERROR", "1")
        env.put("MESA_SHADER_CACHE_DISABLE", "false")
        env.put("MESA_SHADER_CACHE_MAX_SIZE", "512MB")
        env.put("mesa_glthread", "true")
        env.put("GALLIUM_DRIVER", "zink")
        env.put("ZINK_DESCRIPTORS", "lazy")
        env.put("ZINK_DEBUG", "compact")
        env.put("TU_DEBUG", "noconform")
        env.put("TU_OVERRIDE_HEAP_SIZE", "4096")
        env.put("MESA_VK_WSI_PRESENT_MODE", "mailbox")
        env.put("vblank_mode", "0")
        return env
    }

    /** Настройки Box64 из BIOS («Режим Box64»), значения — как пресеты Winlator. */
    fun box64EnvVars(context: Context): EnvVars {
        val env = EnvVars()
        when (SystemSettings(context).get(SystemSettings.BOX64)) {
            0 -> { // Быстрее
                env.put("BOX64_DYNAREC_SAFEFLAGS", "1"); env.put("BOX64_DYNAREC_FASTNAN", "1")
                env.put("BOX64_DYNAREC_FASTROUND", "1"); env.put("BOX64_DYNAREC_X87DOUBLE", "0")
                env.put("BOX64_DYNAREC_BIGBLOCK", "3"); env.put("BOX64_DYNAREC_STRONGMEM", "0")
                env.put("BOX64_DYNAREC_FORWARD", "512"); env.put("BOX64_DYNAREC_CALLRET", "1")
                env.put("BOX64_DYNAREC_WAIT", "1")
            }
            2 -> { // Стабильнее
                env.put("BOX64_DYNAREC_SAFEFLAGS", "2"); env.put("BOX64_DYNAREC_FASTNAN", "0")
                env.put("BOX64_DYNAREC_FASTROUND", "0"); env.put("BOX64_DYNAREC_X87DOUBLE", "1")
                env.put("BOX64_DYNAREC_BIGBLOCK", "0"); env.put("BOX64_DYNAREC_STRONGMEM", "2")
                env.put("BOX64_DYNAREC_FORWARD", "128"); env.put("BOX64_DYNAREC_CALLRET", "0")
                env.put("BOX64_DYNAREC_WAIT", "0")
            }
            else -> { // Сбалансированно (как «Совместимость» в Winlator)
                env.put("BOX64_DYNAREC_SAFEFLAGS", "2"); env.put("BOX64_DYNAREC_FASTNAN", "0")
                env.put("BOX64_DYNAREC_FASTROUND", "0"); env.put("BOX64_DYNAREC_X87DOUBLE", "1")
                env.put("BOX64_DYNAREC_BIGBLOCK", "0"); env.put("BOX64_DYNAREC_STRONGMEM", "1")
                env.put("BOX64_DYNAREC_FORWARD", "128"); env.put("BOX64_DYNAREC_CALLRET", "0")
                env.put("BOX64_DYNAREC_WAIT", "1")
            }
        }
        return env
    }

    /**
     * Путь Android → путь Windows. Файлы из папки AleksOS — на диске D:, остальное — через Z:.
     * Пробелы экранируются «\ », так команду понимает запуск через proot.
     */
    fun toWindowsPath(file: File): String {
        val path = file.absolutePath
        val aleksos = FileOps.aleksosDir.absolutePath
        val storage = FileOps.storageRoot.absolutePath
        fun under(root: String) = path == root || path.startsWith("$root/")
        val (drive, rest) = when {
            under(aleksos) -> "D:" to path.removePrefix(aleksos)
            under(storage) -> "E:" to path.removePrefix(storage)
            else -> "Z:" to path
        }
        return drive + rest.replace('/', '\\').ifEmpty { "\\" }
    }

    fun escape(arg: String) = arg.replace(" ", "\\ ")

    /**
     * Команда в форме для proot («start /max /d D:\Мои\ игры game.exe») → что запускать и аргументы
     * для ShellExecute внутри Wine: экранирование «\ » снимается, слова с пробелами берутся в кавычки.
     */
    fun toExec(command: String): Pair<String, String> {
        val tokens = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (c == '\\' && i + 1 < command.length && command[i + 1] == ' ') { cur.append(' '); i += 2; continue }
            if (c == ' ') { if (cur.isNotEmpty()) tokens += cur.toString(); cur.clear() } else cur.append(c)
            i++
        }
        if (cur.isNotEmpty()) tokens += cur.toString()
        fun quote(t: String) = if (' ' in t && !t.startsWith("\"")) "\"$t\"" else t
        val file = tokens.firstOrNull() ?: return "" to ""
        return file to tokens.drop(1).joinToString(" ") { quote(it) }
    }

    private var nextShortDrive = 0
    private const val SHORT_DRIVES = "mnopqrstuvwxy"

    /**
     * Длинный путь к программе не помещается в команду для Wine — даём её папке
     * свою букву диска (M:…Y:, по кругу) и запускаем оттуда.
     */
    fun mapShortDrive(context: Context, dir: File): String {
        val letter = synchronized(this) { SHORT_DRIVES[nextShortDrive++ % SHORT_DRIVES.length] }
        val dosdevices = File(EngineManager.rootfsDir(context), "home/xuser/.wine/dosdevices")
        link(dir.absolutePath, File(dosdevices, "$letter:"))
        return "${letter.uppercaseChar()}:"
    }
}
