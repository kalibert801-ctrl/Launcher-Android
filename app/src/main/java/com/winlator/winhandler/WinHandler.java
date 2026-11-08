package com.winlator.winhandler;

/**
 * AleksOS: заглушка. В Winlator WinHandler общается с winhandler.exe внутри Wine
 * (относительная мышь, вывод окна на передний план). Пока не нужен — методы ничего не делают.
 */
public class WinHandler {
    public void mouseEvent(int flags, int dx, int dy, int wheelDelta) {}
    public void bringToFront(String className, long handle) {}
}
