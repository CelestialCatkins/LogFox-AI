package com.f0x1d.logfox.ai;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Tiny in-memory log used by the AI/MCP console. */
public final class AiLog {

    private static final int CAPACITY = 600;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private AiLog() {
    }

    public static synchronized void i(String tag, String msg) {
        String line = FMT.format(new Date()) + " " + tag + ": " + msg;
        LINES.addLast(line);
        while (LINES.size() > CAPACITY) LINES.removeFirst();
    }

    public static synchronized void e(String tag, String msg) {
        i(tag, "ERROR " + msg);
    }

    public static synchronized String tail(int n) {
        if (n <= 0) n = 80;
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, LINES.size() - n);
        int i = 0;
        for (String line : LINES) {
            if (i++ < skip) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
