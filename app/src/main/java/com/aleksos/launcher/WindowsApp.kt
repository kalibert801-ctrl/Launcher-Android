package com.aleksos.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Windows-программа, добавленная на рабочий стол. */
data class WindowsApp(
    val id: String,
    val name: String,
    /** content:// ссылка на .exe/.lnk/.bat/.msi, выбранный пользователем. */
    val uri: String,
)

/** Хранит список Windows-программ рабочего стола (в SharedPreferences, как JSON). */
class WindowsAppStore(context: Context) {

    private val prefs = context.getSharedPreferences("windows_apps", Context.MODE_PRIVATE)

    fun load(): List<WindowsApp> {
        val raw = prefs.getString(KEY_APPS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                WindowsApp(o.getString("id"), o.getString("name"), o.getString("uri"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(name: String, uri: String): WindowsApp {
        val app = WindowsApp(UUID.randomUUID().toString(), name, uri)
        save(load() + app)
        return app
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    private fun save(apps: List<WindowsApp>) {
        val array = JSONArray()
        apps.forEach { app ->
            array.put(
                JSONObject()
                    .put("id", app.id)
                    .put("name", app.name)
                    .put("uri", app.uri)
            )
        }
        prefs.edit().putString(KEY_APPS, array.toString()).apply()
    }

    private companion object {
        const val KEY_APPS = "apps"
    }
}
