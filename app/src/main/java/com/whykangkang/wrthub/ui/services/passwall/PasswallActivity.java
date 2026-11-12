package com.whykangkang.wrthub.ui.services.passwall;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.PasswallApi;
import com.whykangkang.wrthub.model.PasswallNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PassWall / PassWall2 页:按 LuCI 状态页布局 ——
 * 运行状态+主开关一行、节点选择、国内(百度)/国外(谷歌)网络监测、
 * 国内/国外 IP、订阅管理(增删 + subscribe.lua 更新)、按 group 分组的
 * 节点列表;其后是代理设置卡(含快捷模式)与 DNS 卡。
 * 数据与 LuCI 同源(uci),改完 apply + 重启服务生效。
 * 配置里没有的键整行隐藏 —— PassWall2 只有本机/局域网/远程 DNS 三项。
 */
public class PasswallActivity extends AppCompatActivity {

    private TextView statusText, currentNodeLabel, emptyLabel, modeCustomLabel;
    private View statusDot;
    private View statusSpinner;
    private MaterialButton btnRestart, btnRefresh;
    private SwitchMaterial mainSwitch;
    private TextView pickName, pickMeta, checkCnValue, checkGValue, ipCnValue, ipGValue;
    private TextView compTcp, compUdp, compDns, compHaproxy;
    private PasswallApi.ComponentStatus compStatus;
    private LinearLayout subRows;
    private TextView subEmpty;
    private NestedScrollView scrollView;
    private View nodesHeader;
    private MaterialButtonToggleGroup modeGroup, segTcp, segUdp, segChn;
    private SwitchMaterial swGfw, swLocalhost, swClient;
    private LinearLayout nodeRows;
    private TextView dnsShuntValue, dnsModeValue, remoteDnsValue;
    private SwipeRefreshLayout swipeRefresh;

    private PasswallApi.PwSettings settings;
    private Boolean running;
    /** 正在写配置时锁住全部控件,防连点 */
    private boolean busy;
    /** 程序回填控件值时别触发写回 */
    private boolean rendering;
    /** 网络检测进行中(进页面自动跑一次,防重入) */
    private boolean checkRunning;
    /** 节点分组的展开状态(组名 → 展开);默认全折叠,只展开当前节点所在组 */
    private final java.util.Set<String> expandedGroups = new java.util.HashSet<>();
    private boolean groupsInitialized;

    /** 配置名(passwall);PassWall2 子类覆写为 passwall2 */
    protected String pwConfig() {
        return PasswallApi.CONFIG;
    }

    /** 页标题字符串(PassWall / PassWall2) */
    protected int pwTitleRes() {
        return R.string.svc_passwall;
    }

    protected String pwName() {
        return getString(pwTitleRes());
    }

    /** 服务端二级页的类(PassWall2 子类覆写为它的服务端页) */
    protected Class<?> serverActivityClass() {
        return PasswallServerActivity.class;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_passwall);
        ((TextView) findViewById(R.id.pw_title)).setText(pwTitleRes());

        statusText = findViewById(R.id.pw_status_text);
        statusDot = findViewById(R.id.pw_status_dot);
        statusSpinner = findViewById(R.id.pw_status_spinner);
        currentNodeLabel = findViewById(R.id.pw_current_node);
        emptyLabel = findViewById(R.id.pw_empty);
        modeCustomLabel = findViewById(R.id.pw_mode_custom);
        nodeRows = findViewById(R.id.pw_node_rows);
        mainSwitch = findViewById(R.id.pw_main_switch);
        btnRestart = findViewById(R.id.pw_btn_restart);
        btnRefresh = findViewById(R.id.pw_btn_refresh);
        pickName = findViewById(R.id.pw_pick_name);
        pickMeta = findViewById(R.id.pw_pick_meta);
        checkCnValue = findViewById(R.id.pw_check_cn_value);
        checkGValue = findViewById(R.id.pw_check_g_value);
        ipCnValue = findViewById(R.id.pw_ip_cn_value);
        ipGValue = findViewById(R.id.pw_ip_g_value);
        compTcp = findViewById(R.id.pw_comp_tcp);
        compUdp = findViewById(R.id.pw_comp_udp);
        compDns = findViewById(R.id.pw_comp_dns);
        compHaproxy = findViewById(R.id.pw_comp_haproxy);
        subRows = findViewById(R.id.pw_sub_rows);
        subEmpty = findViewById(R.id.pw_sub_empty);
        scrollView = findViewById(R.id.pw_scroll);
        nodesHeader = findViewById(R.id.pw_nodes_header);
        modeGroup = findViewById(R.id.pw_mode_group);
        segTcp = findViewById(R.id.pw_seg_tcp);
        segUdp = findViewById(R.id.pw_seg_udp);
        segChn = findViewById(R.id.pw_seg_chn);
        swGfw = findViewById(R.id.pw_sw_gfw);
        swLocalhost = findViewById(R.id.pw_sw_localhost);
        swClient = findViewById(R.id.pw_sw_client);
        dnsShuntValue = findViewById(R.id.pw_dns_shunt_value);
        dnsModeValue = findViewById(R.id.pw_dns_mode_value);
        remoteDnsValue = findViewById(R.id.pw_remote_dns_value);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefresh.setOnRefreshListener(this::load);

        mainSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (rendering || busy || settings == null) return;
            if (checked != settings.enabled) toggleEnabled();
        });
        btnRestart.setOnClickListener(v -> {
            if (!busy) restartService();
        });
        btnRefresh.setOnClickListener(v -> {
            if (!busy) load();
        });
        findViewById(R.id.pw_pick_card).setOnClickListener(v ->
                scrollView.post(() -> scrollView.smoothScrollTo(0, nodesHeader.getTop())));
        findViewById(R.id.pw_server_card).setOnClickListener(v ->
                startActivity(new Intent(this, serverActivityClass())));
        View.OnClickListener checkClick = v -> {
            if (!busy && settings != null) runNetCheck();
        };
        findViewById(R.id.pw_check_cn_card).setOnClickListener(checkClick);
        findViewById(R.id.pw_check_g_card).setOnClickListener(checkClick);
        findViewById(R.id.pw_sub_add).setOnClickListener(v -> {
            if (!busy) showAddSubscription();
        });
        findViewById(R.id.pw_sub_update_all).setOnClickListener(v -> {
            if (!busy && settings != null && !settings.subscriptions.isEmpty()) {
                updateAllSubscriptions();
            }
        });

        modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (rendering || busy || !isChecked || settings == null) return;
            PasswallApi.QuickMode mode = quickModeForButton(checkedId);
            if (mode != null) applyQuickMode(mode);
        });
        segTcp.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (rendering || busy || !isChecked || settings == null) return;
            writeOption("tcp_proxy_mode",
                    checkedId == R.id.pw_tcp_proxy ? "proxy" : "disable");
        });
        segUdp.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (rendering || busy || !isChecked || settings == null) return;
            writeOption("udp_proxy_mode",
                    checkedId == R.id.pw_udp_proxy ? "proxy" : "disable");
        });
        segChn.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (rendering || busy || !isChecked || settings == null) return;
            String value = checkedId == R.id.pw_chn_direct ? "direct"
                    : checkedId == R.id.pw_chn_proxy ? "proxy" : "0";
            writeOption("chn_list", value);
        });
        swGfw.setOnCheckedChangeListener((v, checked) -> {
            if (rendering || busy || settings == null) return;
            writeOption("use_gfw_list", checked ? "1" : "0");
        });
        swLocalhost.setOnCheckedChangeListener((v, checked) -> {
            if (rendering || busy || settings == null) return;
            writeOption("localhost_proxy", checked ? "1" : "0");
        });
        swClient.setOnCheckedChangeListener((v, checked) -> {
            if (rendering || busy || settings == null) return;
            writeOption("client_proxy", checked ? "1" : "0");
        });

        findViewById(R.id.pw_row_dns_shunt).setOnClickListener(v -> {
            if (settings != null && !busy) pickDnsShunt();
        });
        findViewById(R.id.pw_row_dns_mode).setOnClickListener(v -> {
            if (settings != null && !busy) pickDnsMode();
        });
        findViewById(R.id.pw_row_remote_dns).setOnClickListener(v -> {
            if (settings != null && !busy) editRemoteDns();
        });

        load();
    }

    // =====================================================================
    // 加载与渲染
    // =====================================================================

    private void load() {
        swipeRefresh.setRefreshing(true);
        PasswallApi.getSettings(pwConfig(), new ApiCallback<PasswallApi.PwSettings>() {
            @Override
            public void onSuccess(PasswallApi.PwSettings result) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                settings = result;
                groupsInitialized = false;
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                showEmpty(error.getType() == ApiError.Type.PERMISSION_DENIED
                                || error.getType() == ApiError.Type.CONFIG_NOT_FOUND
                        ? getString(R.string.pw_not_installed, pwName())
                        : getString(R.string.pw_load_failed, pwName(), error.getMessage()));
            }
        });
        PasswallApi.serviceRunning(pwConfig(), new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (isFinishing() || isDestroyed()) return;
                running = result;
                renderState();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                running = null;
                renderState();
            }
        });
        // 组件状态以 LuCI 状态页的 index_status 为准(查的是核心进程本身,
        // procd 的 service list 在 PassWall 上报不准)
        PasswallApi.getComponentStatus(pwConfig(),
                new ApiCallback<PasswallApi.ComponentStatus>() {
                    @Override
                    public void onSuccess(PasswallApi.ComponentStatus result) {
                        if (isFinishing() || isDestroyed()) return;
                        compStatus = result;
                        renderState();
                        renderComponents();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        // 拿不到就保持 — 显示
                    }
                });
    }

    private void renderComponents() {
        if (compStatus == null) return;
        setCompText(compTcp, compStatus.tcp);
        setCompText(compUdp, compStatus.udp);
        setCompText(compDns, compStatus.dns);
        if (compStatus.haproxy == null) {
            compHaproxy.setText("—");
            compHaproxy.setTextColor(ContextCompat.getColor(this, R.color.label_secondary));
        } else if (compStatus.haproxy == -1) {
            compHaproxy.setText(R.string.pw_comp_absent);
            compHaproxy.setTextColor(ContextCompat.getColor(this, R.color.label_secondary));
        } else if (compStatus.haproxy == 0) {
            compHaproxy.setText(R.string.pw_state_running);
            compHaproxy.setTextColor(ContextCompat.getColor(this, R.color.ios_green));
        } else {
            compHaproxy.setText(R.string.pw_comp_stopped);
            compHaproxy.setTextColor(ContextCompat.getColor(this, R.color.ios_red));
        }
    }

    private void setCompText(TextView view, Boolean runningState) {
        if (runningState == null) {
            view.setText("—");
            view.setTextColor(ContextCompat.getColor(this, R.color.label_secondary));
        } else if (runningState) {
            view.setText(R.string.pw_state_running);
            view.setTextColor(ContextCompat.getColor(this, R.color.ios_green));
        } else {
            view.setText(R.string.pw_comp_stopped);
            view.setTextColor(ContextCompat.getColor(this, R.color.ios_red));
        }
    }

    private void render() {
        if (settings == null) return;
        rendering = true;
        renderState();

        PasswallNode current = settings.currentNode();
        currentNodeLabel.setText(current != null ? current.displayName()
                : getString(R.string.pw_no_node_selected));
        pickName.setText(current != null ? current.displayName()
                : getString(R.string.pw_no_node_selected));
        String pickMetaText = current != null ? current.subtitle() : "";
        pickMeta.setText(pickMetaText);
        pickMeta.setVisibility(pickMetaText.isEmpty() ? View.GONE : View.VISIBLE);
        renderSubscriptions();

        // 快捷模式(PassWall 才有这组键)
        boolean hasMode = settings.tcpProxyMode != null;
        findViewById(R.id.pw_mode_row).setVisibility(hasMode ? View.VISIBLE : View.GONE);
        PasswallApi.QuickMode qm = PasswallApi.quickModeOf(settings);
        modeCustomLabel.setVisibility(hasMode && qm == PasswallApi.QuickMode.CUSTOM
                ? View.VISIBLE : View.GONE);
        int modeButton = qm == PasswallApi.QuickMode.GFW ? R.id.pw_mode_gfw
                : qm == PasswallApi.QuickMode.CHNROUTE ? R.id.pw_mode_chn
                : qm == PasswallApi.QuickMode.GLOBAL ? R.id.pw_mode_global
                : qm == PasswallApi.QuickMode.RETURNHOME ? R.id.pw_mode_return : -1;
        if (modeButton != -1) {
            modeGroup.check(modeButton);
        } else {
            modeGroup.clearChecked();
        }

        // 代理设置卡:没有对应键的行整行隐藏,一行不剩时整卡隐藏
        boolean anySettingsRow = false;
        anySettingsRow |= rowVisible(R.id.pw_row_tcp, settings.tcpProxyMode != null);
        anySettingsRow |= rowVisible(R.id.pw_row_udp, settings.udpProxyMode != null);
        anySettingsRow |= rowVisible(R.id.pw_row_chn, settings.chnList != null);
        anySettingsRow |= rowVisible(R.id.pw_row_gfw, settings.useGfwList != null);
        anySettingsRow |= rowVisible(R.id.pw_row_localhost, settings.localhostProxy != null);
        anySettingsRow |= rowVisible(R.id.pw_row_client, settings.clientProxy != null);
        findViewById(R.id.pw_settings_card).setVisibility(
                anySettingsRow ? View.VISIBLE : View.GONE);
        findViewById(R.id.pw_settings_header).setVisibility(
                anySettingsRow ? View.VISIBLE : View.GONE);
        if (settings.tcpProxyMode != null) {
            segTcp.check("proxy".equals(settings.tcpProxyMode)
                    ? R.id.pw_tcp_proxy : R.id.pw_tcp_disable);
        }
        if (settings.udpProxyMode != null) {
            segUdp.check("proxy".equals(settings.udpProxyMode)
                    ? R.id.pw_udp_proxy : R.id.pw_udp_disable);
        }
        if (settings.chnList != null) {
            segChn.check("direct".equals(settings.chnList) ? R.id.pw_chn_direct
                    : "proxy".equals(settings.chnList) ? R.id.pw_chn_proxy : R.id.pw_chn_off);
        }
        if (settings.useGfwList != null) swGfw.setChecked(settings.useGfwList);
        if (settings.localhostProxy != null) swLocalhost.setChecked(settings.localhostProxy);
        if (settings.clientProxy != null) swClient.setChecked(settings.clientProxy);

        // DNS 卡
        boolean anyDnsRow = false;
        anyDnsRow |= rowVisible(R.id.pw_row_dns_shunt, settings.dnsShunt != null);
        anyDnsRow |= rowVisible(R.id.pw_row_dns_mode, settings.dnsMode != null);
        anyDnsRow |= rowVisible(R.id.pw_row_remote_dns, settings.remoteDns != null);
        findViewById(R.id.pw_dns_card).setVisibility(anyDnsRow ? View.VISIBLE : View.GONE);
        findViewById(R.id.pw_dns_header).setVisibility(anyDnsRow ? View.VISIBLE : View.GONE);
        dnsShuntValue.setText(dnsShuntLabel(settings.dnsShunt));
        dnsModeValue.setText(dnsModeLabel(settings.dnsMode));
        remoteDnsValue.setText(settings.remoteDns == null
                ? getString(R.string.pw_not_set) : settings.remoteDns);

        renderNodes();
        rendering = false;
        // 进页面自动测一次(与 LuCI 状态页一致);服务没开时国外项自然测不通
        if (settings.enabled && !checkRunning) runNetCheck();
    }

    private boolean rowVisible(int rowId, boolean visible) {
        findViewById(rowId).setVisibility(visible ? View.VISIBLE : View.GONE);
        return visible;
    }

    /** 运行状态点/文字 + 主开关(TCP 组件状态优先,缺失时用 procd 状态兜底) */
    private void renderState() {
        if (settings == null) return;
        Boolean effective = compStatus != null && compStatus.tcp != null
                ? compStatus.tcp : running;
        boolean isRunning = Boolean.TRUE.equals(effective);
        if (isRunning) {
            statusDot.setBackgroundResource(R.drawable.dot_green);
            statusText.setText(R.string.pw_state_running);
            statusText.setTextColor(ContextCompat.getColor(this, R.color.ios_green));
        } else if (!settings.enabled) {
            statusDot.setBackgroundResource(R.drawable.dot_gray);
            statusText.setText(R.string.pw_state_disabled);
            statusText.setTextColor(ContextCompat.getColor(this, R.color.label_secondary));
        } else if (effective == null) {
            statusDot.setBackgroundResource(R.drawable.dot_gray);
            statusText.setText(R.string.pw_state_unknown);
            statusText.setTextColor(ContextCompat.getColor(this, R.color.label_secondary));
        } else {
            statusDot.setBackgroundResource(R.drawable.dot_red);
            statusText.setText(R.string.pw_state_stopped_big);
            statusText.setTextColor(ContextCompat.getColor(this, R.color.ios_red));
        }
        boolean prevRendering = rendering;
        rendering = true;
        mainSwitch.setChecked(settings.enabled);
        rendering = prevRendering;
        btnRestart.setEnabled(settings.enabled && !busy);
    }

    /** 节点列表:按 group 分组(保持配置里的先后顺序),组头带计数、可折叠;
     *  默认全折叠,只展开当前节点所在组 */
    private void renderNodes() {
        nodeRows.removeAllViews();
        if (settings.nodes.isEmpty()) {
            showEmpty(getString(R.string.pw_no_nodes, pwName()));
            return;
        }
        emptyLabel.setVisibility(View.GONE);
        Map<String, java.util.List<PasswallNode>> groups = new LinkedHashMap<>();
        for (PasswallNode node : settings.nodes) {
            String group = node.group == null ? "" : node.group.trim();
            groups.computeIfAbsent(group, k -> new java.util.ArrayList<>()).add(node);
        }
        if (!groupsInitialized) {
            expandedGroups.clear();
            for (Map.Entry<String, java.util.List<PasswallNode>> e : groups.entrySet()) {
                for (PasswallNode n : e.getValue()) {
                    if (n.section.equals(settings.nodeId)) {
                        expandedGroups.add(e.getKey());
                        break;
                    }
                }
            }
            groupsInitialized = true;
        }
        boolean multiGroup = groups.size() > 1;
        for (Map.Entry<String, java.util.List<PasswallNode>> e : groups.entrySet()) {
            final String groupKey = e.getKey();
            boolean expanded = expandedGroups.contains(groupKey);
            if (multiGroup || !groupKey.isEmpty()) {
                View header = getLayoutInflater().inflate(
                        R.layout.item_passwall_group, nodeRows, false);
                TextView label = header.findViewById(R.id.pwg_label);
                TextView arrow = header.findViewById(R.id.pwg_arrow);
                String name = groupKey.isEmpty()
                        ? getString(R.string.pw_group_default) : groupKey;
                label.setText(getString(R.string.pw_group_count, name, e.getValue().size()));
                arrow.setText(expanded ? "▾" : "▸");
                header.setOnClickListener(v -> {
                    if (expandedGroups.contains(groupKey)) {
                        expandedGroups.remove(groupKey);
                    } else {
                        expandedGroups.add(groupKey);
                    }
                    renderNodes();
                });
                nodeRows.addView(header);
            } else {
                // 只有一个未分组的组:不显示组头,永远展开
                expanded = true;
            }
            if (expanded) {
                for (PasswallNode node : e.getValue()) {
                    nodeRows.addView(buildNodeRow(node));
                }
            }
        }
    }

    private View buildNodeRow(PasswallNode node) {
        View row = getLayoutInflater().inflate(
                R.layout.item_passwall_node, nodeRows, false);
        TextView name = row.findViewById(R.id.pwn_name);
        TextView meta = row.findViewById(R.id.pwn_meta);
        TextView mark = row.findViewById(R.id.pwn_current);
        android.widget.RadioButton radio = row.findViewById(R.id.pwn_radio);
        boolean isCurrent = node.section.equals(settings.nodeId);
        name.setText(node.displayName());
        String metaText = node.subtitle();
        meta.setText(metaText);
        meta.setVisibility(metaText.isEmpty() ? View.GONE : View.VISIBLE);
        mark.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
        radio.setChecked(isCurrent);
        row.setOnClickListener(v -> {
            if (busy || isCurrent) return;
            switchNode(node);
        });
        radio.setOnClickListener(v -> {
            if (busy || isCurrent) return;
            switchNode(node);
        });
        row.setEnabled(!busy);
        return row;
    }

    private void showEmpty(String message) {
        nodeRows.removeAllViews();
        emptyLabel.setText(message);
        emptyLabel.setVisibility(View.VISIBLE);
    }

    // =====================================================================
    // 写操作
    // =====================================================================

    private void setBusy(boolean value) {
        busy = value;
        statusSpinner.setVisibility(value ? View.VISIBLE : View.GONE);
        mainSwitch.setEnabled(!value);
        btnRefresh.setEnabled(!value);
        modeGroup.setEnabled(!value);
        segTcp.setEnabled(!value);
        segUdp.setEnabled(!value);
        segChn.setEnabled(!value);
        swGfw.setEnabled(!value);
        swLocalhost.setEnabled(!value);
        swClient.setEnabled(!value);
        renderState();
        if (settings != null) {
            renderNodes();
            renderSubscriptions();
        }
    }

    private void toggleEnabled() {
        final boolean target = !settings.enabled;
        setBusy(true);
        PasswallApi.setEnabled(settings, target, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                Toast.makeText(PasswallActivity.this,
                        getString(target ? R.string.pw_enabled_toast
                                : R.string.pw_disabled_toast, pwName()),
                        Toast.LENGTH_SHORT).show();
                load();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                toastError(error);
                load();
            }
        });
    }

    private void switchNode(PasswallNode node) {
        setBusy(true);
        PasswallApi.setNode(settings, node.section, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                Toast.makeText(PasswallActivity.this,
                        getString(R.string.pw_node_switched, node.displayName()),
                        Toast.LENGTH_SHORT).show();
                load();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                toastError(error);
                load();
            }
        });
    }

    private void applyQuickMode(PasswallApi.QuickMode mode) {
        setBusy(true);
        PasswallApi.setQuickMode(settings, mode, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                Toast.makeText(PasswallActivity.this,
                        getString(R.string.pw_mode_switched, quickModeLabel(mode)),
                        Toast.LENGTH_SHORT).show();
                load();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                toastError(error);
                load();
            }
        });
    }

    /** 单键写回(分段/开关共用):静默成功,只重拉对齐 */
    private void writeOption(String key, String value) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put(key, value);
        setBusy(true);
        PasswallApi.setOptions(settings, options, null, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                load();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                toastError(error);
                load();
            }
        });
    }

    private void restartService() {
        setBusy(true);
        PasswallApi.restart(pwConfig(), new ApiCallback<com.google.gson.JsonObject>() {
            @Override
            public void onSuccess(com.google.gson.JsonObject result) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                Toast.makeText(PasswallActivity.this,
                        getString(R.string.pw_restarted_toast, pwName()),
                        Toast.LENGTH_SHORT).show();
                load();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                toastError(error);
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void toastError(ApiError error) {
        Toast.makeText(this, getString(R.string.pw_op_failed, error.getMessage()),
                Toast.LENGTH_SHORT).show();
    }

    // =====================================================================
    // DNS 选择/编辑
    // =====================================================================

    private void pickDnsShunt() {
        String[] values = {"dnsmasq", "chinadns-ng", "smartdns"};
        String[] labels = {"Dnsmasq", "ChinaDNS-NG", "SmartDNS"};
        pickOption(getString(R.string.pw_dns_shunt_label), values, labels,
                settings.dnsShunt, "dns_shunt");
    }

    private void pickDnsMode() {
        String[] values = {"udp", "tcp", "dns2socks", "sing-box", "xray"};
        String[] labels = {"UDP", "TCP", "dns2socks", "Sing-Box", "Xray"};
        pickOption(getString(R.string.pw_dns_mode_label), values, labels,
                settings.dnsMode, "dns_mode");
    }

    private void pickOption(String title, String[] values, String[] labels,
                            String current, String key) {
        int checked = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (!values[which].equals(current)) {
                        writeOption(key, values[which]);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void editRemoteDns() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(settings.remoteDns == null ? "" : settings.remoteDns);
        input.setSelection(input.getText().length());
        input.setHint(R.string.pw_remote_dns_hint);
        FrameLayout container = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pw_remote_dns_label)
                .setView(container)
                .setPositiveButton(R.string.btn_save, (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.equals(settings.remoteDns == null ? "" : settings.remoteDns)) {
                        writeOption("remote_dns", value);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // =====================================================================
    // 文案映射
    // =====================================================================

    private PasswallApi.QuickMode quickModeForButton(int checkedId) {
        if (checkedId == R.id.pw_mode_gfw) return PasswallApi.QuickMode.GFW;
        if (checkedId == R.id.pw_mode_chn) return PasswallApi.QuickMode.CHNROUTE;
        if (checkedId == R.id.pw_mode_global) return PasswallApi.QuickMode.GLOBAL;
        if (checkedId == R.id.pw_mode_return) return PasswallApi.QuickMode.RETURNHOME;
        return null;
    }

    private String quickModeLabel(PasswallApi.QuickMode mode) {
        switch (mode) {
            case GFW:
                return getString(R.string.pw_mode_gfw);
            case CHNROUTE:
                return getString(R.string.pw_mode_chn);
            case GLOBAL:
                return getString(R.string.pw_mode_global);
            case RETURNHOME:
                return getString(R.string.pw_mode_return);
            default:
                return "";
        }
    }

    private String dnsShuntLabel(String value) {
        if (value == null) return getString(R.string.pw_not_set);
        switch (value) {
            case "dnsmasq":
                return "Dnsmasq";
            case "chinadns-ng":
                return "ChinaDNS-NG";
            case "smartdns":
                return "SmartDNS";
            default:
                return value;
        }
    }

    private String dnsModeLabel(String value) {
        if (value == null) return getString(R.string.pw_not_set);
        switch (value) {
            case "udp":
                return "UDP";
            case "tcp":
                return "TCP";
            case "sing-box":
                return "Sing-Box";
            case "xray":
                return "Xray";
            default:
                return value;
        }
    }

    // =====================================================================
    // 网络状态监测 + IP(路由器侧 curl,口径同 LuCI 状态页)
    // =====================================================================

    private void runNetCheck() {
        if (settings == null || checkRunning) return;
        checkRunning = true;
        checkCnValue.setText(R.string.pw_check_running);
        checkGValue.setText(R.string.pw_check_running);
        PasswallApi.netCheck(pwConfig(), settings.socksPort, new ApiCallback<PasswallApi.NetCheck>() {
            @Override
            public void onSuccess(PasswallApi.NetCheck result) {
                if (isFinishing() || isDestroyed()) return;
                checkRunning = false;
                checkCnValue.setText(result.baiduMs != null
                        ? result.baiduMs + " ms" : getString(R.string.pw_check_fail));
                checkGValue.setText(result.googleMs != null
                        ? result.googleMs + " ms" : getString(R.string.pw_check_fail));
                ipCnValue.setText(result.cnIp != null ? result.cnIp : "—");
                ipGValue.setText(result.globalIp != null ? result.globalIp : "—");
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                checkRunning = false;
                checkCnValue.setText(R.string.pw_check_tap);
                checkGValue.setText(R.string.pw_check_tap);
            }
        });
    }

    // =====================================================================
    // 订阅管理
    // =====================================================================

    private void renderSubscriptions() {
        subRows.removeAllViews();
        if (settings == null) return;
        subEmpty.setVisibility(settings.subscriptions.isEmpty()
                ? View.VISIBLE : View.GONE);
        for (PasswallApi.Subscription sub : settings.subscriptions) {
            View row = getLayoutInflater().inflate(
                    R.layout.item_passwall_sub, subRows, false);
            TextView remark = row.findViewById(R.id.pws_remark);
            TextView url = row.findViewById(R.id.pws_url);
            View update = row.findViewById(R.id.pws_update);
            View delete = row.findViewById(R.id.pws_delete);
            remark.setText(sub.remark.isEmpty() ? sub.section : sub.remark);
            // url 可能是多行(多个地址),行内只展示第一行
            url.setText(sub.url.split("\n")[0].trim());
            update.setEnabled(!busy);
            delete.setEnabled(!busy);
            update.setOnClickListener(v -> {
                if (!busy) updateSubscription(sub);
            });
            delete.setOnClickListener(v -> {
                if (!busy) confirmDeleteSubscription(sub);
            });
            subRows.addView(row);
        }
    }

    private void showAddSubscription() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText remarkInput = new EditText(this);
        remarkInput.setHint(R.string.pw_sub_remark_hint);
        remarkInput.setSingleLine(true);
        EditText urlInput = new EditText(this);
        urlInput.setHint(R.string.pw_sub_url_hint);
        urlInput.setSingleLine(false);
        urlInput.setMaxLines(3);
        box.addView(remarkInput);
        box.addView(urlInput);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pw_sub_add_title)
                .setView(box)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.btn_save, (dialog, which) -> {
                    String remark = remarkInput.getText().toString().trim();
                    String url = urlInput.getText().toString().trim();
                    if (url.isEmpty()) {
                        toast(getString(R.string.pw_sub_url_hint));
                        return;
                    }
                    setBusy(true);
                    PasswallApi.addSubscription(pwConfig(), remark, url,
                            new ApiCallback<Boolean>() {
                                @Override
                                public void onSuccess(Boolean ok) {
                                    if (isFinishing() || isDestroyed()) return;
                                    setBusy(false);
                                    load();
                                }

                                @Override
                                public void onFailure(ApiError error) {
                                    if (isFinishing() || isDestroyed()) return;
                                    setBusy(false);
                                    toast(error.getMessage());
                                }
                            });
                })
                .show();
    }

    private void confirmDeleteSubscription(PasswallApi.Subscription sub) {
        String name = sub.remark.isEmpty() ? sub.section : sub.remark;
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.pw_sub_delete_confirm, name))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.pw_sub_delete, (dialog, which) -> {
                    setBusy(true);
                    PasswallApi.deleteSubscription(pwConfig(), sub.section,
                            new ApiCallback<Boolean>() {
                                @Override
                                public void onSuccess(Boolean ok) {
                                    if (isFinishing() || isDestroyed()) return;
                                    setBusy(false);
                                    load();
                                }

                                @Override
                                public void onFailure(ApiError error) {
                                    if (isFinishing() || isDestroyed()) return;
                                    setBusy(false);
                                    toast(error.getMessage());
                                }
                            });
                })
                .show();
    }

    private void updateSubscription(PasswallApi.Subscription sub) {
        setBusy(true);
        toast(getString(R.string.pw_sub_updating));
        PasswallApi.updateSubscription(pwConfig(), sub.section, sub.url, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                toast(getString(R.string.pw_sub_triggered));
                // 服务端后台更新要一会儿,延迟重拉一次节点列表
                swipeRefresh.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) load();
                }, 5000);
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                toast(getString(R.string.pw_sub_update_failed, error.getMessage()));
            }
        });
    }

    /** 全部更新:LuCI 的 subscribe_manual_all 一次触发(服务端后台跑) */
    private void updateAllSubscriptions() {
        if (settings == null || settings.subscriptions.isEmpty()) return;
        setBusy(true);
        toast(getString(R.string.pw_sub_updating));
        PasswallApi.updateAllSubscriptions(pwConfig(), settings.subscriptions,
                new ApiCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean ok) {
                        if (isFinishing() || isDestroyed()) return;
                        setBusy(false);
                        toast(getString(R.string.pw_sub_triggered));
                        // 服务端后台更新要一会儿,延迟重拉一次节点列表
                        swipeRefresh.postDelayed(() -> {
                            if (!isFinishing() && !isDestroyed()) load();
                        }, 5000);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        if (isFinishing() || isDestroyed()) return;
                        setBusy(false);
                        toast(getString(R.string.pw_sub_update_failed, error.getMessage()));
                    }
                });
    }
}
