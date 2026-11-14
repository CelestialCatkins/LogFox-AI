package com.f0x1d.logfox.ai;

import android.content.Context;
import android.content.SharedPreferences;

public final class AiPrefs {

    public static final String FILE = "logfox_ai";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_PORT = "port";

    private AiPrefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context context) {
        return get(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        get(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int port(Context context) {
        int p = get(context).getInt(KEY_PORT, AiService.DEFAULT_PORT);
        if (p < 1024 || p > 65000) p = AiService.DEFAULT_PORT;
        return p;
    }

    public static void setPort(Context context, int port) {
        get(context).edit().putInt(KEY_PORT, port).apply();
    }
}
