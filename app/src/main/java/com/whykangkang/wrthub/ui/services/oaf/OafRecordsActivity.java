package com.whykangkang.wrthub.ui.services.oaf;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OafApi;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 上网记录(OpenAppFilter):在线记录 + 近 7 天历史记录。
 *
 * 记录是「某设备访问某 App」的聚合条目(应用名、最近时间、累计时长、
 * 是否被拦截),协议本身不下发域名,这点与 OPAssistant 的展示一致。
 */
public class OafRecordsActivity extends AppCompatActivity {

    private SwitchMaterial recordSwitch;
    private TextView emptyLabel;
    private LinearLayout rowsBox;
    private MaterialButton moreButton;
    private MaterialButtonToggleGroup tabGroup;
    private SwipeRefreshLayout swipeRefresh;

    private JsonObject recordBase;
    private boolean syncingSwitch;
    private boolean historyMode;
    private int page = 1;
    private int totalPage = 1;
    private final List<OafApi.Record> records = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_oaf_records);

        View advRow = findViewById(R.id.rec_adv_row);
        ((TextView) advRow.findViewById(R.id.row_label)).setText(R.string.oaf_record_enable);
        recordSwitch = advRow.findViewById(R.id.row_switch);
        recordSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (syncingSwitch || recordBase == null) return;
            OafApi.setRecordEnabled(recordBase, checked, new ApiCallback<JsonObject>() {
                @Override
                public void onSuccess(JsonObject data) {
                    if (recordBase != null) {
                        recordBase.addProperty("enable", checked ? 1 : 0);
                    }
                }

                @Override
                public void onFailure(ApiError error) {
                    if (isFinishing() || isDestroyed()) return;
                    syncingSwitch = true;
                    recordSwitch.setChecked(!checked);
                    syncingSwitch = false;
                    alert(getString(R.string.oaf_save_failed, error.getMessage()));
                }
            });
        });
        emptyLabel = findViewById(R.id.rec_empty);
        rowsBox = findViewById(R.id.rec_rows);
        moreButton = findViewById(R.id.btn_rec_more);
        tabGroup = findViewById(R.id.group_rec_tab);
        swipeRefresh = findViewById(R.id.swipe_refresh);

        tabGroup.check(R.id.rec_tab_active);
        tabGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            boolean history = id == R.id.rec_tab_history;
            if (history != historyMode) {
                historyMode = history;
                reload();
            }
        });
        moreButton.setOnClickListener(v -> {
            if (page < totalPage) loadPage(page + 1, true);
        });
        swipeRefresh.setOnRefreshListener(this::reload);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadBase();
        reload();
    }

    private void loadBase() {
        OafApi.getRecordBase(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject data) {
                if (isFinishing() || isDestroyed()) return;
                recordBase = data;
                boolean enabled = false;
                try {
                    enabled = data.has("enable") && data.get("enable").getAsInt() != 0;
                } catch (Exception ignored) {
                }
                syncingSwitch = true;
                recordSwitch.setChecked(enabled);
                syncingSwitch = false;
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                recordSwitch.setEnabled(false);
            }
        });
    }

    private void reload() {
        records.clear();
        page = 1;
        totalPage = 1;
        render();
        loadPage(1, false);
    }

    private void loadPage(int targetPage, boolean append) {
        swipeRefresh.setRefreshing(true);
        ApiCallback<OafApi.RecordPage> cb = new ApiCallback<OafApi.RecordPage>() {
            @Override
            public void onSuccess(OafApi.RecordPage p) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                if (!append) records.clear();
                records.addAll(p.list);
                page = p.page > 0 ? p.page : targetPage;
                totalPage = Math.max(p.totalPage, 1);
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                if (!append) {
                    records.clear();
                    render();
                    showEmpty(error.getType() == ApiError.Type.PERMISSION_DENIED
                                    || error.getType() == ApiError.Type.CONFIG_NOT_FOUND
                            ? getString(R.string.oaf_not_installed)
                            : getString(R.string.oaf_load_failed, error.getMessage()));
                }
            }
        };
        if (historyMode) {
            long now = System.currentTimeMillis() / 1000L;
            OafApi.loadHistoryRecords(now - 7L * 24 * 3600, now, targetPage, cb);
        } else {
            OafApi.loadActiveRecords(targetPage, cb);
        }
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private void render() {
        rowsBox.removeAllViews();
        if (records.isEmpty()) {
            showEmpty(getString(R.string.oaf_no_records));
        } else {
            emptyLabel.setVisibility(View.GONE);
        }
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) rowsBox.addView(separator());
            rowsBox.addView(recordRow(records.get(i)));
        }
        moreButton.setVisibility(page < totalPage ? View.VISIBLE : View.GONE);
    }

    private View recordRow(OafApi.Record r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView app = new TextView(this);
        app.setText(r.appName.isEmpty() ? ("#" + r.appId) : r.appName);
        app.setTextSize(15);
        app.setTextColor(getColor(R.color.label_primary));
        titleLine.addView(app);
        if (r.act == 1) {
            TextView blocked = new TextView(this);
            blocked.setText(R.string.oaf_blocked);
            blocked.setTextSize(11);
            blocked.setTextColor(getColor(R.color.ios_red));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(8));
            blocked.setLayoutParams(lp);
            titleLine.addView(blocked);
        }
        left.addView(titleLine);

        TextView device = new TextView(this);
        device.setText(r.deviceName());
        device.setTextSize(12);
        device.setTextColor(getColor(R.color.label_secondary));
        left.addView(device);
        row.addView(left);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        TextView time = new TextView(this);
        time.setText(r.latestTime > 0 ? formatTime(r.latestTime) : "");
        time.setTextSize(12);
        time.setTextColor(getColor(R.color.label_secondary));
        time.setGravity(Gravity.END);
        right.addView(time);
        if (r.totalTime > 0) {
            TextView dur = new TextView(this);
            dur.setText(getString(R.string.oaf_duration, formatDuration(r.totalTime)));
            dur.setTextSize(12);
            dur.setTextColor(getColor(R.color.label_tertiary));
            dur.setGravity(Gravity.END);
            right.addView(dur);
        }
        row.addView(right);
        return row;
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

    private void alert(String message) {
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private static String formatTime(long epochSec) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new Date(epochSec * 1000L));
    }

    /** 秒 → "2h 05m" / "12m" / "45s" */
    private static String formatDuration(long sec) {
        if (sec >= 3600) {
            return (sec / 3600) + "h " + String.format(Locale.US, "%02dm", (sec % 3600) / 60);
        }
        if (sec >= 60) return (sec / 60) + "m";
        return sec + "s";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
