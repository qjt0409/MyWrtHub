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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端实例新增/编辑页:按类型动态生成表单(通用项 + 类型特有项 + 用户绑定)。
 * 只写本页管理的键;传输/TLS/自定义配置等高级键不读不写、原样保留。
 */
public class PasswallServerEditActivity extends AppCompatActivity {

    public static final String EXTRA_SECTION = "section";

    private static final String[] TYPE_LABELS = {
            "Socks", "Sing-Box", "Xray", "Shadowsocks Rust", "ShadowsocksR", "Hysteria2"};

    private LinearLayout form;
    private EditText etRemarks, etPort;
    private SwitchMaterial swEnable;
    private Spinner spType;
    private LinearLayout dynamicBox, usersBox;

    private final Map<String, EditText> textMap = new LinkedHashMap<>();
    private final Map<String, Spinner> spinMap = new LinkedHashMap<>();
    private final Map<String, String[]> spinValues = new LinkedHashMap<>();
    private final Map<String, SwitchMaterial> flagMap = new LinkedHashMap<>();
    private Spinner spProtocol;
    private String[] protocolValues = new String[0];
    private Spinner spUserSingle;
    private final List<CheckBox> userChecks = new ArrayList<>();

    private PasswallApi.ServerInfo info;
    private PasswallApi.ServerEntry editing;
    private String section;
    /** 动态字段的当前值(重建表单时不丢):key -> value */
    private final Map<String, String> cur = new LinkedHashMap<>();
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
                    curUsers.clear();
                    curUsers.addAll(editing.users);
                    curUser = editing.user;
                }
                buildForm();
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

        dynamicBox = new LinearLayout(this);
        dynamicBox.setOrientation(LinearLayout.VERTICAL);
        form.addView(dynamicBox);

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
                harvestDynamic();
                // 换类型时协议回到该类型第一个,避免残留不兼容的协议值
                cur.remove("protocol");
                rebuildDynamic();
            }
        });
        building = false;
        rebuildDynamic();
    }

    private void rebuildDynamic() {
        building = true;
        harvestDynamicWidgetsOnly();
        dynamicBox.removeAllViews();
        textMap.clear();
        spinMap.clear();
        spinValues.clear();
        flagMap.clear();
        spProtocol = null;
        protocolValues = PasswallApi.protocolsFor(currentType());
        if (protocolValues.length > 0) {
            String proto = cur.getOrDefault("protocol", protocolValues[0]);
            spProtocol = addSpinnerTo(dynamicBox, null, R.string.pwse_protocol,
                    protocolValues, protocolLabels(protocolValues), proto);
            spProtocol.setOnItemSelectedListener(new SimpleItemSelected() {
                @Override
                public void onItemSelected(int position) {
                    if (building) return;
                    harvestDynamic();
                    rebuildDynamic();
                }
            });
        }
        buildTypeFields();
        usersBox = new LinearLayout(this);
        usersBox.setOrientation(LinearLayout.VERTICAL);
        dynamicBox.addView(usersBox);
        rebuildUsers();
        building = false;
    }

    private void buildTypeFields() {
        String type = currentType();
        String proto = currentProtocol();
        switch (type) {
            case "Socks":
                addFlagTo(dynamicBox, "auth", R.string.pwse_auth, curFlag("auth", false));
                break;
            case "SS-Rust":
                addSpinnerTo(dynamicBox, "method", R.string.pwse_method,
                        PasswallApi.SSRUST_METHODS, PasswallApi.SSRUST_METHODS,
                        cur.getOrDefault("method", "aes-256-gcm"));
                addTextTo(dynamicBox, "timeout", R.string.pwse_timeout,
                        cur.getOrDefault("timeout", ""), true);
                addFlagTo(dynamicBox, "tcp_fast_open", R.string.pwse_tcp_fast_open,
                        curFlag("tcp_fast_open", false));
                break;
            case "SSR":
                addSpinnerTo(dynamicBox, "method", R.string.pwse_method,
                        PasswallApi.SSR_METHODS, PasswallApi.SSR_METHODS,
                        cur.getOrDefault("method", "aes-256-cfb"));
                addSpinnerTo(dynamicBox, "protocol", R.string.pwse_protocol,
                        PasswallApi.SSR_PROTOCOLS, PasswallApi.SSR_PROTOCOLS,
                        cur.getOrDefault("protocol", "origin"));
                addTextTo(dynamicBox, "protocol_param", R.string.pwse_protocol_param,
                        cur.getOrDefault("protocol_param", ""), false);
                addSpinnerTo(dynamicBox, "obfs", R.string.pwse_obfs,
                        PasswallApi.SSR_OBFS, PasswallApi.SSR_OBFS,
                        cur.getOrDefault("obfs", "plain"));
                addTextTo(dynamicBox, "obfs_param", R.string.pwse_obfs_param,
                        cur.getOrDefault("obfs_param", ""), false);
                addTextTo(dynamicBox, "timeout", R.string.pwse_timeout,
                        cur.getOrDefault("timeout", ""), true);
                addFlagTo(dynamicBox, "tcp_fast_open", R.string.pwse_tcp_fast_open,
                        curFlag("tcp_fast_open", false));
                addFlagTo(dynamicBox, "udp_forward", R.string.pwse_udp_forward,
                        curFlag("udp_forward", false));
                break;
            case "Hysteria2":
                addSpinnerTo(dynamicBox, "obfs_type", R.string.pwse_obfs_type,
                        new String[]{"", "salamander", "gecko"},
                        new String[]{getString(R.string.pwse_obfs_none), "Salamander", "Gecko"},
                        cur.getOrDefault("obfs_type", ""));
                addTextTo(dynamicBox, "obfs_password", R.string.pwse_obfs_password,
                        cur.getOrDefault("obfs_password", ""), false);
                break;
            case "sing-box":
            case "Xray":
                if ("shadowsocks".equals(proto)) {
                    addSpinnerTo(dynamicBox, "method", R.string.pwse_method,
                            PasswallApi.SS_METHODS, PasswallApi.SS_METHODS,
                            cur.getOrDefault("method", "aes-256-gcm"));
                    addTextTo(dynamicBox, "ss_password", R.string.pwse_ss_password,
                            cur.getOrDefault("ss_password", ""), false);
                }
                break;
            default:
                break;
        }
    }

    private void rebuildUsers() {
        if (usersBox == null) return;
        // 先收割当前勾选,避免新建用户后丢失
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
        if ("SSR".equals(currentType())) {
            Spinner p = spinMap.get("protocol");
            return p != null ? PasswallApi.SSR_PROTOCOLS[p.getSelectedItemPosition()] : "";
        }
        if (spProtocol != null && protocolValues.length > 0) {
            return protocolValues[spProtocol.getSelectedItemPosition()];
        }
        return cur.getOrDefault("protocol", "");
    }

    private boolean curFlag(String key, boolean def) {
        String v = cur.get(key);
        return v == null ? def : "1".equals(v);
    }

    /** 把动态区控件的值收进 cur / curUser / curUsers(重建前调用) */
    private void harvestDynamic() {
        harvestDynamicWidgetsOnly();
        harvestUsersOnly();
        if (spProtocol != null && protocolValues.length > 0) {
            cur.put("protocol", protocolValues[spProtocol.getSelectedItemPosition()]);
        }
    }

    private void harvestDynamicWidgetsOnly() {
        for (Map.Entry<String, EditText> e : textMap.entrySet()) {
            cur.put(e.getKey(), e.getValue().getText().toString().trim());
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
        if (spUserSingle != null) {
            int pos = spUserSingle.getSelectedItemPosition();
            curUser = pos <= 0 ? "" : info.users.get(pos - 1).section;
        }
    }

    private void save() {
        harvestDynamic();
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
        String[] protos = PasswallApi.protocolsFor(type);
        if (protos.length > 0) {
            set.put("protocol", currentProtocol());
        }
        // 动态字段:text 空 = 删键,其余照写
        for (String key : textMap.keySet()) {
            putOrDel(set, del, key, cur.getOrDefault(key, ""));
        }
        for (String key : spinMap.keySet()) {
            if ("protocol".equals(key) && "SSR".equals(type)) {
                set.put("protocol", cur.getOrDefault("protocol", "origin"));
            } else {
                set.put(key, cur.getOrDefault(key, ""));
            }
        }
        for (String key : flagMap.keySet()) {
            set.put(key, cur.getOrDefault(key, "0"));
        }
        PasswallApi.UserMode mode = PasswallApi.userModeFor(type, currentProtocol());
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
        PasswallApi.saveServer(pwConfig(), section, set, del, usersParam,
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

    /** 新建用户后刷新用户列表,并自动选中刚建的那个 */
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
        return addTextTo(form, null, labelRes, value, number);
    }

    private EditText addTextTo(ViewGroup parent, String key, int labelRes,
                               String value, boolean number) {
        addLabelTo(parent, labelRes);
        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(16);
        if (number) et.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(et);
        if (key != null) textMap.put(key, et);
        return et;
    }

    private Spinner addSpinner(int labelRes, String[] values, String[] labels,
                               String current) {
        return addSpinnerTo(form, null, labelRes, values, labels, current);
    }

    private Spinner addSpinnerTo(ViewGroup parent, String key, int labelRes,
                                 String[] values, String[] labels, String current) {
        addLabelTo(parent, labelRes);
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

    private SwitchMaterial addFlag(int labelRes, boolean on) {
        return addFlagRow(form, null, labelRes, on);
    }

    private SwitchMaterial addFlagTo(ViewGroup parent, String key, int labelRes, boolean on) {
        return addFlagRow(parent, key, labelRes, on);
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
