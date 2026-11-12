package com.whykangkang.wrthub.ui.services.passwall;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
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
 * 服务端单个用户的编辑页:用户名/密码/UUID + WireGuard 三件套 + Allowed IPs。
 * 新建(section 为空)走 addServerUser;编辑走 updateServerUser;可删除。
 */
public class PasswallServerUserEditActivity extends AppCompatActivity {

    public static final String EXTRA_SECTION =
            "com.whykangkang.wrthub.extra.PW_USER_SECTION";

    private LinearLayout form;
    private TextView tvStatus;
    private MaterialButton btnSave;
    private MaterialButton btnDelete;
    private String section;
    private boolean busy;

    private EditText etUsername;
    private EditText etPassword;
    private EditText etUuid;
    private EditText etWgPub;
    private EditText etWgPriv;
    private EditText etWgPsk;
    private EditText etAllowedIps;

    protected String pwConfig() {
        return "passwall";
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_passwall_server_user_edit);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        MaterialToolbar toolbar = findViewById(R.id.pwue_toolbar);
        toolbar.setTitle(section == null ? R.string.pwu_add_title
                : R.string.pwu_edit_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        form = findViewById(R.id.pwue_form);
        tvStatus = findViewById(R.id.pwue_status);
        btnSave = findViewById(R.id.pwue_save);
        btnDelete = findViewById(R.id.pwue_delete);
        btnSave.setOnClickListener(v -> save());
        btnDelete.setOnClickListener(v -> confirmDelete());
        btnDelete.setVisibility(section == null ? View.GONE : View.VISIBLE);
        buildForm();
        if (section != null) {
            load();
        }
    }

    private void buildForm() {
        etUsername = addText(R.string.pwu_username);
        etPassword = addText(R.string.pwu_password);
        etUuid = addUuidField();
        etWgPub = addText(R.string.pwu_wg_pub);
        etWgPriv = addText(R.string.pwu_wg_priv);
        etWgPsk = addText(R.string.pwu_wg_psk);
        etAllowedIps = addText(R.string.pwu_allowed_ips);
    }

    private EditText addText(int labelRes) {
        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(14);
        label.setPadding(0, 12, 0, 2);
        form.addView(label);
        EditText et = new EditText(this);
        et.setTextSize(16);
        form.addView(et);
        return et;
    }

    private EditText addUuidField() {
        TextView label = new TextView(this);
        label.setText(R.string.pwu_uuid);
        label.setTextSize(14);
        label.setPadding(0, 12, 0, 2);
        form.addView(label);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        EditText et = new EditText(this);
        et.setTextSize(16);
        et.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(et);
        MaterialButton gen = new MaterialButton(this);
        gen.setText(R.string.pwu_gen_uuid);
        gen.setOnClickListener(v ->
                et.setText(UUID.randomUUID().toString()));
        row.addView(gen);
        form.addView(row);
        return et;
    }

    private void load() {
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(R.string.pw_loading);
        PasswallApi.getServerInfo(pwConfig(),
                new ApiCallback<PasswallApi.ServerInfo>() {
                    @Override
                    public void onSuccess(PasswallApi.ServerInfo info) {
                        tvStatus.setVisibility(View.GONE);
                        for (PasswallApi.PwServerUser u : info.users) {
                            if (section.equals(u.section)) {
                                fill(u);
                                return;
                            }
                        }
                        tvStatus.setVisibility(View.VISIBLE);
                        tvStatus.setText(R.string.pw_load_failed);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        tvStatus.setText(getString(R.string.pw_load_failed,
                                error.getMessage()));
                    }
                });
    }

    private void fill(PasswallApi.PwServerUser u) {
        etUsername.setText(u.username);
        etPassword.setText(u.fields.getOrDefault("password", ""));
        etUuid.setText(u.fields.getOrDefault("uuid", ""));
        etWgPub.setText(u.fields.getOrDefault("wireguard_public_key", ""));
        etWgPriv.setText(u.fields.getOrDefault("wireguard_private_key", ""));
        etWgPsk.setText(u.fields.getOrDefault("wireguard_pre_shared_key", ""));
        List<String> ips = u.listFields.get("allowed_ips");
        etAllowedIps.setText(ips == null ? "" : String.join(", ", ips));
    }

    private Map<String, String> collectSet() {
        Map<String, String> set = new LinkedHashMap<>();
        set.put("username", etUsername.getText().toString().trim());
        putIfNotEmpty(set, "password", etPassword);
        putIfNotEmpty(set, "uuid", etUuid);
        putIfNotEmpty(set, "wireguard_public_key", etWgPub);
        putIfNotEmpty(set, "wireguard_private_key", etWgPriv);
        putIfNotEmpty(set, "wireguard_pre_shared_key", etWgPsk);
        return set;
    }

    private List<String> collectDel() {
        List<String> del = new ArrayList<>();
        if (section == null) {
            return del;
        }
        if (etPassword.getText().toString().trim().isEmpty()) del.add("password");
        if (etUuid.getText().toString().trim().isEmpty()) del.add("uuid");
        if (etWgPub.getText().toString().trim().isEmpty()) {
            del.add("wireguard_public_key");
        }
        if (etWgPriv.getText().toString().trim().isEmpty()) {
            del.add("wireguard_private_key");
        }
        if (etWgPsk.getText().toString().trim().isEmpty()) {
            del.add("wireguard_pre_shared_key");
        }
        return del;
    }

    private void save() {
        if (busy) {
            return;
        }
        String username = etUsername.getText().toString().trim();
        if (username.isEmpty()) {
            etUsername.setError(getString(R.string.pwu_username_required));
            return;
        }
        busy = true;
        if (section == null) {
            String uuid = etUuid.getText().toString().trim();
            if (uuid.isEmpty()) {
                uuid = UUID.randomUUID().toString();
            }
            PasswallApi.addServerUserFull(pwConfig(), username,
                    etPassword.getText().toString().trim(), uuid,
                    new ApiCallback<String>() {
                        @Override
                        public void onSuccess(String newSection) {
                            // 新建只带了账号三件套;WireGuard/AllowedIPs 再补一刀
                            Map<String, String> extra = new LinkedHashMap<>();
                            putIfNotEmpty(extra, "wireguard_public_key", etWgPub);
                            putIfNotEmpty(extra, "wireguard_private_key", etWgPriv);
                            putIfNotEmpty(extra, "wireguard_pre_shared_key", etWgPsk);
                            List<String> ips = splitList(etAllowedIps.getText().toString());
                            if (extra.isEmpty() && ips.isEmpty()) {
                                busy = false;
                                finish();
                                return;
                            }
                            Map<String, List<String>> lists = new LinkedHashMap<>();
                            if (!ips.isEmpty()) {
                                lists.put("allowed_ips", ips);
                            }
                            PasswallApi.updateServerUser(pwConfig(), newSection,
                                    extra, new ArrayList<>(), lists,
                                    new ApiCallback<Boolean>() {
                                        @Override
                                        public void onSuccess(Boolean r) {
                                            busy = false;
                                            finish();
                                        }

                                        @Override
                                        public void onFailure(ApiError error) {
                                            busy = false;
                                            toastMsg(error.getMessage());
                                        }
                                    });
                        }

                        @Override
                        public void onFailure(ApiError error) {
                            busy = false;
                            toastMsg(error.getMessage());
                        }
                    });
            return;
        }
        Map<String, String> set = collectSet();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        List<String> del = collectDel();
        List<String> ips = splitList(etAllowedIps.getText().toString());
        if (!ips.isEmpty()) {
            lists.put("allowed_ips", ips);
        } else {
            del.add("allowed_ips");
        }
        PasswallApi.updateServerUser(pwConfig(), section, set, del, lists,
                new ApiCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        busy = false;
                        finish();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        busy = false;
                        toastMsg(error.getMessage());
                    }
                });
    }

    private void confirmDelete() {
        if (section == null || busy) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.pwu_delete_confirm)
                .setPositiveButton(R.string.pwu_delete, (d, w) -> doDelete())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void doDelete() {
        busy = true;
        PasswallApi.deleteServerUser(pwConfig(), section,
                new ApiCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        busy = false;
                        finish();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        busy = false;
                        toastMsg(error.getMessage());
                    }
                });
    }

    private static void putIfNotEmpty(Map<String, String> set, String key, EditText et) {
        String v = et.getText().toString().trim();
        if (!v.isEmpty()) {
            set.put(key, v);
        }
    }

    private static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[, ]+")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private void toastMsg(String msg) {
        android.widget.Toast.makeText(this, msg,
                android.widget.Toast.LENGTH_LONG).show();
    }
}
