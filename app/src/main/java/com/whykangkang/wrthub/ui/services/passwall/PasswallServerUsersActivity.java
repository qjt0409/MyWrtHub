package com.whykangkang.wrthub.ui.services.passwall;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.PasswallApi;

/**
 * 服务端用户管理:列出 user 段,点任意一行进编辑页(改名/改密码/改 UUID/
 * WireGuard 密钥),右上角加号新建。VMess/VLESS/Trojan 等的账号就在这里改。
 */
public class PasswallServerUsersActivity extends AppCompatActivity {

    private TextView tvStatus;
    private LinearLayout listBox;

    protected String pwConfig() {
        return "passwall";
    }

    protected Class<?> userEditActivityClass() {
        return PasswallServerUserEditActivity.class;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_passwall_server_users);
        MaterialToolbar toolbar = findViewById(R.id.pwu_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.pwu_add).setOnClickListener(v ->
                startActivity(new Intent(this, userEditActivityClass())));
        tvStatus = findViewById(R.id.pwu_status);
        listBox = findViewById(R.id.pwu_list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(R.string.pw_loading);
        listBox.removeAllViews();
        PasswallApi.getServerInfo(pwConfig(),
                new ApiCallback<PasswallApi.ServerInfo>() {
                    @Override
                    public void onSuccess(PasswallApi.ServerInfo info) {
                        render(info);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        tvStatus.setText(getString(R.string.pw_load_failed,
                                error.getMessage()));
                    }
                });
    }

    private void render(PasswallApi.ServerInfo info) {
        listBox.removeAllViews();
        if (info.users.isEmpty()) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(R.string.pwu_empty);
            return;
        }
        tvStatus.setVisibility(View.GONE);
        for (PasswallApi.PwServerUser u : info.users) {
            View row = getLayoutInflater().inflate(
                    R.layout.item_passwall_server_user, listBox, false);
            TextView name = row.findViewById(R.id.pwsu_name);
            TextView sub = row.findViewById(R.id.pwsu_uuid);
            name.setText(u.username.isEmpty()
                    ? getString(R.string.pwse_unnamed) : u.username);
            String uuid = u.fields.getOrDefault("uuid", "");
            sub.setText(uuid.isEmpty() ? u.section : "UUID " + uuid);
            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, userEditActivityClass());
                intent.putExtra(PasswallServerUserEditActivity.EXTRA_SECTION, u.section);
                startActivity(intent);
            });
            listBox.addView(row);
        }
    }

    protected void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }
}
