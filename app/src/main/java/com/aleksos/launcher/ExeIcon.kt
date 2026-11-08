package com.aleksos.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Иконка Windows-программы из её .exe (как в Проводнике Windows): читаем ресурсы файла
 * (RT_GROUP_ICON + RT_ICON), берём самую крупную картинку и сохраняем её в PNG-кэш.
 */
object ExeIcon {

    private const val RT_ICON = 3
    private const val RT_GROUP_ICON = 14
    private const val MAX_FILE = 512L * 1024 * 1024

    private val memory = HashMap<String, Bitmap?>()

    /** Иконка программы (null — в файле нет иконки). Читает файл — вызывать не в главном потоке. */
    fun load(context: Context, file: File): Bitmap? {
        if (!file.isFile) return null
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(memory) { if (memory.containsKey(key)) return memory[key] }
        val cacheDir = File(context.cacheDir, "exe-icons").apply { mkdirs() }
        val cached = File(cacheDir, Integer.toHexString(key.hashCode()) + ".png")
        val noIcon = File(cacheDir, Integer.toHexString(key.hashCode()) + ".none")
        val bitmap: Bitmap? = when {
            cached.isFile -> BitmapFactory.decodeFile(cached.path)
            noIcon.isFile -> null
            else -> runCatching { extractIco(file) }.getOrNull()
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                .also { bmp ->
                    runCatching {
                        if (bmp != null) cached.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        else noIcon.createNewFile()
                    }
                }
        }
        synchronized(memory) { memory[key] = bitmap }
        return bitmap
    }

    fun drawable(context: Context, file: File?): Drawable? {
        file ?: return null
        val bmp = load(context, file) ?: return null
        return BitmapDrawable(context.resources, bmp)
    }

    fun isExe(file: File) = file.extension.lowercase() in setOf("exe", "dll", "scr", "cpl")

    /**
     * Самая крупная иконка из .exe в виде файла .ico (одна картинка) — его умеет открывать Android.
     * null — в файле нет ресурса иконки или это не программа Windows.
     */
    fun extractIco(file: File): ByteArray? {
        if (file.length() > MAX_FILE || file.length() < 64) return null
        RandomAccessFile(file, "r").use { raf ->
            fun read(pos: Long, size: Int): ByteBuffer {
                val b = ByteArray(size)
                raf.seek(pos); raf.readFully(b)
                return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            }
            if (read(0, 2).short.toInt() != 0x5A4D) return null           // «MZ»
            val pe = read(0x3C, 4).int.toLong()
            if (pe <= 0 || pe > file.length() - 256) return null
            if (read(pe, 4).int != 0x00004550) return null               // «PE\0\0»
            val coff = read(pe + 4, 20)
            val sectionCount = coff.getShort(2).toInt() and 0xFFFF
            val optSize = coff.getShort(16).toInt() and 0xFFFF
            val opt = pe + 24
            val magic = read(opt, 2).short.toInt() and 0xFFFF
            val dirs = opt + if (magic == 0x20B) 112 else 96                // PE32+ / PE32
            val resRva = read(dirs + 2 * 8, 4).int.toLong() and 0xFFFFFFFFL
            if (resRva == 0L) return null

            val sections = read(opt + optSize, sectionCount * 40)
            fun rvaToOffset(rva: Long): Long {
                for (i in 0 until sectionCount) {
                    val base = i * 40
                    val vSize = sections.getInt(base + 8).toLong() and 0xFFFFFFFFL
                    val vAddr = sections.getInt(base + 12).toLong() and 0xFFFFFFFFL
                    val rawSize = sections.getInt(base + 16).toLong() and 0xFFFFFFFFL
                    val rawPtr = sections.getInt(base + 20).toLong() and 0xFFFFFFFFL
                    if (rva >= vAddr && rva < vAddr + maxOf(vSize, rawSize)) return rva - vAddr + rawPtr
                }
                return -1
            }
            val resBase = rvaToOffset(resRva)
            if (resBase < 0) return null

            /** Записи каталога ресурсов: (id или -1 для имени, смещение, это подкаталог). */
            fun entries(dirOffset: Long): List<Triple<Int, Long, Boolean>> {
                val head = read(resBase + dirOffset, 16)
                val count = (head.getShort(12).toInt() and 0xFFFF) + (head.getShort(14).toInt() and 0xFFFF)
                if (count == 0 || count > 4096) return emptyList()
                val e = read(resBase + dirOffset + 16, count * 8)
                return (0 until count).map {
                    val name = e.getInt(it * 8)
                    val off = e.getInt(it * 8 + 4)
                    Triple(if (name < 0) -1 else name, (off and 0x7FFFFFFF).toLong(), off < 0)
                }
            }
            /** Данные ресурса: спуск по каталогам до первого листа. */
            fun data(entry: Triple<Int, Long, Boolean>): ByteArray? {
                var cur = entry
                var guard = 0
                while (cur.third) {
                    cur = entries(cur.second).firstOrNull() ?: return null
                    if (++guard > 4) return null
                }
                val leaf = read(resBase + cur.second, 16)
                val rva = leaf.getInt(0).toLong() and 0xFFFFFFFFL
                val size = leaf.getInt(4)
                if (size <= 0 || size > 8 * 1024 * 1024) return null
                val off = rvaToOffset(rva)
                if (off < 0 || off + size > file.length()) return null
                return read(off, size).array()
            }

            val types = entries(0)
            val groupType = types.firstOrNull { it.first == RT_GROUP_ICON } ?: return null
            val iconType = types.firstOrNull { it.first == RT_ICON } ?: return null
            val group = entries(groupType.second).firstOrNull()?.let { data(it) } ?: return null
            val icons = entries(iconType.second).filter { it.first >= 0 }.associateBy { it.first }

            // GRPICONDIR: 6 байт заголовка, затем записи по 14 байт
            val g = ByteBuffer.wrap(group).order(ByteOrder.LITTLE_ENDIAN)
            val count = g.getShort(4).toInt() and 0xFFFF
            data class Candidate(val w: Int, val bpp: Int, val id: Int, val header: ByteArray)
            val candidates = (0 until count).mapNotNull {
                val base = 6 + it * 14
                if (base + 14 > group.size) return@mapNotNull null
                val w = (group[base].toInt() and 0xFF).let { v -> if (v == 0) 256 else v }
                val bpp = g.getShort(base + 6).toInt() and 0xFFFF
                val id = g.getShort(base + 12).toInt() and 0xFFFF
                Candidate(w, bpp, id, group.copyOfRange(base, base + 8))
            }
            val best = candidates.filter { icons.containsKey(it.id) }
                .maxWithOrNull(compareBy<Candidate>({ it.w }, { it.bpp })) ?: return null
            val image = data(icons.getValue(best.id)) ?: return null
            // крупные иконки (256×256) обычно хранятся готовым PNG
            if (image.size > 8 && image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte()) return image

            // Файл .ico: ICONDIR (6) + ICONDIRENTRY (16) + картинка
            val ico = ByteBuffer.allocate(22 + image.size).order(ByteOrder.LITTLE_ENDIAN)
            ico.putShort(0).putShort(1).putShort(1)
            ico.put(best.header)                 // ширина, высота, цвета, резерв, плоскости, бит на точку
            ico.putInt(image.size).putInt(22)
            ico.put(image)
            return ico.array()
        }
    }
}
