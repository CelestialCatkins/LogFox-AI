package com.f0x1d.logfox.ai;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.f0x1d.logfox.R;
import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

public class AiSettingsFragment extends PreferenceFragmentCompat {

    private final Handler main = new Handler(Looper.getMainLooper());

    private SwitchPreferenceCompat enabled;
    private EditTextPreference port;
    private Preference status;
    private Preference terminal;
    private Preference copy;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName(AiPrefs.FILE);
        addPreferencesFromResource(R.xml.settings_ai);

        enabled = findPreference("ai_enabled");
        port = findPreference("ai_port");
        status = findPreference("ai_status");
        terminal = findPreference("ai_terminal");
        copy = findPreference("ai_copy");

        if (enabled != null) {
            enabled.setChecked(AiPrefs.enabled(requireContext()));
            enabled.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean on = Boolean.TRUE.equals(newValue);
                AiPrefs.setEnabled(requireContext(), on);
                if (on) AiService.start(requireContext());
                else AiService.stop(requireContext());
                main.postDelayed(this::refresh, 700);
                return true;
            });
        }
        if (port != null) {
            port.setText(String.valueOf(AiPrefs.port(requireContext())));
            port.setOnPreferenceChangeListener((preference, newValue) -> {
                int p;
                try {
                    p = Integer.parseInt(String.valueOf(newValue).trim());
                } catch (Throwable t) {
                    p = 0;
                }
                if (p < 1024 || p > 65000) {
                    Toast.makeText(requireContext(), "端口需在 1024–65000 之间", Toast.LENGTH_SHORT).show();
                    return false;
                }
                AiPrefs.setPort(requireContext(), p);
                if (AiService.running) {
                    AiService.stop(requireContext());
                    main.postDelayed(() -> AiService.start(requireContext()), 400);
                }
                main.postDelayed(this::refresh, 500);
                return true;
            });
        }
        if (terminal != null) {
            terminal.setOnPreferenceClickListener(p -> {
                Toast.makeText(requireContext(), "正在检测日志权限…", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    String text;
                    try {
                        AiDebug d = AiDebug.create(requireContext());
                        JSONObject req = d.requestShizuku();
                        JSONObject info = d.terminalInfo();
                        text = "Shizuku: " + req.toString() + "\n\n" + info.toString(2);
                    } catch (Throwable t) {
                        text = "检测失败：" + t;
                    }
                    final String result = text;
                    main.post(() -> {
                        if (isAdded()) showDialog("终端与日志权限", result);
                    });
                }, "ai-terminal-check").start();
                return true;
            });
        }
        if (copy != null) {
            copy.setOnPreferenceClickListener(p -> {
                String endpoint = "http://127.0.0.1:" + AiPrefs.port(requireContext()) + "/mcp";
                ClipboardManager cm = (ClipboardManager) requireContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("LogFox AI MCP", endpoint));
                    Toast.makeText(requireContext(), "已复制 " + endpoint, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
        Preference tools = findPreference("ai_tools");
        if (tools != null) {
            tools.setOnPreferenceClickListener(p -> {
                showDialog("MCP 工具", toolListText());
                return true;
            });
        }
        Preference log = findPreference("ai_log");
        if (log != null) {
            log.setOnPreferenceClickListener(p -> {
                String tail = AiLog.tail(120);
                showDialog("最近调用", tail.isEmpty() ? "暂无记录" : tail);
                return true;
            });
        }
        Preference help = findPreference("ai_help");
        if (help != null) {
            help.setOnPreferenceClickListener(p -> {
                showDialog("使用说明", helpText());
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View toolbarView = view.findViewById(
                com.f0x1d.logfox.core.ui.preference.R.id.toolbar);
        if (toolbarView instanceof MaterialToolbar) {
            MaterialToolbar toolbar = (MaterialToolbar) toolbarView;
            toolbar.setTitle("AI 调试");
            toolbar.setNavigationIcon(R.drawable.ic_ai_back);
            toolbar.setNavigationOnClickListener(v -> requireActivity().finish());
        }
        refresh();
        main.postDelayed(this::refresh, 1500);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
        main.postDelayed(this::refresh, 1200);
    }

    private void refresh() {
        if (!isAdded()) return;
        Context c = requireContext();
        if (enabled != null) enabled.setChecked(AiPrefs.enabled(c));
        if (status != null) {
            if (AiService.running) {
                status.setSummary("运行中 · http://127.0.0.1:" + AiService.port + "/mcp");
            } else if (AiPrefs.enabled(c)) {
                status.setSummary("已启用，但服务未运行（点按「启用 MCP 服务」重试）");
            } else {
                status.setSummary("已停止");
            }
        }
        if (terminal != null) {
            terminal.setSummary("点按检测终端类型与 logcat 可读性（Shizuku / root / READ_LOGS）");
        }
        if (port != null) {
            port.setText(String.valueOf(AiPrefs.port(c)));
        }
        if (copy != null) {
            copy.setSummary("http://127.0.0.1:" + AiPrefs.port(c) + "/mcp");
        }
    }

    private void showDialog(String title, String message) {
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("关闭", null)
                .show();
    }

    private String toolListText() {
        return "device_info · 设备/系统/内存/存储\n"
                + "apps_list / app_info · 应用版本、uid、pid\n"
                + "processes · 运行进程\n"
                + "logs_dump · 结构化日志（包名 / pid / tag / 级别 / 关键字 / 正则）\n"
                + "logs_wait · 等某条日志出现\n"
                + "log_stats · 级别与 tag 统计\n"
                + "crashes_list · LogFox 崩溃记录（Java/JNI/ANR）\n"
                + "crash_detail · 崩溃详情 + 堆栈日志\n"
                + "crash_buffer · 系统崩溃缓冲区（logcat -b crash）\n"
                + "crash_watch · 复现时自动抓崩溃 + 最近告警日志\n"
                + "debug_report · 一次返回应用诊断包\n"
                + "dumpsys · 系统服务信息（activity/window/package/meminfo/dropbox...）\n"
                + "batch · 多步调试调用\n"
                + "status / help";
    }

    private String helpText() {
        return "推荐给 AI 的调试流程：\n"
                + "1. app_info 拿到包名、版本、pid\n"
                + "2. logs_dump（package + level=W）看最近的告警和错误\n"
                + "3. crash_buffer / crashes_list 找崩溃\n"
                + "4. crash_detail 看完整堆栈\n"
                + "5. 复现时用 crash_watch 或 logs_wait\n"
                + "6. 需要一次拿全量时用 debug_report\n\n"
                + "连接地址：http://127.0.0.1:"
                + AiPrefs.port(requireContext()) + "/mcp\n"
                + "协议：MCP（JSON-RPC 2.0 over HTTP）\n"
                + "只返回文本/JSON，不传截图，不模拟触控。\n\n"
                + "日志读取依赖 LogFox 的终端：在「设置 → 服务」里启用 Shizuku/root，"
                + "或用 adb 授予 READ_LOGS。";
    }
}
