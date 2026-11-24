package com.aleksos.launcher

import java.io.File
import java.io.RandomAccessFile

/**
 * Заплатки для функций, которые в Wine 9.2 объявлены, но не написаны («unimplemented function»):
 * при вызове такой функции программа сразу падает. В новых Wine часть из них стала безопасной
 * «пустышкой» — повторяем это прямо в DLL Wine: в начало функции пишем «вернуть 0».
 *
 * Сейчас: mscms.WcsCreateIccProfile — её вызывает Qt 6 при открытии окна (исправлено в Wine
 * в июне 2026, MR 11138: заглушка, возвращающая NULL).
 */
object WineStubPatches {
    /** Функция-«ловушка» → «вернуть 0»; argBytes — сколько байт аргументов снимает stdcall в 32-битной версии. */
    private class Patch(val dll: String, val function: String, argBytes: Int) {
        val x64 = byteArrayOf(0x31, 0xC0.toByte(), 0xC3.toByte())                                  // xor eax,eax; ret
        val x86 = byteArrayOf(0x31, 0xC0.toByte(), 0xC2.toByte(), argBytes.toByte(), 0x00)          // xor eax,eax; ret N
    }

    private val PATCHES = listOf(
        Patch("mscms.dll", "WcsCreateIccProfile", 8),          // (HPROFILE, DWORD) → NULL
        Patch("mscms.dll", "WcsGetDefaultColorProfile", 28),   // 7 аргументов → FALSE («профиля нет»)
    )

    /** Так начинается «ловушка» winebuild (вызов __wine_spec_unimplemented_stub) — настоящий код не трогаем. */
    private val STUB_X64 = byteArrayOf(0x48, 0x83.toByte(), 0xEC.toByte(), 0x28, 0x48, 0x8D.toByte(), 0x0D)
    private val STUB_X86 = byteArrayOf(0x90.toByte(), 0x90.toByte(), 0x90.toByte(), 0x90.toByte())

    fun apply(rootfs: File) {
        val dirs = listOf(
            "opt/wine/lib/wine/x86_64-windows", "opt/wine/lib/wine/i386-windows",
            "home/xuser/.wine/drive_c/windows/system32", "home/xuser/.wine/drive_c/windows/syswow64")
        for (p in PATCHES) for (d in dirs) {
            val f = File(rootfs, "$d/${p.dll}")
            if (!f.isFile) continue
            runCatching {
                when (patchFile(f, p)) {
                    true -> WineRuntime.writeLog("Заплатка Wine: ${p.dll}.${p.function} → пустышка ($d)")
                    false -> {}
                    null -> {}
                }
            }.onFailure { WineRuntime.writeLog("Заплатка Wine ${p.dll}: $it") }
        }
    }

    /** true — исправлено сейчас, false — уже было исправлено, null — функция не найдена. */
    private fun patchFile(file: File, p: Patch): Boolean? {
        RandomAccessFile(file, "rw").use { raf ->
            fun u16(o: Long): Int { raf.seek(o); return raf.read() or (raf.read() shl 8) }
            fun u32(o: Long): Long { raf.seek(o); var v = 0L; for (i in 0 until 4) v = v or (raf.read().toLong() shl (8 * i)); return v }
            if (u16(0) != 0x5A4D) return null
            val pe = u32(0x3C)
            if (u32(pe) != 0x4550L) return null
            val machine = u16(pe + 4)
            val nSections = u16(pe + 6)
            val optSize = u16(pe + 20)
            val opt = pe + 24
            val is64 = u16(opt) == 0x20B
            val dataDir = opt + if (is64) 112 else 96
            val exportRva = u32(dataDir)
            if (exportRva == 0L) return null
            val sections = (0 until nSections).map { i ->
                val s = opt + optSize + i * 40L
                longArrayOf(u32(s + 12), maxOf(u32(s + 8), u32(s + 16)), u32(s + 20))   // va, size, raw offset
            }
            fun off(rva: Long): Long = sections.firstOrNull { rva >= it[0] && rva < it[0] + it[1] }?.let { rva - it[0] + it[2] } ?: -1
            val ed = off(exportRva)
            if (ed < 0) return null
            val nFuncs = u32(ed + 20); val nNames = u32(ed + 24)
            val funcs = off(u32(ed + 28)); val names = off(u32(ed + 32)); val ords = off(u32(ed + 36))
            for (i in 0 until nNames) {
                val nameOff = off(u32(names + 4 * i))
                raf.seek(nameOff)
                val sb = StringBuilder()
                while (true) { val c = raf.read(); if (c <= 0) break; sb.append(c.toChar()); if (sb.length > 80) break }
                if (sb.toString() != p.function) continue
                val ord = u16(ords + 2 * i)
                if (ord >= nFuncs) return null
                val rva = u32(funcs + 4L * ord)
                // переадресация в другую DLL (адрес внутри таблицы экспорта) — не код, не трогаем
                if (rva >= exportRva && rva < exportRva + u32(dataDir + 4)) return null
                val codeOff = off(rva)
                if (codeOff < 0) return null
                val bytes = if (machine == 0x8664 || is64) p.x64 else p.x86
                val cur = ByteArray(bytes.size); raf.seek(codeOff); raf.readFully(cur)
                if (cur.contentEquals(bytes)) return false
                val sig = if (bytes === p.x64) STUB_X64 else STUB_X86
                val head = ByteArray(sig.size); raf.seek(codeOff); raf.readFully(head)
                if (!head.contentEquals(sig)) return null     // это уже не «ловушка» (другая версия Wine)
                raf.seek(codeOff); raf.write(bytes)
                return true
            }
            return null
        }
    }
}
