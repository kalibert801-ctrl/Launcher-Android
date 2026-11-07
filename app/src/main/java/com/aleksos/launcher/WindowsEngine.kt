package com.aleksos.launcher

import android.app.AlertDialog
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

    fun launch(context: Context, app: WindowsApp) {
        if (!isAvailable) {
            AlertDialog.Builder(context)
                .setTitle(app.name)
                .setMessage(
                    "Движок Windows (Wine + Box64) ещё не подключён.\n\n" +
                        "Программа добавлена на рабочий стол и запустится, " +
                        "когда движок появится в следующих версиях AleksOS."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        // TODO: этап 2 — запуск через Wine.
    }
}
