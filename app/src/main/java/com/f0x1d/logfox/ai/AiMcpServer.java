package com.f0x1d.logfox.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.DisplayMetrics;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal MCP server (JSON-RPC 2.0 over HTTP) for LogFox AI.
 *
 * POST http://127.0.0.1:PORT/mcp
 * GET  http://127.0.0.1:PORT/health
 */
public final class AiMcpServer {

    public static final int DEFAULT_PORT = 18923;
    public static final String VERSION = "1.0.0";

    private final Context ctx;
    private ServerSocket server;
    private Thread acceptor;
    private volatile boolean running;
    private volatile int port = DEFAULT_PORT;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile long startedAt;

    public AiMcpServer(Context context) {
        this.ctx = context.getApplicationContext();
    }

    public int port() {
        return port;
    }

    public boolean running() {
        return running;
    }

    public long startedAt() {
        return startedAt;
    }

    public int requests() {
        return requests.get();
    }

    public synchronized boolean start(int preferredPort) {
        if (running) return true;
        port = preferredPort <= 0 ? DEFAULT_PORT : preferredPort;
        for (int attempt = 0; attempt < 12; attempt++) {
            try {
                server = new ServerSocket(port, 24, InetAddress.getByName("127.0.0.1"));
                server.setReuseAddress(true);
                running = true;
                startedAt = System.currentTimeMillis();
                acceptor = new Thread(this::acceptLoop, "logfox-ai-mcp");
                acceptor.setDaemon(true);
                acceptor.start();
                AiLog.i("mcp", "listening on 127.0.0.1:" + port);
                return true;
            } catch (Throwable t) {
                AiLog.e("mcp", "bind " + port + ": " + t);
                try {
                    if (server != null) server.close();
                } catch (Throwable ignored) {
                }
                server = null;
                port++;
            }
        }
        return false;
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Throwable ignored) {
        }
        server = null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "logfox-ai-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                if (running) AiLog.e("mcp", "accept: " + t);
            }
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            String reqLine = readLine(in);
            if (reqLine == null) return;
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.US);
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.US),
                            line.substring(c + 1).trim());
                }
            }
            int len = 0;
            try {
                len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (Throwable ignored) {
            }
            byte[] body = new byte[Math.max(0, Math.min(len, 8 * 1024 * 1024))];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }
            String payload = new String(body, 0, read, StandardCharsets.UTF_8);

            if (method.equals("OPTIONS")) {
                respond(out, 204, "text/plain", "", null);
                return;
            }
            if (method.equals("GET") && (path.equals("/health") || path.equals("/"))) {
                respond(out, 200, "application/json", health().toString(), null);
                return;
            }
            if (!method.equals("POST")) {
                respond(out, 405, "text/plain", "POST /mcp only", null);
                return;
            }
            if (!path.equals("/mcp") && !path.equals("/")) {
                respond(out, 404, "text/plain", "not found", null);
                return;
            }
            requests.incrementAndGet();
            JSONObject req;
            try {
                req = new JSONObject(payload);
            } catch (Throwable t) {
                respond(out, 200, "application/json",
                        errorObj(null, -32700, "parse error: " + t).toString(), null);
                return;
            }
            if (!req.has("id")) {
                respond(out, 202, "text/plain", "", null);
                return;
            }
            JSONObject resp = dispatch(req);
            String sid = Long.toHexString(System.currentTimeMillis());
            respond(out, 200, "application/json", resp.toString(), sid);
        } catch (Throwable t) {
            AiLog.e("mcp", "serve: " + t);
        }
    }

    private void respond(OutputStream out, int code, String type, String body,
                         String sessionId) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" OK\r\n");
        sb.append("Content-Type: ").append(type).append("; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(b.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        if (sessionId != null) sb.append("Mcp-Session-Id: ").append(sessionId).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
        }
        if (c == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- JSON-RPC

    private JSONObject dispatch(JSONObject req) {
        Object id = req.opt("id");
        String method = req.optString("method", "");
        try {
            switch (method) {
                case "initialize": {
                    JSONObject params = req.optJSONObject("params");
                    String proto = params != null
                            ? params.optString("protocolVersion", "2025-06-18")
                            : "2025-06-18";
                    JSONObject result = new JSONObject();
                    result.put("protocolVersion", proto);
                    JSONObject caps = new JSONObject();
                    caps.put("tools", new JSONObject().put("listChanged", false));
                    caps.put("resources", new JSONObject());
                    result.put("capabilities", caps);
                    JSONObject info = new JSONObject();
                    info.put("name", "logfox-ai");
                    info.put("title", "LogFox AI");
                    info.put("version", VERSION);
                    result.put("serverInfo", info);
                    result.put("instructions", instructions());
                    return ok(id, result);
                }
                case "ping":
                    return ok(id, new JSONObject());
                case "tools/list": {
                    JSONObject result = new JSONObject();
                    result.put("tools", toolList());
                    return ok(id, result);
                }
                case "tools/call": {
                    JSONObject params = req.optJSONObject("params");
                    String name = params != null ? params.optString("name", "") : "";
                    JSONObject args = params != null ? params.optJSONObject("arguments") : null;
                    if (args == null) args = new JSONObject();
                    return ok(id, callToolSafe(name, args, 0));
                }
                case "resources/list": {
                    JSONObject result = new JSONObject();
                    result.put("resources", new JSONArray());
                    return ok(id, result);
                }
                case "prompts/list": {
                    JSONObject result = new JSONObject();
                    result.put("prompts", new JSONArray());
                    return ok(id, result);
                }
                default:
                    return errorObj(id, -32601, "method not found: " + method);
            }
        } catch (Throwable t) {
            AiLog.e("mcp", "dispatch " + method + ": " + t);
            return errorObj(id, -32603, String.valueOf(t));
        }
    }

    private String instructions() {
        return "LogFox AI controls this Android device through Accessibility. "
                + "Coordinates are physical pixels; screen size is in observe.status. "
                + "Preferred loop: observe -> tap_ref/tap_text (or swipe/gesture) -> wait_idle/wait_for -> observe. "
                + "Refs stay valid until the next observe/ui_dump/find. "
                + "Prefer text/ref targeting over raw coordinates. Use wait_for instead of fixed sleeps. "
                + "observe returns a compact outline plus interactive targets; ui_dump returns the full tree.";
    }

    private JSONObject ok(Object id, JSONObject result) {
        JSONObject o = new JSONObject();
        try {
            o.put("jsonrpc", "2.0");
            o.put("id", id == null ? JSONObject.NULL : id);
            o.put("result", result);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private JSONObject errorObj(Object id, int code, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("jsonrpc", "2.0");
            o.put("id", id == null ? JSONObject.NULL : id);
            JSONObject e = new JSONObject();
            e.put("code", code);
            e.put("message", msg);
            o.put("error", e);
        } catch (Throwable ignored) {
        }
        return o;
    }

    // -------------------------------------------------------------- tools

    private JSONArray toolList() {
        JSONArray a = new JSONArray();
        a.put(tool("status", "Service/accessibility/screen status. Call this first if unsure.",
                s("{\"type\":\"object\",\"properties\":{}}")));
        a.put(tool("observe",
                "One-call snapshot: current app, screen size, keyboard, focus, compact text outline, "
                        + "and interactive targets with refs. Set screenshot=true to also get a PNG.",
                s("{\"type\":\"object\",\"properties\":{"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"max targets, default 80\"},"
                        + "\"screenshot\":{\"type\":\"boolean\"},"
                        + "\"scale\":{\"type\":\"number\",\"description\":\"screenshot scale 0.1-1, default 0.5\"}"
                        + "}}")));
        a.put(tool("ui_dump",
                "Full accessibility tree with bounds and refs. Use when observe is not enough.",
                s("{\"type\":\"object\",\"properties\":{"
                        + "\"limit\":{\"type\":\"integer\",\"default\":400},"
                        + "\"all\":{\"type\":\"boolean\",\"description\":\"include non-interactive nodes\"}"
                        + "}}")));
        a.put(tool("find", "Find nodes by text/desc/id/class; returns refs and bounds.",
                s("{\"type\":\"object\",\"properties\":{"
                        + "\"text\":{\"type\":\"string\"},\"desc\":{\"type\":\"string\"},"
                        + "\"id\":{\"type\":\"string\"},\"class\":{\"type\":\"string\"},"
                        + "\"exact\":{\"type\":\"boolean\"},\"clickable\":{\"type\":\"boolean\"},"
                        + "\"limit\":{\"type\":\"integer\"}}}")));
        a.put(tool("tap", "Tap absolute pixel coordinates.",
                s("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},"
                        + "\"y\":{\"type\":\"integer\"}},\"required\":[\"x\",\"y\"]}")));
        a.put(tool("tap_ref", "Tap a node by ref from the latest observe/ui_dump/find.",
                s("{\"type\":\"object\",\"properties\":{\"ref\":{\"type\":\"integer\"},"
                        + "\"long\":{\"type\":\"boolean\"}},\"required\":[\"ref\"]}")));
        a.put(tool("tap_text", "Find visible text/description and tap it (clickable ancestor first).",
                s("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},"
                        + "\"exact\":{\"type\":\"boolean\"},\"index\":{\"type\":\"integer\"}},"
                        + "\"required\":[\"text\"]}")));
        a.put(tool("long_press", "Long press by coordinates or by ref.",
                s("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},"
                        + "\"y\":{\"type\":\"integer\"},\"ref\":{\"type\":\"integer\"},"
                        + "\"ms\":{\"type\":\"integer\"}}}")));
        a.put(tool("swipe", "Swipe from (x1,y1) to (x2,y2). ms defaults to 300.",
                s("{\"type\":\"object\",\"properties\":{\"x1\":{\"type\":\"integer\"},"
                        + "\"y1\":{\"type\":\"integer\"},\"x2\":{\"type\":\"integer\"},"
                        + "\"y2\":{\"type\":\"integer\"},\"ms\":{\"type\":\"integer\"}},"
                        + "\"required\":[\"x1\",\"y1\",\"x2\",\"y2\"]}")));
        a.put(tool("gesture",
                "Multi-touch gesture. strokes is an array of strokes, each an array of [x,y] points. "
                        + "Two strokes with opposite directions = pinch/zoom.",
                s("{\"type\":\"object\",\"properties\":{\"strokes\":{\"type\":\"array\"},"
                        + "\"duration_ms\":{\"type\":\"integer\"}},\"required\":[\"strokes\"]}")));
        a.put(tool("scroll", "Scroll a container: direction up/down/left/right (swipe direction).",
                s("{\"type\":\"object\",\"properties\":{\"direction\":{\"type\":\"string\"},"
                        + "\"ref\":{\"type\":\"integer\"},\"x\":{\"type\":\"integer\"},"
                        + "\"y\":{\"type\":\"integer\"}}}")));
        a.put(tool("key", "Global key: back, home, recents, notifications, quick.",
                s("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},"
                        + "\"required\":[\"name\"]}")));
        a.put(tool("text", "Set text in the focused input (or at ref). submit sends IME enter.",
                s("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},"
                        + "\"ref\":{\"type\":\"integer\"},\"submit\":{\"type\":\"boolean\"}},"
                        + "\"required\":[\"text\"]}")));
        a.put(tool("open_app", "Launch an app by package name or label.",
                s("{\"type\":\"object\",\"properties\":{\"app\":{\"type\":\"string\"}},"
                        + "\"required\":[\"app\"]}")));
        a.put(tool("list_apps", "List launchable apps, optionally filtered by label/package.",
                s("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},"
                        + "\"limit\":{\"type\":\"integer\"}}}")));
        a.put(tool("screenshot", "PNG screenshot. Returns text + image content.",
                s("{\"type\":\"object\",\"properties\":{\"scale\":{\"type\":\"number\"},"
                        + "\"return_image\":{\"type\":\"boolean\"}}}")));
        a.put(tool("wait_for",
                "Wait until text/desc/id/pkg/activity appears (or disappears with gone=true).",
                s("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},"
                        + "\"desc\":{\"type\":\"string\"},\"id\":{\"type\":\"string\"},"
                        + "\"pkg\":{\"type\":\"string\"},\"activity\":{\"type\":\"string\"},"
                        + "\"exact\":{\"type\":\"boolean\"},\"gone\":{\"type\":\"boolean\"},"
                        + "\"timeout_ms\":{\"type\":\"integer\",\"default\":8000}}}")));
        a.put(tool("wait_idle", "Wait until the UI stops changing.",
                s("{\"type\":\"object\",\"properties\":{\"stable_ms\":{\"type\":\"integer\",\"default\":700},"
                        + "\"timeout_ms\":{\"type\":\"integer\",\"default\":8000}}}")));
        a.put(tool("clipboard", "Get or set the clipboard. Set `set` to write.",
                s("{\"type\":\"object\",\"properties\":{\"set\":{\"type\":\"string\"},"
                        + "\"get\":{\"type\":\"boolean\"}}}")));
        a.put(tool("wake", "Wake the screen (keeps it on briefly) when it is off.",
                s("{\"type\":\"object\",\"properties\":{}}")));
        a.put(tool("toast", "Show a toast on the device.",
                s("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
                        + "\"required\":[\"text\"]}")));
        a.put(tool("batch",
                "Run multiple tool calls sequentially (max 60), with optional wait_ms between steps. "
                        + "Great for multi-step flows.",
                s("{\"type\":\"object\",\"properties\":{\"steps\":{\"type\":\"array\","
                        + "\"items\":{\"type\":\"object\",\"properties\":{"
                        + "\"tool\":{\"type\":\"string\"},\"arguments\":{\"type\":\"object\"},"
                        + "\"wait_ms\":{\"type\":\"integer\"}}}}}")));
        a.put(tool("logcat", "Read recent logcat lines (needs READ_LOGS/Shizuku on modern Android).",
                s("{\"type\":\"object\",\"properties\":{\"lines\":{\"type\":\"integer\",\"default\":150},"
                        + "\"contains\":{\"type\":\"string\"}}}")));
        a.put(tool("help", "List tool names and a short usage recipe.",
                s("{\"type\":\"object\",\"properties\":{}}")));
        return a;
    }

    private static JSONObject s(String json) {
        try {
            return new JSONObject(json);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static JSONObject tool(String name, String desc, JSONObject input) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("description", desc);
            o.put("inputSchema", input);
        } catch (Throwable ignored) {
        }
        return o;
    }

    // --------------------------------------------------------- tool calls

    private JSONObject callToolSafe(String name, JSONObject args, int depth) {
        try {
            return callTool(name, args, depth);
        } catch (Throwable t) {
            AiLog.e("tool", name + ": " + t);
            return fail(name + " failed: " + t);
        }
    }

    private JSONObject callTool(String name, JSONObject a, int depth) throws Exception {
        AiAccessibilityService a11y = AiAccessibilityService.get();
        AiLog.i("tool", name + " " + a.toString());
        switch (name) {
            case "status":
            case "health":
                return text(health().toString());
            case "observe": {
                if (a11y == null) return fail("accessibility service is not enabled");
                int limit = a.optInt("limit", 80);
                JSONObject o = a11y.observe(limit, true);
                if (!o.optBoolean("ok", false)) return text(o.toString());
                JSONArray content = new JSONArray();
                content.put(new JSONObject().put("type", "text").put("text", o.toString()));
                if (a.optBoolean("screenshot", false)) {
                    JSONObject shot = screenshot(a.optDouble("scale", 0.5), true);
                    JSONArray sc = shot.optJSONArray("content");
                    if (sc != null) for (int i = 0; i < sc.length(); i++) content.put(sc.get(i));
                }
                return content(content);
            }
            case "ui_dump": {
                if (a11y == null) return fail("accessibility service is not enabled");
                return text(a11y.dump(a.optInt("limit", 400), a.optBoolean("all", false), true).toString());
            }
            case "find": {
                if (a11y == null) return fail("accessibility service is not enabled");
                JSONArray arr = a11y.find(a.optString("text", null), a.optString("desc", null),
                        a.optString("id", null), a.optString("class", null),
                        a.has("clickable") ? a.optBoolean("clickable") : null,
                        a.optInt("limit", 20), a.optBoolean("exact", false));
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("count", arr.length());
                o.put("nodes", arr);
                return text(o.toString());
            }
            case "tap": {
                if (a11y == null) return fail("accessibility service is not enabled");
                boolean ok = a11y.tap(a.optInt("x"), a.optInt("y"));
                return ok ? text("{\"ok\":true}") : fail("tap failed");
            }
            case "tap_ref": {
                if (a11y == null) return fail("accessibility service is not enabled");
                JSONObject o = a11y.tapRef(a.optInt("ref"), a.optBoolean("long", false));
                return o.optBoolean("ok", false) ? text(o.toString()) : fail(o.toString());
            }
            case "tap_text": {
                if (a11y == null) return fail("accessibility service is not enabled");
                JSONObject o = a11y.tapText(a.optString("text", ""), a.optBoolean("exact", false),
                        a.optInt("index", 0));
                return o.optBoolean("ok", false) ? text(o.toString()) : fail(o.toString());
            }
            case "long_press": {
                if (a11y == null) return fail("accessibility service is not enabled");
                boolean ok;
                if (a.has("ref")) {
                    JSONObject o = a11y.tapRef(a.optInt("ref"), true);
                    ok = o.optBoolean("ok", false);
                } else {
                    ok = a11y.longPress(a.optInt("x"), a.optInt("y"), a.optLong("ms", 800));
                }
                return ok ? text("{\"ok\":true}") : fail("long press failed");
            }
            case "swipe": {
                if (a11y == null) return fail("accessibility service is not enabled");
                boolean ok = a11y.swipe(a.optInt("x1"), a.optInt("y1"),
                        a.optInt("x2"), a.optInt("y2"), a.optLong("ms", 300));
                return ok ? text("{\"ok\":true}") : fail("swipe failed");
            }
            case "gesture": {
                if (a11y == null) return fail("accessibility service is not enabled");
                JSONArray strokes = a.optJSONArray("strokes");
                long dur = Math.min(60000, Math.max(30, a.optLong("duration_ms", 300)));
                boolean ok = a11y.multiGesture(strokes, dur);
                return ok ? text("{\"ok\":true}") : fail("gesture failed");
            }
            case "scroll": {
                if (a11y == null) return fail("accessibility service is not enabled");
                JSONObject o = a11y.scroll(a.optString("direction", "down"),
                        a.has("ref") ? a.optInt("ref") : null,
                        a.optInt("x"), a.optInt("y"));
                return o.optBoolean("ok", false) ? text(o.toString()) : fail(o.toString());
            }
            case "key": {
                if (a11y == null) return fail("accessibility service is not enabled");
                boolean ok = a11y.key(a.optString("name", "back"));
                return ok ? text("{\"ok\":true}") : fail("unknown key: " + a.optString("name"));
            }
            case "text": {
                if (a11y == null) return fail("accessibility service is not enabled");
                JSONObject o = a11y.setText(a.optString("text", ""),
                        a.has("ref") ? a.optInt("ref") : null, a.optBoolean("submit", false));
                return o.optBoolean("ok", false) ? text(o.toString()) : fail(o.toString());
            }
            case "open_app": {
                if (a11y == null) return fail("accessibility service is not enabled");
                JSONObject o = a11y.openApp(a.optString("app", ""));
                return o.optBoolean("ok", false) ? text(o.toString()) : fail(o.toString());
            }
            case "list_apps": {
                if (a11y == null) return fail("accessibility service is not enabled");
                JSONArray all = a11y.apps();
                String q = a.optString("query", "").toLowerCase(Locale.ROOT);
                int max = a.optInt("limit", 200);
                JSONArray out = new JSONArray();
                for (int i = 0; i < all.length() && out.length() < max; i++) {
                    JSONObject app = all.getJSONObject(i);
                    if (!q.isEmpty()) {
                        String label = app.optString("label", "").toLowerCase(Locale.ROOT);
                        String pkg = app.optString("pkg", "").toLowerCase(Locale.ROOT);
                        if (!label.contains(q) && !pkg.contains(q)) continue;
                    }
                    out.put(app);
                }
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("count", out.length());
                o.put("apps", out);
                return text(o.toString());
            }
            case "screenshot":
                return screenshot(a.optDouble("scale", 0.5), a.optBoolean("return_image", true));
            case "wait_for": {
                if (a11y == null) return fail("accessibility service is not enabled");
                JSONObject cond = new JSONObject();
                cond.put("text", a.optString("text", ""));
                cond.put("desc", a.optString("desc", ""));
                cond.put("id", a.optString("id", ""));
                cond.put("pkg", a.optString("pkg", ""));
                cond.put("activity", a.optString("activity", ""));
                cond.put("exact", a.optBoolean("exact", false));
                JSONObject o = a11y.waitFor(cond, a.optLong("timeout_ms", 8000),
                        a.optBoolean("gone", false));
                return o.optBoolean("ok", false) ? text(o.toString()) : fail(o.toString());
            }
            case "wait_idle": {
                if (a11y == null) return fail("accessibility service is not enabled");
                JSONObject o = a11y.waitIdle(a.optLong("stable_ms", 700),
                        a.optLong("timeout_ms", 8000));
                return o.optBoolean("ok", false) ? text(o.toString()) : fail(o.toString());
            }
            case "clipboard": {
                if (a11y == null) return fail("accessibility service is not enabled");
                boolean get = a.has("get") && a.optBoolean("get");
                if (!get && !a.has("set")) get = true;
                JSONObject o = a11y.clipboard(a.has("set") ? a.optString("set") : null);
                return text(o.toString());
            }
            case "wake": {
                if (a11y == null) return fail("accessibility service is not enabled");
                return text(a11y.wake().toString());
            }
            case "toast": {
                if (a11y != null) a11y.toast(a.optString("text", ""));
                else Toast.makeText(ctx, a.optString("text", ""), Toast.LENGTH_SHORT).show();
                return text("{\"ok\":true}");
            }
            case "batch": {
                if (depth > 0) return fail("nested batch is not allowed");
                if (a11y == null) return fail("accessibility service is not enabled");
                JSONArray steps = a.optJSONArray("steps");
                if (steps == null) return fail("steps is required");
                JSONArray results = new JSONArray();
                boolean allOk = true;
                for (int i = 0; i < steps.length() && i < 60; i++) {
                    JSONObject step = steps.optJSONObject(i);
                    if (step == null) continue;
                    String tool = step.optString("tool", "");
                    JSONObject args = step.optJSONObject("arguments");
                    if (args == null) args = new JSONObject();
                    long t0 = System.currentTimeMillis();
                    JSONObject r = callToolSafe(tool, args, depth + 1);
                    JSONObject row = new JSONObject();
                    row.put("step", i);
                    row.put("tool", tool);
                    row.put("ok", !r.optBoolean("isError", false));
                    row.put("ms", System.currentTimeMillis() - t0);
                    row.put("result", contentText(r));
                    results.put(row);
                    if (r.optBoolean("isError", false)) allOk = false;
                    long wait = step.optLong("wait_ms", 0);
                    if (wait > 0 && wait < 60000) Thread.sleep(wait);
                }
                JSONObject o = new JSONObject();
                o.put("ok", allOk);
                o.put("results", results);
                return text(o.toString());
            }
            case "logcat":
                return text(logcat(a.optInt("lines", 150), a.optString("contains", "")).toString());
            case "help": {
                StringBuilder sb = new StringBuilder();
                JSONArray tools = toolList();
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject t = tools.getJSONObject(i);
                    sb.append("- ").append(t.optString("name")).append(": ")
                            .append(t.optString("description")).append('\n');
                }
                sb.append("\nRecipe: status -> observe -> tap_ref/tap_text -> wait_idle -> observe. "
                        + "Use batch for multi-step flows.");
                return text(sb.toString());
            }
            default:
                return fail("unknown tool: " + name);
        }
    }

    private JSONObject screenshot(double scale, boolean returnImage) {
        AiAccessibilityService a11y = AiAccessibilityService.get();
        if (a11y == null) return fail("accessibility service is not enabled");
        Bitmap bmp = a11y.screenshot(4);
        if (bmp == null) return fail("screenshot failed (secure screen or rate limited)");
        float sc = (float) scale;
        if (sc <= 0 || sc > 1) sc = 0.5f;
        Bitmap out = bmp;
        if (sc < 0.999f) {
            out = Bitmap.createScaledBitmap(bmp,
                    Math.max(1, (int) (bmp.getWidth() * sc)),
                    Math.max(1, (int) (bmp.getHeight() * sc)), true);
        }
        String path = "";
        try {
            File dir = new File(ctx.getFilesDir(), "shots");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "shot-" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            path = f.getAbsolutePath();
        } catch (Throwable t) {
            AiLog.e("mcp", "save shot: " + t);
        }
        try {
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text",
                    "screenshot " + out.getWidth() + "x" + out.getHeight()
                            + (path.isEmpty() ? "" : " saved=" + path)));
            if (returnImage) {
                content.put(new JSONObject().put("type", "image")
                        .put("data", AiAccessibilityService.pngBase64(out))
                        .put("mimeType", "image/png"));
            }
            return content(content);
        } catch (Throwable t) {
            return fail("encode failed: " + t);
        }
    }

    private JSONObject logcat(int lines, String contains) {
        int n = Math.max(10, Math.min(1000, lines));
        StringBuilder sb = new StringBuilder();
        int exit = -1;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-t", String.valueOf(n), "-v", "time"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int count = 0;
            String filter = contains == null ? "" : contains.toLowerCase(Locale.ROOT);
            while ((line = r.readLine()) != null) {
                if (!filter.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(filter)) continue;
                if (count++ > 0) sb.append('\n');
                sb.append(line);
                if (sb.length() > 200000) break;
            }
            try {
                exit = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (Throwable t) {
            sb.append("logcat error: ").append(t);
        }
        try {
            JSONObject o = new JSONObject();
            o.put("ok", exit == 0 || sb.length() > 0);
            o.put("exit", exit);
            o.put("lines", sb.toString());
            if (exit != 0) {
                o.put("hint", "logcat may be restricted; grant READ_LOGS via adb/Shizuku or use LogFox UI");
            }
            return o;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    // ------------------------------------------------------------- health

    public JSONObject health() {
        JSONObject o = new JSONObject();
        try {
            AiAccessibilityService a11y = AiAccessibilityService.get();
            o.put("ok", true);
            o.put("service", running);
            o.put("port", port);
            o.put("endpoint", "http://127.0.0.1:" + port + "/mcp");
            o.put("accessibility", a11y != null);
            o.put("version", VERSION);
            o.put("android", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("requests", requests.get());
            o.put("uptime_ms", startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt);
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            o.put("screen", new JSONArray().put(dm.widthPixels).put(dm.heightPixels));
            if (a11y != null) {
                o.put("state", a11y.status());
            }
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("error", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    // ----------------------------------------------------------- content

    private JSONObject text(String s) {
        JSONObject r = new JSONObject();
        try {
            JSONArray c = new JSONArray();
            c.put(new JSONObject().put("type", "text").put("text", s));
            r.put("content", c);
            r.put("isError", false);
        } catch (Throwable ignored) {
        }
        return r;
    }

    private JSONObject fail(String s) {
        JSONObject r = new JSONObject();
        try {
            JSONArray c = new JSONArray();
            c.put(new JSONObject().put("type", "text").put("text", s));
            r.put("content", c);
            r.put("isError", true);
        } catch (Throwable ignored) {
        }
        return r;
    }

    private JSONObject content(JSONArray content) {
        JSONObject r = new JSONObject();
        try {
            r.put("content", content);
            r.put("isError", false);
        } catch (Throwable ignored) {
        }
        return r;
    }

    private String contentText(JSONObject r) {
        JSONArray c = r.optJSONArray("content");
        if (c == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length(); i++) {
            JSONObject item = c.optJSONObject(i);
            if (item != null && "text".equals(item.optString("type"))) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(item.optString("text"));
            }
        }
        return sb.toString();
    }
}
