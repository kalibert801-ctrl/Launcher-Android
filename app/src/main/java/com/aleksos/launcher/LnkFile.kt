package com.aleksos.launcher

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Ярлык Windows (.lnk): куда он ведёт. Формат «Shell Link» от Microsoft (открытая спецификация MS-SHLLINK):
 * берём путь из LinkInfo (LocalBasePath + CommonPathSuffix), а если его нет — относительный путь.
 */
class LnkFile private constructor(
    /** Путь Windows к программе, например C:\Program Files\Game\game.exe */
    val target: String,
    /** Рабочая папка (может быть пустой). */
    val workingDir: String,
    val arguments: String,
) {
    companion object {
        private val ANSI: Charset = runCatching { Charset.forName("windows-1251") }.getOrDefault(Charsets.ISO_8859_1)

        fun read(file: File): LnkFile? = runCatching {
            if (file.length() > 1024 * 1024) return null
            parse(file.readBytes(), file)
        }.getOrNull()

        private fun parse(d: ByteArray, file: File): LnkFile? {
            val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            if (d.size < 0x4C || b.getInt(0) != 0x4C) return null
            val flags = b.getInt(0x14)
            val unicode = flags and 0x80 != 0
            var pos = 0x4C
            var idPath = ""
            if (flags and 0x1 != 0) {                                                  // IDList
                val size = b.getShort(pos).toInt() and 0xFFFF
                idPath = runCatching { pathFromIdList(d, pos + 2, minOf(d.size, pos + 2 + size)) }.getOrDefault("")
                pos += 2 + size
            }

            fun cstr(at: Int, wide: Boolean): String {
                if (at <= 0 || at >= d.size) return ""
                if (!wide) {
                    var e = at
                    while (e < d.size && d[e] != 0.toByte()) e++
                    return String(d, at, e - at, ANSI)
                }
                var e = at
                while (e + 1 < d.size && (d[e] != 0.toByte() || d[e + 1] != 0.toByte())) e += 2
                return String(d, at, e - at, Charsets.UTF_16LE)
            }

            var base = ""
            if (flags and 0x2 != 0 && pos + 0x1C <= d.size) {                          // LinkInfo
                val size = b.getInt(pos)
                val headerSize = b.getInt(pos + 4)
                val infoFlags = b.getInt(pos + 8)
                if (infoFlags and 0x1 != 0) {
                    val local = if (headerSize >= 0x24) b.getInt(pos + 0x1C) else 0
                    val suffixW = if (headerSize >= 0x24) b.getInt(pos + 0x20) else 0
                    base = if (local > 0) cstr(pos + local, true) else cstr(pos + b.getInt(pos + 0x10), false)
                    val suffix = if (suffixW > 0) cstr(pos + suffixW, true) else cstr(pos + b.getInt(pos + 0x18), false)
                    if (suffix.isNotEmpty()) base = base.trimEnd('\\') + "\\" + suffix
                }
                pos += size
            }

            // StringData: имя, относительный путь, рабочая папка, аргументы, иконка — по флагам
            fun next(present: Boolean): String {
                if (!present || pos + 2 > d.size) return ""
                val count = b.getShort(pos).toInt() and 0xFFFF
                val bytes = if (unicode) count * 2 else count
                pos += 2
                if (pos + bytes > d.size) return ""
                val s = String(d, pos, bytes, if (unicode) Charsets.UTF_16LE else ANSI)
                pos += bytes
                return s
            }
            next(flags and 0x4 != 0)                       // описание
            val relative = next(flags and 0x8 != 0)
            val workDir = next(flags and 0x10 != 0)
            val args = next(flags and 0x20 != 0)

            val target = base.ifEmpty { idPath }.ifEmpty {
                if (relative.isEmpty()) return null
                // относительный путь считается от папки ярлыка; переводим её в путь Windows позже
                "\u0000" + relative
            }
            return LnkFile(target, workDir, args).also { it.relativeFrom = file.parentFile }
        }

        /**
         * Путь из списка «элементов оболочки» (так хранит путь Windows, если ярлык создан в Windows):
         * диск («D:\»), затем папки и файл. Длинное имя берём из блока 0xBEEF0004, иначе короткое 8.3.
         */
        private fun pathFromIdList(d: ByteArray, start: Int, end: Int): String {
            val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            var p = start
            var drive = ""
            val parts = ArrayList<String>()
            while (p + 2 <= end) {
                val size = b.getShort(p).toInt() and 0xFFFF
                if (size == 0 || p + size > end) break
                val type = d[p + 2].toInt() and 0xFF
                when (type and 0x70) {
                    0x20 -> {                                   // диск
                        var e = p + 3
                        while (e < p + size && d[e] != 0.toByte()) e++
                        drive = String(d, p + 3, e - p - 3, Charsets.US_ASCII).trimEnd('\\')
                    }
                    0x30 -> {                                   // папка или файл
                        var e = p + 14
                        while (e < p + size && d[e] != 0.toByte()) e++
                        val short = String(d, p + 14, maxOf(0, e - p - 14), ANSI)
                        parts += longName(d, p, size) ?: short
                    }
                }
                p += size
            }
            if (drive.length != 2 || drive[1] != ':') return ""
            return drive + "\\" + parts.joinToString("\\")
        }

        /** Длинное имя из блока расширения 0xBEEF0004 внутри элемента. */
        private fun longName(d: ByteArray, item: Int, size: Int): String? {
            val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            var i = item + 4
            while (i + 8 <= item + size) {
                if (b.getInt(i) == 0xBEEF0004.toInt()) {
                    val ext = i - 4                              // начало блока: размер (2), версия (2), подпись (4)
                    val extSize = b.getShort(ext).toInt() and 0xFFFF
                    val version = b.getShort(ext + 2).toInt() and 0xFFFF
                    val nameAt = ext + when {
                        version >= 8 -> 0x2E
                        version >= 7 -> 0x2A
                        version >= 3 -> 0x14
                        else -> return null
                    }
                    val limit = minOf(ext + extSize, item + size)
                    var e = nameAt
                    while (e + 1 < limit && (d[e] != 0.toByte() || d[e + 1] != 0.toByte())) e += 2
                    if (e <= nameAt || e + 1 >= limit) return null
                    return String(d, nameAt, e - nameAt, Charsets.UTF_16LE).takeIf { s -> s.all { it >= ' ' } }
                }
                i += 2
            }
            return null
        }
    }

    internal var relativeFrom: File? = null

    /** Файл на телефоне, куда ведёт ярлык (null — не нашёлся). */
    fun targetFile(context: android.content.Context): File? {
        if (target.startsWith("\u0000")) {
            val rel = target.substring(1).replace('\\', '/')
            return File(relativeFrom ?: return null, rel).canonicalFile.takeIf { it.exists() }
        }
        return WineRuntime.fromWindowsPath(context, target)?.takeIf { it.exists() }
    }
}
