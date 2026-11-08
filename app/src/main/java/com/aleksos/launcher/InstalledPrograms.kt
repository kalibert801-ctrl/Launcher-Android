package com.aleksos.launcher

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Программы, установленные через setup.exe: установщик кладёт ярлыки (.lnk) в меню «Пуск» Windows
 * и на общий рабочий стол. Находим новые ярлыки и добавляем программы на рабочий стол AleksOS.
 */
object InstalledPrograms {

    private const val PREFS = "installed_programs"
    private const val KEY_SEEN = "seen"
    private val SKIP = Regex("(?i)uninst|удал|readme|help|справк|manual|руководств|website|web site|сайт|update|обновл|register|регистр")

    /** Папки, куда установщики кладут ярлыки. */
    private fun shortcutDirs(context: Context): List<File> {
        val c = WineRuntime.driveC(context)
        return listOf(
            File(c, "ProgramData/Microsoft/Windows/Start Menu/Programs"),
            File(c, "users/xuser/AppData/Roaming/Microsoft/Windows/Start Menu/Programs"),
            File(c, "users/Public/Desktop"),
        )
    }

    /**
     * Найти новые программы и добавить их на рабочий стол. Возвращает названия добавленных.
     * Вызывать не в главном потоке.
     */
    fun scan(context: Context, store: WindowsAppStore): List<String> {
        if (!EngineManager.isInstalled(context)) return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_SEEN, emptySet())!!.toMutableSet()
        val firstScan = !prefs.contains(KEY_SEEN)

        val links = shortcutDirs(context).flatMap { dir ->
            dir.walkTopDown().maxDepth(4)
                .filter { it.isFile && it.extension.equals("lnk", true) }
                .toList()
        }
        // программы, которые уже есть на рабочем столе (добавлены вручную или ярлыком в папке стола)
        val known = HashSet<String>()
        store.load().forEach { app ->
            WindowsEngine.resolveFile(context, app.uri)?.let { known += it.canonicalPath.lowercase() }
        }
        FileOps.desktopDir.listFiles()?.filter { it.extension.equals("lnk", true) }?.forEach { lnk ->
            LnkFile.read(lnk)?.targetFile(context)?.let { known += it.canonicalPath.lowercase() }
        }

        val added = ArrayList<String>()
        for (link in links) {
            val key = link.absolutePath
            if (key in seen) continue
            seen += key
            if (firstScan) continue                        // ярлыки, которые были в Wine изначально
            val name = link.nameWithoutExtension
            if (SKIP.containsMatchIn(name)) continue
            val target = LnkFile.read(link)?.targetFile(context) ?: continue
            if (!target.isFile || !target.extension.equals("exe", true)) continue
            val path = target.canonicalPath.lowercase()
            if (path in known) continue
            // системные программы Wine (Блокнот и т. п.) уже есть в «Пуск → Стандартные Windows»
            if (path.contains("/drive_c/windows/")) continue
            known += path
            store.add(name, Uri.fromFile(target).toString())
            added += name
        }
        prefs.edit().putStringSet(KEY_SEEN, seen).apply()
        return added
    }
}
