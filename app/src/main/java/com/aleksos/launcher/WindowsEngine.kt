package com.aleksos.launcher

import android.content.Context

/**
 * Точка входа в движок запуска Windows-программ.
 *
 * Сейчас это заглушка. На следующих этапах здесь появится
 * Wine + Box64 + DXVK/Turnip: запуск .exe в фоновом сервисе
 * и показ каждой программы в отдельном окне Android.
 */
object WindowsEngine {

    /** Готов ли движок к запуску программ. */
    val isAvailable: Boolean = false

    /** Модуль движка: название, зачем нужен и установленная версия (null — не установлен). */
    class Module(val name: String, val purpose: String, val version: String?)

    /** Список модулей для экрана загрузки и BIOS. Версии появятся после установки движка. */
    fun modules(): List<Module> = listOf(
        Module("Wine", "Windows API", null),
        Module("Box64", "x86-64 → ARM64", null),
        Module("DXVK", "DirectX 9–11 → Vulkan", null),
        Module("VKD3D", "DirectX 12 → Vulkan", null),
        Module("Turnip", "Vulkan для Adreno", null),
        Module("X-сервер", "окна программ", null),
        Module("PulseAudio", "звук", null),
    )

    fun launch(context: Context, app: WindowsApp) {
        if (!isAvailable) {
            AosDialog(context)
                .title(app.name)
                .message(
                    "Движок Windows (Wine + Box64) ещё не подключён.\n\n" +
                        "Программа добавлена на рабочий стол и запустится, " +
                        "когда движок появится в следующих версиях AleksOS."
                )
                .button("OK", primary = true)
                .show()
            return
        }
        // TODO: этап 2 — запуск через Wine.
    }
}
