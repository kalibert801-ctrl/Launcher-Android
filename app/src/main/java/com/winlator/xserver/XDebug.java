package com.winlator.xserver;

import java.util.HashSet;
import java.util.function.Consumer;

/** AleksOS: краткая диагностика X-сервера в журнал (каждая разная строка — один раз, не больше 60). */
public final class XDebug {
    public static volatile Consumer<String> sink;
    private static final HashSet<String> seen = new HashSet<>();

    private XDebug() {}

    public static void log(String line) {
        Consumer<String> s = sink;
        if (s == null) return;
        synchronized (seen) {
            if (seen.size() >= 60 || !seen.add(line)) return;
        }
        s.accept("X: " + line);
    }

    public static void reset() {
        synchronized (seen) { seen.clear(); }
    }
}
