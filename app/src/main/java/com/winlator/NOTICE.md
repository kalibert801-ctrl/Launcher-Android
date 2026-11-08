# Код из Winlator

Пакеты `com.winlator.*` (X-сервер, отрисовка, сокеты, общая память, запуск через proot)
и нативный код `app/src/main/cpp/winlator`, `app/src/main/cpp/proot` взяты из
Winlator Cmod (ветка `main`, https://github.com/coffincolors/winlator), основанного на
Winlator (https://github.com/brunodev85/winlator).

Лицензия Winlator: MIT, Copyright (c) 2023 BrunoSX. proot: GPL-2.0-or-later (Termux/proot).

Изменения AleksOS: убраны зависимости AndroidX и VR-режим, курсор рисуется в коде,
WinHandler заменён заглушкой, упрощён запуск (GuestProgramLauncherComponent),
загрузчик proot собирается как самостоятельная программа без libc.
