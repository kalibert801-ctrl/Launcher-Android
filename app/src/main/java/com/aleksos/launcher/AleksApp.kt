package com.aleksos.launcher

import android.app.Application
import android.graphics.BitmapFactory

/** Общий запуск приложения: загружаем знак-логотип AleksOS для [LogoDrawable]. */
class AleksApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogoDrawable.mark = runCatching { BitmapFactory.decodeResource(resources, R.drawable.aleksos_mark) }.getOrNull()
    }
}
