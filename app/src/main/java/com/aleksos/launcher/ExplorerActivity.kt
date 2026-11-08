package com.aleksos.launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Окно «Этот телефон» — проводник AleksOS.
 * Отдельная задача Android (видна в «недавних»), с кнопками «Свернуть», «Развернуть/Уменьшить» и «Закрыть».
 * В уменьшенном виде окно можно перетаскивать за заголовок, а нажатия мимо окна уходят на рабочий стол.
 */
class ExplorerActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"

        /** Открыто ли окно (для значка на панели задач). */
        @Volatile var isOpen = false
            private set

        fun open(context: Context, dir: File? = null) {
            val intent = Intent(context, ExplorerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (dir != null) intent.putExtra(EXTRA_PATH, dir.absolutePath)
            context.startActivity(intent)
        }
    }

    private class Entry(val file: File, val title: String, val icon: Drawable, val detail: String)

    private val executor = Executors.newSingleThreadExecutor()
    private var currentDir: File? = null          // null = «Этот телефон»
    private val history = ArrayList<File?>()
    private var maximized = true
    private var loadGeneration = 0

    private lateinit var frame: LinearLayout
    private lateinit var titleIcon: ImageView
    private lateinit var titleText: TextView
    private lateinit var maximizeButton: TextView
    private lateinit var address: TextView
    private lateinit var pasteButton: TextView
    private lateinit var newFolderButton: TextView
    private lateinit var status: TextView
    private lateinit var accessBanner: TextView
    private lateinit var adapter: EntryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isOpen = true
        adapter = EntryAdapter()
        setContentView(buildLayout())
        setTaskDescription(ActivityManager.TaskDescription("Этот телефон"))
        applyWindowMode()
        intent.getStringExtra(EXTRA_PATH)?.let { navigate(File(it), addToHistory = false) } ?: navigate(null, addToHistory = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PATH)?.let { navigate(File(it)) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        isOpen = false
        executor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (history.isNotEmpty()) navigate(history.removeAt(history.size - 1), addToHistory = false)
        else moveTaskToBack(true)
    }

    // ---------- Окно ----------

    private fun buildLayout(): View {
        frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(16).toFloat()
        }

        // Заголовок окна: иконка, название, кнопки
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            setBackgroundColor(0xFFE8EDF4.toInt())
        }
        titleIcon = ImageView(this)
        titleBar.addView(titleIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
        titleText = TextView(this).apply {
            setTextColor(Ui.TEXT)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(8), 0)
        }
        titleBar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(windowButton("—") { moveTaskToBack(true) })
        maximizeButton = windowButton("❐") { maximized = !maximized; applyWindowMode() }
        titleBar.addView(maximizeButton)
        titleBar.addView(windowButton("✕", danger = true) { finishAndRemoveTask() })
        enableWindowDrag(titleBar)
        frame.addView(titleBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        // Навигация: назад, вверх, адрес, обновить
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        nav.addView(navButton("←") { onBackPressedCompat() })
        nav.addView(navButton("↑") { goUp() })
        address = TextView(this).apply {
            setTextColor(Ui.TEXT)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = Ui.rounded(0xFFFFFFFF.toInt(), dp(8).toFloat(), Ui.BORDER, 1)
        }
        nav.addView(address, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        nav.addView(navButton("⟳") { refresh() })
        frame.addView(nav)

        // Команды
        val commands = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(6))
        }
        newFolderButton = commandButton("＋ Новая папка") { currentDir?.let { FileMenu.createFolder(this, it) { refresh() } } }
        commands.addView(newFolderButton)
        pasteButton = commandButton("Вставить") { currentDir?.let { FileMenu.paste(this, it) { refresh() } } }
        commands.addView(pasteButton)
        commands.addView(commandButton("Рабочий стол") { navigate(FileOps.desktopDir.also { it.mkdirs() }) })
        commands.addView(commandButton("Свойства") { currentDir?.let { FileMenu.properties(this, it) } })
        frame.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(commands)
        })
        frame.addView(View(this).apply { setBackgroundColor(Ui.BORDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        accessBanner = TextView(this).apply {
            text = "Нет доступа к файлам. Нажмите, чтобы разрешить."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Ui.ACCENT_TEXT)
            visibility = View.GONE
            setOnClickListener { FileOps.requestAccess(this@ExplorerActivity) }
        }
        frame.addView(accessBanner)

        // Список файлов
        val list = ListView(this).apply {
            adapter = this@ExplorerActivity.adapter
            divider = null
            setBackgroundColor(0xFFFFFFFF.toInt())
            setOnItemClickListener { _, _, pos, _ -> openEntry(this@ExplorerActivity.adapter.getItem(pos).file) }
            setOnItemLongClickListener { _, _, pos, _ ->
                val entry = this@ExplorerActivity.adapter.getItem(pos)
                if (currentDir != null) FileMenu.show(this@ExplorerActivity, entry.file, ::openEntry) { refresh() }
                else FileMenu.properties(this@ExplorerActivity, entry.file)
                true
            }
        }
        frame.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Строка состояния
        status = TextView(this).apply {
            setTextColor(Ui.TEXT_DIM)
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(0xFFE8EDF4.toInt())
        }
        frame.addView(status)

        val root = FrameLayout(this)
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (maximized) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    root.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
            } else {
                root.setPadding(0, 0, 0, 0)
            }
            insets
        }
        return root
    }

    /** Развёрнуто — на весь экран. Уменьшено — плавающее окно поверх рабочего стола. */
    private fun applyWindowMode() {
        val metrics = resources.displayMetrics
        val attrs = window.attributes
        // в развёрнутом виде полоса под часами — цвета заголовка окна
        (frame.parent as? View)?.setBackgroundColor(if (maximized) 0xFFE8EDF4.toInt() else 0)
        if (maximized) {
            attrs.width = WindowManager.LayoutParams.MATCH_PARENT
            attrs.height = WindowManager.LayoutParams.MATCH_PARENT
            attrs.x = 0
            attrs.y = 0
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            frame.background = Ui.rounded(0xFFFFFFFF.toInt(), 0f)
            maximizeButton.text = "❐"
        } else {
            attrs.width = (metrics.widthPixels * 0.92f).toInt()
            attrs.height = (metrics.heightPixels * 0.6f).toInt()
            attrs.gravity = Gravity.TOP or Gravity.START
            attrs.x = (metrics.widthPixels - attrs.width) / 2
            attrs.y = (metrics.heightPixels * 0.12f).toInt()
            // нажатия мимо окна получает рабочий стол
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            frame.background = Ui.rounded(0xFFFFFFFF.toInt(), dp(12).toFloat(), Ui.BORDER, dp(1))
            maximizeButton.text = "▢"
        }
        frame.clipToOutline = true
        window.attributes = attrs
        window.decorView.requestApplyInsets()
    }

    /** Перетаскивание уменьшенного окна за заголовок; двойное нажатие — развернуть/уменьшить. */
    private fun enableWindowDrag(handle: View) {
        var startX = 0f
        var startY = 0f
        var winX = 0
        var winY = 0
        var lastTap = 0L
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    winX = window.attributes.x; winY = window.attributes.y
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 300) {
                        maximized = !maximized
                        applyWindowMode()
                        lastTap = 0
                    } else {
                        lastTap = now
                    }
                }
                MotionEvent.ACTION_MOVE -> if (!maximized) {
                    val attrs = window.attributes
                    val m = resources.displayMetrics
                    attrs.x = (winX + (e.rawX - startX)).toInt().coerceIn(-attrs.width / 2, m.widthPixels - attrs.width / 2)
                    attrs.y = (winY + (e.rawY - startY)).toInt().coerceIn(0, m.heightPixels - dp(60))
                    window.attributes = attrs
                }
            }
            true
        }
    }

    private fun windowButton(label: String, danger: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(Ui.TEXT)
        gravity = Gravity.CENTER
        val pressed = if (danger) 0xFFE5484D.toInt() else Ui.PRESSED
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(pressed), null, android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt())
        )
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun navButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        setTextColor(Ui.TEXT)
        gravity = Gravity.CENTER
        Ui.pressable(this, null, dp(8).toFloat())
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
    }

    private fun commandButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Ui.TEXT)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        Ui.pressable(this, Ui.rounded(Ui.FIELD, dp(8).toFloat()), dp(8).toFloat())
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(6) }
    }

    // ---------- Навигация ----------

    private fun onBackPressedCompat() {
        if (history.isNotEmpty()) navigate(history.removeAt(history.size - 1), addToHistory = false)
    }

    private fun goUp() {
        val dir = currentDir ?: return
        val root = FileOps.storageRoot.canonicalFile
        val parent = dir.parentFile
        // выше корня памяти — «Этот телефон»
        navigate(if (dir.canonicalFile == root || parent == null || !dir.canonicalPath.startsWith(root.path)) null else parent)
    }

    private fun navigate(dir: File?, addToHistory: Boolean = true) {
        if (addToHistory && dir?.path != currentDir?.path) history += currentDir
        currentDir = dir
        refresh()
    }

    private fun openEntry(file: File) {
        when {
            file.isDirectory -> navigate(file)
            file.extension.lowercase() in setOf("exe", "msi", "bat", "lnk") -> {
                AosDialog(this)
                    .title(file.name)
                    .message("Это Windows-программа. Добавить её на рабочий стол AleksOS?")
                    .button("Отмена")
                    .button("Добавить", primary = true) {
                        WindowsAppStore(this).add(file.nameWithoutExtension, android.net.Uri.fromFile(file).toString())
                        android.widget.Toast.makeText(this, "«${file.nameWithoutExtension}» добавлена на рабочий стол", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            else -> try {
                FileOps.open(this, file)
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "Нет приложения, чтобы открыть этот файл", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- Содержимое ----------

    private fun refresh() {
        val dir = currentDir
        val access = FileOps.hasAccess(this)
        accessBanner.visibility = if (access) View.GONE else View.VISIBLE
        titleText.text = dir?.let { displayName(it) } ?: "Этот телефон"
        titleIcon.setImageDrawable(if (dir == null) FileIconDrawable.phone() else FileIconDrawable.folder())
        address.text = dir?.let { addressOf(it) } ?: "Этот телефон"
        newFolderButton.isEnabled = dir != null
        newFolderButton.alpha = if (dir != null) 1f else 0.4f
        val canPaste = dir != null && FileOps.clipboard != null
        pasteButton.isEnabled = canPaste
        pasteButton.alpha = if (canPaste) 1f else 0.4f

        val generation = ++loadGeneration
        executor.execute {
            val entries = if (dir == null) rootEntries() else listEntries(dir)
            val footer = if (dir == null) "" else freeSpace(dir)
            runOnUiThread {
                if (isDestroyed || generation != loadGeneration) return@runOnUiThread
                adapter.items = entries
                status.text = "Элементов: ${entries.size}" + (if (footer.isNotEmpty()) "   ·   $footer" else "")
            }
        }
    }

    private fun listEntries(dir: File): List<Entry> {
        val files = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
        return files
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { f ->
                val detail = if (f.isDirectory) {
                    FileOps.formatDate(f.lastModified()) + "   ·   Папка"
                } else {
                    FileOps.formatDate(f.lastModified()) + "   ·   " + FileOps.formatSize(f.length())
                }
                Entry(f, f.name, FileIconDrawable.forEntry(f), detail)
            }
    }

    /** Главная страница: диски и быстрые папки. */
    private fun rootEntries(): List<Entry> {
        val list = ArrayList<Entry>()
        val root = FileOps.storageRoot
        list += Entry(root, "Внутренняя память", FileIconDrawable.drive(), freeSpace(root))
        // SD-карты и флешки (OTG)
        getExternalFilesDirs(null).drop(1).filterNotNull().forEach { appDir ->
            val path = appDir.absolutePath.substringBefore("/Android/")
            val card = File(path)
            list += Entry(card, "Карта памяти / USB", FileIconDrawable.drive(), freeSpace(card))
        }
        val quick = listOf(
            "Рабочий стол" to FileOps.desktopDir,
            "Загрузки" to File(root, "Download"),
            "Документы" to File(root, "Documents"),
            "Изображения" to File(root, "Pictures"),
            "Камера" to File(root, "DCIM"),
            "Музыка" to File(root, "Music"),
            "Видео" to File(root, "Movies"),
        )
        quick.forEach { (title, dir) ->
            if (dir.isDirectory || dir == FileOps.desktopDir) list += Entry(dir, title, FileIconDrawable.folder(), "Папка")
        }
        return list
    }

    private fun freeSpace(dir: File): String = try {
        val stat = StatFs(dir.path)
        "свободно ${FileOps.formatSize(stat.availableBytes)} из ${FileOps.formatSize(stat.totalBytes)}"
    } catch (_: Exception) {
        ""
    }

    private fun displayName(dir: File): String =
        if (dir.canonicalPath == FileOps.storageRoot.canonicalPath) "Внутренняя память"
        else if (dir.canonicalPath == FileOps.desktopDir.canonicalPath) "Рабочий стол"
        else dir.name

    private fun addressOf(dir: File): String {
        val rootPath = FileOps.storageRoot.canonicalPath
        val path = dir.canonicalPath
        val desktopPath = FileOps.desktopDir.canonicalPath
        if (path == desktopPath || path.startsWith(desktopPath + "/")) {
            return "Рабочий стол" + path.removePrefix(desktopPath).replace("/", " › ")
        }
        return if (path.startsWith(rootPath)) {
            "Этот телефон › Внутренняя память" + path.removePrefix(rootPath).replace("/", " › ")
        } else {
            "Этот телефон › " + path.trimStart('/').replace("/", " › ")
        }
    }

    // ---------- Список ----------

    private inner class EntryAdapter : BaseAdapter() {
        var items: List<Entry> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@ExplorerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                addView(ImageView(context).apply { tag = "icon" }, LinearLayout.LayoutParams(dp(36), dp(36)))
                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(TextView(context).apply {
                        tag = "title"; setTextColor(Ui.TEXT); textSize = 15f
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    })
                    addView(TextView(context).apply {
                        tag = "detail"; setTextColor(Ui.TEXT_DIM); textSize = 12f; maxLines = 1
                    })
                }
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val entry = items[position]
            row.findViewWithTag<ImageView>("icon").setImageDrawable(entry.icon)
            row.findViewWithTag<TextView>("title").text = entry.title
            row.findViewWithTag<TextView>("detail").text = entry.detail
            return row
        }
    }

    private fun dp(value: Int) = Ui.dp(this, value)
}
