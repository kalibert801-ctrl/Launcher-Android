package com.aleksos.launcher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Точка входа в движок запуска Windows-программ (Wine + Box64 + DXVK/Turnip).
 *
 * Этап 2, шаг 1: установка движка (скачивание и распаковка — EngineManager).
 * Шаг 2 (следующий): запуск Wine через proot + Box64 и X-сервер для окон программ.
 */
object WindowsEngine {

    /** Установлен ли движок. */
    fun isAvailable(context: Context): Boolean = EngineManager.isInstalled(context)

    /** Модуль движка: название, зачем нужен и установленная версия (null — не установлен). */
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
            Module("X-сервер", "окна программ", null),
            Module("PulseAudio", "звук", null),
        )
    }

    fun launch(activity: Activity, app: WindowsApp) {
        if (!isAvailable(activity)) {
            offerInstall(activity, "Чтобы запускать «${app.name}» и другие Windows-программы, нужен движок Windows.")
            return
        }
        AosDialog(activity)
            .title(app.name)
            .message(
                "Движок Windows установлен ✔\n\n" +
                    "Запуск программ появится в следующей сборке AleksOS: сейчас подключаем Wine к экрану телефона."
            )
            .button("OK", primary = true)
            .show()
    }

    /** Предложить установить движок (или показать ход установки, если она уже идёт). */
    fun offerInstall(activity: Activity, reason: String) {
        if (EngineManager.isRunning) {
            showProgress(activity)
            return
        }
        AosDialog(activity)
            .title("Движок Windows")
            .message(
                "$reason\n\n" +
                    "• Скачать: около 300 МБ (лучше по Wi-Fi)\n" +
                    "• Займёт на телефоне: около 0,8 ГБ\n" +
                    "• Состав: Wine 9.2, Box64, Turnip, DXVK, VKD3D\n\n" +
                    "Скачивание идёт в фоне, можно пользоваться телефоном. Если связь прервётся — продолжится с того же места."
            )
            .button("Позже")
            .button("Установить", primary = true) {
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
                        AosDialog(activity).title("Движок Windows")
                            .message("Движок установлен ✔\n\nВерсии модулей видны в BIOS и при загрузке AleksOS.")
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
            .title("Установка движка Windows")
            .view(box)
            .cancelable(false)
            .button("Отменить") { EngineManager.cancel(); EngineManager.removeListener(listener) }
            .button("Скрыть", primary = true) { EngineManager.removeListener(listener) }
            .show()
        EngineManager.addListener(listener)
    }
}
