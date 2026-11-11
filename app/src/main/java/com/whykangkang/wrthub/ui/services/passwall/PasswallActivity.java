package com.whykangkang.wrthub.ui.services.passwall;

import android.os.Bundle;
import android.view.Gravity;
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
import com.whykangkang.wrthub.model.PasswallNode;

/**
 * PassWall:总开关、运行状态、当前节点、节点点选切换、重启服务。
 * 数据与 LuCI 同源(uci passwall),改完 apply + 重启服务生效。
 */
public class PasswallActivity extends AppCompatActivity {

    private TextView stateLabel, currentNodeLabel, emptyLabel;
    private SwitchMaterial enabledSwitch;
    private LinearLayout nodeRows;
    private SwipeRefreshLayout swipeRefresh;

    private PasswallApi.PwSettings settings;
    private Boolean running;
    /** 正在写配置时锁住开关与节点列表,防连点 */
    private boolean busy;
    /** 程序给开关赋值时别触发写回 */
    private boolean renderingSwitch;

    /** 配置名(passwall);PassWall2 子类覆写为 passwall2(两者结构同构) */
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_passwall);
        ((TextView) findViewById(R.id.pw_title)).setText(pwTitleRes());

        stateLabel = findViewById(R.id.pw_state);
        currentNodeLabel = findViewById(R.id.pw_current_node);
        emptyLabel = findViewById(R.id.pw_empty);
        enabledSwitch = findViewById(R.id.pw_enabled_switch);
        nodeRows = findViewById(R.id.pw_node_rows);
        swipeRefresh = findViewById(R.id.swipe_refresh);

        swipeRefresh.setOnRefreshListener(this::load);
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (renderingSwitch || settings == null) return;
            setEnabled(checked);
        });
        findViewById(R.id.pw_restart).setOnClickListener(v -> restartService());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // =====================================================================
    // 载入
    // =====================================================================

    private void load() {
        swipeRefresh.setRefreshing(true);
        PasswallApi.getSettings(pwConfig(), new ApiCallback<PasswallApi.PwSettings>() {
            @Override
            public void onSuccess(PasswallApi.PwSettings value) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                settings = value;
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                settings = null;
                render();
                showEmpty(error.getType() == ApiError.Type.PERMISSION_DENIED
                                || error.getType() == ApiError.Type.CONFIG_NOT_FOUND
                        ? getString(R.string.pw_not_installed, pwName())
                        : getString(R.string.pw_load_failed, pwName(), error.getMessage()));
            }
        });
        PasswallApi.serviceRunning(pwConfig(), new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean value) {
                if (isFinishing() || isDestroyed()) return;
                running = value;
                renderState();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                running = null;
                renderState();
            }
        });
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private void render() {
        renderState();
        renderingSwitch = true;
        enabledSwitch.setChecked(settings != null && settings.enabled);
        renderingSwitch = false;
        enabledSwitch.setEnabled(settings != null && !busy);

        nodeRows.removeAllViews();
        if (settings == null) return;
        if (settings.nodes.isEmpty()) {
            showEmpty(getString(R.string.pw_no_nodes, pwName()));
            return;
        }
        emptyLabel.setVisibility(View.GONE);
        for (int i = 0; i < settings.nodes.size(); i++) {
            PasswallNode node = settings.nodes.get(i);
            nodeRows.addView(nodeRow(node, node.section.equals(settings.nodeId)));
            if (i < settings.nodes.size() - 1) nodeRows.addView(divider());
        }
    }

    private void renderState() {
        if (settings == null) {
            stateLabel.setText(R.string.pw_state_unknown);
            currentNodeLabel.setText("");
            return;
        }
        if (!settings.enabled) {
            stateLabel.setText(R.string.pw_state_disabled);
        } else if (running == null) {
            stateLabel.setText(R.string.pw_state_unknown);
        } else {
            stateLabel.setText(running ? R.string.pw_state_running : R.string.pw_state_stopped);
        }
        PasswallNode current = settings.currentNode();
        currentNodeLabel.setText(current != null
                ? getString(R.string.pw_current_node, current.displayName())
                : getString(R.string.pw_current_node, getString(R.string.pw_no_node_selected)));
    }

    private View nodeRow(PasswallNode node, boolean current) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setEnabled(!busy);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView name = new TextView(this);
        name.setText(node.displayName());
        name.setTextColor(getColor(R.color.label_primary));
        name.setTextSize(15);
        texts.addView(name);

        String sub = node.subtitle();
        if (!sub.isEmpty()) {
            TextView subtitle = new TextView(this);
            subtitle.setText(sub);
            subtitle.setTextColor(getColor(R.color.label_secondary));
            subtitle.setTextSize(12);
            texts.addView(subtitle);
        }
        row.addView(texts);

        if (current) {
            TextView mark = new TextView(this);
            mark.setText(R.string.pw_current_mark);
            mark.setTextColor(getColor(R.color.ios_blue));
            mark.setTextSize(14);
            row.addView(mark);
        }

        row.setOnClickListener(v -> {
            if (!node.section.equals(settings.nodeId)) switchNode(node);
        });
        return row;
    }

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        v.setBackgroundColor(getColor(R.color.separator));
        return v;
    }

    private void showEmpty(String text) {
        emptyLabel.setText(text);
        emptyLabel.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // =====================================================================
    // 操作
    // =====================================================================

    private void setBusy(boolean value) {
        busy = value;
        enabledSwitch.setEnabled(settings != null && !value);
        render();
    }

    private void setEnabled(boolean enabled) {
        setBusy(true);
        PasswallApi.setEnabled(settings, enabled, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                Toast.makeText(PasswallActivity.this,
                        getString(enabled ? R.string.pw_enabled_toast
                                : R.string.pw_disabled_toast, pwName()),
                        Toast.LENGTH_SHORT).show();
                load();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                Toast.makeText(PasswallActivity.this,
                        getString(R.string.pw_op_failed, error.getMessage()),
                        Toast.LENGTH_LONG).show();
                load();
            }
        });
    }

    private void switchNode(PasswallNode node) {
        setBusy(true);
        PasswallApi.setNode(settings, node.section, new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
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
                Toast.makeText(PasswallActivity.this,
                        getString(R.string.pw_op_failed, error.getMessage()),
                        Toast.LENGTH_LONG).show();
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
                Toast.makeText(PasswallActivity.this,
                        getString(R.string.pw_op_failed, error.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        });
    }
}
