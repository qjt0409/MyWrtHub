package com.whykangkang.wrthub.ui.services.passwall;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.PasswallApi;
import com.whykangkang.wrthub.model.PasswallNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端实例新增/编辑页:字段按 LuCI server_config 的结构动态生成 ——
 * 通用项 + 协议细项 + 传输 + TLS/REALITY + 用户绑定。
 * 只写本页管理的键;自定义配置、PEM 粘贴等极少数字段不读不写、原样保留。
 * 协议下拉是固定槽位(不参与重建),其余细项区按类型/协议/TLS 开关重建。
 */
public class PasswallServerEditActivity extends AppCompatActivity {

    public static final String EXTRA_SECTION = "section";

    private static final String[] TYPE_LABELS = {
            "Socks", "Sing-Box", "Xray", "Shadowsocks Rust", "ShadowsocksR", "Hysteria2"};
    private static final String[] ALPN_VALUES = {
            "default", "h3", "h2", "h3,h2", "http/1.1", "h2,http/1.1", "h3,h2,http/1.1"};
    private static final String[] TCP_UDP = {"tcp", "udp", "tcp,udp"};
    private static final String[] GUISE = {
            "none", "srtp", "utp", "wechat-video", "dtls", "wireguard", "dns"};

    private LinearLayout form, protocolBox, extrasBox, usersBox;
    private EditText etRemarks, etPort;
    private SwitchMaterial swEnable;
    private Spinner spType, spProtocol;
    private String[] protocolValues = new String[0];

    private final Map<String, EditText> textMap = new LinkedHashMap<>();
    private final Map<String, EditText> listTextMap = new LinkedHashMap<>();
    private final Map<String, Spinner> spinMap = new LinkedHashMap<>();
    private final Map<String, String[]> spinValues = new LinkedHashMap<>();
    private final Map<String, SwitchMaterial> flagMap = new LinkedHashMap<>();
    private Spinner spUserSingle;
    private final List<CheckBox> userChecks = new ArrayList<>();

    private PasswallApi.ServerInfo info;
    private PasswallApi.ServerEntry editing;
    /** 客户端节点(outbound_node 的选项来源);拉不到就只有内置四项 */
    private final List<PasswallNode> clientNodes = new ArrayList<>();
    private String section;
    /** 动态字段当前值(重建细项区时不丢) */
    private final Map<String, String> cur = new LinkedHashMap<>();
    private final Map<String, List<String>> curLists = new LinkedHashMap<>();
    private final List<String> curUsers = new ArrayList<>();
    private String curUser = "";
    private boolean busy;
    private boolean building;

    protected String pwConfig() {
        return PasswallApi.CONFIG;
    }

    protected int pwTitleRes() {
        return R.string.svc_passwall;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_passwall_server_edit);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        form = findViewById(R.id.pse_form);
        findViewById(R.id.pse_save).setOnClickListener(v -> {
            if (!busy) save();
        });
        View deleteBtn = findViewById(R.id.pse_delete);
        if (section != null) {
            deleteBtn.setVisibility(View.VISIBLE);
            deleteBtn.setOnClickListener(v -> confirmDelete());
        }
        load();
    }

    private void load() {
        PasswallApi.getServerInfo(pwConfig(), new ApiCallback<PasswallApi.ServerInfo>() {
            @Override
            public void onSuccess(PasswallApi.ServerInfo result) {
                info = result;
                if (section != null) {
                    editing = result.entry(section);
                    if (editing == null) {
                        toast(getString(R.string.pw_server_unnamed));
                        finish();
                        return;
                    }
                    cur.putAll(editing.fields);
                    curLists.putAll(editing.listFields);
                    curUsers.clear();
                    curUsers.addAll(editing.users);
                    curUser = editing.user;
                }
                // 出站节点的选项要客户端节点列表;失败不挡表单
                PasswallApi.getSettings(pwConfig(),
                        new ApiCallback<PasswallApi.PwSettings>() {
                            @Override
                            public void onSuccess(PasswallApi.PwSettings settings) {
                                clientNodes.clear();
                                clientNodes.addAll(settings.nodes);
                                buildForm();
                            }

                            @Override
                            public void onFailure(ApiError error) {
                                buildForm();
                            }
                        });
            }

            @Override
            public void onFailure(ApiError error) {
                toast(error.getMessage());
                finish();
            }
        });
    }

    // =====================================================================
    // 表单构建
    // =====================================================================

    private void buildForm() {
        building = true;
        form.removeAllViews();
        TextView title = new TextView(this);
        title.setTextAppearance(R.style.TextAppearance_Wrthub_LargeTitle);
        title.setText(getString(pwTitleRes()) + " · " + getString(
                section == null ? R.string.pwse_add_title : R.string.pwse_edit_title));
        title.setPadding(0, dp(12), 0, dp(4));
        form.addView(title);

        etRemarks = addText(R.string.pwse_remarks,
                editing != null ? editing.remarks : "", false);
        swEnable = addFlag(R.string.pwse_enable,
                editing == null || editing.enable);
        spType = addSpinner(R.string.pwse_type, PasswallApi.SERVER_TYPES, TYPE_LABELS,
                editing != null ? editing.type : "sing-box");
        etPort = addText(R.string.pwse_port,
                editing != null ? editing.port : "", true);

        protocolBox = new LinearLayout(this);
        protocolBox.setOrientation(LinearLayout.VERTICAL);
        form.addView(protocolBox);
        extrasBox = new LinearLayout(this);
        extrasBox.setOrientation(LinearLayout.VERTICAL);
        form.addView(extrasBox);
        usersBox = new LinearLayout(this);
        usersBox.setOrientation(LinearLayout.VERTICAL);
        form.addView(usersBox);

        TextView note = new TextView(this);
        note.setText(R.string.pwse_advanced_note);
        note.setTextColor(getColor(R.color.label_secondary));
        note.setTextSize(13);
        note.setPadding(0, dp(16), 0, 0);
        form.addView(note);

        spType.setOnItemSelectedListener(new SimpleItemSelected() {
            @Override
            public void onItemSelected(int position) {
                if (building) return;
                harvestAll();
                cur.remove("protocol");
                buildProtocolSpinner();
                rebuildExtras();
            }
        });
        building = false;
        buildProtocolSpinner();
        rebuildExtras();
    }

    /** 协议下拉(固定槽位,重建细项时不动它) */
    private void buildProtocolSpinner() {
        building = true;
        protocolBox.removeAllViews();
        spProtocol = null;
        String type = currentType();
        if ("SSR".equals(type)) {
            protocolValues = PasswallApi.SSR_PROTOCOLS;
        } else {
            protocolValues = PasswallApi.protocolsFor(type);
        }
        if (protocolValues.length > 0) {
            String proto = cur.getOrDefault("protocol", protocolValues[0]);
            spProtocol = addSpinnerTo(protocolBox, null, R.string.pwse_protocol,
                    protocolValues, protocolLabels(protocolValues), proto);
            spProtocol.setOnItemSelectedListener(new SimpleItemSelected() {
                @Override
                public void onItemSelected(int position) {
                    if (building) return;
                    harvestAll();
                    rebuildExtras();
                }
            });
        }
        building = false;
    }

    private void rebuildExtras() {
        building = true;
        harvestExtrasOnly();
        extrasBox.removeAllViews();
        textMap.clear();
        listTextMap.clear();
        spinMap.clear();
        spinValues.clear();
        flagMap.clear();
        buildTypeFields();
        // 这些开关变化时细项跟着变
        for (String key : new String[]{"tls", "reality", "tls_use_pem", "custom"}) {
            SwitchMaterial sw = flagMap.get(key);
            if (sw != null) {
                sw.setOnCheckedChangeListener((v, checked) -> {
                    if (building) return;
                    harvestAll();
                    rebuildExtras();
                });
            }
        }
        // 自定义配置模式下 LuCI 没有用户绑定
        if (curFlag("custom", false) && !"Socks".equals(currentType())) {
            usersBox.removeAllViews();
        } else {
            rebuildUsers();
        }
        building = false;
    }

    private void buildTypeFields() {
        String type = currentType();
        String proto = currentProtocol();
        // 自定义配置(除 Socks 外都有):开启后 LuCI 只剩这一个大文本框
        if (!"Socks".equals(type)) {
            addFlagTo("custom", R.string.pwse_custom, curFlag("custom", false));
            if (curFlag("custom", false)) {
                addBigTextTo("custom_config", R.string.pwse_custom_config,
                        cur.getOrDefault("custom_config", ""));
                buildTail(type);
                return;
            }
        }
        switch (type) {
            case "Socks":
                addFlagTo("auth", R.string.pwse_auth, curFlag("auth", false));
                break;
            case "SS-Rust":
                addSpinnerTo("method", R.string.pwse_method,
                        PasswallApi.SSRUST_METHODS, PasswallApi.SSRUST_METHODS,
                        cur.getOrDefault("method", "aes-256-gcm"));
                addTextTo("timeout", R.string.pwse_timeout,
                        cur.getOrDefault("timeout", ""), true);
                addFlagTo("tcp_fast_open", R.string.pwse_tcp_fast_open,
                        curFlag("tcp_fast_open", false));
                break;
            case "SSR":
                addSpinnerTo("method", R.string.pwse_method,
                        PasswallApi.SSR_METHODS, PasswallApi.SSR_METHODS,
                        cur.getOrDefault("method", "aes-256-cfb"));
                addTextTo("protocol_param", R.string.pwse_protocol_param,
                        cur.getOrDefault("protocol_param", ""), false);
                addSpinnerTo("obfs", R.string.pwse_obfs,
                        PasswallApi.SSR_OBFS, PasswallApi.SSR_OBFS,
                        cur.getOrDefault("obfs", "plain"));
                addTextTo("obfs_param", R.string.pwse_obfs_param,
                        cur.getOrDefault("obfs_param", ""), false);
                addTextTo("timeout", R.string.pwse_timeout,
                        cur.getOrDefault("timeout", ""), true);
                addFlagTo("tcp_fast_open", R.string.pwse_tcp_fast_open,
                        curFlag("tcp_fast_open", false));
                addFlagTo("udp_forward", R.string.pwse_udp_forward,
                        curFlag("udp_forward", false));
                break;
            case "Hysteria2":
                addFlagTo("realms", R.string.pwse_realms, curFlag("realms", false));
                addTextTo("realm_url", R.string.pwse_realm_url,
                        cur.getOrDefault("realm_url", ""), false);
                addObfsFields("obfs_type", "obfs_password", "", "");
                break;
            case "sing-box":
                buildSingboxFields(proto);
                break;
            case "Xray":
                buildXrayFields(proto);
                break;
            default:
                break;
        }
        if ("sing-box".equals(type) || "Xray".equals(type)) {
            addOutboundGroup();
        }
        buildTail(type);
    }

    /** 通用尾巴:日志 + 防火墙放行(LuCI 各类型都有) */
    private void buildTail(String type) {
        addFlagTo("log", R.string.pwse_log, curFlag("log", true));
        addFlagTo("firewall_allow", R.string.pwse_firewall,
                curFlag("firewall_allow", false));
        addTextTo("firewall_allow_src", R.string.pwse_firewall_src,
                cur.getOrDefault("firewall_allow_src", ""), false);
        if ("sing-box".equals(type) || "Xray".equals(type)) {
            addFlagTo("accept_lan", R.string.pwse_accept_lan,
                    curFlag("accept_lan", false));
        }
    }

    /** 出站节点:关 / 自定义 Socks / HTTP / 接口 / 某个客户端节点 */
    private void addOutboundGroup() {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        values.add("");
        labels.add(getString(R.string.pwse_outbound_off));
        values.add("_socks");
        labels.add(getString(R.string.pwse_outbound_socks));
        values.add("_http");
        labels.add(getString(R.string.pwse_outbound_http));
        values.add("_iface");
        labels.add(getString(R.string.pwse_outbound_iface));
        for (PasswallNode n : clientNodes) {
            values.add(n.section);
            labels.add(n.remarks.isEmpty() ? n.section : n.remarks);
        }
        addSpinnerTo("outbound_node", R.string.pwse_outbound,
                values.toArray(new String[0]), labels.toArray(new String[0]),
                cur.getOrDefault("outbound_node", ""));
        addTextTo("outbound_node_address", R.string.pwse_outbound_addr,
                cur.getOrDefault("outbound_node_address", ""), false);
        addTextTo("outbound_node_port", R.string.pwse_outbound_port,
                cur.getOrDefault("outbound_node_port", ""), true);
    }

    private void buildSingboxFields(String proto) {
        switch (proto) {
            case "direct":
                addSpinnerTo("d_protocol", R.string.pwse_d_proto, TCP_UDP, TCP_UDP,
                        cur.getOrDefault("d_protocol", "tcp"));
                addTextTo("d_address", R.string.pwse_d_addr,
                        cur.getOrDefault("d_address", ""), false);
                addTextTo("d_port", R.string.pwse_d_port,
                        cur.getOrDefault("d_port", ""), true);
                break;
            case "shadowsocks":
                addSsFields();
                addTransportGroup(false);
                break;
            case "vmess":
            case "vless":
            case "trojan":
                if ("vless".equals(proto)) {
                    addTextTo("decryption", R.string.pwse_decryption,
                            cur.getOrDefault("decryption", "none"), false);
                }
                addTransportGroup(false);
                addTlsGroup(false, proto);
                addFlagTo("mux", R.string.pwse_mux, curFlag("mux", false));
                addFlagTo("tcpbrutal", R.string.pwse_tcpbrutal,
                        curFlag("tcpbrutal", false));
                addTextTo("tcpbrutal_up_mbps", R.string.pwse_up_mbps,
                        cur.getOrDefault("tcpbrutal_up_mbps", ""), true);
                addTextTo("tcpbrutal_down_mbps", R.string.pwse_down_mbps,
                        cur.getOrDefault("tcpbrutal_down_mbps", ""), true);
                break;
            case "hysteria":
                addTextTo("hysteria_obfs", R.string.pwse_obfs_password,
                        cur.getOrDefault("hysteria_obfs", ""), false);
                addTextTo("hysteria_up_mbps", R.string.pwse_up_mbps,
                        cur.getOrDefault("hysteria_up_mbps", ""), true);
                addTextTo("hysteria_down_mbps", R.string.pwse_down_mbps,
                        cur.getOrDefault("hysteria_down_mbps", ""), true);
                addTextTo("hysteria_recv_window_conn", R.string.pwse_hy_win_conn,
                        cur.getOrDefault("hysteria_recv_window_conn", ""), true);
                addTextTo("hysteria_recv_window_client", R.string.pwse_hy_win_client,
                        cur.getOrDefault("hysteria_recv_window_client", ""), true);
                addTextTo("hysteria_max_conn_client", R.string.pwse_hy_max_conn,
                        cur.getOrDefault("hysteria_max_conn_client", ""), true);
                addFlagTo("hysteria_disable_mtu_discovery", R.string.pwse_hy_no_mtu,
                        curFlag("hysteria_disable_mtu_discovery", false));
                addTlsGroup(false, proto);
                break;
            case "hysteria2":
                addHysteria2Fields();
                addTlsGroup(false, proto);
                break;
            case "tuic":
                addSpinnerTo("tuic_congestion_control", R.string.pwse_tuic_cc,
                        new String[]{"bbr", "cubic", "new_reno"},
                        new String[]{"BBR", "CUBIC", "New Reno"},
                        cur.getOrDefault("tuic_congestion_control", "bbr"));
                addFlagTo("tuic_zero_rtt_handshake", R.string.pwse_tuic_0rtt,
                        curFlag("tuic_zero_rtt_handshake", false));
                addTextTo("tuic_heartbeat", R.string.pwse_tuic_heartbeat,
                        cur.getOrDefault("tuic_heartbeat", ""), true);
                addSpinnerTo("tuic_alpn", R.string.pwse_alpn,
                        ALPN_VALUES, ALPN_VALUES,
                        cur.getOrDefault("tuic_alpn", "default"));
                addTlsGroup(false, proto);
                break;
            case "naive":
            case "anytls":
                addTlsGroup(false, proto);
                break;
            case "masque":
                addMasqueFields();
                break;
            case "wireguard":
                addWireguardFields();
                break;
            case "snell":
                addSpinnerTo("snell_version", R.string.pwse_snell_version,
                        new String[]{"5", "6"}, new String[]{"5", "6"},
                        cur.getOrDefault("snell_version", "5"));
                addTextTo("snell_psk", R.string.pwse_snell_psk,
                        cur.getOrDefault("snell_psk", ""), false);
                addSpinnerTo("snell_obfs_mode", R.string.pwse_snell_obfs,
                        new String[]{"none", "http"}, new String[]{"none", "http"},
                        cur.getOrDefault("snell_obfs_mode", "none"));
                addSpinnerTo("snell_mode", R.string.pwse_snell_mode,
                        new String[]{"default", "unshaped", "unsafe-raw"},
                        new String[]{"default", "unshaped", "unsafe-raw"},
                        cur.getOrDefault("snell_mode", "default"));
                break;
            default:
                break;
        }
    }

    private void buildXrayFields(String proto) {
        switch (proto) {
            case "tunnel":
                addSpinnerTo("d_protocol", R.string.pwse_d_proto, TCP_UDP, TCP_UDP,
                        cur.getOrDefault("d_protocol", "tcp"));
                addTextTo("d_address", R.string.pwse_d_addr,
                        cur.getOrDefault("d_address", ""), false);
                addTextTo("d_port", R.string.pwse_d_port,
                        cur.getOrDefault("d_port", ""), true);
                break;
            case "shadowsocks":
                addSsFields();
                addSpinnerTo("ss_network", R.string.pwse_ss_network, TCP_UDP, TCP_UDP,
                        cur.getOrDefault("ss_network", "tcp"));
                addFlagTo("udp_forward", R.string.pwse_udp_forward,
                        curFlag("udp_forward", false));
                break;
            case "vmess":
            case "vless":
            case "trojan":
                if ("vless".equals(proto)) {
                    addTextTo("decryption", R.string.pwse_decryption,
                            cur.getOrDefault("decryption", "none"), false);
                }
                addTransportGroup(true);
                addTlsGroup(true, proto);
                addFlagTo("acceptProxyProtocol", R.string.pwse_accept_proxy,
                        curFlag("acceptProxyProtocol", false));
                addFlagTo("tcp_fast_open", R.string.pwse_tcp_fast_open,
                        curFlag("tcp_fast_open", false));
                addFlagTo("fallback", R.string.pwse_fallback,
                        curFlag("fallback", false));
                addTextTo("fallback_alpn", R.string.pwse_fb_alpn,
                        cur.getOrDefault("fallback_alpn", ""), false);
                addTextTo("fallback_path", R.string.pwse_fb_path,
                        cur.getOrDefault("fallback_path", ""), false);
                addTextTo("fallback_dest", R.string.pwse_fb_dest,
                        cur.getOrDefault("fallback_dest", ""), false);
                addTextTo("fallback_xver", R.string.pwse_fb_xver,
                        cur.getOrDefault("fallback_xver", ""), true);
                addListTextTo("fallback_list", R.string.pwse_fb_list);
                break;
            case "hysteria2":
                addHysteria2Fields();
                addTlsGroup(true, proto);
                break;
            case "masque":
                addMasqueFields();
                break;
            case "wireguard":
                addWireguardFields();
                break;
            default:
                break;
        }
    }

    private void addSsFields() {
        addSpinnerTo("method", R.string.pwse_method,
                PasswallApi.SS_METHODS, PasswallApi.SS_METHODS,
                cur.getOrDefault("method", "aes-256-gcm"));
        addTextTo("ss_password", R.string.pwse_ss_password,
                cur.getOrDefault("ss_password", ""), false);
    }

    private void addObfsFields(String typeKey, String passKey,
                               String minKey, String maxKey) {
        addSpinnerTo(typeKey, R.string.pwse_obfs_type,
                new String[]{"", "salamander", "gecko"},
                new String[]{getString(R.string.pwse_obfs_none), "Salamander", "Gecko"},
                cur.getOrDefault(typeKey, ""));
        addTextTo(passKey, R.string.pwse_obfs_password,
                cur.getOrDefault(passKey, ""), false);
        if (!minKey.isEmpty()) {
            addTextTo(minKey, R.string.pwse_pkt_min,
                    cur.getOrDefault(minKey, ""), true);
            addTextTo(maxKey, R.string.pwse_pkt_max,
                    cur.getOrDefault(maxKey, ""), true);
        }
    }

    private void addHysteria2Fields() {
        addFlagTo("hysteria2_realms", R.string.pwse_realms,
                curFlag("hysteria2_realms", false));
        addTextTo("hysteria2_realm_url", R.string.pwse_realm_url,
                cur.getOrDefault("hysteria2_realm_url", ""), false);
        addObfsFields("hysteria2_obfs_type", "hysteria2_obfs_password",
                "hysteria2_obfs_MinPacketSize", "hysteria2_obfs_MaxPacketSize");
        addTextTo("hysteria2_up_mbps", R.string.pwse_up_mbps,
                cur.getOrDefault("hysteria2_up_mbps", ""), true);
        addTextTo("hysteria2_down_mbps", R.string.pwse_down_mbps,
                cur.getOrDefault("hysteria2_down_mbps", ""), true);
        addFlagTo("hysteria2_ignore_client_bandwidth", R.string.pwse_ignore_bw,
                curFlag("hysteria2_ignore_client_bandwidth", false));
    }

    private void addMasqueFields() {
        addTextTo("masque_path", R.string.pwse_masque_path,
                cur.getOrDefault("masque_path", ""), false);
        addListTextTo("masque_address", R.string.pwse_masque_addr);
        addTextTo("masque_mtu", R.string.pwse_mtu,
                cur.getOrDefault("masque_mtu", ""), true);
    }

    private void addWireguardFields() {
        addFlagTo("wireguard_system_interface", R.string.pwse_wg_sysif,
                curFlag("wireguard_system_interface", false));
        addTextTo("wireguard_mtu", R.string.pwse_mtu,
                cur.getOrDefault("wireguard_mtu", ""), true);
        addListTextTo("wireguard_local_address", R.string.pwse_wg_localaddr);
        addTextTo("wireguard_private_key", R.string.pwse_wg_priv,
                cur.getOrDefault("wireguard_private_key", ""), false);
        addTextTo("wireguard_public_key", R.string.pwse_wg_pub,
                cur.getOrDefault("wireguard_public_key", ""), false);
    }

    /** TLS 组:开关 + ALPN + REALITY / 证书路径 / ECH;vless 时带 flow */
    private void addTlsGroup(boolean xray, String proto) {
        addFlagTo("tls", R.string.pwse_tls, curFlag("tls", false));
        if (!curFlag("tls", false)) return;
        addSpinnerTo("alpn", R.string.pwse_alpn, ALPN_VALUES, ALPN_VALUES,
                cur.getOrDefault("alpn", "default"));
        if ("vless".equals(proto)) {
            addSpinnerTo("flow", R.string.pwse_flow,
                    new String[]{"", "xtls-rprx-vision"},
                    new String[]{getString(R.string.pwse_obfs_none), "xtls-rprx-vision"},
                    cur.getOrDefault("flow", ""));
        }
        addFlagTo("reality", R.string.pwse_reality, curFlag("reality", false));
        if (curFlag("reality", false)) {
            addTextTo("reality_private_key", R.string.pwse_reality_privkey,
                    cur.getOrDefault("reality_private_key", ""), false);
            addTextTo("reality_shortId", R.string.pwse_reality_shortid,
                    cur.getOrDefault("reality_shortId", ""), false);
            if (xray) {
                addTextTo("reality_dest", R.string.pwse_reality_dest,
                        cur.getOrDefault("reality_dest", ""), false);
                addListTextTo("reality_serverNames", R.string.pwse_reality_servernames);
                addFlagTo("use_mldsa65Seed", R.string.pwse_mldsa,
                        curFlag("use_mldsa65Seed", false));
                addBigTextTo("reality_mldsa65Seed", R.string.pwse_mldsa_key,
                        cur.getOrDefault("reality_mldsa65Seed", ""));
            } else {
                addTextTo("reality_handshake_server", R.string.pwse_reality_handshake,
                        cur.getOrDefault("reality_handshake_server", ""), false);
                addTextTo("reality_handshake_server_port",
                        R.string.pwse_reality_handshake_port,
                        cur.getOrDefault("reality_handshake_server_port", ""), true);
            }
        } else {
            addFlagTo("tls_use_pem", R.string.pwse_use_pem,
                    curFlag("tls_use_pem", false));
            if (curFlag("tls_use_pem", false)) {
                addBigTextTo("tls_certificate", R.string.pwse_pem_cert,
                        cur.getOrDefault("tls_certificate", ""));
                addBigTextTo("tls_key", R.string.pwse_pem_key,
                        cur.getOrDefault("tls_key", ""));
            } else {
                addTextTo("tls_certificateFile", R.string.pwse_cert_file,
                        cur.getOrDefault("tls_certificateFile", ""), false);
                addTextTo("tls_keyFile", R.string.pwse_key_file,
                        cur.getOrDefault("tls_keyFile", ""), false);
            }
            addFlagTo("ech", R.string.pwse_ech, curFlag("ech", false));
            addTextTo("ech_key", R.string.pwse_ech_key,
                    cur.getOrDefault("ech_key", ""), false);
        }
        if (xray) {
            addFlagTo("use_finalmask", R.string.pwse_finalmask,
                    curFlag("use_finalmask", false));
            addBigTextTo("finalmask", R.string.pwse_finalmask_json,
                    cur.getOrDefault("finalmask", ""));
        }
    }

    /** 传输组:按 transport 值出对应细项 */
    private void addTransportGroup(boolean xray) {
        String[] values = xray
                ? new String[]{"raw", "mkcp", "ws", "grpc", "httpupgrade", "xhttp"}
                : new String[]{"tcp", "http", "ws", "httpupgrade", "quic", "grpc"};
        String[] labels = xray
                ? new String[]{"RAW", "mKCP", "WebSocket", "gRPC", "HttpUpgrade", "XHTTP"}
                : new String[]{"TCP", "HTTP", "WebSocket", "HTTPUpgrade", "QUIC", "gRPC"};
        String transport = cur.getOrDefault("transport", values[0]);
        addSpinnerTo("transport", R.string.pwse_transport, values, labels, transport);
        switch (transport) {
            case "http":
                addListTextTo("http_host", R.string.pwse_http_host);
                addTextTo("http_path", R.string.pwse_http_path,
                        cur.getOrDefault("http_path", ""), false);
                break;
            case "ws":
                addTextTo("ws_host", R.string.pwse_ws_host,
                        cur.getOrDefault("ws_host", ""), false);
                addTextTo("ws_path", R.string.pwse_ws_path,
                        cur.getOrDefault("ws_path", ""), false);
                if (!xray) {
                    addTextTo("ws_earlyDataHeaderName", R.string.pwse_ws_early,
                            cur.getOrDefault("ws_earlyDataHeaderName", ""), false);
                }
                break;
            case "httpupgrade":
                addTextTo("httpupgrade_host", R.string.pwse_hu_host,
                        cur.getOrDefault("httpupgrade_host", ""), false);
                addTextTo("httpupgrade_path", R.string.pwse_hu_path,
                        cur.getOrDefault("httpupgrade_path", ""), false);
                break;
            case "xhttp":
                addTextTo("xhttp_host", R.string.pwse_xhttp_host,
                        cur.getOrDefault("xhttp_host", ""), false);
                addTextTo("xhttp_path", R.string.pwse_xhttp_path,
                        cur.getOrDefault("xhttp_path", ""), false);
                break;
            case "grpc":
                addTextTo("grpc_serviceName", R.string.pwse_grpc_name,
                        cur.getOrDefault("grpc_serviceName", ""), false);
                break;
            case "mkcp":
                addSpinnerTo("mkcp_guise", R.string.pwse_mkcp_guise, GUISE, GUISE,
                        cur.getOrDefault("mkcp_guise", "none"));
                addTextTo("mkcp_mtu", R.string.pwse_mkcp_mtu,
                        cur.getOrDefault("mkcp_mtu", ""), true);
                addTextTo("mkcp_seed", R.string.pwse_mkcp_seed,
                        cur.getOrDefault("mkcp_seed", ""), false);
                break;
            case "raw":
                addSpinnerTo("tcp_guise", R.string.pwse_tcp_guise,
                        new String[]{"none", "http"},
                        new String[]{"none", "http"},
                        cur.getOrDefault("tcp_guise", "none"));
                addListTextTo("tcp_guise_http_host", R.string.pwse_http_host);
                addListTextTo("tcp_guise_http_path", R.string.pwse_http_path);
                break;
            default:
                break;
        }
        // transport 本身也要在切换时重建细项
        Spinner sp = spinMap.get("transport");
        if (sp != null) {
            sp.setOnItemSelectedListener(new SimpleItemSelected() {
                @Override
                public void onItemSelected(int position) {
                    if (building) return;
                    harvestAll();
                    rebuildExtras();
                }
            });
        }
    }

    private void rebuildUsers() {
        harvestUsersOnly();
        usersBox.removeAllViews();
        userChecks.clear();
        spUserSingle = null;
        PasswallApi.UserMode mode = PasswallApi.userModeFor(currentType(), currentProtocol());
        if (mode == PasswallApi.UserMode.NONE) return;
        addHeaderTo(usersBox, R.string.pwse_users);
        if (info.users.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.pwse_no_users);
            empty.setTextColor(getColor(R.color.label_secondary));
            empty.setTextSize(14);
            empty.setPadding(0, dp(4), 0, dp(4));
            usersBox.addView(empty);
        } else if (mode == PasswallApi.UserMode.MULTI) {
            for (PasswallApi.PwServerUser u : info.users) {
                CheckBox cb = new CheckBox(this);
                cb.setText(u.username.isEmpty() ? u.section : u.username);
                cb.setTag(u.section);
                cb.setChecked(curUsers.contains(u.section));
                cb.setTextSize(16);
                usersBox.addView(cb);
                userChecks.add(cb);
            }
        } else {
            String[] labels = new String[info.users.size() + 1];
            labels[0] = getString(R.string.pwse_user_none);
            for (int i = 0; i < info.users.size(); i++) {
                String name = info.users.get(i).username;
                labels[i + 1] = name.isEmpty() ? info.users.get(i).section : name;
            }
            spUserSingle = new Spinner(this);
            spUserSingle.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, labels));
            int sel = 0;
            for (int i = 0; i < info.users.size(); i++) {
                if (info.users.get(i).section.equals(curUser)) sel = i + 1;
            }
            spUserSingle.setSelection(sel);
            usersBox.addView(spUserSingle);
        }
        TextView newUser = new TextView(this);
        newUser.setText("+ " + getString(R.string.pwse_new_user));
        newUser.setTextColor(getColor(R.color.ios_blue));
        newUser.setTextSize(15);
        newUser.setPadding(0, dp(10), 0, dp(2));
        newUser.setOnClickListener(v -> showNewUserDialog());
        usersBox.addView(newUser);
    }

    // =====================================================================
    // 收割与保存
    // =====================================================================

    private String currentType() {
        if (spType == null) return editing != null ? editing.type : "sing-box";
        return PasswallApi.SERVER_TYPES[spType.getSelectedItemPosition()];
    }

    private String currentProtocol() {
        if (spProtocol != null && protocolValues.length > 0) {
            return protocolValues[spProtocol.getSelectedItemPosition()];
        }
        return cur.getOrDefault("protocol", "");
    }

    private boolean curFlag(String key, boolean def) {
        SwitchMaterial sw = flagMap.get(key);
        if (sw != null && extrasBox != null && sw.getParent() != null) {
            return sw.isChecked();
        }
        String v = cur.get(key);
        return v == null ? def : "1".equals(v);
    }

    private void harvestAll() {
        harvestExtrasOnly();
        harvestUsersOnly();
        if (spProtocol != null && protocolValues.length > 0) {
            cur.put("protocol", protocolValues[spProtocol.getSelectedItemPosition()]);
        }
    }

    private void harvestExtrasOnly() {
        for (Map.Entry<String, EditText> e : textMap.entrySet()) {
            cur.put(e.getKey(), e.getValue().getText().toString().trim());
        }
        for (Map.Entry<String, EditText> e : listTextMap.entrySet()) {
            curLists.put(e.getKey(), splitList(e.getValue().getText().toString()));
        }
        for (Map.Entry<String, Spinner> e : spinMap.entrySet()) {
            String[] vals = spinValues.get(e.getKey());
            int pos = e.getValue().getSelectedItemPosition();
            if (vals != null && pos >= 0 && pos < vals.length) {
                cur.put(e.getKey(), vals[pos]);
            }
        }
        for (Map.Entry<String, SwitchMaterial> e : flagMap.entrySet()) {
            cur.put(e.getKey(), e.getValue().isChecked() ? "1" : "0");
        }
    }

    private void harvestUsersOnly() {
        if (!userChecks.isEmpty()) {
            curUsers.clear();
            for (CheckBox cb : userChecks) {
                if (cb.isChecked()) curUsers.add((String) cb.getTag());
            }
        }
        if (spUserSingle != null && info != null) {
            int pos = spUserSingle.getSelectedItemPosition();
            curUser = pos <= 0 ? "" : info.users.get(pos - 1).section;
        }
    }

    static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[,\n]")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void save() {
        harvestAll();
        String port = etPort.getText().toString().trim();
        if (!port.isEmpty()) {
            try {
                int p = Integer.parseInt(port);
                if (p < 1 || p > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                toast(getString(R.string.pwse_port_invalid));
                return;
            }
        }
        Map<String, String> set = new LinkedHashMap<>();
        List<String> del = new ArrayList<>();
        putOrDel(set, del, "remarks", etRemarks.getText().toString().trim());
        set.put("enable", swEnable.isChecked() ? "1" : "0");
        String type = currentType();
        set.put("type", type);
        putOrDel(set, del, "port", port);
        if (protocolValues.length > 0) {
            set.put("protocol", currentProtocol());
        }
        for (String key : textMap.keySet()) {
            putOrDel(set, del, key, cur.getOrDefault(key, ""));
        }
        Map<String, List<String>> lists = new LinkedHashMap<>();
        for (String key : listTextMap.keySet()) {
            lists.put(key, curLists.getOrDefault(key, new ArrayList<>()));
        }
        for (String key : spinMap.keySet()) {
            // 空值(如 flow/obfs_type 选了「不启用」)按删键处理,不写空串
            putOrDel(set, del, key, cur.getOrDefault(key, ""));
        }
        for (String key : flagMap.keySet()) {
            set.put(key, cur.getOrDefault(key, "0"));
        }
        PasswallApi.UserMode mode = PasswallApi.userModeFor(type, currentProtocol());
        // 自定义配置模式:不带用户绑定(与 LuCI 一致)
        if (!"Socks".equals(type) && "1".equals(cur.get("custom"))) {
            mode = PasswallApi.UserMode.NONE;
        }
        List<String> usersParam = null;
        if (mode == PasswallApi.UserMode.MULTI) {
            usersParam = new ArrayList<>(curUsers);
            del.add("user");
        } else if (mode == PasswallApi.UserMode.SINGLE) {
            putOrDel(set, del, "user", curUser);
            usersParam = new ArrayList<>();
        } else {
            del.add("user");
            usersParam = new ArrayList<>();
        }
        busy = true;
        PasswallApi.saveServer(pwConfig(), section, set, del, lists, usersParam,
                new ApiCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        busy = false;
                        finish();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        busy = false;
                        toast(error.getMessage());
                    }
                });
    }

    private void putOrDel(Map<String, String> set, List<String> del,
                          String key, String value) {
        if (value == null || value.isEmpty()) {
            if (section != null) del.add(key);
        } else {
            set.put(key, value);
        }
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.pwse_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    busy = true;
                    PasswallApi.deleteServer(pwConfig(), section,
                            new ApiCallback<Boolean>() {
                                @Override
                                public void onSuccess(Boolean result) {
                                    busy = false;
                                    finish();
                                }

                                @Override
                                public void onFailure(ApiError error) {
                                    busy = false;
                                    toast(error.getMessage());
                                }
                            });
                })
                .show();
    }

    private void showNewUserDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText etName = dialogInput(box, R.string.pwse_username, "");
        EditText etPass = dialogInput(box, R.string.pwse_password, "");
        EditText etUuid = dialogInput(box, R.string.pwse_uuid, UUID.randomUUID().toString());
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pwse_new_user)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String pass = etPass.getText().toString().trim();
                    String uuid = etUuid.getText().toString().trim();
                    if (name.isEmpty() || pass.isEmpty() || uuid.isEmpty()) {
                        toast(getString(R.string.pwse_user_required));
                        return;
                    }
                    busy = true;
                    PasswallApi.addServerUser(pwConfig(), name, pass, uuid,
                            new ApiCallback<Boolean>() {
                                @Override
                                public void onSuccess(Boolean result) {
                                    busy = false;
                                    toast(getString(R.string.pwse_user_created));
                                    reloadUsers(name);
                                }

                                @Override
                                public void onFailure(ApiError error) {
                                    busy = false;
                                    toast(error.getMessage());
                                }
                            });
                })
                .show();
    }

    private void reloadUsers(String newUsername) {
        PasswallApi.getServerInfo(pwConfig(), new ApiCallback<PasswallApi.ServerInfo>() {
            @Override
            public void onSuccess(PasswallApi.ServerInfo result) {
                info = result;
                for (PasswallApi.PwServerUser u : result.users) {
                    if (u.username.equals(newUsername)) {
                        PasswallApi.UserMode mode =
                                PasswallApi.userModeFor(currentType(), currentProtocol());
                        if (mode == PasswallApi.UserMode.MULTI
                                && !curUsers.contains(u.section)) {
                            curUsers.add(u.section);
                        } else if (mode == PasswallApi.UserMode.SINGLE) {
                            curUser = u.section;
                        }
                        break;
                    }
                }
                rebuildUsers();
            }

            @Override
            public void onFailure(ApiError error) {
                toast(error.getMessage());
            }
        });
    }

    // =====================================================================
    // 控件小工具
    // =====================================================================

    private abstract static class SimpleItemSelected
            implements android.widget.AdapterView.OnItemSelectedListener {
        public abstract void onItemSelected(int position);

        @Override
        public final void onItemSelected(android.widget.AdapterView<?> parent,
                                         View view, int position, long id) {
            onItemSelected(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void addHeaderTo(ViewGroup parent, int labelRes) {
        TextView t = new TextView(this);
        t.setText(labelRes);
        t.setTextAppearance(R.style.TextAppearance_Wrthub_SectionHeader);
        t.setPadding(0, dp(14), 0, dp(4));
        parent.addView(t);
    }

    private TextView addLabelTo(ViewGroup parent, int labelRes) {
        TextView t = new TextView(this);
        t.setText(labelRes);
        t.setTextColor(getColor(R.color.label_secondary));
        t.setTextSize(13);
        t.setPadding(0, dp(12), 0, dp(2));
        parent.addView(t);
        return t;
    }

    private EditText addText(int labelRes, String value, boolean number) {
        addLabelTo(form, labelRes);
        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(16);
        if (number) et.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(et);
        return et;
    }

    private void addTextTo(String key, int labelRes, String value, boolean number) {
        addLabelTo(extrasBox, labelRes);
        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(16);
        if (number) et.setInputType(InputType.TYPE_CLASS_NUMBER);
        extrasBox.addView(et);
        textMap.put(key, et);
    }

    private void addListTextTo(String key, int labelRes) {
        addLabelTo(extrasBox, labelRes);
        EditText et = new EditText(this);
        List<String> items = curLists.get(key);
        et.setText(items == null ? "" : String.join(", ", items));
        et.setTextSize(16);
        et.setHint(R.string.pwse_list_hint);
        extrasBox.addView(et);
        listTextMap.put(key, et);
    }

    /** 多行大文本(自定义配置 JSON / PEM 内容等),按标量键存 */
    private void addBigTextTo(String key, int labelRes, String value) {
        addLabelTo(extrasBox, labelRes);
        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(14);
        et.setMinLines(5);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        extrasBox.addView(et);
        textMap.put(key, et);
    }

    private Spinner addSpinner(int labelRes, String[] values, String[] labels,
                               String current) {
        return addSpinnerTo(form, null, labelRes, values, labels, current);
    }

    private void addSpinnerTo(String key, int labelRes, String[] values,
                              String[] labels, String current) {
        addSpinnerTo(extrasBox, key, labelRes, values, labels, current);
    }

    private Spinner addSpinnerTo(ViewGroup parent, String key, int labelRes,
                                 String[] values, String[] labels, String current) {
        addLabelTo(parent, labelRes);
        // 当前值不在选项里时(如值来自更新版本)把它加进来,避免打开编辑就被改掉
        if (current != null && !current.isEmpty()
                && !Arrays.asList(values).contains(current)) {
            values = prepend(values, current);
            labels = prepend(labels, current);
        }
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) sp.setSelection(i);
        }
        parent.addView(sp);
        if (key != null) {
            spinMap.put(key, sp);
            spinValues.put(key, values);
        }
        return sp;
    }

    private static String[] prepend(String[] arr, String v) {
        String[] out = new String[arr.length + 1];
        out[0] = v;
        System.arraycopy(arr, 0, out, 1, arr.length);
        return out;
    }

    private SwitchMaterial addFlag(int labelRes, boolean on) {
        return addFlagRow(form, null, labelRes, on);
    }

    private void addFlagTo(String key, int labelRes, boolean on) {
        addFlagRow(extrasBox, key, labelRes, on);
    }

    private SwitchMaterial addFlagRow(ViewGroup parent, String key, int labelRes, boolean on) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(2));
        TextView t = new TextView(this);
        t.setText(labelRes);
        t.setTextSize(16);
        t.setTextColor(getColor(R.color.label_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        row.addView(t, lp);
        SwitchMaterial sw = new SwitchMaterial(this);
        sw.setChecked(on);
        row.addView(sw);
        parent.addView(row);
        if (key != null) flagMap.put(key, sw);
        return sw;
    }

    private EditText dialogInput(LinearLayout box, int hintRes, String value) {
        EditText et = new EditText(this);
        et.setHint(hintRes);
        et.setText(value);
        et.setTextSize(16);
        box.addView(et);
        return et;
    }

    private String[] protocolLabels(String[] values) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = PasswallApi.ServerEntry.protocolDisplay(values[i]);
            if (labels[i].isEmpty()) labels[i] = values[i];
        }
        return labels;
    }
}
