package com.f0x1d.logfox.ai;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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

public class AiSettingsFragment extends PreferenceFragmentCompat {

    private final Handler main = new Handler(Looper.getMainLooper());

    private SwitchPreferenceCompat enabled;
    private EditTextPreference port;
    private Preference status;
    private Preference access;
    private Preference copy;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName(AiPrefs.FILE);
        addPreferencesFromResource(R.xml.settings_ai);

        enabled = findPreference("ai_enabled");
        port = findPreference("ai_port");
        status = findPreference("ai_status");
        access = findPreference("ai_access");
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
        if (access != null) {
            access.setOnPreferenceClickListener(p -> {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable t) {
                    Toast.makeText(requireContext(), "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
                }
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
            toolbar.setTitle("AI 与 MCP");
            toolbar.setNavigationIcon(R.drawable.ic_ai_back);
            toolbar.setNavigationOnClickListener(v -> requireActivity().finish());
        }
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
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
        if (access != null) {
            boolean on = AiAccessibilityService.isEnabled(c);
            access.setSummary(on ? "已开启 · 点击可进入系统设置" : "未开启 · 点按去系统设置里启用");
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
        return "observe · 一次拿到当前界面、可点元素和可选截图\n"
                + "ui_dump · 完整无障碍树（含坐标与 ref）\n"
                + "find · 按文字 / 描述 / id / 类名查找\n"
                + "tap / tap_ref / tap_text / long_press · 点击\n"
                + "swipe / gesture / scroll · 滑动与多指手势\n"
                + "text · 输入文字（可提交）\n"
                + "key · 返回 / 主页 / 最近任务 / 通知 / 快捷设置\n"
                + "open_app / list_apps · 启动应用\n"
                + "screenshot · 截图（PNG）\n"
                + "wait_for / wait_idle · 等待条件或界面稳定\n"
                + "batch · 一次执行多步操作\n"
                + "clipboard / wake / toast / logcat / status";
    }

    private String helpText() {
        return "推荐给 AI 的操作流程：\n"
                + "1. observe 看界面，拿 ref 和坐标\n"
                + "2. tap_ref / tap_text / swipe 操作\n"
                + "3. wait_idle 或 wait_for 等界面变化\n"
                + "4. 再 observe 确认结果\n\n"
                + "连接地址：http://127.0.0.1:"
                + AiPrefs.port(requireContext()) + "/mcp\n"
                + "协议：MCP（JSON-RPC 2.0 over HTTP）\n"
                + "所有数据只在本机，不上传。\n\n"
                + "若服务无法启动，先确认无障碍服务已开启；"
                + "若端口被占用，换一个端口。";
    }
}
