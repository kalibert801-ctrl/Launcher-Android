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
    /** Новый машинный код вместо «ловушки»: x64 — для 64-битной DLL, x86 — для 32-битной (null — не трогать). */
    private class Patch(val dll: String, val function: String, val x64: ByteArray?, val x86: ByteArray?)

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** «вернуть 0»; argBytes — сколько байт аргументов снимает stdcall в 32-битной версии. */
    private fun zero(dll: String, function: String, argBytes: Int) =
        Patch(dll, function, hex("31C0C3"), byteArrayOf(0x31, 0xC0.toByte(), 0xC2.toByte(), argBytes.toByte(), 0x00))

    // Комплексные числа из ucrtbase (C99 <complex.h>, их зовёт numpy). Соглашения Microsoft:
    // x64 — _Fcomplex (8 байт) в RCX и обратно в RAX; _Dcomplex/_Lcomplex (16 байт) по указателю,
    // результат-структура — через скрытый указатель в RCX. x86 (cdecl) — аргументы в стеке, число — в ST0.
    private val X64_CABS_D = "66 0F 10 01  66 0F 59 C0  66 0F 70 C8 EE  F2 0F 58 C1"          // re²+im² (double)
    private val X64_CABS_F = "66 48 0F 6E C1  0F 59 C0  66 0F 70 C8 01  F3 0F 58 C1"          // re²+im² (float)
    private val X86_CABS_D = "DD 44 24 04  D8 C8  DD 44 24 0C  D8 C8  DE C1"
    private val X86_CABS_F = "D9 44 24 04  D8 C8  D9 44 24 08  D8 C8  DE C1"

    private val PATCHES = listOf(
        zero("mscms.dll", "WcsCreateIccProfile", 8),          // (HPROFILE, DWORD) → NULL, как в Wine MR 11138
        zero("mscms.dll", "WcsGetDefaultColorProfile", 28),   // 7 аргументов → FALSE («профиля нет»)

        Patch("ucrtbase.dll", "crealf", hex("66 0F 6E C1 C3"), hex("D9 44 24 04 C3")),
        Patch("ucrtbase.dll", "cimagf", hex("48 89 C8  48 C1 E8 20  66 0F 6E C0  C3"), hex("D9 44 24 08 C3")),
        Patch("ucrtbase.dll", "creall", hex("F2 0F 10 01 C3"), hex("DD 44 24 04 C3")),
        Patch("ucrtbase.dll", "cimag", hex("F2 0F 10 41 08 C3"), hex("DD 44 24 0C C3")),
        Patch("ucrtbase.dll", "cimagl", hex("F2 0F 10 41 08 C3"), hex("DD 44 24 0C C3")),
        Patch("ucrtbase.dll", "conjf", hex("48 89 C8  48 0F BA F8 3F  C3"), hex("8B 44 24 04  8B 54 24 08  81 F2 00 00 00 80  C3")),
        Patch("ucrtbase.dll", "conj", hex("48 8B 02  48 89 01  48 8B 42 08  48 0F BA F8 3F  48 89 41 08  48 89 C8  C3"), null),
        Patch("ucrtbase.dll", "conjl", hex("48 8B 02  48 89 01  48 8B 42 08  48 0F BA F8 3F  48 89 41 08  48 89 C8  C3"), null),
        Patch("ucrtbase.dll", "_FCbuild", hex("66 0F 7E C0  66 0F 7E CA  48 C1 E2 20  48 09 D0  C3"), hex("8B 44 24 04  8B 54 24 08  C3")),
        Patch("ucrtbase.dll", "_LCbuild", hex("F2 0F 11 09  F2 0F 11 51 08  48 89 C8  C3"), hex("8B 44 24 04  DD 44 24 08  DD 18  DD 44 24 10  DD 58 08  C3")),
        Patch("ucrtbase.dll", "cabs", hex("$X64_CABS_D F2 0F 51 C0 C3"), hex("$X86_CABS_D D9 FA C3")),
        Patch("ucrtbase.dll", "cabsl", hex("$X64_CABS_D F2 0F 51 C0 C3"), hex("$X86_CABS_D D9 FA C3")),
        Patch("ucrtbase.dll", "cabsf", hex("$X64_CABS_F F3 0F 51 C0 C3"), hex("$X86_CABS_F D9 FA C3")),
        Patch("ucrtbase.dll", "norm", hex("$X64_CABS_D C3"), hex("$X86_CABS_D C3")),
        Patch("ucrtbase.dll", "norml", hex("$X64_CABS_D C3"), hex("$X86_CABS_D C3")),
        Patch("ucrtbase.dll", "normf", hex("$X64_CABS_F C3"), hex("$X86_CABS_F C3")),
    )

    /** Сколько байт занимает «ловушка» (новый код не должен быть длиннее). */
    private const val STUB_LEN_X64 = 24
    private const val STUB_LEN_X86 = 31

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
                val bytes = (if (machine == 0x8664 || is64) p.x64 else p.x86) ?: return null
                if (bytes.size > (if (bytes === p.x64) STUB_LEN_X64 else STUB_LEN_X86)) return null
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
