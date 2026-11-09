package com.aleksos.launcher

import com.winlator.winhandler.WinHandler
import java.io.File
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.winlator.widget.XServerView
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xenvironment.components.ALSAServerComponent
import com.winlator.xserver.Pointer
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import com.winlator.xserver.Keyboard as XKeyboard
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Окно Windows-программы: свой X-сервер (из Winlator) + Wine внутри proot.
 * Пальцем: касание — щелчок левой кнопкой, долгое касание — правой,
 * перетаскивание — с зажатой левой кнопкой, два пальца — прокрутка.
 */
class WineActivity : Activity() {

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_COMMAND = "command"

        /** Название запущенной программы (null — ничего не запущено). */
        @Volatile var runningTitle: String? = null
            private set

        fun start(context: Context, title: String, command: String) {
            context.startActivity(Intent(context, WineActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_COMMAND, command)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        @Volatile private var instance: WineActivity? = null

        /**
         * Запустить ещё одну программу в уже открытом окне Windows (тот же Wine, тот же рабочий стол).
         * false — окно Windows не открыто.
         */
        fun launchInRunning(context: Context, title: String, command: String): Boolean {
            val act = instance ?: return false
            if (act.closing || act.isFinishing || act.environment == null) return false
            act.runOnUiThread { act.launchExtra(title, command) }
            bringToFront(context)
            return true
        }

        /** Вернуться к уже запущенной программе. */
        fun bringToFront(context: Context) {
            context.startActivity(Intent(context, WineActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var xServer: XServer
    private lateinit var xServerView: WineSurface
    private var environment: XEnvironment? = null
    private lateinit var status: TextView
    private var title = "Windows"
    private var startedAt = 0L
    private var closing = false

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF0B1220.toInt()
        window.navigationBarColor = Color.BLACK

        title = intent.getStringExtra(EXTRA_TITLE) ?: "Windows"
        val command = intent.getStringExtra(EXTRA_COMMAND)
        if (command == null) {
            finish(); return
        }

        instance = this
        val screen = screenSize()
        xServer = XServer(ScreenInfo(screen.first, screen.second))
        xServerView = WineSurface(this, xServer)
        val renderer = xServerView.renderer
        renderer.setCursorVisible(true)
        xServer.renderer = renderer

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(titleBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        val frame = FrameLayout(this)
        frame.addView(xServerView)
        status = TextView(this).apply {
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            text = "Запуск «$title»…\nПервый запуск может занять до минуты."
        }
        frame.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        contentFrame = frame
        setupTouch()
        setupKeyboardShift(root)

        runningTitle = title
        startedAt = System.currentTimeMillis()
        thread(name = "wine-start") { startWine(command, screen) }
    }

    /** Размер экрана Windows: «Авто» — по размеру окна, крупно (как 160 dpi), иначе из BIOS. */
    private fun screenSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        val choice = SystemSettings(this).valueOf(SystemSettings.RESOLUTION)
        Regex("(\\d+)×(\\d+)").find(choice)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        val scale = maxOf(1f, m.density / 1.6f)
        // место под рабочий стол Windows = окно без строки состояния, навигации и нашего заголовка
        var widthPx = m.widthPixels
        var heightPx = m.heightPixels - dp(44) - dp(24)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            widthPx = metrics.bounds.width() - bars.left - bars.right
            heightPx = metrics.bounds.height() - bars.top - bars.bottom - dp(44)
        }
        val w = (widthPx / scale).toInt()
        val h = (heightPx / scale).toInt()
        return (w - w % 2) to (h - h % 2)
    }

    private fun startWine(command: String, screen: Pair<Int, Int>) {
        try {
            WineRuntime.startLog(this, "$title: $command")
            WineRuntime.prepareDrives(this)
            val imageFs = ImageFs(EngineManager.rootfsDir(this))
            val rootPath = imageFs.rootDir.path
            val env = XEnvironment(this, imageFs)
            env.addComponent(SysVSharedMemoryComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)))
            env.addComponent(XServerComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)))
            // звук: ALSA внутри Wine → AAudio Android
            env.addComponent(ALSAServerComponent(UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH)))
            val launcher = GuestProgramLauncherComponent()
            desktopArg = "/desktop=shell,${screen.first}x${screen.second}"
            WineRuntime.writeLog("Экран Windows: ${screen.first}x${screen.second}, DirectX: ${SystemSettings(this).valueOf(SystemSettings.DIRECTX)}, Box64: ${SystemSettings(this).valueOf(SystemSettings.BOX64)}, вывод: ${SystemSettings(this).valueOf(SystemSettings.PRESENT)}")
            val handlerExe = File(imageFs.rootDir, "home/xuser/.wine/drive_c/windows/winhandler.exe")
            // winhandler.exe живёт внутри Wine и по команде из AleksOS запускает программы —
            // так все программы работают в одном Wine (отдельные процессы Wine не запускались)
            useHandler = handlerExe.isFile && execInWine(command)
            if (useHandler) {
                xServer.winHandler.start()
                launcher.setGuestExecutable("wine explorer $desktopArg winhandler.exe rundll32")
                main.postDelayed({
                    if (!closing && !isFinishing && !xServer.winHandler.isReady) {
                        WineRuntime.writeLog("winhandler не ответил — запуск отдельным процессом")
                        useHandler = false
                        launchProcess(title, command)
                    }
                }, 40_000)
            } else {
                launcher.setGuestExecutable("wine explorer $desktopArg $command")
            }
            configure(launcher)
            launcher.setTerminationCallback { code -> main.post { onWineExit(code) } }
            env.addComponent(launcher)
            environment = env
            env.startEnvironmentComponents()
            if (launcher.pid <= 0) {
                main.post { showError("Не удалось запустить Windows Kernel (proot не стартовал).") }
                return
            }
            // как только Wine нарисует первое окно — убираем надпись «Запуск…»
            main.postDelayed(object : Runnable {
                override fun run() {
                    if (closing || isFinishing) return
                    if (xServer.windowManager.rootWindow.children.isNotEmpty()) { status.visibility = View.GONE; return }
                    if (System.currentTimeMillis() - startedAt > 90_000) {
                        status.text = "«$title» долго не открывается.\nЖурнал Windows Kernel: кнопка ☰ → «Журнал Windows Kernel»."
                        return
                    }
                    main.postDelayed(this, 500)
                }
            }, 500)
        } catch (e: Throwable) {
            WineRuntime.writeLog("Ошибка AleksOS: $e")
            main.post { showError("Ошибка запуска: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private fun configure(launcher: GuestProgramLauncherComponent) {
        launcher.setEnvVars(WineRuntime.envVars(this))
        launcher.setBox64EnvVars(WineRuntime.box64EnvVars(this))
        launcher.setBindingPaths(arrayOf(FileOps.storageRoot.absolutePath))
        // рабочая папка — AleksOS (в Windows это диск D:)
        if (FileOps.aleksosDir.isDirectory) launcher.setCwd(FileOps.aleksosDir.absolutePath)
    }

    /** Ещё одна программа в этом же окне Windows: отдельный процесс, тот же Wine. */
    private fun launchExtra(newTitle: String, command: String) {
        val env = environment ?: return
        title = newTitle
        titleView?.text = newTitle
        WineRuntime.writeLog("=== AleksOS: ещё одна программа — $newTitle: $command ===")
        if (useHandler) {
            if (!execInWine(command)) launchProcess(newTitle, command)
        } else launchProcess(newTitle, command)
    }

    @Volatile private var useHandler = false

    /** Передать команду winhandler.exe внутри Wine. false — команда слишком длинная. */
    private fun execInWine(command: String): Boolean {
        val (exe, params) = WineRuntime.toExec(command)
        if (exe.isEmpty() || !WinHandler.fits(exe, params)) return false
        WineRuntime.writeLog("winhandler: $exe $params")
        xServer.winHandler.exec(exe, params)
        return true
    }

    /** Запуск отдельным процессом Wine (запасной путь). */
    private fun launchProcess(newTitle: String, command: String) {
        val env = environment ?: return
        thread(name = "wine-extra") {
            runCatching {
                val extra = GuestProgramLauncherComponent()
                // через тот же рабочий стол «shell»: Wine видит, что он уже открыт, и просто
                // запускает программу в нём (без этого программа уходила на невидимый рабочий стол)
                extra.setGuestExecutable("wine explorer $desktopArg $command")
                configure(extra)
                extra.setEnvironment(env)
                extra.setTerminationCallback { code -> WineRuntime.writeLog("=== $newTitle: процесс завершился, код $code ===") }
                extra.start()
                if (extra.pid <= 0) main.post { showError("Не удалось запустить «$newTitle».") }
                synchronized(extras) { extras += extra }
            }.onFailure { WineRuntime.writeLog("Ошибка запуска: $it") }
        }
    }

    private var contentFrame: FrameLayout? = null
    private var lastTouchY = 0f

    /**
     * Экранная клавиатура не сжимает рабочий стол Windows, а закрывает его низ.
     * Если нажатое место оказалось под клавиатурой — сдвигаем рабочий стол вверх.
     */
    private fun setupKeyboardShift(root: View) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        root.setOnApplyWindowInsetsListener { v, insets ->
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            val nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            val frame = contentFrame
            if (frame != null) {
                val covered = (ime - nav).coerceAtLeast(0)
                val visible = frame.height - covered
                val shift = if (covered == 0) 0f else (lastTouchY + dp(90) - visible).coerceIn(0f, covered.toFloat())
                frame.animate().translationY(-shift).setDuration(150).start()
            }
            v.onApplyWindowInsets(insets)
        }
    }

    private val extras = ArrayList<GuestProgramLauncherComponent>()
    @Volatile private var desktopArg = "/desktop=shell"
    private var titleView: TextView? = null

    private fun onWineExit(code: Int) {
        WineRuntime.writeLog("=== Wine завершился, код $code ===")
        runningTitle = null
        if (instance === this) instance = null
        if (closing || isFinishing) return
        val quick = System.currentTimeMillis() - startedAt < 15_000
        if (quick || code != 0) showError("Программа закрылась (код $code).")
        else finishAndRemoveTask()
    }

    private fun showError(message: String) {
        if (isFinishing) return
        status.visibility = View.VISIBLE
        status.text = message
        AosDialog(this)
            .title(title)
            .message("$message\n\nЖурнал Windows Kernel поможет найти причину.")
            .button("Закрыть") { close() }
            .button("Журнал", primary = true) { showLog() }
            .show()
    }

    private fun showLog() {
        val text = TextView(this).apply {
            this.text = WineRuntime.readLogTail(this@WineActivity, 120)
            setTextColor(Ui.TEXT)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        AosDialog(this)
            .title("Журнал Windows Kernel")
            .view(scroll.apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)) })
            .button("Закрыть", primary = true)
            .show()
    }

    // ---------- Заголовок окна ----------

    private fun titleBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0B1220.toInt())
            setPadding(dp(10), 0, dp(4), 0)
        }
        bar.addView(ImageView(this).apply { setImageDrawable(LogoDrawable()) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        bar.addView(TextView(this).also { titleView = it }.apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barButton("⌨") { toggleKeyboard() })
        bar.addView(barButton("☰") { showMenu() })
        bar.addView(barButton("—") { moveTaskToBack(true) })
        bar.addView(barButton("✕") { confirmClose() })
        return bar
    }

    private fun barButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 17f
        gravity = Gravity.CENTER
        Ui.pressable(this, null, dp(8).toFloat())
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
    }

    private fun showMenu() {
        val actions = listOf<Pair<String, () -> Unit>>(
            "Клавиатура" to { toggleKeyboard() },
            "Esc" to { tapKey(KeyEvent.KEYCODE_ESCAPE) },
            "Enter" to { tapKey(KeyEvent.KEYCODE_ENTER) },
            "Журнал Windows Kernel" to { showLog() },
            "Закрыть программу" to { confirmClose() },
        )
        AosDialog(this).title(title).items(actions.map { it.first }) { actions[it].second() }.show()
    }

    private fun confirmClose() {
        AosDialog(this)
            .title(title)
            .message("Закрыть программу? Несохранённые данные пропадут.")
            .button("Отмена")
            .button("Закрыть", primary = true) { close() }
            .show()
    }

    private fun close() {
        closing = true
        runningTitle = null
        thread { stopAll() }
        finishAndRemoveTask()
    }

    private fun stopAll() {
        runCatching { xServer.winHandler.stop() }
        synchronized(extras) { extras.forEach { runCatching { it.stop() } }; extras.clear() }
        runCatching { environment?.stopEnvironmentComponents() }
        if (instance === this) instance = null
    }

    override fun onBackPressed() {
        // «Назад» — как Esc в Windows-программе
        tapKey(KeyEvent.KEYCODE_ESCAPE)
    }

    override fun onResume() {
        super.onResume()
        if (::xServerView.isInitialized) xServerView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::xServerView.isInitialized) xServerView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!closing && isFinishing) {
            closing = true
            runningTitle = null
            thread { stopAll() }
        }
    }

    // ---------- Клавиатура ----------

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        xServerView.requestFocus()
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun tapKey(keyCode: Int) {
        if (!::xServer.isInitialized) return
        xServer.keyboard.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        xServer.keyboard.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::xServer.isInitialized && event.keyCode != KeyEvent.KEYCODE_BACK &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (handleKeyEvent(xServer, event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Касания → мышь ----------

    private fun setupTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val longPressMs = 450L
        var downX = 0f; var downY = 0f
        var dragging = false
        var longDone = false
        var scrolling = false
        var scrollAcc = 0f
        var lastY = 0f
        val longPress = Runnable {
            longDone = true
            click(Pointer.Button.BUTTON_RIGHT)
        }
        xServerView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    lastTouchY = e.y
                    dragging = false; longDone = false; scrolling = false
                    moveTo(e.x, e.y)
                    main.postDelayed(longPress, longPressMs)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    main.removeCallbacks(longPress)
                    if (dragging) { xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT); dragging = false }
                    scrolling = true; scrollAcc = 0f; lastY = e.getY(0)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scrolling) {
                        scrollAcc += e.getY(0) - lastY; lastY = e.getY(0)
                        val step = dp(40).toFloat()
                        while (abs(scrollAcc) >= step) {
                            val b = if (scrollAcc > 0) Pointer.Button.BUTTON_SCROLL_UP else Pointer.Button.BUTTON_SCROLL_DOWN
                            click(b)
                            scrollAcc -= if (scrollAcc > 0) step else -step
                        }
                    } else if (!longDone) {
                        if (!dragging && hypot(e.x - downX, e.y - downY) > slop) {
                            main.removeCallbacks(longPress)
                            dragging = true
                            moveTo(downX, downY)
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                        }
                        if (dragging) moveTo(e.x, e.y)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    when {
                        scrolling || longDone -> {}
                        dragging -> { moveTo(e.x, e.y); xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT) }
                        else -> click(Pointer.Button.BUTTON_LEFT)
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (dragging) xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
                    dragging = false
                }
            }
            true
        }
    }

    private fun moveTo(viewX: Float, viewY: Float) {
        val t = xServerView.renderer.viewTransformation
        if (t.aspect <= 0f) return
        val x = ((viewX - t.viewOffsetX) / t.aspect).toInt().coerceIn(0, xServer.screenInfo.width - 1)
        val y = ((viewY - t.viewOffsetY) / t.aspect).toInt().coerceIn(0, xServer.screenInfo.height - 1)
        xServer.injectPointerMove(x, y)
    }

    private fun click(button: Pointer.Button) {
        xServer.injectPointerButtonPress(button)
        xServer.injectPointerButtonRelease(button)
    }

    /** Поверхность X-сервера, которая умеет принимать текст с экранной клавиатуры. */
    private inner class WineSurface(context: Context, private val server: XServer) : XServerView(context, server) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onCheckIsTextEditor() = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            // «видимый пароль»: клавиатура не подсказывает слова и отдаёт буквы сразу по одной
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            return object : BaseInputConnection(this, false) {
                private var composing = ""

                override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                    replaceComposing(text.toString()); composing = ""
                    return true
                }

                override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                    replaceComposing(text.toString()); composing = text.toString()
                    return true
                }

                override fun finishComposingText(): Boolean { composing = ""; return true }

                /** Набираемое слово: стираем лишнее и допечатываем разницу. */
                private fun replaceComposing(text: String) {
                    val common = composing.commonPrefixWith(text).length
                    repeat(composing.length - common) { key(KeyEvent.KEYCODE_DEL) }
                    typeText(server, text.substring(common))
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(maxOf(1, beforeLength)) { key(KeyEvent.KEYCODE_DEL) }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    handleKeyEvent(server, event)
                    return true
                }

                override fun performEditorAction(actionCode: Int): Boolean {
                    key(KeyEvent.KEYCODE_ENTER)
                    return true
                }

                private fun key(code: Int) {
                    server.keyboard.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    server.keyboard.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
            }
        }
    }

    /** Напечатать текст: латиница и знаки — обычными клавишами, русские буквы — своими. */
    private fun typeText(server: XServer, text: String) {
        val charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        for (ch in text) {
            when {
                ch == '\n' -> tapKey(KeyEvent.KEYCODE_ENTER)
                XKeyboard.isCyrillic(ch) -> server.injectCyrillicChar(ch)
                else -> charMap.getEvents(charArrayOf(ch))?.forEach { server.keyboard.onKeyEvent(it) }
            }
        }
    }

    /** Клавиша с клавиатуры: русская буква (по символу) или обычная клавиша. */
    private fun handleKeyEvent(server: XServer, event: KeyEvent): Boolean {
        val ch = event.unicodeChar.toChar()
        if (event.unicodeChar != 0 && XKeyboard.isCyrillic(ch)) {
            if (event.action == KeyEvent.ACTION_DOWN) server.injectCyrillicChar(ch)
            return true
        }
        return server.keyboard.onKeyEvent(event)
    }
}
