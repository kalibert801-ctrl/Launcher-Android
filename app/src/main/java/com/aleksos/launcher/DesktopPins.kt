package com.aleksos.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import org.json.JSONArray

/** Закреплённые Android-приложения (на рабочем столе или на панели задач), порядок сохраняется. */
class DesktopPins(
    private val context: Context,
    prefsName: String = "desktop",
    /** Сколько приложений закрепить при первом запуске. */
    private val defaultLimit: Int = Int.MAX_VALUE,
) {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun load(): List<ComponentName> {
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            save(defaultPins().take(defaultLimit))
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
        }
        val raw = prefs.getString(KEY_PINS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { ComponentName.unflattenFromString(array.getString(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isPinned(component: ComponentName) = component in load()

    fun pin(component: ComponentName) {
        val list = load()
        if (component !in list) save(list + component)
    }

    fun unpin(component: ComponentName) = save(load() - component)

    private fun save(list: List<ComponentName>) {
        val array = JSONArray()
        list.forEach { array.put(it.flattenToString()) }
        prefs.edit().putString(KEY_PINS, array.toString()).apply()
    }

    /** При первом запуске кладём на стол телефон, сообщения, браузер, камеру, галерею и Telegram. */
    private fun defaultPins(): List<ComponentName> {
        val pm = context.packageManager
        val packages = LinkedHashSet<String>()
        val probes = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
        )
        for (probe in probes) {
            val pkg = try {
                pm.resolveActivity(probe, 0)?.activityInfo?.packageName
            } catch (_: Exception) {
                null
            }
            // "android" = окно выбора приложения, а не конкретное приложение
            if (pkg != null && pkg != "android") packages += pkg
        }
        packages += "org.telegram.messenger"
        return packages.mapNotNull { pkg ->
            try {
                pm.getLaunchIntentForPackage(pkg)?.component
            } catch (_: Exception) {
                null
            }
        }
    }

    private companion object {
        const val KEY_PINS = "pins"
        const val KEY_INITIALIZED = "initialized"
    }
}
