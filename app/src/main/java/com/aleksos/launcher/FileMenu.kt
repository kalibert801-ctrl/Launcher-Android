package com.aleksos.launcher

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/** Действия с файлом или папкой — общие для проводника и рабочего стола. */
object FileMenu {

    /** Меню по долгому нажатию на файл/папку. */
    fun show(activity: Activity, file: File, onOpen: (File) -> Unit, onChanged: () -> Unit) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "Открыть" to { onOpen(file) }
        actions += "Копировать" to { FileOps.copyToClipboard(file, cut = false); toast(activity, "Скопировано: ${file.name}") }
        actions += "Вырезать" to { FileOps.copyToClipboard(file, cut = true); toast(activity, "Вырезано: ${file.name}") }
        if (file.isDirectory && FileOps.clipboard != null) actions += "Вставить сюда" to { paste(activity, file, onChanged) }
        actions += "Переименовать" to { rename(activity, file, onChanged) }
        actions += "Удалить" to { confirmDelete(activity, file, onChanged) }
        if (file.isFile) actions += "Отправить…" to { FileOps.share(activity, file) }
        actions += "Свойства" to { properties(activity, file) }

        AosDialog(activity)
            .title(file.name)
            .items(actions.map { it.first }) { actions[it].second() }
            .show()
    }

    /** Создать «Новую папку» и сразу предложить её переименовать (как в Windows). */
    fun createFolder(activity: Activity, dir: File, onChanged: () -> Unit) {
        val folder = try {
            dir.mkdirs()
            FileOps.createFolder(dir)
        } catch (e: Exception) {
            toast(activity, "Не удалось создать папку: ${e.message}")
            return
        }
        onChanged()
        rename(activity, folder, onChanged, title = "Новая папка")
    }

    fun rename(activity: Activity, file: File, onChanged: () -> Unit, title: String = "Переименовать") {
        val input = EditText(activity).apply {
            setText(file.name)
            setTextColor(Ui.TEXT)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            // выделяем имя без расширения
            val dot = if (file.isDirectory) -1 else file.name.lastIndexOf('.')
            setSelection(0, if (dot > 0) dot else file.name.length)
            requestFocus()
        }
        val dialog = AosDialog(activity)
            .title(title)
            .view(input)
            .button("Отмена")
            .button("Готово", primary = true) {
                val name = input.text.toString().trim()
                when {
                    name == file.name -> {}
                    !FileOps.isValidName(name) -> toast(activity, "Имя не может содержать символы / \\ : * ? \" < > |")
                    File(file.parentFile, name).exists() -> toast(activity, "Файл с таким именем уже есть")
                    !file.renameTo(File(file.parentFile, name)) -> toast(activity, "Не удалось переименовать")
                    else -> onChanged()
                }
            }
            .show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    fun confirmDelete(activity: Activity, file: File, onChanged: () -> Unit) {
        val what = if (file.isDirectory) "папку «${file.name}» со всем содержимым" else "файл «${file.name}»"
        AosDialog(activity)
            .title("Удаление")
            .message("Удалить $what?\n\nКорзины пока нет — восстановить будет нельзя.")
            .button("Отмена")
            .button("Удалить", primary = true) {
                runInBackground(activity, "Удаляю…", { FileOps.deleteRecursive(file) || !file.exists() }) { ok ->
                    if (!ok) toast(activity, "Удалено не всё: нет доступа к части файлов")
                    onChanged()
                }
            }
            .show()
    }

    fun paste(activity: Activity, dir: File, onChanged: () -> Unit) {
        if (FileOps.clipboard == null) {
            toast(activity, "Буфер пуст: сначала скопируйте или вырежьте файл")
            return
        }
        runInBackground(activity, "Вставляю…", { FileOps.paste(dir) }) { onChanged() }
    }

    fun properties(activity: Activity, file: File) {
        val info = TextView(activity).apply {
            setTextColor(Ui.TEXT)
            textSize = 14f
            setLineSpacing(0f, 1.25f)
        }
        fun render(stats: FileOps.Stats?) {
            info.text = buildString {
                append("Тип: ${FileOps.typeName(file)}\n")
                append("Расположение: ${file.parent}\n")
                append("Размер: ").append(stats?.let { "${FileOps.formatSize(it.bytes)} (${it.bytes} байт)" } ?: "считаю…").append('\n')
                if (file.isDirectory) {
                    append("Содержит: ").append(stats?.let { "файлов ${it.files}, папок ${it.folders}" } ?: "считаю…").append('\n')
                }
                append("Изменён: ${FileOps.formatDate(file.lastModified())}\n")
                append("Атрибуты: ")
                append(if (file.canWrite()) "чтение и запись" else "только чтение")
                if (file.isHidden) append(", скрытый")
            }
        }
        render(null)
        AosDialog(activity).title("Свойства: ${file.name}").view(info).button("OK", primary = true).show()
        thread(name = "properties") {
            val stats = FileOps.stats(file)
            activity.runOnUiThread { render(stats) }
        }
    }

    /** Выполнить долгую операцию в фоне с окошком «подождите». */
    private fun <T> runInBackground(activity: Activity, title: String, work: () -> T, done: (T) -> Unit) {
        val bar = AosDialog.progressBar(activity).apply { isIndeterminate = true }
        val dialog = AosDialog(activity).title(title).view(bar).cancelable(false).show()
        thread(name = "file-op") {
            val result = runCatching(work)
            activity.runOnUiThread {
                runCatching { dialog.dismiss() }
                result.onSuccess(done).onFailure { toast(activity, "Ошибка: ${it.message}") }
            }
        }
    }

    private fun toast(activity: Activity, text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
