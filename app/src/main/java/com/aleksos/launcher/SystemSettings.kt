package com.aleksos.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import java.io.File

/**
 * Настройки «BIOS» AleksOS. Хранятся в SharedPreferences.
 * Параметры ядра (память, ядра, графика…) будут использоваться, когда подключим Wine.
 */
class SystemSettings(context: Context) {

    /** Один параметр: ключ, название, варианты значений и значение по умолчанию (индекс). */
    class Option(val key: String, val title: String, val hint: String, val values: List<String>, val default: Int)

    private val prefs = context.getSharedPreferences("bios", Context.MODE_PRIVATE)

    fun get(option: Option): Int = prefs.getInt(option.key, option.default).coerceIn(0, option.values.size - 1)
    fun valueOf(option: Option): String = option.values[get(option)]

    /** Сохранить сразу несколько значений (кнопка «Сохранить и выйти»). */
    fun saveAll(values: Map<String, Int>) {
        val e = prefs.edit()
        values.forEach { (k, v) -> e.putInt(k, v) }
        e.apply()
    }

    // ---------- Когда показывать загрузку ----------

    /** Показывать ли загрузку сейчас (вызывается при запуске лаунчера). */
    fun shouldShowBoot(): Boolean {
        val mode = get(BOOT_MODE)
        if (mode == 2) return false                 // никогда
        if (mode == 1) return true                  // каждый запуск лаунчера
        // при включении телефона: момент включения = текущее время − время с момента включения
        val bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val last = prefs.getLong(KEY_LAST_BOOT, 0)
        return Math.abs(bootedAt - last) > 60_000   // другое включение телефона
    }

    fun markBootShown() {
        prefs.edit().putLong(KEY_LAST_BOOT, System.currentTimeMillis() - SystemClock.elapsedRealtime()).apply()
    }

    companion object {
        private const val KEY_LAST_BOOT = "last_boot"

        // Ядро
        val MEMORY = Option("memory", "Память для Windows-программ", "Сколько ОЗУ может занять Windows Kernel",
            listOf("2 ГБ", "4 ГБ", "6 ГБ", "8 ГБ"), 1)
        val CPU_CORES = Option("cores", "Ядра процессора", "Сколько ядер отдавать Windows-программам",
            listOf("Все", "Только быстрые", "4 ядра", "2 ядра"), 0)
        val BOX64 = Option("box64", "Режим Box64", "Быстрее — для игр, стабильнее — если программа вылетает",
            listOf("Быстрее", "Сбалансированно", "Стабильнее"), 1)
        val BOX64_VERSION = Option("box64ver", "Версия Box64", "Новая переводит AVX2 — нужна новым играм (ставится в «Компонентах Windows»)",
            listOf("0.2.9 (стабильная)", "0.4.4 (новые игры, AVX2)"), 0)
        val WINDOWS_VERSION = Option("winver", "Версия Windows", "Какой Windows притворяется Wine",
            listOf("Windows XP", "Windows 7", "Windows 10"), 2)

        // Графика
        val GPU_DRIVER = Option("gpu2", "Графический драйвер", "Новый — Turnip и Zink из Mesa 25 (ставится в «Компонентах Windows»)",
            listOf("Turnip 24.1 + Zink 22 (стабильный)", "Turnip + Zink 25.0 (новый)"), 0)
        val DIRECTX = Option("directx", "DirectX", "DXVK — быстрее в играх, WineD3D — совместимее",
            listOf("DXVK", "WineD3D"), 0)
        val RESOLUTION = Option("resolution", "Разрешение экрана", "Для старых игр лучше 800×600",
            listOf("Авто", "640×480", "800×600", "1024×768", "1280×720", "1920×1080"), 0)
        val FPS_LIMIT = Option("fps", "Ограничение кадров", "Меньше — телефон меньше греется",
            listOf("Без ограничения", "30 кадров", "60 кадров"), 0)
        val AUDIO = Option("audio", "Звук", "PulseAudio — ровнее (сам держит ритм и запас), ALSA — прямой вывод",
            listOf("PulseAudio (ровнее)", "ALSA (прямой)"), 0)
        val PRESENT = Option("present", "Вывод кадров", "Если картинка игры видна кусками — «Совместимый»",
            listOf("Совместимый", "DRI3 (быстрее)"), 0)

        // Загрузка
        val BOOT_MODE = Option("boot_mode", "Показ загрузки", "Когда показывать загрузку AleksOS",
            listOf("При включении телефона", "Каждый запуск", "Никогда"), 0)
        val BIOS_WAIT = Option("bios_wait", "Ожидание кнопки BIOS", "Сколько секунд показывать экран проверки",
            listOf("2 секунды", "3 секунды", "5 секунд"), 1)
        val SPLASH = Option("splash", "Заставка AleksOS", "Картинка загрузки после проверки",
            listOf("Включена", "Выключена"), 0)

        val ENGINE_OPTIONS = listOf(MEMORY, CPU_CORES, BOX64, BOX64_VERSION, WINDOWS_VERSION)
        val GRAPHICS_OPTIONS = listOf(GPU_DRIVER, DIRECTX, RESOLUTION, FPS_LIMIT, PRESENT, AUDIO)
        val BOOT_OPTIONS = listOf(BOOT_MODE, BIOS_WAIT, SPLASH)
        val ALL = ENGINE_OPTIONS + GRAPHICS_OPTIONS + BOOT_OPTIONS

        fun biosWaitMillis(settings: SystemSettings) = when (settings.get(BIOS_WAIT)) {
            0 -> 2000L
            2 -> 5000L
            else -> 3000L
        }
    }
}

/** Сведения о «железе» телефона для экрана проверки и вкладки «Главная» в BIOS. */
object HardwareInfo {

    class Line(val label: String, val value: String, val ok: Boolean = true)

    @Volatile private var gpuCache: String? = null

    fun collect(context: Context): List<Line> {
        val lines = ArrayList<Line>()
        lines += Line("Версия BIOS", BiosVersion.full(context))
        lines += Line("Устройство", deviceName())
        lines += Line("Процессор", cpu())
        lines += Line("Оперативная память", ram(context))
        lines += Line("Видеокарта", gpu())
        lines += Line("Накопитель", storage())
        lines += Line("Экран", screen(context))
        lines += Line("Батарея", battery(context))
        lines += Line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        lines += Line("Windows Kernel", if (WindowsEngine.isAvailable(context)) "готов" else "не установлен", ok = WindowsEngine.isAvailable(context))
        return lines
    }

    /** «Motorola edge 40 pro», а не «Motorola motorola edge 40 pro». */
    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val full = if (model.startsWith(Build.MANUFACTURER, ignoreCase = true)) model else "$maker $model"
        return full.replaceFirstChar { it.uppercase() }
    }

    private fun cpu(): String {
        val cores = Runtime.getRuntime().availableProcessors()
        val maxMhz = (0 until cores).maxOfOrNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000 }
                .getOrDefault(0L)
        } ?: 0L
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val name = socName(Build.SOC_MODEL)
            "${Build.SOC_MANUFACTURER} $name".trim()
        } else Build.HARDWARE
        val freq = if (maxMhz > 0) String.format(java.util.Locale.US, ", до %.2f ГГц", maxMhz / 1000.0) else ""
        return "$soc, ядер: $cores$freq"
    }

    /** Понятные названия известных процессоров. */
    private fun socName(model: String): String = when (model.uppercase()) {
        "SM8550" -> "Snapdragon 8 Gen 2 (SM8550)"
        "SM8650" -> "Snapdragon 8 Gen 3 (SM8650)"
        "SM8750" -> "Snapdragon 8 Elite (SM8750)"
        "SM8475" -> "Snapdragon 8+ Gen 1 (SM8475)"
        "SM8450" -> "Snapdragon 8 Gen 1 (SM8450)"
        else -> model
    }

    private fun ram(context: Context): String {
        val mi = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val totalGb = Math.round(mi.totalMem / 1_000_000_000.0)   // как пишут производители: 12 ГБ
        return "$totalGb ГБ, свободно ${FileOps.formatSize(mi.availMem)}"
    }

    /** Название видеокарты через короткий запуск OpenGL (без окна). */
    fun gpu(): String {
        gpuCache?.let { return it }
        val name = runCatching {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(display, ver, 0, ver, 1)
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
            ), 0, configs, 0, 1, num, 0)
            val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surface, surface, context)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "неизвестно"
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            renderer
        }.getOrDefault("неизвестно")
        gpuCache = name
        return name
    }

    private fun storage(): String = runCatching {
        val stat = StatFs(FileOps.storageRoot.path)
        "${FileOps.formatSize(stat.totalBytes)}, свободно ${FileOps.formatSize(stat.availableBytes)}"
    }.getOrDefault("неизвестно")

    private fun screen(context: Context): String {
        val m = context.resources.displayMetrics
        val w = minOf(m.widthPixels, m.heightPixels)
        val h = maxOf(m.widthPixels, m.heightPixels)
        val hz = runCatching {
            context.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
                .supportedModes.maxOf { it.refreshRate }
        }.getOrDefault(60f)
        return "${w}×${h}, до ${Math.round(hz)} Гц"
    }

    private fun battery(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "неизвестно"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "${level * 100 / scale}%" + (if (charging) ", заряжается" else "") + ", ${temp}°C"
    }
}
