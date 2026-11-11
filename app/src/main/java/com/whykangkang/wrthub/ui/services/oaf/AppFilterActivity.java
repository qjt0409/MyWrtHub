package com.whykangkang.wrthub.ui.services.oaf;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OafApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用过滤(OpenAppFilter):总开关、过滤规则、白名单、应用分类浏览。
 *
 * 与 LuCI / OPAssistant 同走 fwx/common 协议(见 OafApi)。单个应用没有
 * 独立开关,规则里勾哪些应用就过滤哪些,改完走 update_filter_rule 全量提交。
 */
public class AppFilterActivity extends AppCompatActivity {

    private SwitchMaterial advSwitch;
    private TextView featureInfo, emptyLabel;
    private LinearLayout rulesBox, whitelistBox, classesBox;
    private SwipeRefreshLayout swipeRefresh;

    private final List<OafApi.AppClass> classes = new ArrayList<>();
    private final List<OafApi.Rule> rules = new ArrayList<>();
    private final List<OafApi.DeviceEntry> whitelist = new ArrayList<>();
    /** 正在把服务端状态同步进开关,期间不触发写回 */
    private boolean syncingSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oaf);

        View advRow = findViewById(R.id.oaf_adv_row);
        ((TextView) advRow.findViewById(R.id.row_label)).setText(R.string.oaf_enable);
        advSwitch = advRow.findViewById(R.id.row_switch);
        advSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (syncingSwitch) return;
            setEnabled(checked);
        });
        featureInfo = findViewById(R.id.oaf_feature_info);
        emptyLabel = findViewById(R.id.oaf_empty);
        rulesBox = findViewById(R.id.oaf_rules);
        whitelistBox = findViewById(R.id.oaf_whitelist);
        classesBox = findViewById(R.id.oaf_classes);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefresh.setOnRefreshListener(this::load);

        findViewById(R.id.btn_oaf_records).setOnClickListener(v ->
                startActivity(new Intent(this, OafRecordsActivity.class)));
        findViewById(R.id.btn_oaf_add_rule).setOnClickListener(v -> promptNewRule());
        findViewById(R.id.btn_oaf_add_whitelist).setOnClickListener(v -> promptAddWhitelist());
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
        OafApi.getFilterEnabled(new ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean enabled) {
                if (isFinishing() || isDestroyed()) return;
                syncingSwitch = true;
                advSwitch.setChecked(enabled);
                syncingSwitch = false;
                emptyLabel.setVisibility(View.GONE);
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                advSwitch.setEnabled(false);
                showEmpty(isNotAvailable(error)
                        ? getString(R.string.oaf_not_installed)
                        : getString(R.string.oaf_load_failed, error.getMessage()));
            }
        });
        OafApi.getFeatureInfo(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject data) {
                if (isFinishing() || isDestroyed()) return;
                String version = data.has("version") ? data.get("version").getAsString() : "";
                long count = 0;
                try {
                    count = data.has("app_count") ? data.get("app_count").getAsLong() : 0;
                } catch (Exception ignored) {
                }
                if (!version.isEmpty()) {
                    featureInfo.setText(getString(R.string.oaf_feature_info, version, count));
                    featureInfo.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                if (!isFinishing() || isDestroyed()) return;
                featureInfo.setVisibility(View.GONE);
            }
        });
        OafApi.loadClasses(new ApiCallback<List<OafApi.AppClass>>() {
            @Override
            public void onSuccess(List<OafApi.AppClass> list) {
                if (isFinishing() || isDestroyed()) return;
                classes.clear();
                classes.addAll(list);
                renderClasses();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                classes.clear();
                renderClasses();
            }
        });
        OafApi.loadRules(new ApiCallback<List<OafApi.Rule>>() {
            @Override
            public void onSuccess(List<OafApi.Rule> list) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                rules.clear();
                rules.addAll(list);
                renderRules();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                rules.clear();
                renderRules();
            }
        });
        loadWhitelist();
    }

    private void loadWhitelist() {
        OafApi.loadWhitelist(new ApiCallback<List<OafApi.DeviceEntry>>() {
            @Override
            public void onSuccess(List<OafApi.DeviceEntry> list) {
                if (isFinishing() || isDestroyed()) return;
                whitelist.clear();
                whitelist.addAll(list);
                renderWhitelist();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                whitelist.clear();
                renderWhitelist();
            }
        });
    }

    private static boolean isNotAvailable(ApiError error) {
        return error.getType() == ApiError.Type.PERMISSION_DENIED
                || error.getType() == ApiError.Type.CONFIG_NOT_FOUND;
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private void renderRules() {
        rulesBox.removeAllViews();
        if (rules.isEmpty()) {
            rulesBox.addView(hintRow(getString(R.string.oaf_no_rules)));
            return;
        }
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) rulesBox.addView(separator());
            rulesBox.addView(ruleRow(rules.get(i)));
        }
    }

    private View ruleRow(OafApi.Rule rule) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(8), dp(10));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(rule.name.isEmpty() ? ("#" + rule.id) : rule.name);
        name.setTextSize(15);
        name.setTextColor(getColor(R.color.label_primary));

        TextView meta = new TextView(this);
        int appCount = rule.expandedAppIds().size();
        String base = rule.mode == 2
                ? getString(R.string.oaf_rule_meta_user,
                        rule.userName.isEmpty() ? rule.userMac : rule.userName, appCount)
                : getString(R.string.oaf_rule_meta_all, appCount);
        String time = rule.timeSummary();
        meta.setText(time.isEmpty() ? base : base + " · " + time);
        meta.setTextSize(12);
        meta.setTextColor(getColor(R.color.label_secondary));

        left.addView(name);
        left.addView(meta);
        row.addView(left);

        SwitchMaterial sw = new SwitchMaterial(this);
        sw.setChecked(rule.enabled);
        sw.setOnCheckedChangeListener((v, checked) -> {
            if (checked == rule.enabled) return;
            boolean old = rule.enabled;
            rule.enabled = checked;
            OafApi.updateRule(rule, new ApiCallback<JsonObject>() {
                @Override
                public void onSuccess(JsonObject data) {
                }

                @Override
                public void onFailure(ApiError error) {
                    if (isFinishing() || isDestroyed()) return;
                    rule.enabled = old;
                    sw.setChecked(old);
                    alert(getString(R.string.oaf_save_failed, error.getMessage()));
                }
            });
        });
        row.addView(sw);

        row.setOnClickListener(v -> ruleActions(rule));
        return row;
    }

    private void renderWhitelist() {
        whitelistBox.removeAllViews();
        if (whitelist.isEmpty()) {
            whitelistBox.addView(hintRow(getString(R.string.oaf_no_whitelist)));
            return;
        }
        for (int i = 0; i < whitelist.size(); i++) {
            if (i > 0) whitelistBox.addView(separator());
            OafApi.DeviceEntry d = whitelist.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(8), dp(8), dp(8));

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView name = new TextView(this);
            name.setText(d.displayName());
            name.setTextSize(15);
            name.setTextColor(getColor(R.color.label_primary));
            TextView mac = new TextView(this);
            mac.setText(d.mac);
            mac.setTextSize(12);
            mac.setTextColor(getColor(R.color.label_secondary));
            left.addView(name);
            left.addView(mac);
            row.addView(left);

            MaterialButton remove = new MaterialButton(this, null,
                    com.google.android.material.R.attr.borderlessButtonStyle);
            remove.setText(R.string.oaf_remove);
            remove.setMinWidth(0);
            remove.setOnClickListener(v -> OafApi.delWhitelist(d.mac,
                    new ApiCallback<JsonObject>() {
                        @Override
                        public void onSuccess(JsonObject data) {
                            loadWhitelist();
                        }

                        @Override
                        public void onFailure(ApiError error) {
                            if (isFinishing() || isDestroyed()) return;
                            alert(getString(R.string.oaf_save_failed, error.getMessage()));
                        }
                    }));
            row.addView(remove);
            whitelistBox.addView(row);
        }
    }

    private void renderClasses() {
        classesBox.removeAllViews();
        for (int i = 0; i < classes.size(); i++) {
            if (i > 0) classesBox.addView(separator());
            OafApi.AppClass c = classes.get(i);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(10), dp(16), dp(10));

            TextView title = new TextView(this);
            title.setText(c.name + "  ·  " + getString(R.string.oaf_class_count, c.apps.size()));
            title.setTextSize(14);
            title.setTextColor(getColor(R.color.label_primary));

            TextView names = new TextView(this);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < c.apps.size(); j++) {
                if (j > 0) sb.append("、");
                sb.append(c.apps.get(j).name);
            }
            names.setText(sb.toString());
            names.setTextSize(12);
            names.setTextColor(getColor(R.color.label_secondary));

            box.addView(title);
            box.addView(names);
            classesBox.addView(box);
        }
    }

    private View hintRow(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(getColor(R.color.label_secondary));
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        return t;
    }

    private View separator() {
        View line = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(dp(16));
        line.setLayoutParams(lp);
        line.setBackgroundColor(getColor(R.color.separator));
        return line;
    }

    private void showEmpty(String message) {
        emptyLabel.setText(message);
        emptyLabel.setVisibility(View.VISIBLE);
    }

    // =====================================================================
    // 交互:总开关 / 规则增删改 / 白名单
    // =====================================================================

    private void setEnabled(boolean enabled) {
        OafApi.setFilterEnabled(enabled, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject data) {
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                syncingSwitch = true;
                advSwitch.setChecked(!enabled);
                syncingSwitch = false;
                alert(getString(R.string.oaf_save_failed, error.getMessage()));
            }
        });
    }

    private void ruleActions(OafApi.Rule rule) {
        CharSequence[] items = {
                getString(R.string.oaf_pick_apps),
                getString(R.string.oaf_delete_rule)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(rule.name.isEmpty() ? ("#" + rule.id) : rule.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) pickApps(rule, null);
                    else confirmDeleteRule(rule);
                })
                .show();
    }

    private void confirmDeleteRule(OafApi.Rule rule) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.oaf_delete_rule_confirm, rule.name))
                .setPositiveButton(R.string.oaf_delete_rule, (d, w) ->
                        OafApi.deleteRule(rule.id, new ApiCallback<JsonObject>() {
                            @Override
                            public void onSuccess(JsonObject data) {
                                load();
                            }

                            @Override
                            public void onFailure(ApiError error) {
                                if (isFinishing() || isDestroyed()) return;
                                alert(getString(R.string.oaf_save_failed, error.getMessage()));
                            }
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 新增规则:先起名,再勾应用 */
    private void promptNewRule() {
        if (classes.isEmpty()) {
            alert(getString(R.string.oaf_not_installed));
            return;
        }
        EditText input = new EditText(this);
        input.setHint(R.string.oaf_rule_name);
        input.setSingleLine(true);
        int pad = dp(20);
        input.setPadding(pad, dp(8), pad, dp(8));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.oaf_add_rule)
                .setView(input)
                .setPositiveButton(R.string.oaf_pick_apps, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = getString(R.string.oaf_add_rule);
                    pickApps(null, name);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * 应用多选。rule != null 时改这条规则的 app_ids;rule == null 时用 newName 新建。
     * 写回时按单个 id 提交(不做区间压缩,服务端同样接受)。
     */
    private void pickApps(OafApi.Rule rule, String newName) {
        List<OafApi.App> all = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (OafApi.AppClass c : classes) {
            for (OafApi.App a : c.apps) {
                all.add(a);
                labels.add(a.name + " · " + c.name);
            }
        }
        if (all.isEmpty()) {
            alert(getString(R.string.oaf_not_installed));
            return;
        }
        Set<Integer> current = rule != null ? rule.expandedAppIds() : new HashSet<>();
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) checked[i] = current.contains(all.get(i).id);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.oaf_pick_apps)
                .setMultiChoiceItems(labels.toArray(new CharSequence[0]), checked,
                        (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    List<Integer> selected = new ArrayList<>();
                    for (int i = 0; i < all.size(); i++) {
                        if (checked[i]) selected.add(all.get(i).id);
                    }
                    if (rule == null) {
                        OafApi.addRule(newName, selected, saveCallback());
                    } else {
                        rule.appIds.clear();
                        for (Integer id : selected) rule.appIds.add(String.valueOf(id));
                        OafApi.updateRule(rule, saveCallback());
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private ApiCallback<JsonObject> saveCallback() {
        return new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject data) {
                load();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                alert(getString(R.string.oaf_save_failed, error.getMessage()));
            }
        };
    }

    private void promptAddWhitelist() {
        OafApi.loadAllUsers(new ApiCallback<List<OafApi.DeviceEntry>>() {
            @Override
            public void onSuccess(List<OafApi.DeviceEntry> users) {
                if (isFinishing() || isDestroyed()) return;
                Set<String> existing = new HashSet<>();
                for (OafApi.DeviceEntry d : whitelist) existing.add(d.mac.toLowerCase());
                List<OafApi.DeviceEntry> candidates = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                for (OafApi.DeviceEntry u : users) {
                    if (existing.contains(u.mac.toLowerCase())) continue;
                    candidates.add(u);
                    labels.add(u.displayName() + "\n" + u.mac);
                }
                if (candidates.isEmpty()) {
                    alert(getString(R.string.oaf_no_whitelist));
                    return;
                }
                boolean[] checked = new boolean[candidates.size()];
                new MaterialAlertDialogBuilder(AppFilterActivity.this)
                        .setTitle(R.string.oaf_pick_devices)
                        .setMultiChoiceItems(labels.toArray(new CharSequence[0]), checked,
                                (d, which, isChecked) -> checked[which] = isChecked)
                        .setPositiveButton(R.string.oaf_add, (d, w) -> {
                            List<String> macs = new ArrayList<>();
                            for (int i = 0; i < candidates.size(); i++) {
                                if (checked[i]) macs.add(candidates.get(i).mac);
                            }
                            if (macs.isEmpty()) return;
                            OafApi.addWhitelist(macs, new ApiCallback<JsonObject>() {
                                @Override
                                public void onSuccess(JsonObject data) {
                                    loadWhitelist();
                                }

                                @Override
                                public void onFailure(ApiError error) {
                                    if (isFinishing() || isDestroyed()) return;
                                    alert(getString(R.string.oaf_save_failed,
                                            error.getMessage()));
                                }
                            });
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                alert(getString(R.string.oaf_load_failed, error.getMessage()));
            }
        });
    }

    private void alert(String message) {
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
