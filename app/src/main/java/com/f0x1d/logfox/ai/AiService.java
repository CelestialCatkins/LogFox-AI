package com.f0x1d.logfox.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.f0x1d.logfox.R;

/**
 * Foreground service hosting the local MCP server.
 */
public class AiService extends Service {

    public static final int DEFAULT_PORT = AiMcpServer.DEFAULT_PORT;
    public static final String ACTION_STOP = "com.f0x1d.logfox.ai.STOP";
    private static final String CHANNEL = "logfox_ai_mcp";
    private static final int NOTIFICATION_ID = 0x10f0;

    public static volatile boolean running;
    public static volatile int port = DEFAULT_PORT;

    private AiMcpServer server;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, AiService.class));
        } catch (Throwable t) {
            AiLog.e("svc", "start: " + t);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, AiService.class));
        } catch (Throwable ignored) {
        }
    }

    public static void startIfEnabled(Context context) {
        if (AiPrefs.enabled(context)) start(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AiPrefs.setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("正在启动…"));

        if (server == null) server = new AiMcpServer(this, AiDebug.create(this));
        if (!server.running()) {
            final AiMcpServer srv = server;
            new Thread(() -> {
                boolean ok = srv.start(AiPrefs.port(AiService.this));
                port = srv.port();
                running = ok && srv.running();
                AiLog.i("svc", "server start=" + running + " port=" + port);
                updateNotification(running
                        ? "MCP 已就绪 · 127.0.0.1:" + port + "/mcp"
                        : "端口被占用，未能启动");
            }, "logfox-ai-start").start();
        } else {
            running = true;
            port = server.port();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (server != null) {
            server.stop();
            server = null;
        }
        AiLog.i("svc", "stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "AI / MCP",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("LogFox AI 本地 MCP 服务");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, AiSettingsActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 1, open, piFlags);

        Intent stop = new Intent(this, AiService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_ai_star)
                .setContentTitle("LogFox AI")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "停止", stopPi)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }
}
