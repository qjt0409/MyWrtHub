package com.whykangkang.wrthub.ui.services.ratelimit;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.EqosApi;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.model.ConnectedDevice;
import com.whykangkang.wrthub.model.EqosDevice;
import com.whykangkang.wrthub.model.EqosGlobal;
import com.whykangkang.wrthub.ui.devices.ConnectedDeviceLoader;
import com.whykangkang.wrthub.util.NetworkUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 设备限速(luci-app-eqosplus,固件实际装的版本)。
 *
 *  - 总开关 + WAN 总带宽(单位 MB/s,eqosplus 靠它算 HTB 根队列)
 *  - 每台设备一条上/下行上限(MB/s,可带小数),目标可填 IP / MAC / IP 段,
 *    可从已连接设备里挑;每条规则还带生效星期与时段
 *  - 终端详情页会带 EXTRA_TARGET 跳进来:有规则直接改,没有就预填新增
 */
public class DeviceRateLimitActivity extends AppCompatActivity {

    /** 从终端详情页跳入时预填的目标 IP 与设备名 */
    public static final String EXTRA_TARGET = "extra_target";
    public static final String EXTRA_DEVICE_NAME = "extra_device_name";

    private EqosGlobal global = EqosGlobal.defaults();
    private final List<EqosDevice> devices = new ArrayList<>();
    private boolean loaded;
    private boolean prefillHandled;

    private SwipeRefreshLayout refresh;
    private LinearLayout globalContainer;
    private LinearLayout devicesContainer;
    private TextView devicesFooter;
    private TextView statusLabel;
    private View content;
    private boolean suppressSwitchCallback;

    /** 星期选项的值与文案(顺序与 eqosplus 网页端一致) */
    private static final String[] WEEK_VALUES =
            {"0", "1,2,3,4,5", "6,7", "1", "2", "3", "4", "5", "6", "7"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rate_limit);
        refresh = findViewById(R.id.refresh);
        globalContainer = findViewById(R.id.global_container);
        devicesContainer = findViewById(R.id.devices_container);
        devicesFooter = findViewById(R.id.devices_footer);
        statusLabel = findViewById(R.id.status_label);
        content = findViewById(R.id.content);
        refresh.setOnRefreshListener(this::loadSettings);
        findViewById(R.id.btn_add).setOnClickListener(v -> addDeviceTapped());
        loadSettings();
    }

    // =====================================================================
    // 数据
    // =====================================================================

    private void loadSettings() {
        EqosApi.getSettings(new ApiCallback<EqosApi.EqosSettings>() {
            @Override
            public void onSuccess(EqosApi.EqosSettings settings) {
                refresh.setRefreshing(false);
                global = settings.global;
                devices.clear();
                devices.addAll(settings.devices);
                loaded = true;
                statusLabel.setVisibility(View.GONE);
                content.setVisibility(View.VISIBLE);
                render();
                handlePrefill();
            }

            @Override
            public void onFailure(ApiError error) {
                refresh.setRefreshing(false);
                loaded = false;
                content.setVisibility(View.GONE);
                statusLabel.setVisibility(View.VISIBLE);
                statusLabel.setText(friendlyMessage(error));
            }
        });
    }

    /** 终端详情页带目标跳入:已有规则直接打开编辑,没有则预填新增表单(只处理一次) */
    private void handlePrefill() {
        if (prefillHandled) return;
        String target = getIntent().getStringExtra(EXTRA_TARGET);
        if (target == null || target.isEmpty()) return;
        prefillHandled = true;
        for (EqosDevice d : devices) {
            if (target.equals(d.target)) {
                editDevice(d, false);
                return;
            }
        }
        EqosDevice prefilled = new EqosDevice();
        prefilled.target = target;
        String name = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        if (name != null && !name.equals(target)) prefilled.comment = name;
        editDevice(prefilled, true);
    }

    /** uci 读不到 eqosplus 配置,最常见的原因是插件没装 */
    private String friendlyMessage(ApiError error) {
        if (error.getType() == ApiError.Type.CONFIG_NOT_FOUND) {
            return getString(R.string.eqos_not_configured);
        }
        return error.getMessage() != null ? error.getMessage() : getString(R.string.eqos_load_failed);
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private void render() {
        globalContainer.removeAllViews();

        View toggleRow = LayoutInflater.from(this)
                .inflate(R.layout.view_switch_row, globalContainer, false);
        ((TextView) toggleRow.findViewById(R.id.row_label)).setText(R.string.eqos_enable);
        SwitchMaterial toggle = toggleRow.findViewById(R.id.row_switch);
        suppressSwitchCallback = true;
        toggle.setChecked(global.enabled);
        suppressSwitchCallback = false;
        toggle.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressSwitchCallback) return;
            EqosGlobal next = copyGlobal(checked);
            saveGlobal(next, ok -> {
                if (!ok) {
                    suppressSwitchCallback = true;
                    toggle.setChecked(global.enabled);
                    suppressSwitchCallback = false;
                }
            });
        });
        globalContainer.addView(toggleRow);
        globalContainer.addView(separator());
        globalContainer.addView(bandwidthRow(true));
        globalContainer.addView(separator());
        globalContainer.addView(bandwidthRow(false));

        devicesContainer.removeAllViews();
        if (devices.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.eqos_empty);
            empty.setTextColor(getColor(R.color.label_secondary));
            empty.setTextSize(15);
            int pad = dp(16);
            empty.setPadding(pad, pad, pad, pad);
            devicesContainer.addView(empty);
            devicesFooter.setText(R.string.eqos_unit_hint);
        } else {
            for (int i = 0; i < devices.size(); i++) {
                devicesContainer.addView(deviceRow(devices.get(i)));
                if (i < devices.size() - 1) devicesContainer.addView(separator());
            }
            devicesFooter.setText(R.string.eqos_devices_footer);
        }
    }

    private EqosGlobal copyGlobal(boolean enabled) {
        EqosGlobal g = new EqosGlobal(enabled, global.download, global.upload, global.ifname);
        g.section = global.section;
        return g;
    }

    private View bandwidthRow(boolean isDownload) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.view_value_row, globalContainer, false);
        ((TextView) row.findViewById(R.id.row_label))
                .setText(isDownload ? R.string.eqos_wan_down : R.string.eqos_wan_up);
        ((TextView) row.findViewById(R.id.row_value)).setText(
                (isDownload ? global.download : global.upload) + " MB/s");
        row.setOnClickListener(v -> editBandwidth(isDownload));
        return row;
    }

    private View deviceRow(EqosDevice device) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.item_samba_share, devicesContainer, false);
        TextView name = row.findViewById(R.id.share_name);
        TextView detail = row.findViewById(R.id.share_path);
        // 网页端可以单独停用某条规则而不删除,这里如实标出来
        name.setText(device.enabled ? device.displayName()
                : getString(R.string.eqos_disabled_suffix, device.displayName()));
        name.setTextColor(getColor(device.enabled
                ? R.color.label_primary : R.color.label_secondary));
        String targetPart = device.displayName().equals(device.target) ? "" : device.target + "   ";
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "%s↓ %s  ↑ %s MB/s",
                targetPart, device.download, device.upload));
        String schedule = scheduleSummary(device);
        if (!schedule.isEmpty()) sb.append("   ").append(schedule);
        detail.setText(sb.toString());
        row.setOnClickListener(v -> editDevice(device, false));
        row.setOnLongClickListener(v -> {
            confirmDelete(device);
            return true;
        });
        return row;
    }

    /** 非默认星期/时段才在行里给出摘要,默认(每天全天)不占地方 */
    private String scheduleSummary(EqosDevice d) {
        boolean allDay = ("00:00".equals(d.timeStart) || d.timeStart.isEmpty())
                && ("23:55".equals(d.timeEnd) || "23:59".equals(d.timeEnd) || d.timeEnd.isEmpty());
        String week = weekLabel(d.week);
        if (allDay && "0".equals(d.week == null || d.week.isEmpty() ? "0" : d.week)) return "";
        StringBuilder sb = new StringBuilder();
        if (!"0".equals(d.week == null || d.week.isEmpty() ? "0" : d.week)) sb.append(week);
        if (!allDay) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(d.timeStart).append("–").append(d.timeEnd);
        }
        return sb.toString();
    }

    private String weekLabel(String week) {
        String[] labels = getResources().getStringArray(R.array.eqos_week_labels);
        String w = week == null || week.isEmpty() ? "0" : week;
        for (int i = 0; i < WEEK_VALUES.length && i < labels.length; i++) {
            if (WEEK_VALUES[i].equals(w)) return labels[i];
        }
        return w;
    }

    // =====================================================================
    // 全局设置
    // =====================================================================

    private void editBandwidth(boolean isDownload) {
        EditText input = decimalInput(isDownload ? global.download : global.upload);
        new MaterialAlertDialogBuilder(this)
                .setTitle(isDownload ? R.string.eqos_wan_down : R.string.eqos_wan_up)
                .setMessage(R.string.eqos_bandwidth_hint)
                .setView(wrap(input))
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!isPositiveRate(value)) {
                        error(getString(R.string.eqos_positive_required));
                        return;
                    }
                    EqosGlobal next = copyGlobal(global.enabled);
                    if (isDownload) {
                        next.download = normalizeRate(value);
                    } else {
                        next.upload = normalizeRate(value);
                    }
                    saveGlobal(next, ok -> {
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private interface Done {
        void onDone(boolean ok);
    }

    private void saveGlobal(EqosGlobal next, Done done) {
        EqosApi.saveGlobal(next, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                global = next;
                render();
                done.onDone(true);
            }

            @Override
            public void onFailure(ApiError err) {
                error(friendlyMessage(err));
                done.onDone(false);
            }
        });
    }

    // =====================================================================
    // 设备增删改
    // =====================================================================

    private void addDeviceTapped() {
        if (!loaded) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.eqos_add_device)
                .setItems(new CharSequence[]{
                        getString(R.string.eqos_pick_connected),
                        getString(R.string.eqos_enter_ip)
                }, (d, which) -> {
                    if (which == 0) {
                        pickFromConnectedDevices();
                    } else {
                        editDevice(null, true);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 手机上敲 IP 太费劲,直接从租约列表里挑 */
    private void pickFromConnectedDevices() {
        new ConnectedDeviceLoader(OpenWrtApi.getInstance(), NetworkUtils.getLocalIpv4(this))
                .load(all -> {
                    // 已经限速过的不再列出来
                    Set<String> limited = new HashSet<>();
                    for (EqosDevice d : devices) limited.add(d.target);
                    List<ConnectedDevice> candidates = new ArrayList<>();
                    for (ConnectedDevice d : all) {
                        if (d.ipAddress == null || limited.contains(d.ipAddress)) continue;
                        candidates.add(d);
                        if (candidates.size() >= 20) break;
                    }
                    if (candidates.isEmpty()) {
                        error(getString(R.string.eqos_no_candidates));
                        return;
                    }
                    CharSequence[] labels = new CharSequence[candidates.size()];
                    for (int i = 0; i < candidates.size(); i++) {
                        ConnectedDevice d = candidates.get(i);
                        labels[i] = d.getPrimaryTitle().equals(d.ipAddress)
                                ? d.ipAddress : d.getPrimaryTitle() + " · " + d.ipAddress;
                    }
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.eqos_pick_title)
                            .setItems(labels, (dialog, which) -> {
                                ConnectedDevice d = candidates.get(which);
                                EqosDevice prefilled = new EqosDevice();
                                prefilled.target = d.ipAddress;
                                prefilled.comment = d.getPrimaryTitle().equals(d.ipAddress)
                                        ? "" : d.getPrimaryTitle();
                                editDevice(prefilled, true);
                            })
                            .setNegativeButton(R.string.action_cancel, null)
                            .show();
                });
    }

    /** existing == null 表示手动新增;creating 用于区分「从连接设备预填」也是新增 */
    private void editDevice(EqosDevice existing, boolean creating) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);

        EditText comment = textInput(getString(R.string.eqos_comment_hint),
                existing != null ? existing.comment : "");
        EditText target = textInput(getString(R.string.eqos_target_hint),
                existing != null ? existing.target : "");
        // 已有条目的目标就是 uci 里的匹配键,改它等于换一条规则,容易误操作
        target.setEnabled(creating);
        EditText down = decimalInput(existing != null ? existing.download : "");
        down.setHint(R.string.eqos_down_hint);
        EditText up = decimalInput(existing != null ? existing.upload : "");
        up.setHint(R.string.eqos_up_hint);

        // 星期:预置选项;遇到网页端填的自定义组合(如 1,3,5)原样保留为首项
        String currentWeek = existing != null && existing.week != null && !existing.week.isEmpty()
                ? existing.week : "0";
        List<String> weekValues = new ArrayList<>();
        List<String> weekLabels = new ArrayList<>();
        String[] presetLabels = getResources().getStringArray(R.array.eqos_week_labels);
        int selectedWeek = -1;
        for (int i = 0; i < WEEK_VALUES.length; i++) {
            weekValues.add(WEEK_VALUES[i]);
            weekLabels.add(i < presetLabels.length ? presetLabels[i] : WEEK_VALUES[i]);
            if (WEEK_VALUES[i].equals(currentWeek)) selectedWeek = i;
        }
        if (selectedWeek < 0) {
            weekValues.add(0, currentWeek);
            weekLabels.add(0, currentWeek);
            selectedWeek = 0;
        }
        TextView weekTitle = new TextView(this);
        weekTitle.setText(R.string.eqos_week_label);
        weekTitle.setTextSize(13);
        weekTitle.setTextColor(getColor(R.color.label_secondary));
        weekTitle.setPadding(0, dp(10), 0, 0);
        Spinner week = new Spinner(this);
        week.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, weekLabels));
        week.setSelection(selectedWeek);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText start = textInput("00:00", existing != null ? existing.timeStart : "00:00");
        EditText end = textInput("23:55", existing != null ? existing.timeEnd : "23:55");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        timeRow.addView(start, half);
        timeRow.addView(end, half);

        TextView timeTitle = new TextView(this);
        timeTitle.setText(R.string.eqos_time_label);
        timeTitle.setTextSize(13);
        timeTitle.setTextColor(getColor(R.color.label_secondary));
        timeTitle.setPadding(0, dp(10), 0, 0);

        form.addView(comment);
        form.addView(target);
        form.addView(down);
        form.addView(up);
        form.addView(weekTitle);
        form.addView(week);
        form.addView(timeTitle);
        form.addView(timeRow);

        new MaterialAlertDialogBuilder(this)
                .setTitle(creating ? R.string.eqos_add_device : R.string.eqos_edit_device)
                .setMessage(R.string.eqos_device_hint)
                .setView(form)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    String targetText = target.getText().toString().trim();
                    String downText = down.getText().toString().trim();
                    String upText = up.getText().toString().trim();
                    if (!isValidTarget(targetText)) {
                        error(getString(R.string.eqos_invalid_target));
                        return;
                    }
                    // eqosplus 允许单个方向为 0(=该方向不限),但两个不能同时为 0/空
                    if (!isNonNegativeRate(downText) || !isNonNegativeRate(upText)
                            || (parseRate(downText) == 0 && parseRate(upText) == 0)) {
                        error(getString(R.string.eqos_both_required));
                        return;
                    }
                    String startText = start.getText().toString().trim();
                    String endText = end.getText().toString().trim();
                    if (!isValidClock(startText) || !isValidClock(endText)) {
                        error(getString(R.string.eqos_invalid_time));
                        return;
                    }
                    EqosDevice device = new EqosDevice();
                    device.section = existing != null ? existing.section : "";
                    device.enabled = existing == null || existing.enabled;
                    device.target = targetText;
                    device.download = normalizeRate(downText);
                    device.upload = normalizeRate(upText);
                    device.comment = comment.getText().toString().trim();
                    device.week = weekValues.get(week.getSelectedItemPosition());
                    device.timeStart = startText;
                    device.timeEnd = endText;
                    if (creating) {
                        createDevice(device);
                    } else {
                        saveDevice(device);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void createDevice(EqosDevice device) {
        EqosApi.addDevice(device, new ApiCallback<String>() {
            @Override
            public void onSuccess(String section) {
                loadSettings();
            }

            @Override
            public void onFailure(ApiError err) {
                error(friendlyMessage(err));
            }
        });
    }

    private void saveDevice(EqosDevice device) {
        EqosApi.updateDevice(device, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                loadSettings();
            }

            @Override
            public void onFailure(ApiError err) {
                error(friendlyMessage(err));
            }
        });
    }

    private void confirmDelete(EqosDevice device) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.eqos_remove_title)
                .setMessage(getString(R.string.eqos_remove_msg, device.displayName()))
                .setPositiveButton(R.string.ok, (d, w) ->
                        EqosApi.deleteDevice(device.section, new ApiCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean ok) {
                                loadSettings();
                            }

                            @Override
                            public void onFailure(ApiError err) {
                                error(friendlyMessage(err));
                            }
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 目标可以是单个 IPv4、MAC、IP 段(a-b)或带掩码的 IPv4(a/n) */
    static boolean isValidTarget(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        if (t.matches("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")) return true;
        int dash = t.indexOf('-');
        if (dash > 0) {
            return isValidIPv4(t.substring(0, dash).trim())
                    && isValidIPv4(t.substring(dash + 1).trim());
        }
        int slash = t.indexOf('/');
        if (slash > 0) {
            if (!isValidIPv4(t.substring(0, slash).trim())) return false;
            try {
                int mask = Integer.parseInt(t.substring(slash + 1).trim());
                return mask >= 0 && mask <= 32;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return isValidIPv4(t);
    }

    static boolean isValidIPv4(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            int v = Integer.parseInt(part);
            if (v < 0 || v > 255) return false;
        }
        return true;
    }

    static boolean isValidClock(String s) {
        if (s == null) return false;
        String[] parts = s.trim().split(":", -1);
        if (parts.length != 2) return false;
        try {
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            return h >= 0 && h <= 24 && m >= 0 && m <= 59 && (h != 24 || m == 0);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double parseRate(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isPositiveRate(String s) {
        return parseRate(s) > 0;
    }

    private static boolean isNonNegativeRate(String s) {
        return s != null && !s.trim().isEmpty() && parseRate(s) >= 0;
    }

    /** 规整速率文本:去掉无意义的小数尾零(1.50 → 1.5,2.0 → 2) */
    static String normalizeRate(String s) {
        double v = parseRate(s);
        if (v < 0) return s == null ? "" : s.trim();
        if (v == Math.floor(v) && v < 1e15) return String.valueOf((long) v);
        String out = String.valueOf(v);
        return out;
    }

    private EditText decimalInput(String value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(value);
        return e;
    }

    private EditText textInput(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private View wrap(View child) {
        LinearLayout box = new LinearLayout(this);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(child, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void error(String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.eqos_action_failed)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private View separator() {
        View line = new View(this);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(dp(16));
        line.setLayoutParams(lp);
        line.setBackgroundColor(getColor(R.color.separator));
        return line;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
