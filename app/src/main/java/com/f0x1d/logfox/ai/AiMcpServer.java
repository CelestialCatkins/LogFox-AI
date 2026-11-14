package com.f0x1d.logfox.ai;

import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
 * MCP server (JSON-RPC 2.0 over HTTP) exposing LogFox debug data:
 * structured logs, crash records, app/process/device info.
 *
 * Deliberately text-only: no screenshots, no touch injection.
 *
 * POST http://127.0.0.1:PORT/mcp
 * GET  http://127.0.0.1:PORT/health
 */
public final class AiMcpServer {

    public static final int DEFAULT_PORT = 18923;
    public static final String VERSION = "2.0.0";

    private final Context ctx;
    private final AiDebug debug;
    private ServerSocket server;
    private volatile boolean running;
    private volatile int port = DEFAULT_PORT;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile long startedAt;
    private Thread acceptor;

    public AiMcpServer(Context context, AiDebug debug) {
        this.ctx = context.getApplicationContext();
        this.debug = debug;
    }

    public int port() {
        return port;
    }

    public boolean running() {
        return running;
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
            sock.setSoTimeout(60000);
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
                    info.put("title", "LogFox AI Debug");
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
        return "LogFox AI is a debug-focused MCP server. It returns text/JSON only (no screenshots, no touch). "
                + "Typical flow: app_info -> logs_dump (level=W/E, package=...) -> crash_buffer / crashes_list "
                + "-> crash_detail -> dumpsys. Use debug_report for a one-call diagnosis bundle, "
                + "logs_wait to wait for a log pattern, and crash_watch to catch a crash while reproducing.";
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
        a.put(tool("status", "Server, terminal/log-access and crash-DB status.",
                s("{\"type\":\"object\",\"properties\":{}}")));
        a.put(tool("device_info", "Device: model, Android, ABI, memory, storage, screen.",
                s("{\"type\":\"object\",\"properties\":{}}")));
        a.put(tool("apps_list", "Installed apps with version/uid; filter by label or package.",
                s("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}}}")));
        a.put(tool("app_info", "Package details and running pid.",
                s("{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"}},\"required\":[\"package\"]}")));
        a.put(tool("processes", "Running processes (ps -A), filter by name.",
                s("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}}}")));
        a.put(tool("logs_dump",
                "Structured logcat snapshot. Filter by package/pid/tag/level/contains; level=V,D,I,W,E,F. "
                        + "Returns entries plus a rendered text tail.",
                s("{\"type\":\"object\",\"properties\":{"
                        + "\"package\":{\"type\":\"string\"},\"pid\":{\"type\":\"string\"},"
                        + "\"tag\":{\"type\":\"string\"},\"level\":{\"type\":\"string\"},"
                        + "\"contains\":{\"type\":\"string\"},\"regex\":{\"type\":\"boolean\"},"
                        + "\"buffer\":{\"type\":\"string\",\"description\":\"main/system/crash\"},"
                        + "\"limit\":{\"type\":\"integer\"}}}")));
        a.put(tool("logs_wait", "Wait for a new log line matching contains/regex (for reproducing).",
                s("{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"},"
                        + "\"contains\":{\"type\":\"string\"},\"regex\":{\"type\":\"boolean\"},"
                        + "\"timeout_ms\":{\"type\":\"integer\"}}}")));
        a.put(tool("log_stats", "Counts by level and top tags for a window.",
                s("{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"},"
                        + "\"buffer\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}}}")));
        a.put(tool("crashes_list", "Crashes recorded by LogFox (Java/JNI/ANR).",
                s("{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"},"
                        + "\"limit\":{\"type\":\"integer\"}}}")));
        a.put(tool("crash_detail", "Full LogFox crash record with stack/log lines.",
                s("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}},\"required\":[\"id\"]}")));
        a.put(tool("crash_buffer", "System crash buffer (logcat -b crash) filtered by package.",
                s("{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"},"
                        + "\"limit\":{\"type\":\"integer\"}}}")));
        a.put(tool("crash_watch", "Wait for a new crash while reproducing; returns crash text + recent W/E logs.",
                s("{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"},"
                        + "\"timeout_ms\":{\"type\":\"integer\"}}}")));
        a.put(tool("debug_report",
                "One-call diagnosis bundle: app info, W/E logs, crash records, crash buffer, stats, device.",
                s("{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"},"
                        + "\"log_limit\":{\"type\":\"integer\"}},\"required\":[\"package\"]}")));
        a.put(tool("dumpsys", "Run dumpsys for a whitelisted service (activity/window/package/meminfo/dropbox/...).",
                s("{\"type\":\"object\",\"properties\":{\"service\":{\"type\":\"string\"}}}")));
        a.put(tool("batch", "Run several debug tool calls sequentially.",
                s("{\"type\":\"object\",\"properties\":{\"steps\":{\"type\":\"array\","
                        + "\"items\":{\"type\":\"object\",\"properties\":{"
                        + "\"tool\":{\"type\":\"string\"},\"arguments\":{\"type\":\"object\"},"
                        + "\"wait_ms\":{\"type\":\"integer\"}}}}}}")));
        a.put(tool("help", "List tools and a recommended debug workflow.",
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
        AiLog.i("tool", name + " " + a.toString());
        switch (name) {
            case "status":
            case "health":
                return text(health().toString());
            case "device_info":
                return text(debug.deviceInfo().toString());
            case "apps_list":
                return text(debug.apps(a).toString());
            case "app_info":
                return text(debug.appInfo(a).toString());
            case "processes":
                return text(debug.processes(a).toString());
            case "logs_dump":
                return text(debug.logs(a).toString());
            case "logs_wait":
                return text(debug.logsWait(a).toString());
            case "log_stats":
                return text(debug.logStats(a).toString());
            case "crashes_list":
                return text(debug.crashes(a).toString());
            case "crash_detail":
                return text(debug.crashDetail(a).toString());
            case "crash_buffer":
                return text(debug.crashBuffer(a).toString());
            case "crash_watch":
                return text(debug.crashWatch(a).toString());
            case "debug_report":
                return text(debug.debugReport(a).toString());
            case "dumpsys":
                return text(debug.dumpsys(a).toString());
            case "batch": {
                if (depth > 0) return fail("nested batch is not allowed");
                JSONArray steps = a.optJSONArray("steps");
                if (steps == null) return fail("steps is required");
                JSONArray results = new JSONArray();
                boolean allOk = true;
                for (int i = 0; i < steps.length() && i < 30; i++) {
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
            case "help": {
                StringBuilder sb = new StringBuilder();
                JSONArray tools = toolList();
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject t = tools.getJSONObject(i);
                    sb.append("- ").append(t.optString("name")).append(": ")
                            .append(t.optString("description")).append('\n');
                }
                sb.append("\nWorkflow: app_info -> logs_dump(level=W) -> crash_buffer -> crash_detail; "
                        + "debug_report for a bundle; crash_watch while reproducing.");
                return text(sb.toString());
            }
            default:
                return fail("unknown tool: " + name);
        }
    }

    // ------------------------------------------------------------- health

    public JSONObject health() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("service", running);
            o.put("port", port);
            o.put("endpoint", "http://127.0.0.1:" + port + "/mcp");
            o.put("version", VERSION);
            o.put("android", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("requests", requests.get());
            o.put("uptime_ms", startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt);
            o.put("terminal", debug.terminalInfo());
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
