package com.aleksos.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * Главный экран AleksOS.
 * Рабочий стол (иконки можно перетаскивать), панель задач внизу и компактное меню «Пуск».
 * Android-приложения и Windows-программы живут рядом.
 */
class MainActivity : Activity() {

    private enum class Kind { ANDROID, WINDOWS, ADD_WINDOWS, COMPUTER, FILE }

    private class Item(
        val kind: Kind,
        val label: String,
        val icon: Drawable,
        val component: ComponentName? = null,
        val windowsApp: WindowsApp? = null,
        val file: File? = null,
    ) {
        /** Стабильный ключ для запоминания места на рабочем столе. */
        val key: String = when (kind) {
            Kind.ANDROID -> "a:" + component!!.flattenToString()
            Kind.WINDOWS -> "w:" + windowsApp!!.id
            Kind.ADD_WINDOWS -> "add"
            Kind.COMPUTER -> "pc"
            Kind.FILE -> "f:" + file!!.name
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: WindowsAppStore
    private lateinit var desktopPins: DesktopPins
    private lateinit var taskbarPins: DesktopPins

    private lateinit var desktop: DesktopGrid
    private lateinit var taskbarApps: LinearLayout
    private lateinit var startAdapter: ItemAdapter
    private lateinit var startMenu: FrameLayout
    private lateinit var startPanel: LinearLayout
    private lateinit var search: EditText
    private lateinit var versionLabel: TextView

    /** Все приложения (для меню «Пуск»). */
    private var allItems: List<Item> = emptyList()
    private var desktopItems: Map<String, Item> = emptyMap()
    private var loadGeneration = 0

    private lateinit var rootFrame: FrameLayout
    private var boot: BootScreen? = null
    private var biosFromMenu: BiosView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WindowsAppStore(this)
        desktopPins = DesktopPins(this)
        taskbarPins = DesktopPins(this, prefsName = "taskbar", defaultLimit = 4)
        startAdapter = ItemAdapter()
        setContentView(buildLayout())
        // «Включение» AleksOS: проверка оборудования → (BIOS) → заставка → рабочий стол
        if (savedInstanceState == null && SystemSettings(this).shouldShowBoot()) {
            boot = BootScreen(this, rootFrame) {
                boot = null
                Updater.autoCheck(this)
            }.also { it.start() }
        }
    }

    override fun onResume() {
        super.onResume()
        versionLabel.text = "AleksOS ${Updater.currentVersionName(this)}"
        reload()
        if (boot == null) Updater.autoCheck(this)
    }

    /** Нажатие «Домой», когда лаунчер уже открыт: закрыть меню «Пуск». */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        closeStartMenu()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        boot?.let { if (it.isBiosOpen) it.onBack(); return }
        biosFromMenu?.let { it.onBack(); return }
        // На рабочем столе «Назад» только закрывает меню «Пуск».
        if (startMenu.visibility == View.VISIBLE) closeStartMenu()
    }

    // ---------- Интерфейс ----------

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(0x1A000000) }
        rootFrame = root
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        desktop = DesktopGrid(this).apply {
            setPadding(dp(4), dp(8), dp(4), 0)
            onOpen = { key -> desktopItems[key]?.let { onItemClick(it) } }
            onMenu = { key -> desktopItems[key]?.let { onItemLongClick(it) } }
            isLongClickable = true
            setOnLongClickListener { showDesktopMenu(); true }   // долгое нажатие на пустое место
        }
        startMenu = buildStartMenu()

        val desktopArea = FrameLayout(this)
        desktopArea.addView(desktop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        desktopArea.addView(startMenu, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(desktopArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(buildTaskbar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        root.addView(content)

        // Не залезать под статус-бар и панель навигации.
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                content.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                content.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
        return root
    }

    private fun buildTaskbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.TASKBAR, dp(16).toFloat(), Ui.BORDER, 1)
            setPadding(dp(6), 0, dp(6), 0)
        }

        // Кнопка «Пуск»
        bar.addView(taskbarButton(LogoDrawable(), dp(28)) { toggleStartMenu(focusSearch = false) })
        // Поиск
        bar.addView(TextView(this).apply {
            text = "⌕"
            textSize = 22f
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            Ui.pressable(this, null, dp(10).toFloat())
            setOnClickListener { toggleStartMenu(focusSearch = true) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        // Закреплённые приложения
        taskbarApps = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(taskbarApps)

        val wrapper = FrameLayout(this).apply { setPadding(dp(8), dp(2), dp(8), dp(6)) }
        wrapper.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return wrapper
    }

    private fun taskbarButton(icon: Drawable, size: Int, onClick: () -> Unit): View {
        val frame = FrameLayout(this).apply {
            Ui.pressable(this, null, dp(10).toFloat())
            setOnClickListener { onClick() }
        }
        frame.addView(ImageView(this).apply { setImageDrawable(icon) },
            FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        frame.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        return frame
    }

    private fun buildStartMenu(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            visibility = View.GONE
            setOnClickListener { closeStartMenu() }   // нажатие мимо меню закрывает его
        }

        startPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.PANEL, dp(16).toFloat(), Ui.BORDER, 1)
            elevation = dp(12).toFloat()
            setPadding(dp(12), dp(12), dp(12), dp(10))
            isClickable = true  // чтобы нажатия внутри меню не закрывали его
        }

        startPanel.addView(TextView(this).apply {
            text = "Все приложения"
            setTextColor(Ui.TEXT)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, 0, dp(8))
        })

        val grid = GridView(this).apply {
            numColumns = 3
            verticalSpacing = dp(6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = startAdapter
            setOnItemClickListener { _, _, pos, _ ->
                val item = startAdapter.getItem(pos)
                closeStartMenu()
                onItemClick(item)
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                onItemLongClick(startAdapter.getItem(pos))
                true
            }
        }
        startPanel.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        search = EditText(this).apply {
            hint = "⌕  Поиск"
            setHintTextColor(Ui.TEXT_DIM)
            setTextColor(Ui.TEXT)
            textSize = 14f
            isSingleLine = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = Ui.rounded(0xFFFFFFFF.toInt(), dp(18).toFloat(), Ui.BORDER, 1)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
        }
        startPanel.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // Нижняя строка меню: версия и быстрые кнопки
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        bottom.addView(ImageView(this).apply { setImageDrawable(LogoDrawable()) },
            LinearLayout.LayoutParams(dp(18), dp(18)))
        versionLabel = TextView(this).apply {
            setTextColor(Ui.TEXT_DIM)
            textSize = 11f
            setPadding(dp(6), 0, 0, 0)
            maxLines = 1
        }
        bottom.addView(versionLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(menuButton("+ .exe") { closeStartMenu(); pickWindowsProgram() })
        bottom.addView(menuButton("⟳") { closeStartMenu(); Updater.manualCheck(this) })
        bottom.addView(menuButton("BIOS") { closeStartMenu(); openBios() })
        bottom.addView(menuButton("⚙") {
            closeStartMenu()
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        startPanel.addView(bottom)

        val screen = resources.displayMetrics
        val width = minOf((screen.widthPixels * 0.82f).toInt(), dp(380))
        val height = (screen.heightPixels * 0.6f).toInt()
        overlay.addView(startPanel, FrameLayout.LayoutParams(width, height, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(8), 0, 0, dp(6))
        })
        return overlay
    }

    private fun menuButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Ui.TEXT)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        Ui.pressable(this, Ui.rounded(Ui.FIELD, dp(8).toFloat()), dp(8).toFloat())
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(6) }
    }

    private fun toggleStartMenu(focusSearch: Boolean) {
        if (startMenu.visibility == View.VISIBLE && !focusSearch) {
            closeStartMenu()
            return
        }
        if (startMenu.visibility != View.VISIBLE) {
            startMenu.visibility = View.VISIBLE
            startPanel.translationY = dp(40).toFloat()
            startPanel.alpha = 0f
            startPanel.animate().translationY(0f).alpha(1f).setDuration(150).start()
        }
        if (focusSearch) {
            search.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeStartMenu() {
        if (startMenu.visibility != View.VISIBLE) return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
        search.setText("")
        search.clearFocus()
        startPanel.animate().translationY(dp(40).toFloat()).alpha(0f).setDuration(110)
            .withEndAction { startMenu.visibility = View.GONE }.start()
    }

    /** BIOS из меню «Пуск» (без перезагрузки). */
    private fun openBios() {
        if (biosFromMenu != null) return
        val view = BiosView(this) {
            biosFromMenu?.let { rootFrame.removeView(it) }
            biosFromMenu = null
            Ui.setFullscreen(this, false)
        }
        Ui.setFullscreen(this, true)
        biosFromMenu = view
        rootFrame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Меню по долгому нажатию на пустое место рабочего стола. */
    private fun showDesktopMenu() {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "Создать папку" to { withFileAccess { FileMenu.createFolder(this, FileOps.desktopDir) { reload() } } }
        if (FileOps.clipboard != null) actions += "Вставить" to { withFileAccess { FileMenu.paste(this, FileOps.desktopDir) { reload() } } }
        actions += "Обновить" to { reload() }
        actions += "Добавить Windows-программу" to { pickWindowsProgram() }
        actions += "Открыть «Этот телефон»" to { ExplorerActivity.open(this) }
        actions += "Сменить обои" to { safeStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Обои")) }
        actions += "Параметры экрана" to { safeStart(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        AosDialog(this)
            .title("Рабочий стол")
            .items(actions.map { it.first }) { actions[it].second() }
            .show()
    }

    /** Выполнить действие с файлами, а если доступа нет — попросить его. */
    private fun withFileAccess(action: () -> Unit) {
        if (FileOps.hasAccess(this)) action() else FileOps.requestAccess(this)
    }

    // ---------- Загрузка списков ----------

    private fun reload() {
        val generation = ++loadGeneration
        val windowsApps = store.load()
        executor.execute {
            val androidItems = loadAndroidApps()
            val pinnedDesktop = desktopPins.load()
            val pinnedTaskbar = taskbarPins.load()
            // Файлы и папки из папки «Desktop» в памяти телефона
            val desktopFiles = if (FileOps.hasAccess(this)) {
                runCatching { FileOps.ensureStructure() }
                FileOps.desktopDir.listFiles()
                    ?.filter { !it.name.startsWith(".") }
                    ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
            } else emptyList()
            runOnUiThread {
                if (isDestroyed || generation != loadGeneration) return@runOnUiThread
                val windowsItems = windowsApps.sortedBy { it.name.lowercase() }.map {
                    Item(Kind.WINDOWS, it.name, TileIconDrawable.forWindowsApp(it.name), windowsApp = it)
                }
                val byComponent = androidItems.associateBy { it.component }

                // Рабочий стол: закреплённые приложения и Windows-программы.
                // Добавить программу можно через меню рабочего стола или «Пуск».
                val onDesktop = ArrayList<Item>()
                onDesktop += Item(Kind.COMPUTER, "Этот телефон", FileIconDrawable.phone())
                pinnedDesktop.mapNotNullTo(onDesktop) { byComponent[it] }
                onDesktop += windowsItems
                desktopFiles.mapTo(onDesktop) {
                    Item(Kind.FILE, it.name, FileIconDrawable.forEntry(it), file = it)
                }
                desktopItems = onDesktop.associateBy { it.key }
                desktop.setEntries(onDesktop.map { it.key to makeCell(it, darkText = false) })

                // Панель задач
                taskbarApps.removeAllViews()
                if (ExplorerActivity.isOpen) {
                    // открытое (или свёрнутое) окно «Этот телефон» — как значок программы на панели задач
                    taskbarApps.addView(taskbarButton(FileIconDrawable.phone(), dp(30)) { ExplorerActivity.open(this) }.apply {
                        foreground = Ui.rounded(0, dp(10).toFloat(), Ui.ACCENT_TEXT, dp(2))
                    })
                }
                pinnedTaskbar.mapNotNull { byComponent[it] }.forEach { item ->
                    taskbarApps.addView(taskbarButton(item.icon, dp(30)) { onItemClick(item) }.apply {
                        setOnLongClickListener { onItemLongClick(item); true }
                    })
                }

                // Меню «Пуск»: всё по алфавиту.
                allItems = (windowsItems + androidItems).sortedBy { it.label.lowercase() }
                applyFilter()
            }
        }
    }

    private fun loadAndroidApps(): List<Item> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return activities
            .filter { it.activityInfo.packageName != packageName }
            .map { info ->
                Item(
                    kind = Kind.ANDROID,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm),
                    component = ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun applyFilter() {
        val query = search.text.toString().trim().lowercase()
        startAdapter.items = if (query.isEmpty()) allItems
        else allItems.filter { it.label.lowercase().contains(query) }
    }

    // ---------- Нажатия ----------

    private fun onItemClick(item: Item) {
        when (item.kind) {
            Kind.ANDROID -> {
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(item.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    toast("Не удалось открыть ${item.label}")
                    reload()
                } catch (e: SecurityException) {
                    toast("Нет доступа к ${item.label}")
                }
            }
            Kind.WINDOWS -> item.windowsApp?.let { WindowsEngine.launch(this, it) }
            Kind.ADD_WINDOWS -> pickWindowsProgram()
            Kind.COMPUTER -> ExplorerActivity.open(this)
            Kind.FILE -> item.file?.let { openFile(it) }
        }
    }

    private fun openFile(file: File) {
        if (file.isDirectory) {
            ExplorerActivity.open(this, file)
            return
        }
        try {
            FileOps.open(this, file)
        } catch (_: Exception) {
            toast("Нет приложения, чтобы открыть этот файл")
        }
    }

    private fun onItemLongClick(item: Item) {
        when (item.kind) {
            Kind.ANDROID -> {
                val component = item.component ?: return
                val onDesk = desktopPins.isPinned(component)
                val onBar = taskbarPins.isPinned(component)
                AosDialog(this)
                    .title(item.label)
                    .items(listOf(
                        "Открыть",
                        if (onDesk) "Убрать с рабочего стола" else "Закрепить на рабочем столе",
                        if (onBar) "Открепить от панели задач" else "Закрепить на панели задач",
                        "Свойства",
                    )) { which ->
                        when (which) {
                            0 -> onItemClick(item)
                            1 -> { if (onDesk) desktopPins.unpin(component) else desktopPins.pin(component); reload() }
                            2 -> { if (onBar) taskbarPins.unpin(component) else taskbarPins.pin(component); reload() }
                            3 -> safeStart(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${component.packageName}"))
                            )
                        }
                    }
                    .show()
            }
            Kind.WINDOWS -> {
                val app = item.windowsApp ?: return
                AosDialog(this)
                    .title(app.name)
                    .items(listOf("Открыть", "Убрать из AleksOS")) { which ->
                        if (which == 0) {
                            onItemClick(item)
                            return@items
                        }
                        store.remove(app.id)
                        try {
                            contentResolver.releasePersistableUriPermission(
                                Uri.parse(app.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: SecurityException) {
                        }
                        reload()
                    }
                    .show()
            }
            Kind.ADD_WINDOWS -> pickWindowsProgram()
            Kind.COMPUTER -> AosDialog(this)
                .title("Этот телефон")
                .items(listOf("Открыть", "Свойства")) { which ->
                    if (which == 0) ExplorerActivity.open(this)
                    else safeStart(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                }
                .show()
            Kind.FILE -> item.file?.let { FileMenu.show(this, it, ::openFile) { reload() } }
        }
    }

    // ---------- Добавление Windows-программы ----------

    private fun pickWindowsProgram() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_PICK_EXE)
        } catch (e: ActivityNotFoundException) {
            toast("Не найден файловый менеджер")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_EXE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        val fileName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Программа"
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in WINDOWS_EXTENSIONS) {
            toast("Это не Windows-программа. Нужен файл .exe, .msi, .bat или .lnk")
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Некоторые файловые менеджеры не дают постоянный доступ — сохраним ссылку всё равно.
        }
        val name = fileName.substringBeforeLast('.')
        store.add(name, uri.toString())
        toast("«$name» добавлена на рабочий стол")
        reload()
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }

    // ---------- Иконки ----------

    /** Иконка с подписью. На рабочем столе текст белый с тенью, в меню «Пуск» — тёмный. */
    private fun makeCell(item: Item?, darkText: Boolean): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(6), dp(2), dp(4))
            Ui.pressable(this, null, dp(12).toFloat())
        }
        val frame = FrameLayout(this)
        frame.addView(
            ImageView(this).apply { tag = TAG_ICON },
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
        )
        frame.addView(
            TextView(this).apply {
                tag = TAG_BADGE
                text = "WIN"
                textSize = 8f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dp(3), dp(1), dp(3), dp(1))
                background = Ui.rounded(Ui.ACCENT_2, dp(4).toFloat())
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        )
        cell.addView(frame, LinearLayout.LayoutParams(dp(54), dp(54)))
        cell.addView(
            TextView(this).apply {
                tag = TAG_LABEL
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                if (darkText) {
                    setTextColor(Ui.TEXT)
                } else {
                    setTextColor(Color.WHITE)
                    setShadowLayer(4f, 0f, 1f, Color.BLACK)
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        )
        item?.let { bindCell(cell, it) }
        return cell
    }

    private fun bindCell(cell: View, item: Item) {
        cell.findViewWithTag<ImageView>(TAG_ICON).setImageDrawable(item.icon)
        cell.findViewWithTag<TextView>(TAG_LABEL).text = item.label
        cell.findViewWithTag<TextView>(TAG_BADGE).visibility = if (item.kind == Kind.WINDOWS) View.VISIBLE else View.GONE
    }

    /** Сетка меню «Пуск». */
    private inner class ItemAdapter : BaseAdapter() {
        var items: List<Item> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView ?: makeCell(null, darkText = true).apply { background = null }
            bindCell(cell, items[position])
            return cell
        }
    }

    // ---------- Утилиты ----------

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast("Не удалось открыть")
        }
    }

    private fun dp(value: Int): Int = Ui.dp(this, value)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQUEST_PICK_EXE = 1001
        const val TAG_ICON = "icon"
        const val TAG_BADGE = "badge"
        const val TAG_LABEL = "label"
        val WINDOWS_EXTENSIONS = setOf("exe", "msi", "bat", "lnk")
    }
}
