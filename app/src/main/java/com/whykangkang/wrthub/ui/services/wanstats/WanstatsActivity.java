package com.whykangkang.wrthub.ui.services.wanstats;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.WanstatsApi;
import com.whykangkang.wrthub.util.Formatters;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * WAN流量统计:自家 luci-app-wanstats 的手机版页面。
 *
 * 数据走 ubus 对象 wanstats(get_history / get_status),与 LuCI
 * 「状态 → WAN流量统计」同源:每日一行、月度按每日求和、保留 13 个月。
 */
public class WanstatsActivity extends AppCompatActivity {

    /** 每日图最多画 31 根柱子;每月最多 13 根,天然不挤 */
    private static final int MAX_DAILY_BARS = 31;

    private TextView deviceLabel, statusLabel, totalRx, totalTx, totalAll;
    private TextView totalCaption, emptyLabel;
    private LinearLayout rows, summary;
    private BarChart chart;
    private MaterialButtonToggleGroup periodGroup;
    private SwipeRefreshLayout swipeRefresh;

    private WanstatsApi.History history;
    private WanstatsApi.Status status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wanstats);

        deviceLabel = findViewById(R.id.wanstats_device);
        statusLabel = findViewById(R.id.wanstats_status);
        totalRx = findViewById(R.id.wanstats_total_rx);
        totalTx = findViewById(R.id.wanstats_total_tx);
        totalAll = findViewById(R.id.wanstats_total_all);
        totalCaption = findViewById(R.id.wanstats_total_caption);
        emptyLabel = findViewById(R.id.wanstats_empty);
        rows = findViewById(R.id.wanstats_rows);
        summary = findViewById(R.id.wanstats_summary);
        chart = findViewById(R.id.wanstats_chart);
        periodGroup = findViewById(R.id.group_wanstats_period);
        swipeRefresh = findViewById(R.id.swipe_refresh);

        periodGroup.check(R.id.wanstats_period_day);
        periodGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) render();
        });
        swipeRefresh.setOnRefreshListener(this::load);
        setupChart();
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
        WanstatsApi.loadStatus(new ApiCallback<WanstatsApi.Status>() {
            @Override
            public void onSuccess(WanstatsApi.Status value) {
                if (isFinishing() || isDestroyed()) return;
                status = value;
                renderStatus();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                status = null;
                renderStatus();
            }
        });
        WanstatsApi.loadHistory(new ApiCallback<WanstatsApi.History>() {
            @Override
            public void onSuccess(WanstatsApi.History value) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                history = value;
                render();
            }

            @Override
            public void onFailure(ApiError error) {
                if (isFinishing() || isDestroyed()) return;
                swipeRefresh.setRefreshing(false);
                history = null;
                render();
                if (error.getType() == ApiError.Type.PERMISSION_DENIED
                        || error.getType() == ApiError.Type.CONFIG_NOT_FOUND) {
                    showEmpty(getString(R.string.wanstats_not_installed));
                } else {
                    showEmpty(getString(R.string.wanstats_load_failed, error.getMessage()));
                }
            }
        });
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private void renderStatus() {
        if (status == null) {
            statusLabel.setText(R.string.fb_status_unknown);
            statusLabel.setTextColor(getColor(R.color.label_secondary));
            if (history != null && !history.device.isEmpty()) {
                deviceLabel.setText(history.device);
            }
            return;
        }
        deviceLabel.setText(status.device.isEmpty() ? "—" : status.device);
        statusLabel.setText(status.running ? R.string.fb_running : R.string.vnstat_not_running);
        statusLabel.setTextColor(getColor(status.running ? R.color.ios_green : R.color.ios_red));
    }

    private void render() {
        renderStatus();
        rows.removeAllViews();
        summary.removeAllViews();
        if (history == null || (history.daily.isEmpty() && history.monthly.isEmpty())) {
            deviceLabel.setText("—");
            totalRx.setText("—");
            totalTx.setText("—");
            totalAll.setText("—");
            chart.setVisibility(View.GONE);
            if (history != null) showEmpty(getString(R.string.wanstats_no_data));
            return;
        }
        emptyLabel.setVisibility(View.GONE);

        // 顶部总计卡 = 本月(月度列表第一条就是当前月)
        WanstatsApi.Entry month = history.monthly.isEmpty() ? null : history.monthly.get(0);
        if (month != null) {
            totalCaption.setText(getString(R.string.vnstat_this_month) + " (" + month.label + ")");
            totalRx.setText(Formatters.bytes(month.rx));
            totalTx.setText(Formatters.bytes(month.tx));
            totalAll.setText(Formatters.bytes(month.total()));
        }
        renderSummary();

        List<WanstatsApi.Entry> entries = currentEntries();
        if (entries.isEmpty()) {
            chart.setVisibility(View.GONE);
            showEmpty(getString(R.string.wanstats_no_data));
            return;
        }
        chart.setVisibility(View.VISIBLE);
        renderChart(entries);
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) rows.addView(separator());
            rows.addView(entryRow(entries.get(i)));
        }
    }

    private List<WanstatsApi.Entry> currentEntries() {
        if (history == null) return Collections.emptyList();
        return periodGroup.getCheckedButtonId() == R.id.wanstats_period_month
                ? history.monthly : history.daily;
    }

    /** 摘要:最近一天 / 本月 / 累计 + 已记录天数与更新时间 */
    private void renderSummary() {
        WanstatsApi.Entry day = history.daily.isEmpty() ? null : history.daily.get(0);
        WanstatsApi.Entry month = history.monthly.isEmpty() ? null : history.monthly.get(0);
        long allRx = 0, allTx = 0;
        for (WanstatsApi.Entry e : history.monthly) {
            allRx += e.rx;
            allTx += e.tx;
        }
        // 行标题直接用日期/月份本身,避免路由器时间不准时把旧数据叫成「今日」
        addSummaryRow(day != null ? day.label : getString(R.string.vnstat_today), day);
        summary.addView(separator());
        addSummaryRow(month != null ? month.label : getString(R.string.vnstat_this_month), month);
        summary.addView(separator());
        addSummaryRow(getString(R.string.vnstat_all_time),
                new WanstatsApi.Entry("", allRx, allTx));

        StringBuilder sb = new StringBuilder();
        if (status != null && status.days > 0) {
            sb.append(getString(R.string.wanstats_days, status.days));
        }
        long updated = history.updated > 0 ? history.updated
                : (status != null ? status.updated : 0);
        if (updated > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(getString(R.string.wanstats_updated, formatTime(updated)));
        }
        if (sb.length() > 0) {
            summary.addView(separator());
            summary.addView(sectionLabel(sb.toString()));
        }
    }

    private void addSummaryRow(String title, WanstatsApi.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(15);
        label.setTextColor(getColor(R.color.label_primary));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setTextSize(13);
        value.setTextColor(getColor(R.color.label_secondary));
        value.setGravity(Gravity.END);
        if (entry == null) {
            value.setText("—");
        } else {
            value.setText(getString(R.string.vnstat_row_value,
                    Formatters.bytes(entry.rx), Formatters.bytes(entry.tx),
                    Formatters.bytes(entry.total())));
        }

        row.addView(label);
        row.addView(value);
        summary.addView(row);
    }

    private View sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(getColor(R.color.label_secondary));
        label.setPadding(dp(16), dp(10), dp(16), dp(6));
        return label;
    }

    private void showEmpty(String message) {
        emptyLabel.setText(message);
        emptyLabel.setVisibility(View.VISIBLE);
    }

    private void setupChart() {
        chart.getDescription().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setScaleYEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.getLegend().setTextColor(getColor(R.color.label_secondary));
        chart.getAxisLeft().setTextColor(getColor(R.color.label_secondary));
        chart.getAxisLeft().setAxisMinimum(0f);
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return Formatters.bytes((long) value);
            }
        });
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setTextColor(getColor(R.color.label_secondary));
        x.setGranularity(1f);
    }

    private void renderChart(List<WanstatsApi.Entry> all) {
        boolean monthly = periodGroup.getCheckedButtonId() == R.id.wanstats_period_month;
        int max = monthly ? all.size() : Math.min(all.size(), MAX_DAILY_BARS);
        // 最近的在列表最前面,画图按时间从左到右,取前 N 条再反过来
        List<WanstatsApi.Entry> entries = new ArrayList<>(all.subList(0, max));
        Collections.reverse(entries);

        List<BarEntry> rx = new ArrayList<>();
        List<BarEntry> tx = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            rx.add(new BarEntry(i, entries.get(i).rx));
            tx.add(new BarEntry(i, entries.get(i).tx));
            // 每日标签只留 MM-DD,每月用 YYYY-MM 原样
            String label = entries.get(i).label;
            labels.add(!monthly && label.length() > 5 ? label.substring(5) : label);
        }
        BarDataSet rxSet = new BarDataSet(rx, getString(R.string.vnstat_rx));
        rxSet.setColor(getColor(R.color.ios_green));
        rxSet.setDrawValues(false);
        BarDataSet txSet = new BarDataSet(tx, getString(R.string.vnstat_tx));
        txSet.setColor(getColor(R.color.ios_blue));
        txSet.setDrawValues(false);

        BarData data = new BarData(rxSet, txSet);
        float barWidth = 0.38f;
        float barSpace = 0.02f;
        float groupSpace = 1f - (barWidth + barSpace) * 2;
        data.setBarWidth(barWidth);
        chart.setData(data);
        chart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        chart.getXAxis().setLabelCount(Math.min(labels.size(), 6));
        chart.getXAxis().setAxisMinimum(0f);
        chart.getXAxis().setAxisMaximum(entries.size());
        chart.groupBars(0f, groupSpace, barSpace);
        chart.invalidate();
    }

    private View entryRow(WanstatsApi.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView label = new TextView(this);
        label.setText(entry.label);
        label.setTextSize(14);
        label.setTextColor(getColor(R.color.label_primary));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setText(getString(R.string.vnstat_row_value,
                Formatters.bytes(entry.rx), Formatters.bytes(entry.tx),
                Formatters.bytes(entry.total())));
        value.setTextSize(13);
        value.setTextColor(getColor(R.color.label_secondary));
        value.setGravity(Gravity.END);

        row.addView(label);
        row.addView(value);
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

    private static String formatTime(long epochSec) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new Date(epochSec * 1000L));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
