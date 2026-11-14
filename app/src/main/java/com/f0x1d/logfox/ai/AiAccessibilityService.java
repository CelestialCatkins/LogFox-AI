package com.f0x1d.logfox.ai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Accessibility engine used by the MCP server.
 *
 * It can observe the screen (UI tree + optional screenshot), tap, swipe,
 * long-press, multi-touch gestures, set text, press global keys, launch apps,
 * wait for conditions, and keep the screen awake.
 */
public class AiAccessibilityService extends AccessibilityService {

    private static volatile AiAccessibilityService INSTANCE;

    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile Snapshot lastSnapshot;
    private volatile String lastPkg = "";
    private volatile String lastActivity = "";

    private volatile long appListAt;
    private volatile JSONArray appList;

    public static AiAccessibilityService get() {
        return INSTANCE;
    }

    public static boolean ready() {
        return INSTANCE != null;
    }

    public static boolean isEnabled(Context context) {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            String pkg = context.getPackageName();
            String cls = AiAccessibilityService.class.getName();
            for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
                if (pkg.equals(info.getResolveInfo().serviceInfo.packageName)
                        && cls.equals(info.getResolveInfo().serviceInfo.name)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            AiLog.e("a11y", "isEnabled: " + t);
        }
        return false;
    }

    @Override
    public void onServiceConnected() {
        INSTANCE = this;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            AiLog.e("a11y", "configure: " + t);
        }
        AiLog.i("a11y", "connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            int type = event.getEventType();
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                CharSequence p = event.getPackageName();
                CharSequence c = event.getClassName();
                if (p != null) lastPkg = p.toString();
                if (c != null) {
                    String s = c.toString();
                    int dot = s.lastIndexOf('.');
                    lastActivity = dot >= 0 ? s.substring(dot + 1) : s;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        INSTANCE = null;
        lastSnapshot = null;
        AiLog.i("a11y", "unbound");
        return super.onUnbind(intent);
    }

    // ---------------------------------------------------------------- models

    public static final class Node {
        public int ref;
        public AccessibilityNodeInfo info;
        public Rect bounds;
        public String text = "";
        public String desc = "";
        public String id = "";
        public String cls = "";
        public boolean clickable;
        public boolean longClickable;
        public boolean editable;
        public boolean scrollable;
        public boolean checkable;
        public boolean checked;
        public boolean enabled = true;
        public boolean focused;
        public int depth;

        public JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("ref", ref);
            if (!text.isEmpty()) o.put("text", text);
            if (!desc.isEmpty()) o.put("desc", desc);
            if (!id.isEmpty()) o.put("id", id);
            o.put("class", cls);
            o.put("bounds", new JSONArray().put(bounds.left).put(bounds.top)
                    .put(bounds.right).put(bounds.bottom));
            if (clickable) o.put("click", true);
            if (longClickable) o.put("long", true);
            if (editable) o.put("editable", true);
            if (scrollable) o.put("scroll", true);
            if (checkable) o.put("checkable", true);
            if (checked) o.put("checked", true);
            if (!enabled) o.put("enabled", false);
            if (focused) o.put("focused", true);
            return o;
        }

        public boolean isTarget() {
            return clickable || longClickable || editable || scrollable
                    || checkable || !text.isEmpty() || !desc.isEmpty() || !id.isEmpty();
        }
    }

    public static final class Snapshot {
        public long id;
        public long at;
        public String pkg = "";
        public String activity = "";
        public int width;
        public int height;
        public String hash = "";
        public final List<Node> nodes = new ArrayList<>();

        public Node byRef(int ref) {
            if (ref < 1 || ref > nodes.size()) return null;
            return nodes.get(ref - 1);
        }
    }

    // ------------------------------------------------------------- snapshot

    private static String trim(CharSequence cs) {
        if (cs == null) return "";
        String s = cs.toString().replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > 240) s = s.substring(0, 240) + "…";
        return s;
    }

    private String nodeId(AccessibilityNodeInfo n) {
        try {
            String id = n.getViewIdResourceName();
            if (id == null) return "";
            int slash = id.indexOf(':');
            return slash >= 0 && slash + 2 < id.length() ? id.substring(slash + 2) : id;
        } catch (Throwable t) {
            return "";
        }
    }

    private String nodeClass(AccessibilityNodeInfo n) {
        try {
            CharSequence c = n.getClassName();
            if (c == null) return "";
            String s = c.toString();
            int dot = s.lastIndexOf('.');
            return dot >= 0 ? s.substring(dot + 1) : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private synchronized Snapshot buildSnapshot(int limit, boolean allNodes) {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable t) {
            root = null;
        }
        if (root == null) {
            lastSnapshot = null;
            return null;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Snapshot s = new Snapshot();
        s.id = System.currentTimeMillis();
        s.at = s.id;
        s.width = dm.widthPixels;
        s.height = dm.heightPixels;
        try {
            CharSequence p = root.getPackageName();
            s.pkg = p == null ? "" : p.toString();
        } catch (Throwable ignored) {
        }
        s.activity = lastActivity;
        collect(root, 0, s, limit, allNodes);
        s.hash = hash(s);
        lastSnapshot = s;
        return s;
    }

    private void collect(AccessibilityNodeInfo n, int depth, Snapshot s,
                         int limit, boolean allNodes) {
        if (n == null || s.nodes.size() >= limit || depth > 50) return;
        Rect r = new Rect();
        boolean visible = false;
        try {
            n.getBoundsInScreen(r);
            visible = n.isVisibleToUser();
        } catch (Throwable ignored) {
        }
        boolean onScreen = r.width() > 1 && r.height() > 1
                && r.right > 0 && r.bottom > 0 && r.left < s.width && r.top < s.height;
        boolean usable = visible && onScreen;

        boolean clickable = safeClickable(n, false);
        boolean longClickable = safeLongClickable(n);
        boolean editable = safe(n, 3);
        boolean scrollable = safe(n, 2);
        boolean checkable = safe(n, 4);
        boolean checked = safe(n, 5);
        boolean enabled = safe(n, 6);
        boolean focused = safe(n, 7);
        String text = trim(safeText(n));
        String desc = trim(safeDesc(n));
        String id = nodeId(n);

        boolean wanted = usable && (allNodes || clickable || longClickable || editable || scrollable
                || checkable || !text.isEmpty() || !desc.isEmpty() || !id.isEmpty());
        if (wanted) {
            Node node = new Node();
            node.info = n;
            node.bounds = r;
            node.text = text;
            node.desc = desc;
            node.id = id;
            node.cls = nodeClass(n);
            node.clickable = clickable;
            node.longClickable = longClickable;
            node.editable = editable;
            node.scrollable = scrollable;
            node.checkable = checkable;
            node.checked = checked;
            node.enabled = enabled;
            node.focused = focused;
            node.depth = depth;
            node.ref = s.nodes.size() + 1;
            s.nodes.add(node);
        }
        int count = 0;
        try {
            count = n.getChildCount();
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = n.getChild(i);
            } catch (Throwable ignored) {
            }
            if (c != null) collect(c, depth + 1, s, limit, allNodes);
        }
    }

    private boolean safe(AccessibilityNodeInfo n, int what) {
        try {
            switch (what) {
                case 2: return n.isScrollable();
                case 3: return n.isEditable();
                case 4: return n.isCheckable();
                case 5: return n.isChecked();
                case 6: return n.isEnabled();
                case 7: return n.isFocused();
                default: return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean safeClickable(AccessibilityNodeInfo n, boolean def) {
        try { return n.isClickable(); } catch (Throwable t) { return def; }
    }

    private boolean safeLongClickable(AccessibilityNodeInfo n) {
        try { return n.isLongClickable(); } catch (Throwable t) { return false; }
    }

    private CharSequence safeText(AccessibilityNodeInfo n) {
        try { return n.getText(); } catch (Throwable t) { return null; }
    }

    private CharSequence safeDesc(AccessibilityNodeInfo n) {
        try { return n.getContentDescription(); } catch (Throwable t) { return null; }
    }

    private String hash(Snapshot s) {
        StringBuilder sb = new StringBuilder();
        for (Node n : s.nodes) {
            sb.append(n.text).append('|').append(n.desc).append('|').append(n.id)
                    .append('|').append(n.bounds.left).append(',').append(n.bounds.top)
                    .append('|').append(n.checked).append('\n');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private Snapshot current(int limit, boolean force) {
        Snapshot s = lastSnapshot;
        if (!force && s != null && System.currentTimeMillis() - s.at < 2500) return s;
        return buildSnapshot(limit, false);
    }

    private String outline(Snapshot s) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (Node n : s.nodes) {
            String t = n.text.isEmpty() ? n.desc : n.text;
            if (t.isEmpty()) continue;
            String key = t + "@" + n.bounds.left + "," + n.bounds.top;
            if (!seen.add(key)) continue;
            if (sb.length() + t.length() > 12000) break;
            sb.append(t).append('\n');
        }
        return sb.toString();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean keyboardVisible() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------ observe

    public JSONObject observe(int limit, boolean force) throws Exception {
        Snapshot s = current(limit <= 0 ? 100 : limit, force);
        if (s == null) {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", "no active window (secure screen, lock screen, or accessibility is off)");
            return o;
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("snapshot", s.id);
        JSONObject app = new JSONObject();
        app.put("pkg", s.pkg);
        app.put("activity", s.activity);
        app.put("label", appLabel(s.pkg));
        o.put("app", app);
        JSONObject screen = new JSONObject();
        screen.put("w", s.width);
        screen.put("h", s.height);
        screen.put("density", getResources().getDisplayMetrics().densityDpi);
        screen.put("portrait", s.height >= s.width);
        o.put("screen", screen);
        o.put("keyboard", keyboardVisible());
        Node focus = null;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo f = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null) {
                Rect r = new Rect();
                f.getBoundsInScreen(r);
                focus = new Node();
                focus.ref = 0;
                focus.bounds = r;
                focus.text = trim(f.getText());
                focus.desc = trim(f.getContentDescription());
                focus.id = nodeId(f);
                focus.cls = nodeClass(f);
                focus.editable = true;
            }
        } catch (Throwable ignored) {
        }
        if (focus != null) {
            o.put("focus", focus.json());
        }
        JSONArray targets = new JSONArray();
        int max = limit <= 0 ? 100 : limit;
        for (Node n : s.nodes) {
            if (targets.length() >= max) break;
            if (n.isTarget()) targets.put(n.json());
        }
        o.put("targets", targets);
        o.put("outline", outline(s));
        return o;
    }

    public JSONObject dump(int limit, boolean allNodes, boolean force) throws Exception {
        Snapshot s = current(limit <= 0 ? 400 : limit, true);
        if (s == null) {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", "no active window");
            return o;
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("snapshot", s.id);
        o.put("pkg", s.pkg);
        o.put("activity", s.activity);
        o.put("screen", new JSONArray().put(s.width).put(s.height));
        JSONArray arr = new JSONArray();
        int max = limit <= 0 ? 400 : limit;
        for (Node n : s.nodes) {
            if (arr.length() >= max) break;
            arr.put(n.json());
        }
        o.put("nodes", arr);
        return o;
    }

    public JSONArray find(String text, String desc, String id, String cls,
                          Boolean clickable, int limit, boolean exact) throws Exception {
        Snapshot s = current(400, false);
        JSONArray out = new JSONArray();
        if (s == null) return out;
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String d = desc == null ? "" : desc.toLowerCase(Locale.ROOT);
        String vid = id == null ? "" : id.toLowerCase(Locale.ROOT);
        String c = cls == null ? "" : cls.toLowerCase(Locale.ROOT);
        int max = limit <= 0 ? 20 : limit;
        for (Node n : s.nodes) {
            if (out.length() >= max) break;
            if (!t.isEmpty()) {
                String hay = n.text.toLowerCase(Locale.ROOT);
                if (exact ? !hay.equals(t) : !hay.contains(t)) continue;
            }
            if (!d.isEmpty()) {
                String hay = n.desc.toLowerCase(Locale.ROOT);
                if (exact ? !hay.equals(d) : !hay.contains(d)) continue;
            }
            if (!vid.isEmpty() && !n.id.toLowerCase(Locale.ROOT).contains(vid)) continue;
            if (!c.isEmpty() && !n.cls.toLowerCase(Locale.ROOT).contains(c)) continue;
            if (clickable != null && clickable != n.clickable) continue;
            out.put(n.json());
        }
        return out;
    }

    // -------------------------------------------------------------- input

    public boolean tap(int x, int y) {
        return gesture(x, y, x, y, 60);
    }

    public boolean longPress(int x, int y, long ms) {
        return gesture(x, y, x, y, Math.max(400, ms));
    }

    public boolean swipe(int x1, int y1, int x2, int y2, long ms) {
        return gesture(x1, y1, x2, y2, Math.max(80, ms));
    }

    public boolean gesture(int x1, int y1, int x2, int y2, long ms) {
        try {
            Path p = new Path();
            p.moveTo(x1, y1);
            if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, Math.max(1, ms));
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(stroke);
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            AiLog.e("a11y", "gesture: " + t);
            return false;
        }
    }

    /**
     * strokes: [[[x,y],[x,y],...], ...] each stroke has start=0 and duration from
     * "duration" or 300ms.
     */
    public boolean multiGesture(JSONArray strokes, long duration) {
        try {
            if (strokes == null || strokes.length() == 0) return false;
            GestureDescription.Builder b = new GestureDescription.Builder();
            int added = 0;
            for (int i = 0; i < strokes.length() && i < 10; i++) {
                JSONArray pts = strokes.optJSONArray(i);
                if (pts == null || pts.length() == 0) continue;
                Path p = new Path();
                for (int j = 0; j < pts.length(); j++) {
                    JSONArray xy = pts.optJSONArray(j);
                    if (xy == null || xy.length() < 2) continue;
                    float x = (float) xy.optDouble(0);
                    float y = (float) xy.optDouble(1);
                    if (j == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                b.addStroke(new GestureDescription.StrokeDescription(
                        p, 0, Math.max(1, duration)));
                added++;
            }
            if (added == 0) return false;
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            AiLog.e("a11y", "multiGesture: " + t);
            return false;
        }
    }

    public JSONObject tapRef(int ref, boolean longClick) throws Exception {
        Snapshot s = lastSnapshot;
        Node n = s == null ? null : s.byRef(ref);
        if (n == null) {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", "unknown ref " + ref + ", call observe/ui_dump first");
            return o;
        }
        JSONObject o = new JSONObject();
        o.put("ref", ref);
        o.put("text", n.text.isEmpty() ? n.desc : n.text);
        o.put("bounds", new JSONArray().put(n.bounds.left).put(n.bounds.top)
                .put(n.bounds.right).put(n.bounds.bottom));
        boolean ok;
        if (!longClick && n.clickable && clickNode(n.info)) {
            ok = true;
            o.put("via", "click");
        } else {
            ok = longClick
                    ? longPress(n.bounds.centerX(), n.bounds.centerY(), 700)
                    : tap(n.bounds.centerX(), n.bounds.centerY());
            o.put("via", "gesture");
        }
        o.put("ok", ok);
        return o;
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        try {
            return n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Throwable t) {
            return false;
        }
    }

    public JSONObject tapText(String needle, boolean exact, int index) throws Exception {
        Snapshot s = current(400, false);
        JSONObject o = new JSONObject();
        if (s == null) {
            o.put("ok", false);
            o.put("error", "no active window");
            return o;
        }
        List<Node> matches = new ArrayList<>();
        String q = needle == null ? "" : needle;
        for (Node n : s.nodes) {
            boolean hit = exact
                    ? n.text.equals(q) || n.desc.equals(q)
                    : n.text.contains(q) || n.desc.contains(q);
            if (hit) matches.add(n);
        }
        if (matches.isEmpty()) {
            o.put("ok", false);
            o.put("error", "text not found: " + q);
            return o;
        }
        if (index < 0 || index >= matches.size()) index = 0;
        Node n = matches.get(index);
        boolean ok = false;
        AccessibilityNodeInfo cur = n.info;
        for (int i = 0; i < 8 && cur != null; i++) {
            boolean clickable = false;
            try { clickable = cur.isClickable(); } catch (Throwable ignored) { }
            if (clickable) {
                ok = clickNode(cur);
                if (ok) break;
            }
            try {
                cur = cur.getParent();
            } catch (Throwable t) {
                cur = null;
            }
        }
        if (!ok) ok = tap(n.bounds.centerX(), n.bounds.centerY());
        o.put("ok", ok);
        o.put("matches", matches.size());
        o.put("index", index);
        o.put("ref", n.ref);
        o.put("text", n.text.isEmpty() ? n.desc : n.text);
        o.put("bounds", new JSONArray().put(n.bounds.left).put(n.bounds.top)
                .put(n.bounds.right).put(n.bounds.bottom));
        return o;
    }

    public JSONObject setText(String text, Integer ref, boolean submit) throws Exception {
        JSONObject o = new JSONObject();
        AccessibilityNodeInfo target = null;
        try {
            if (ref != null) {
                Snapshot s = lastSnapshot;
                Node n = s == null ? null : s.byRef(ref);
                if (n != null) target = n.info;
            }
            if (target == null) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                target = root == null ? null
                        : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            }
            if (target == null) {
                o.put("ok", false);
                o.put("error", "no focused editable field; tap the input first");
                return o;
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            o.put("ok", ok);
            o.put("text", text);
            if (submit) {
                boolean sent = false;
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        sent = target.performAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                    } catch (Throwable ignored) {
                    }
                }
                o.put("submit", sent);
            }
            return o;
        } catch (Throwable t) {
            o.put("ok", false);
            o.put("error", String.valueOf(t));
            return o;
        }
    }

    public boolean key(String name) {
        int action;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        switch (n) {
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "recents":
            case "recent": action = GLOBAL_ACTION_RECENTS; break;
            case "notifications":
            case "notify": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick":
            case "quicksettings":
            case "settings": action = GLOBAL_ACTION_QUICK_SETTINGS; break;
            default: return false;
        }
        try {
            return performGlobalAction(action);
        } catch (Throwable t) {
            return false;
        }
    }

    public JSONObject scroll(String direction, Integer ref, int x, int y) throws Exception {
        JSONObject o = new JSONObject();
        Snapshot s = current(400, false);
        Node target = null;
        if (ref != null && s != null) target = s.byRef(ref);
        if (target == null && s != null) {
            // nearest scrollable node containing the point, else first scrollable
            for (Node n : s.nodes) {
                if (!n.scrollable) continue;
                if (n.bounds.contains(x, y)) {
                    target = n;
                    break;
                }
                if (target == null) target = n;
            }
        }
        if (target == null) {
            o.put("ok", false);
            o.put("error", "no scrollable container found");
            return o;
        }
        Rect r = target.bounds;
        String d = direction == null ? "down" : direction.toLowerCase(Locale.ROOT);
        int action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        if ("down".equals(d) || "right".equals(d) || "back".equals(d)) {
            action = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        }
        boolean ok = false;
        try {
            ok = target.info.performAction(action);
        } catch (Throwable ignored) {
        }
        if (!ok) {
            int cx = r.centerX(), cy = r.centerY();
            int dx = 0, dy = 0;
            switch (d) {
                case "up": dy = -Math.max(120, r.height() / 3); break;
                case "down": dy = Math.max(120, r.height() / 3); break;
                case "left": dx = -Math.max(120, r.width() / 3); break;
                case "right": dx = Math.max(120, r.width() / 3); break;
                default: dy = Math.max(120, r.height() / 3); break;
            }
            ok = swipe(cx - dx, cy - dy, cx + dx, cy + dy, 320);
        }
        o.put("ok", ok);
        o.put("ref", target.ref);
        o.put("via", "action");
        return o;
    }

    // ----------------------------------------------------------- wait

    private boolean matches(Snapshot s, JSONObject cond) {
        if (s == null) return false;
        String text = cond.optString("text", "");
        String desc = cond.optString("desc", "");
        String id = cond.optString("id", "");
        String pkg = cond.optString("pkg", "");
        String activity = cond.optString("activity", "");
        boolean exact = cond.optBoolean("exact", false);
        if (!pkg.isEmpty() && !s.pkg.toLowerCase(Locale.ROOT)
                .contains(pkg.toLowerCase(Locale.ROOT))) return false;
        if (!activity.isEmpty() && !s.activity.toLowerCase(Locale.ROOT)
                .contains(activity.toLowerCase(Locale.ROOT))) return false;
        if (text.isEmpty() && desc.isEmpty() && id.isEmpty() && pkg.isEmpty() && activity.isEmpty()) {
            return true;
        }
        for (Node n : s.nodes) {
            if (!text.isEmpty()) {
                boolean hit = exact ? n.text.equals(text) || n.desc.equals(text)
                        : n.text.contains(text) || n.desc.contains(text);
                if (!hit) continue;
            }
            if (!desc.isEmpty() && !(exact ? n.desc.equals(desc) : n.desc.contains(desc))) continue;
            if (!id.isEmpty() && !n.id.contains(id)) continue;
            return true;
        }
        return false;
    }

    public JSONObject waitFor(JSONObject cond, long timeoutMs, boolean gone) throws Exception {
        long start = System.currentTimeMillis();
        long deadline = start + Math.max(300, timeoutMs);
        JSONObject o = new JSONObject();
        while (System.currentTimeMillis() <= deadline) {
            Snapshot s = buildSnapshot(400, false);
            boolean hit = matches(s, cond);
            if (hit != gone) {
                o.put("ok", true);
                o.put("found", hit);
                o.put("elapsed_ms", System.currentTimeMillis() - start);
                if (hit && s != null) {
                    // first matching node
                    String text = cond.optString("text", "");
                    for (Node n : s.nodes) {
                        if (!text.isEmpty() && (n.text.contains(text) || n.desc.contains(text))) {
                            o.put("node", n.json());
                            break;
                        }
                    }
                }
                return o;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        o.put("ok", false);
        o.put("error", "timeout after " + (System.currentTimeMillis() - start) + "ms");
        return o;
    }

    public JSONObject waitIdle(long stableMs, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        long deadline = start + Math.max(500, timeoutMs);
        String prev = null;
        long stableSince = 0;
        while (System.currentTimeMillis() <= deadline) {
            Snapshot s = buildSnapshot(400, false);
            String h = s == null ? "null" : s.hash;
            if (h.equals(prev)) {
                if (stableSince == 0) stableSince = System.currentTimeMillis();
            } else {
                prev = h;
                stableSince = 0;
            }
            if (stableSince > 0 && System.currentTimeMillis() - stableSince >= Math.max(200, stableMs)) {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("elapsed_ms", System.currentTimeMillis() - start);
                o.put("hash", h);
                return o;
            }
            try {
                Thread.sleep(180);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("error", "timeout waiting for a stable screen");
        return o;
    }

    // -------------------------------------------------------- screenshot

    public Bitmap screenshot(int maxTries) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        final String[] err = new String[1];
        for (int attempt = 0; attempt < Math.max(1, maxTries); attempt++) {
            final CountDownLatch latch = new CountDownLatch(1);
            final Bitmap[] holder = new Bitmap[1];
            Runnable call = () -> {
                try {
                    takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                            new AccessibilityService.TakeScreenshotCallback() {
                                @Override
                                public void onSuccess(AccessibilityService.ScreenshotResult result) {
                                    try (HardwareBuffer buf = result.getHardwareBuffer()) {
                                        Bitmap hw = Bitmap.wrapHardwareBuffer(buf, result.getColorSpace());
                                        if (hw != null) {
                                            holder[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                                        }
                                    } catch (Throwable t) {
                                        err[0] = String.valueOf(t);
                                    } finally {
                                        latch.countDown();
                                    }
                                }

                                @Override
                                public void onFailure(int errorCode) {
                                    err[0] = "code " + errorCode;
                                    latch.countDown();
                                }
                            });
                } catch (Throwable t) {
                    err[0] = String.valueOf(t);
                    latch.countDown();
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) call.run();
            else main.post(call);
            try {
                if (!latch.await(6, TimeUnit.SECONDS)) err[0] = "timeout";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (holder[0] != null) return holder[0];
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                return null;
            }
        }
        AiLog.e("a11y", "screenshot failed: " + err[0]);
        return null;
    }

    public static String pngBase64(Bitmap bmp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    // ------------------------------------------------------------ apps

    public JSONArray apps() throws Exception {
        long now = System.currentTimeMillis();
        JSONArray cached = appList;
        if (cached != null && now - appListAt < 60_000) return cached;
        JSONArray out = new JSONArray();
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            try {
                String pkg = ri.activityInfo.packageName;
                String label = ri.loadLabel(pm).toString();
                out.put(new JSONObject().put("label", label).put("pkg", pkg));
            } catch (Throwable ignored) {
            }
            if (out.length() >= 300) break;
        }
        appList = out;
        appListAt = now;
        return out;
    }

    public JSONObject openApp(String nameOrPkg) throws Exception {
        JSONObject o = new JSONObject();
        if (nameOrPkg == null || nameOrPkg.trim().isEmpty()) {
            o.put("ok", false);
            o.put("error", "empty package");
            return o;
        }
        String q = nameOrPkg.trim();
        String pkg = q;
        PackageManager pm = getPackageManager();
        Intent intent = null;
        try {
            intent = pm.getLaunchIntentForPackage(pkg);
        } catch (Throwable ignored) {
        }
        if (intent == null) {
            JSONArray list = apps();
            for (int i = 0; i < list.length(); i++) {
                JSONObject app = list.getJSONObject(i);
                String label = app.optString("label", "");
                if (label.equalsIgnoreCase(q) || label.toLowerCase(Locale.ROOT)
                        .contains(q.toLowerCase(Locale.ROOT))) {
                    pkg = app.optString("pkg");
                    intent = pm.getLaunchIntentForPackage(pkg);
                    break;
                }
            }
        }
        if (intent == null) {
            o.put("ok", false);
            o.put("error", "app not found: " + q);
            return o;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(intent);
        o.put("ok", true);
        o.put("pkg", pkg);
        return o;
    }

    // ------------------------------------------------------- clipboard

    public JSONObject clipboard(String set) throws Exception {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        JSONObject o = new JSONObject();
        if (cm == null) {
            o.put("ok", false);
            o.put("error", "clipboard unavailable");
            return o;
        }
        if (set != null) {
            cm.setPrimaryClip(ClipData.newPlainText("logfox-ai", set));
            o.put("ok", true);
            o.put("set", set.length());
            return o;
        }
        try {
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                o.put("ok", false);
                o.put("error", "clipboard empty or not readable while app is in background");
                return o;
            }
            CharSequence t = clip.getItemAt(0).coerceToText(this);
            o.put("ok", true);
            o.put("text", t == null ? "" : t.toString());
        } catch (Throwable t) {
            o.put("ok", false);
            o.put("error", String.valueOf(t));
        }
        return o;
    }

    // ------------------------------------------------------------ misc

    public JSONObject wake() throws Exception {
        JSONObject o = new JSONObject();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isInteractive()) {
                o.put("ok", true);
                o.put("already_on", pm != null && pm.isInteractive());
                return o;
            }
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE, "logfox-ai:screen");
            wl.acquire(5000);
            o.put("ok", true);
            o.put("woke", true);
        } catch (Throwable t) {
            o.put("ok", false);
            o.put("error", String.valueOf(t));
        }
        return o;
    }

    public void toast(final String text) {
        main.post(() -> {
            try {
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    public JSONObject status() throws Exception {
        Snapshot s = lastSnapshot;
        JSONObject o = new JSONObject();
        o.put("accessibility", true);
        o.put("pkg", s == null ? lastPkg : s.pkg);
        o.put("activity", s == null ? lastActivity : s.activity);
        o.put("snapshot", s == null ? 0 : s.id);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        o.put("screen", new JSONArray().put(dm.widthPixels).put(dm.heightPixels));
        o.put("keyboard", keyboardVisible());
        return o;
    }
}
