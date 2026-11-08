package com.aleksos.launcher

import android.Manifest
import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Работа с файлами: доступ, рабочий стол-папка, буфер «копировать/вырезать», копирование, удаление. */
object FileOps {

    /** Корень памяти телефона (то, что в Windows было бы диском C:). */
    val storageRoot: File get() = Environment.getExternalStorageDirectory()

    /** Папка рабочего стола: всё, что в ней лежит, видно на рабочем столе AleksOS. */
    val desktopDir: File get() = File(storageRoot, "Desktop")

    // ---------- Разрешение на доступ к файлам ----------

    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Объясняет, зачем нужен доступ, и открывает нужный экран настроек. */
    fun requestAccess(activity: Activity) {
        AosDialog(activity)
            .title("Доступ к файлам")
            .message(
                "Чтобы показывать папки и файлы, как в проводнике, AleksOS нужен доступ ко всем файлам.\n\n" +
                    "На следующем экране включите переключатель для AleksOS и вернитесь назад."
            )
            .button("Отмена")
            .button("Разрешить", primary = true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
                        )
                    } catch (_: Exception) {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 2001
                    )
                }
            }
            .show()
    }

    // ---------- Буфер обмена файлов ----------

    /** Что лежит в буфере: файл и «вырезан» ли он (тогда при вставке переносится). */
    var clipboard: File? = null
        private set
    var clipboardCut = false
        private set

    fun copyToClipboard(file: File, cut: Boolean) {
        clipboard = file
        clipboardCut = cut
    }

    /** Вставить из буфера в папку. Возвращает новый файл. Выполнять не в главном потоке. */
    fun paste(targetDir: File): File {
        val source = clipboard ?: error("Буфер пуст")
        if (!source.exists()) error("Исходный файл больше не существует")
        if (source.isDirectory && targetDir.canonicalPath.startsWith(source.canonicalPath + File.separator) ||
            source.isDirectory && targetDir.canonicalPath == source.canonicalPath
        ) error("Нельзя вставить папку саму в себя")
        val sameDir = source.parentFile?.canonicalPath == targetDir.canonicalPath
        if (clipboardCut && sameDir) return source
        val target = uniqueName(targetDir, source.name, copy = !clipboardCut, isDirectory = source.isDirectory)
        if (clipboardCut) {
            if (!source.renameTo(target)) {   // renameTo не работает между разными дисками
                copyRecursive(source, target)
                deleteRecursive(source)
            }
            clipboard = null
        } else {
            copyRecursive(source, target)
        }
        return target
    }

    fun copyRecursive(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) error("Не удалось создать папку ${target.name}")
            source.listFiles()?.forEach { copyRecursive(it, File(target, it.name)) }
        } else {
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
            target.setLastModified(source.lastModified())
        }
    }

    fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        return file.delete()
    }

    /** «Новая папка», «Новая папка (2)»… или «файл - копия.txt». */
    fun uniqueName(dir: File, name: String, copy: Boolean = false, isDirectory: Boolean = false): File {
        val hasExt = !isDirectory && name.lastIndexOf('.') > 0
        val base = if (hasExt) name.substringBeforeLast('.') else name
        val ext = if (hasExt) "." + name.substringAfterLast('.') else ""
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = if (copy) "$base - копия" else base
        candidate = File(dir, stem + ext)
        var n = 2
        while (candidate.exists()) candidate = File(dir, "$stem ($n)$ext").also { n++ }
        return candidate
    }

    fun createFolder(dir: File): File {
        val folder = uniqueName(dir, "Новая папка", isDirectory = true)
        if (!folder.mkdirs()) error("Не удалось создать папку")
        return folder
    }

    /** Имя без запрещённых символов. */
    fun isValidName(name: String) =
        name.isNotBlank() && name != "." && name != ".." && name.none { it in "/\\:*?\"<>|" }

    // ---------- Свойства ----------

    class Stats(val bytes: Long, val files: Int, val folders: Int)

    fun stats(file: File): Stats {
        if (!file.isDirectory) return Stats(file.length(), 1, 0)
        var bytes = 0L
        var files = 0
        var folders = 0
        val stack = ArrayDeque<File>()
        stack.addLast(file)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            dir.listFiles()?.forEach {
                if (it.isDirectory) {
                    folders++
                    stack.addLast(it)
                } else {
                    files++
                    bytes += it.length()
                }
            }
        }
        return Stats(bytes, files, folders)
    }

    fun formatSize(bytes: Long): String {
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return if (i == 0) "$bytes Б" else String.format(Locale("ru"), "%.1f %s", value, units[i])
    }

    fun formatDate(millis: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(millis))

    fun typeName(file: File): String = when {
        file.isDirectory -> "Папка с файлами"
        file.extension.isEmpty() -> "Файл"
        else -> "Файл «${file.extension.uppercase()}»"
    }

    // ---------- Открытие файлов в Android-приложениях ----------

    fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

    fun open(context: Context, file: File) {
        val uri = FilesProvider.uriFor(file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "Открыть с помощью").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun share(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType(file))
            .putExtra(Intent.EXTRA_STREAM, FilesProvider.uriFor(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Отправить").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Отдаёт файлы другим приложениям (фото, музыка, PDF…) по ссылке content://.
 * Своя маленькая замена FileProvider, чтобы не подключать AndroidX.
 */
class FilesProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.aleksos.launcher.files"
        fun uriFor(file: File): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("f").appendPath(file.absolutePath).build()
    }

    private fun fileOf(uri: Uri): File {
        val path = uri.pathSegments.drop(1).joinToString("/")
        val file = File("/$path").canonicalFile
        // отдаём только файлы из памяти телефона
        if (!file.path.startsWith(FileOps.storageRoot.canonicalPath)) throw SecurityException("Нет доступа")
        return file
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String = FileOps.mimeType(fileOf(uri))

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = fileOf(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
