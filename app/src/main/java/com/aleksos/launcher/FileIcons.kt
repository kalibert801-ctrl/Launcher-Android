package com.aleksos.launcher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.io.File

/** Рисованные иконки AleksOS для папок, файлов, дисков и «Этого телефона». */
class FileIconDrawable private constructor(
    private val type: Type,
    private val label: String = "",
    private val color: Int = 0,
) : Drawable() {

    enum class Type { FOLDER, FILE, DRIVE, PHONE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        when (type) {
            Type.FOLDER -> {
                // задняя стенка с «ушком»
                paint.shader = null
                paint.color = 0xFFE0A526.toInt()
                val back = Path().apply {
                    moveTo(l + w * 0.08f, t + h * 0.22f)
                    lineTo(l + w * 0.40f, t + h * 0.22f)
                    lineTo(l + w * 0.48f, t + h * 0.30f)
                    lineTo(l + w * 0.92f, t + h * 0.30f)
                    lineTo(l + w * 0.92f, t + h * 0.80f)
                    lineTo(l + w * 0.08f, t + h * 0.80f)
                    close()
                }
                canvas.drawPath(back, paint)
                // передняя створка
                paint.shader = LinearGradient(l, t + h * 0.36f, l, t + h * 0.82f,
                    0xFFFFD45C.toInt(), 0xFFF5B83A.toInt(), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(l + w * 0.08f, t + h * 0.36f, l + w * 0.92f, t + h * 0.82f),
                    w * 0.05f, w * 0.05f, paint)
            }
            Type.FILE -> {
                paint.shader = null
                paint.color = 0xFFF8FAFC.toInt()
                val page = Path().apply {
                    moveTo(l + w * 0.22f, t + h * 0.08f)
                    lineTo(l + w * 0.62f, t + h * 0.08f)
                    lineTo(l + w * 0.80f, t + h * 0.26f)
                    lineTo(l + w * 0.80f, t + h * 0.92f)
                    lineTo(l + w * 0.22f, t + h * 0.92f)
                    close()
                }
                canvas.drawPath(page, paint)
                paint.color = 0xFFCBD5E1.toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.025f
                canvas.drawPath(page, paint)
                paint.style = Paint.Style.FILL
                // загнутый уголок
                val corner = Path().apply {
                    moveTo(l + w * 0.62f, t + h * 0.08f)
                    lineTo(l + w * 0.62f, t + h * 0.26f)
                    lineTo(l + w * 0.80f, t + h * 0.26f)
                    close()
                }
                canvas.drawPath(corner, paint)
                // цветная метка с расширением
                if (label.isNotEmpty()) {
                    paint.color = color
                    canvas.drawRoundRect(RectF(l + w * 0.12f, t + h * 0.52f, l + w * 0.74f, t + h * 0.76f),
                        w * 0.04f, w * 0.04f, paint)
                    text.textSize = h * 0.15f
                    val cy = t + h * 0.64f - (text.descent() + text.ascent()) / 2
                    canvas.drawText(label, l + w * 0.43f, cy, text)
                }
            }
            Type.DRIVE -> {
                paint.shader = LinearGradient(l, t + h * 0.3f, l, t + h * 0.75f,
                    0xFFE2E8F0.toInt(), 0xFF94A3B8.toInt(), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(l + w * 0.08f, t + h * 0.30f, l + w * 0.92f, t + h * 0.72f),
                    w * 0.08f, w * 0.08f, paint)
                paint.shader = null
                paint.color = Ui.ACCENT
                canvas.drawCircle(l + w * 0.78f, t + h * 0.51f, w * 0.05f, paint)
                paint.color = 0xFF475569.toInt()
                canvas.drawRoundRect(RectF(l + w * 0.18f, t + h * 0.47f, l + w * 0.55f, t + h * 0.55f),
                    w * 0.03f, w * 0.03f, paint)
            }
            Type.PHONE -> {
                paint.shader = null
                paint.color = 0xFF334155.toInt()
                canvas.drawRoundRect(RectF(l + w * 0.26f, t + h * 0.06f, l + w * 0.74f, t + h * 0.94f),
                    w * 0.10f, w * 0.10f, paint)
                paint.shader = LinearGradient(l, t, l + w, t + h, Ui.ACCENT, Ui.ACCENT_2, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(l + w * 0.30f, t + h * 0.12f, l + w * 0.70f, t + h * 0.86f),
                    w * 0.05f, w * 0.05f, paint)
                paint.shader = null
                paint.color = 0xCCFFFFFF.toInt()
                canvas.drawRoundRect(RectF(l + w * 0.43f, t + h * 0.89f, l + w * 0.57f, t + h * 0.91f),
                    w * 0.01f, w * 0.01f, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 96
    override fun getIntrinsicHeight(): Int = 96

    companion object {
        fun folder() = FileIconDrawable(Type.FOLDER)
        fun drive() = FileIconDrawable(Type.DRIVE)
        fun phone() = FileIconDrawable(Type.PHONE)

        /** Иконка файла: цвет метки зависит от типа (картинка, музыка, видео, документ, программа…). */
        fun forFile(file: File): FileIconDrawable {
            val ext = file.extension.lowercase()
            val color = when (ext) {
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> 0xFF10B981.toInt()
                "mp3", "wav", "ogg", "flac", "m4a", "aac" -> 0xFFEC4899.toInt()
                "mp4", "mkv", "avi", "mov", "webm", "3gp" -> 0xFFEF4444.toInt()
                "pdf" -> 0xFFDC2626.toInt()
                "doc", "docx", "txt", "rtf", "odt" -> 0xFF2563EB.toInt()
                "xls", "xlsx", "csv", "ods" -> 0xFF16A34A.toInt()
                "zip", "rar", "7z", "tar", "gz" -> 0xFFA16207.toInt()
                "exe", "msi", "bat", "lnk" -> Ui.ACCENT_2
                "apk" -> 0xFF65A30D.toInt()
                else -> 0xFF64748B.toInt()
            }
            return FileIconDrawable(Type.FILE, ext.take(4).uppercase(), color)
        }

        fun forEntry(file: File) = if (file.isDirectory) folder() else forFile(file)
    }
}
