package com.whykangkang.wrthub.ui.services.passwall;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.PasswallApi;

/**
 * PassWall / PassWall2 服务端(二级页):总开关(global 段 enable 键)+
 * 服务实例列表(server 段逐个开关 + 运行状态)。数据与 LuCI 服务端页同源,
 * 改完 apply + 重启服务生效;实例的新增/编辑仍在 LuCI 完成。
 */
public class PasswallServerActivity extends AppCompatActivity {

    private SwitchMaterial masterSwitch;
    private LinearLayout rows;
    private TextView emptyLabel;
    private SwipeRefreshLayout swipeRefresh;

    private PasswallApi.ServerInfo info;
    private boolean busy;
    private boolean rendering;

    /** 配置名(passwall);PassWall2 子类覆写为 passwall2 */
    protected String pwConfig() {
        return PasswallApi.CONFIG;
    }

    /** App 名字符(PassWall / PassWall2) */
    protected int pwTitleRes() {
        return R.string.svc_passwall;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_passwall_server);
        ((TextView) findViewById(R.id.pws_title)).setText(
                getString(pwTitleRes()) + " · " + getString(R.string.pw_server_entry));
        masterSwitch = findViewById(R.id.pws_master_switch);
        rows = findViewById(R.id.pws_rows);
        emptyLabel = findViewById(R.id.pws_empty);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefresh.setOnRefreshListener(this::load);

        masterSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (rendering || busy || info == null) return;
            if (checked != info.serverEnabled) toggleMaster(checked);
        });
        load();
    }

    private void load() {
        PasswallApi.getServerInfo(pwConfig(), new ApiCallback<PasswallApi.ServerInfo>() {
            @Override
            public void onSuccess(PasswallApi.ServerInfo result) {
                swipeRefresh.setRefreshing(false);
                info = result;
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                swipeRefresh.setRefreshing(false);
                Toast.makeText(PasswallServerActivity.this,
                        error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void render() {
        rendering = true;
        masterSwitch.setChecked(info.serverEnabled);
        rendering = false;
        rows.removeAllViews();
        emptyLabel.setVisibility(info.servers.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < info.servers.size(); i++) {
            PasswallApi.ServerEntry e = info.servers.get(i);
            View row = inflater.inflate(R.layout.item_passwall_server, rows, false);
            View dot = row.findViewById(R.id.pwsr_dot);
            TextView name = row.findViewById(R.id.pwsr_name);
            TextView meta = row.findViewById(R.id.pwsr_meta);
            SwitchMaterial sw = row.findViewById(R.id.pwsr_switch);
            name.setText(e.remarks.isEmpty()
                    ? getString(R.string.pw_server_unnamed) : e.remarks);
            String metaText = metaOf(e, null);
            meta.setText(metaText);
            rendering = true;
            sw.setChecked(e.enable);
            rendering = false;
            sw.setOnCheckedChangeListener((v, checked) -> {
                if (rendering || busy) return;
                if (checked != e.enable) toggleEntry(e, checked);
            });
            rows.addView(row);
            // 运行状态:总开关开着且本实例启用时才查(其余情况必然没在跑)
            if (info.serverEnabled && e.enable) {
                final int index = i;
                PasswallApi.serverRunning(pwConfig(), index, e.section,
                        new ApiCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean running) {
                                dot.setBackgroundResource(Boolean.TRUE.equals(running)
                                        ? R.drawable.dot_green : R.drawable.dot_red);
                                meta.setText(metaOf(e, running));
                            }

                            @Override
                            public void onFailure(ApiError error) {
                                // 状态只是展示,失败保持灰点
                            }
                        });
            }
        }
    }

    private String metaOf(PasswallApi.ServerEntry e, Boolean running) {
        StringBuilder sb = new StringBuilder();
        if (!e.displayType().isEmpty()) sb.append(e.displayType());
        if (!e.port.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(":").append(e.port);
        }
        if (running != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(getString(running
                    ? R.string.pw_server_running : R.string.pw_server_stopped));
        }
        return sb.toString();
    }

    private void toggleMaster(boolean on) {
        busy = true;
        PasswallApi.setServerEnabled(info, on, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                busy = false;
                info.serverEnabled = on;
                // 服务重启后进程要一会儿才起来,稍等再刷新状态
                rows.postDelayed(PasswallServerActivity.this::load, 1500);
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                busy = false;
                Toast.makeText(PasswallServerActivity.this,
                        error.getMessage(), Toast.LENGTH_SHORT).show();
                render();
            }
        });
    }

    private void toggleEntry(PasswallApi.ServerEntry e, boolean on) {
        busy = true;
        PasswallApi.setServerEntryEnabled(pwConfig(), e.section, on,
                new ApiCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        busy = false;
                        e.enable = on;
                        rows.postDelayed(PasswallServerActivity.this::load, 1500);
                        render();
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        busy = false;
                        Toast.makeText(PasswallServerActivity.this,
                                error.getMessage(), Toast.LENGTH_SHORT).show();
                        render();
                    }
                });
    }
}
