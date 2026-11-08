package com.aleksos.launcher

import android.content.Context
import android.system.Os
import com.winlator.core.EnvVars
import com.winlator.core.ProcessHelper
import java.io.File
import java.io.FileWriter

/**
 * Подготовка окружения Wine перед запуском программы:
 * диски C:/D:/Z:, переменные окружения, журнал движка.
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

    /** Диски Windows: C: — системный (внутри движка), D: — папка AleksOS, Z: — весь Linux-корень. */
    fun prepareDrives(context: Context) {
        val rootfs = EngineManager.rootfsDir(context)
        val dosdevices = File(rootfs, "home/xuser/.wine/dosdevices")
        dosdevices.mkdirs()
        dosdevices.listFiles()?.forEach { if (it.name.matches(Regex("[a-z]:"))) it.delete() }
        link("../drive_c", File(dosdevices, "c:"))
        link(FileOps.aleksosDir.absolutePath, File(dosdevices, "d:"))
        link("/", File(dosdevices, "z:"))

        File(rootfs, "tmp").mkdirs()
        File(rootfs, "tmp").listFiles()?.forEach { FileOps.deleteRecursive(it) }
        File(rootfs, "home/xuser/.wine/drive_c/windows/temp").listFiles()?.forEach { FileOps.deleteRecursive(it) }

        // На случай пакета движка без готового wine (пакет 1): берём 64-битный WoW64
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

    private fun copyIfMissing(src: File, dst: File) {
        if (!dst.exists() && src.isFile) runCatching { src.copyTo(dst) }
    }

    /** Переменные окружения Wine (графика, синхронизация, отладка). */
    fun envVars(context: Context): EnvVars {
        val env = EnvVars()
        env.put("WINEPREFIX", WINEPREFIX)
        env.put("WINEDEBUG", "fixme-all")
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
        val win = if (path == aleksos || path.startsWith("$aleksos/")) {
            "D:" + path.removePrefix(aleksos).replace('/', '\\')
        } else {
            "Z:" + path.replace('/', '\\')
        }
        return win
    }

    fun escape(arg: String) = arg.replace(" ", "\\ ")
}
