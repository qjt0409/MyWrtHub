package com.whykangkang.wrthub.ui.devices;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.ArpScanApi;
import com.whykangkang.wrthub.api.OafApi;
import com.whykangkang.wrthub.api.OpenWrtApi;
import com.whykangkang.wrthub.manager.BlacklistDateStore;
import com.whykangkang.wrthub.manager.DeviceNameStore;
import com.whykangkang.wrthub.model.ConnectedDevice;
import com.whykangkang.wrthub.ui.common.SignalBarsView;
import com.whykangkang.wrthub.ui.services.packages.PackageManagerActivity;
import com.whykangkang.wrthub.util.Formatters;
import com.whykangkang.wrthub.util.NetworkUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 终端页(OPAssistant 同款):主数据来自 OpenAppFilter 的 fwx
 * get_all_users(flag=3)——实时上下行速率、今日流量、今日活跃时长、
 * 常用 App、在线/活跃状态,3 秒轮询刷新;DHCP 租约 + WiFi 关联列表
 * 只做补充(信号/SSID/本机标记),租约里有、fwx 里没有的设备排在后面,
 * 点开走老详情。fwx 不可用(没装 OpenAppFilter)时整页退回租约列表。
 *
 * 原有的防火墙黑名单(BlacklistActivity)是本 App 自己的一套封禁,
 * 与终端详情里的 fwx 上网黑名单是两回事,不要混。
 */
public class DevicesFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private TextView summaryView;
    private DeviceAdapter adapter;
    private ConnectedDeviceLoader loader;
    private boolean loading;

    /** 租约/扫描侧的设备(补充信息来源 + fwx 没有时的兜底列表) */
    private final List<ConnectedDevice> devices = new ArrayList<>();
    /** fwx 终端列表(主数据) */
    private final List<OafApi.FwxUser> users = new ArrayList<>();
    private boolean usersLoaded;
    /** 合并后的行:fwx 终端在前,仅租约设备在后 */
    private final List<Row> rows = new ArrayList<>();
    /** 已封禁的 MAC(大写),来自防火墙规则(老详情弹窗用) */
    private final Set<String> blacklistedMacs = new HashSet<>();
    /** MAC(大写) -> 封禁规则的 uci section,解除时用 */
    private final Map<String, String> blockSections = new HashMap<>();
    /** 深度扫描结果,刷新后要并回列表 */
    private final List<ArpScanApi.Entry> scanEntries = new ArrayList<>();
    /** 只有扫描发现、租约里没有的 MAC */
    private final Set<String> scanOnlyMacs = new HashSet<>();

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            refreshUsers(null);
            pollHandler.postDelayed(this, 3000);
        }
    };

    /** 一行 = 一台终端;user 为 null 表示只有租约/扫描数据 */
    private static final class Row {
        final OafApi.FwxUser user;
        final ConnectedDevice conn;

        Row(OafApi.FwxUser user, ConnectedDevice conn) {
            this.user = user;
            this.conn = conn;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_devices, container, false);
        swipeRefresh = root.findViewById(R.id.swipe_refresh);
        summaryView = root.findViewById(R.id.devices_summary);
        RecyclerView list = root.findViewById(R.id.devices_list);
        adapter = new DeviceAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::reload);
        root.findViewById(R.id.btn_blacklist).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), BlacklistActivity.class)));
        root.findViewById(R.id.btn_deep_scan).setOnClickListener(v -> startDeepScan());
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        OpenWrtApi.getInstance().resetSessionIfStale();
        String localIp = NetworkUtils.getLocalIpv4(requireContext());
        loader = new ConnectedDeviceLoader(OpenWrtApi.getInstance(), localIp);
        reload();
        pollHandler.removeCallbacks(pollTask);
        pollHandler.postDelayed(pollTask, 3000);
    }

    @Override
    public void onPause() {
        pollHandler.removeCallbacks(pollTask);
        super.onPause();
    }

    // =====================================================================
    // 数据
    // =====================================================================

    private void reload() {
        if (loading || !OpenWrtApi.getInstance().isConfigured()) {
            swipeRefresh.setRefreshing(false);
            return;
        }
        loading = true;
        swipeRefresh.setRefreshing(true);
        loadBlacklist(() -> loader.load(result -> {
            devices.clear();
            devices.addAll(result);
            // 刷新会用租约列表覆盖 devices,把之前扫到的额外设备再并回去
            mergeScanResults();
            refreshUsers(() -> {
                loading = false;
                if (!isAdded()) return;
                swipeRefresh.setRefreshing(false);
            });
        }));
    }

    /** 拉一次 fwx 终端列表并重建行;失败时保留上次结果(轮询不清空界面) */
    private void refreshUsers(@Nullable Runnable done) {
        if (!OpenWrtApi.getInstance().isConfigured()) {
            if (done != null) done.run();
            return;
        }
        OafApi.loadUsers(new ApiCallback<List<OafApi.FwxUser>>() {
            @Override
            public void onSuccess(List<OafApi.FwxUser> list) {
                users.clear();
                users.addAll(list);
                usersLoaded = true;
                rebuildRows();
                if (isAdded()) adapter.notifyDataSetChanged();
                if (done != null) done.run();
            }

            @Override
            public void onFailure(ApiError error) {
                // fwx 不可用:整页退回租约列表(行重建一次即可)
                if (!usersLoaded) {
                    users.clear();
                    rebuildRows();
                    if (isAdded()) adapter.notifyDataSetChanged();
                }
                if (done != null) done.run();
            }
        });
    }

    /** fwx 终端(按 活跃→在线→离线 排序)在前,仅租约设备补在后面 */
    private void rebuildRows() {
        Map<String, ConnectedDevice> connByMac = new HashMap<>();
        for (ConnectedDevice d : devices) {
            if (d.macAddress != null) {
                connByMac.put(d.macAddress.toLowerCase(Locale.ROOT), d);
            }
        }
        List<OafApi.FwxUser> sorted = new ArrayList<>(users);
        sorted.sort((a, b) -> Integer.compare(rank(a), rank(b)));

        rows.clear();
        Set<String> seen = new HashSet<>();
        for (OafApi.FwxUser u : sorted) {
            String key = u.mac.toLowerCase(Locale.ROOT);
            seen.add(key);
            rows.add(new Row(u, connByMac.get(key)));
        }
        for (ConnectedDevice d : devices) {
            if (d.macAddress != null && seen.contains(d.macAddress.toLowerCase(Locale.ROOT))) {
                continue;
            }
            rows.add(new Row(null, d));
        }
        updateSummary();
    }

    private static int rank(OafApi.FwxUser u) {
        if (u.online && u.active) return 0;
        if (u.online) return 1;
        return 2;
    }

    private void updateSummary() {
        if (summaryView == null) return;
        if (!usersLoaded) {
            summaryView.setVisibility(View.GONE);
            return;
        }
        int online = 0;
        int active = 0;
        for (OafApi.FwxUser u : users) {
            if (u.online) {
                online++;
                if (u.active) active++;
            }
        }
        summaryView.setVisibility(View.VISIBLE);
        summaryView.setText(getString(R.string.term_summary, users.size(), online, active));
    }

    /** 读防火墙里由本 App 添加的封禁规则,拿到 MAC 集合与 section 映射 */
    private void loadBlacklist(Runnable then) {
        OpenWrtApi.getInstance().uciGetConfig("firewall", new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                blacklistedMacs.clear();
                blockSections.clear();
                JsonObject values = result.has("values") && result.get("values").isJsonObject()
                        ? result.getAsJsonObject("values") : result;
                for (String section : values.keySet()) {
                    JsonElement el = values.get(section);
                    if (!el.isJsonObject()) continue;
                    JsonObject rule = el.getAsJsonObject();
                    String name = rule.has("name") ? rule.get("name").getAsString() : "";
                    if (!name.startsWith(OpenWrtApi.BLOCK_RULE_PREFIX)) continue;
                    String srcMac = OpenWrtApi.firstSrcMac(rule);
                    if (srcMac == null) continue;
                    String mac = srcMac.toUpperCase(Locale.ROOT);
                    blacklistedMacs.add(mac);
                    blockSections.put(mac, section);
                }
                then.run();
            }

            @Override
            public void onFailure(ApiError error) {
                // 黑名单读失败不影响设备列表显示
                blacklistedMacs.clear();
                blockSections.clear();
                then.run();
            }
        });
    }

    // =====================================================================
    // arp-scan 深度扫描
    // =====================================================================

    private void startDeepScan() {
        AlertDialog progress = new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.scan_deep_running)
                .setCancelable(false)
                .show();
        ArpScanApi.run(new ArpScanApi.Callback() {
            @Override
            public void onSuccess(List<ArpScanApi.Entry> entries) {
                progress.dismiss();
                if (!isAdded()) return;
                applyScanResults(entries);
            }

            @Override
            public void onFailure(ArpScanApi.Failure failure, String message) {
                progress.dismiss();
                if (!isAdded()) return;
                switch (failure) {
                    case NOT_INSTALLED:
                        showArpScanNotInstalled();
                        break;
                    case PERMISSION_DENIED:
                        showArpScanPermissionDenied();
                        break;
                    default:
                        alert(getString(R.string.scan_deep_failed, message));
                }
            }
        });
    }

    private void applyScanResults(List<ArpScanApi.Entry> entries) {
        scanEntries.clear();
        scanEntries.addAll(entries);
        int before = rows.size();
        mergeScanResults();
        rebuildRows();
        adapter.notifyDataSetChanged();
        int added = rows.size() - before;
        alert(added > 0
                ? getString(R.string.scan_deep_found, entries.size(), added)
                : getString(R.string.scan_deep_found_none, entries.size()));
    }

    /**
     * 把扫描到、但租约列表里没有的设备并入 devices。
     * 幂等:重复调用(每次刷新后都会调)不会产生重复行。
     */
    private void mergeScanResults() {
        scanOnlyMacs.clear();
        if (scanEntries.isEmpty()) return;
        Set<String> known = new HashSet<>();
        for (ConnectedDevice d : devices) {
            if (d.macAddress != null) known.add(d.macAddress.toUpperCase(Locale.ROOT));
        }
        for (ArpScanApi.Entry entry : scanEntries) {
            if (!known.add(entry.mac)) continue;
            ConnectedDevice d = new ConnectedDevice();
            d.ipAddress = entry.ip;
            d.macAddress = entry.mac;
            d.hostname = entry.vendor.isEmpty() ? null : entry.vendor;
            devices.add(d);
            scanOnlyMacs.add(entry.mac);
        }
    }

    private void showArpScanNotInstalled() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.scan_arp_missing_title)
                .setMessage(R.string.scan_arp_missing_msg)
                .setPositiveButton(R.string.svc_go_install, (d, w) -> {
                    Intent intent = new Intent(requireContext(), PackageManagerActivity.class);
                    intent.putExtra(PackageManagerActivity.EXTRA_PREFILL, "arp-scan");
                    startActivity(intent);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showArpScanPermissionDenied() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.scan_arp_denied_title)
                .setMessage(R.string.scan_arp_denied_msg)
                .setPositiveButton(R.string.scan_copy_command, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) requireContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("arp-scan ACL",
                            ArpScanApi.ACL_COMMAND));
                    alert(getString(R.string.scan_copied));
                })
                .setNegativeButton(R.string.ok, null)
                .show();
    }

    // =====================================================================
    // 详情 / 重命名 / 黑名单(仅租约行走老详情;fwx 行进终端详情页)
    // =====================================================================

    private String displayName(ConnectedDevice d) {
        String custom = DeviceNameStore.get(requireContext(), d.macAddress);
        return custom != null ? custom : d.getPrimaryTitle();
    }

    private String displayName(OafApi.FwxUser u) {
        String custom = DeviceNameStore.get(requireContext(), u.mac);
        return custom != null ? custom : u.displayName();
    }

    private void openTerminalDetail(Row row) {
        Intent intent = new Intent(requireContext(), TerminalDeviceActivity.class);
        intent.putExtra(TerminalDeviceActivity.EXTRA_USER_JSON, row.user.raw.toString());
        if (row.conn != null) {
            if (row.conn.ssid != null) {
                intent.putExtra(TerminalDeviceActivity.EXTRA_SSID, row.conn.ssid);
            }
            if (row.conn.signalDbm != null) {
                intent.putExtra(TerminalDeviceActivity.EXTRA_SIGNAL, row.conn.signalDbm);
            }
            intent.putExtra(TerminalDeviceActivity.EXTRA_LOCAL, row.conn.isLocal);
        }
        startActivity(intent);
    }

    private void showDetail(ConnectedDevice d) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, getString(R.string.detail_ip), d.ipAddress);
        appendRow(sb, getString(R.string.detail_mac), d.macAddress);
        if (d.ssid != null) {
            appendRow(sb, getString(R.string.detail_ssid), d.ssid);
        }
        if (d.signalDbm != null) {
            appendRow(sb, getString(R.string.detail_signal), d.signalDbm + " dBm");
        }
        if (d.leaseRemainingSec > 0) {
            appendRow(sb, getString(R.string.detail_lease), leaseRemaining(d.leaseRemainingSec));
        }
        if (d.rxBytes > 0 || d.txBytes > 0) {
            appendRow(sb, getString(R.string.detail_traffic),
                    Formatters.bytes(d.rxBytes) + " / " + Formatters.bytes(d.txBytes));
        }

        boolean blocked = d.macAddress != null
                && blacklistedMacs.contains(d.macAddress.toUpperCase(Locale.ROOT));
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(displayName(d))
                .setMessage(sb.toString().trim())
                .setNeutralButton(R.string.device_rename, (dialog, w) -> renameDevice(d.macAddress, d.getPrimaryTitle()))
                .setNegativeButton(R.string.ok, null);
        // 本机设备不允许操作黑名单
        if (!d.isLocal && d.macAddress != null) {
            builder.setPositiveButton(blocked ? R.string.action_unblock : R.string.action_block,
                    (dialog, w) -> {
                        if (blocked) {
                            confirmUnblock(d);
                        } else {
                            confirmBlock(d);
                        }
                    });
        }
        builder.show();
    }

    /** 重命名:自定义名按 MAC 存本地,留空恢复默认(主机名/IP) */
    private void renameDevice(String mac, String hintName) {
        if (mac == null) return;
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(hintName);
        String current = DeviceNameStore.get(requireContext(), mac);
        if (current != null) input.setText(current);
        LinearLayout box = new LinearLayout(requireContext());
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.device_rename)
                .setMessage(R.string.device_rename_hint)
                .setView(box)
                .setPositiveButton(R.string.ok, (dialog, w) -> {
                    DeviceNameStore.set(requireContext(), mac,
                            input.getText().toString());
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmBlock(ConnectedDevice d) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_block)
                .setMessage(getString(R.string.confirm_block, displayName(d)))
                .setPositiveButton(R.string.action_block, (dialog, w) -> doBlock(d))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void doBlock(ConnectedDevice d) {
        OpenWrtApi.getInstance().blockDevice(d.macAddress, displayName(d),
                new ApiCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        if (!isAdded()) return;
                        // 记一笔加入时间,黑名单页要显示(iOS 的 addedDate)
                        BlacklistDateStore.markAdded(requireContext(), d.macAddress);
                        alert(getString(R.string.msg_block_ok));
                        reload();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        if (!isAdded()) return;
                        alert(getString(R.string.msg_action_failed, error.getMessage()));
                    }
                });
    }

    private void confirmUnblock(ConnectedDevice d) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_unblock)
                .setMessage(getString(R.string.confirm_unblock, displayName(d)))
                .setPositiveButton(R.string.action_unblock, (dialog, w) -> doUnblock(d))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void doUnblock(ConnectedDevice d) {
        String section = blockSections.get(d.macAddress.toUpperCase(Locale.ROOT));
        if (section == null) return;
        OpenWrtApi.getInstance().unblockDevice(section, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                if (!isAdded()) return;
                BlacklistDateStore.clear(requireContext(), d.macAddress);
                alert(getString(R.string.msg_unblock_ok));
                reload();
            }

            @Override
            public void onFailure(ApiError error) {
                if (!isAdded()) return;
                alert(getString(R.string.msg_action_failed, error.getMessage()));
            }
        });
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private void alert(String message) {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void appendRow(StringBuilder sb, String label, String value) {
        if (value == null) return;
        sb.append(label).append(": ").append(value).append('\n');
    }

    /** 列表里的租约行:租约 N小时M分 / 租约 M分钟 */
    private String leaseLine(long remainingSec) {
        int hours = (int) (remainingSec / 3600);
        int minutes = (int) ((remainingSec % 3600) / 60);
        return hours > 0
                ? getString(R.string.lease_line_hm, hours, minutes)
                : getString(R.string.lease_line_m, minutes);
    }

    /** remaining 已经是剩余秒数,不要再和当前时间相减 */
    private String leaseRemaining(long remainingSec) {
        if (remainingSec <= 0) return getString(R.string.lease_expired);
        int hours = (int) (remainingSec / 3600);
        int minutes = (int) ((remainingSec % 3600) / 60);
        return getString(R.string.lease_hm, hours, minutes);
    }

    /** 今日活跃时长:15小时58分 / 58分钟 / 30秒 */
    static String activeDuration(long sec) {
        if (sec <= 0) return "0分钟";
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        if (h > 0) return h + "小时" + m + "分";
        if (m > 0) return m + "分钟";
        return sec + "秒";
    }

    private static final int[] AVATAR_COLORS = {
            0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFFE53935,
            0xFF00ACC1, 0xFF5E35B1, 0xFF6D4C41, 0xFF3949AB, 0xFF00897B
    };

    /** 名字首个字母/数字(中文取首字),给头像与 App 方块用 */
    static String firstLetter(String name) {
        if (name == null) return "?";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "?";
    }

    static int colorFor(String seed) {
        int h = seed == null ? 0 : (seed.hashCode() & 0x7fffffff);
        return AVATAR_COLORS[h % AVATAR_COLORS.length];
    }

    private void setAvatar(TextView v, String name, String seed) {
        v.setText(firstLetter(name));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(12 * getResources().getDisplayMetrics().density);
        bg.setColor(colorFor(seed));
        v.setBackground(bg);
    }

    // =====================================================================
    // 列表
    // =====================================================================

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_terminal_device, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row row = rows.get(position);
            if (row.user != null) {
                bindUser(h, row);
            } else {
                bindConn(h, row.conn);
            }
        }

        private void bindUser(Holder h, Row row) {
            OafApi.FwxUser u = row.user;
            String name = displayName(u);
            setAvatar(h.avatar, name, u.mac);
            h.title.setText(name);

            // 状态徽标:活跃(绿)/ 在线(蓝)/ 离线(灰);被拉黑另有红徽章
            if (u.online && u.active) {
                h.badge.setText(R.string.term_active);
                h.badge.setTextColor(requireContext().getColor(R.color.ios_green));
            } else if (u.online) {
                h.badge.setText(R.string.term_online);
                h.badge.setTextColor(requireContext().getColor(R.color.ios_blue));
            } else {
                h.badge.setText(R.string.term_offline);
                h.badge.setTextColor(requireContext().getColor(R.color.label_tertiary));
            }
            h.blockedBadge.setVisibility(u.inBlacklist ? View.VISIBLE : View.GONE);

            h.ip.setVisibility(u.ip.isEmpty() ? View.GONE : View.VISIBLE);
            h.ip.setText(u.ip);
            h.rates.setVisibility(View.VISIBLE);
            h.rates.setText("↑ " + Formatters.rate(u.upRate)
                    + "   ↓ " + Formatters.rate(u.downRate));
            h.meta.setVisibility(View.VISIBLE);
            h.meta.setText("⏱ " + activeDuration(u.todayActiveSec)
                    + "    ↕ " + Formatters.bytes(u.todayTotalBytes()));
            bindAppBadges(h, u);

            bindWifi(h, row.conn);
            h.itemView.setOnClickListener(v -> openTerminalDetail(row));
            h.itemView.setOnLongClickListener(v -> {
                renameDevice(u.mac, u.displayName());
                return true;
            });
        }

        /** 当前应用 1 个 + 常用其余,最多 5 个彩色首字母方块(与 OPAssistant 一致) */
        private void bindAppBadges(Holder h, OafApi.FwxUser u) {
            h.apps.removeAllViews();
            List<String> names = new ArrayList<>();
            if (!u.currentApp.isEmpty()) names.add(u.currentApp);
            for (OafApi.App a : u.apps) {
                if (!names.contains(a.name)) names.add(a.name);
                if (names.size() >= 5) break;
            }
            h.apps.setVisibility(names.isEmpty() ? View.GONE : View.VISIBLE);
            float density = getResources().getDisplayMetrics().density;
            for (String appName : names) {
                TextView sq = new TextView(requireContext());
                int size = Math.round(24 * density);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMarginEnd(Math.round(5 * density));
                sq.setLayoutParams(lp);
                sq.setGravity(android.view.Gravity.CENTER);
                sq.setText(firstLetter(appName));
                sq.setTextColor(0xFFFFFFFF);
                sq.setTextSize(11);
                sq.setTypeface(null, android.graphics.Typeface.BOLD);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(6 * density);
                bg.setColor(colorFor(appName));
                sq.setBackground(bg);
                h.apps.addView(sq);
            }
        }

        private void bindConn(Holder h, ConnectedDevice d) {
            String mac = d.macAddress != null ? d.macAddress.toUpperCase(Locale.ROOT) : "";
            boolean blocked = blacklistedMacs.contains(mac);
            boolean scanOnly = scanOnlyMacs.contains(mac);
            String name = displayName(d);
            setAvatar(h.avatar, name, d.macAddress);
            h.title.setText(name);

            if (blocked) {
                h.badge.setText(R.string.device_blocked_badge);
                h.badge.setTextColor(requireContext().getColor(R.color.ios_red));
            } else if (scanOnly) {
                h.badge.setText(R.string.lease_from_scan);
                h.badge.setTextColor(requireContext().getColor(R.color.label_tertiary));
            } else {
                h.badge.setText("");
            }
            h.blockedBadge.setVisibility(View.GONE);

            String secondary = d.getSecondaryTitle();
            boolean customNamed = DeviceNameStore.get(requireContext(), d.macAddress) != null;
            if (secondary == null && customNamed) secondary = d.ipAddress;
            if (secondary == null) secondary = d.ipAddress;
            h.ip.setVisibility(secondary != null ? View.VISIBLE : View.GONE);
            h.ip.setText(secondary);
            h.rates.setVisibility(View.GONE);

            // 租约行:静态 IP / 无租约的不占这一行,不然半屏都是「租约 0分钟」
            long total = d.rxBytes + d.txBytes;
            if (d.leaseRemainingSec >= 60) {
                h.meta.setVisibility(View.VISIBLE);
                h.meta.setText(leaseLine(d.leaseRemainingSec)
                        + (total > 0 ? "    ↕ " + Formatters.bytes(total) : ""));
            } else if (total > 0) {
                h.meta.setVisibility(View.VISIBLE);
                h.meta.setText("↕ " + Formatters.bytes(total));
            } else {
                h.meta.setVisibility(View.GONE);
            }
            h.apps.setVisibility(View.GONE);
            bindWifi(h, d);
            h.itemView.setOnClickListener(v -> showDetail(d));
            h.itemView.setOnLongClickListener(v -> {
                renameDevice(d.macAddress, d.getPrimaryTitle());
                return true;
            });
        }

        private void bindWifi(Holder h, @Nullable ConnectedDevice d) {
            boolean wifi = d != null && d.ssid != null && !d.ssid.isEmpty();
            h.wifiRow.setVisibility(wifi ? View.VISIBLE : View.GONE);
            if (wifi) {
                h.ssid.setText(d.ssid);
                int bars = d.signalBars();
                h.signalBars.setVisibility(bars >= 0 ? View.VISIBLE : View.GONE);
                h.signalBars.setBars(bars);
            }
            h.localBadge.setVisibility(d != null && d.isLocal ? View.VISIBLE : View.GONE);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView avatar, title, badge, blockedBadge, ip, rates, meta, ssid, localBadge;
            final LinearLayout apps;
            final View wifiRow;
            final SignalBarsView signalBars;

            Holder(@NonNull View v) {
                super(v);
                avatar = v.findViewById(R.id.term_avatar);
                title = v.findViewById(R.id.term_title);
                badge = v.findViewById(R.id.term_badge);
                blockedBadge = v.findViewById(R.id.term_blocked_badge);
                ip = v.findViewById(R.id.term_ip);
                rates = v.findViewById(R.id.term_rates);
                meta = v.findViewById(R.id.term_meta);
                apps = v.findViewById(R.id.term_apps);
                ssid = v.findViewById(R.id.device_ssid);
                localBadge = v.findViewById(R.id.device_local_badge);
                wifiRow = v.findViewById(R.id.wifi_row);
                signalBars = v.findViewById(R.id.signal_bars);
            }
        }
    }
}
