package com.whykangkang.wrthub.ui.devices;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.ApiCallback;
import com.whykangkang.wrthub.api.ApiError;
import com.whykangkang.wrthub.api.OafApi;
import com.whykangkang.wrthub.manager.DeviceNameStore;
import com.whykangkang.wrthub.ui.services.ratelimit.DeviceRateLimitActivity;
import com.whykangkang.wrthub.util.Formatters;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 终端详情(OPAssistant 同款六页签):基本信息 / 管控规则 / App统计 /
 * 今日流量 / 常用App / 应用记录;底部有重命名、限速(跳 eqosplus)、
 * 拉黑/解除拉黑(fwx 上网黑名单)。用户数据由列表页把原始 JSON 带进来,
 * 各页签的数据点开才拉,用到才请求。
 */
public class TerminalDeviceActivity extends AppCompatActivity {

    public static final String EXTRA_USER_JSON = "user_json";
    public static final String EXTRA_SSID = "ssid";
    public static final String EXTRA_SIGNAL = "signal";
    public static final String EXTRA_LOCAL = "local";

    private OafApi.FwxUser user;
    private String ssid;
    private boolean isLocal;

    private TextView nameView;
    private MaterialButton blockBtn;
    private LinearLayout pageContainer;
    private final Map<Integer, View> builtPages = new HashMap<>();
    private List<OafApi.HourStat> hourlyCache;

    // 应用记录页的分页状态(建在页签视图里,单独持有)
    private LinearLayout recordsContainer;
    private int recordsPage;
    private int recordsTotalPage = 1;
    private boolean recordsLoading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal_device);

        user = parseUser(getIntent().getStringExtra(EXTRA_USER_JSON));
        if (user == null) {
            finish();
            return;
        }
        ssid = getIntent().getStringExtra(EXTRA_SSID);
        isLocal = getIntent().getBooleanExtra(EXTRA_LOCAL, false);

        TextView avatar = findViewById(R.id.td_avatar);
        String name = displayName();
        avatar.setText(DevicesFragment.firstLetter(name));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(14));
        bg.setColor(DevicesFragment.colorFor(user.mac));
        avatar.setBackground(bg);

        nameView = findViewById(R.id.td_name);
        nameView.setText(name);
        TextView badge = findViewById(R.id.td_badge);
        if (user.online && user.active) {
            badge.setText(R.string.term_active);
            badge.setTextColor(getColor(R.color.ios_green));
        } else if (user.online) {
            badge.setText(R.string.term_online);
            badge.setTextColor(getColor(R.color.ios_blue));
        } else {
            badge.setText(R.string.term_offline);
            badge.setTextColor(getColor(R.color.label_tertiary));
        }
        ((TextView) findViewById(R.id.td_ip)).setText(
                user.ip.isEmpty() ? user.mac : user.ip);
        ((TextView) findViewById(R.id.td_mac)).setText("MAC " + user.mac);

        pageContainer = findViewById(R.id.td_page);
        TabLayout tabs = findViewById(R.id.td_tabs);
        int[] titles = {R.string.term_tab_basic, R.string.term_tab_rules,
                R.string.term_tab_appstat, R.string.term_tab_traffic,
                R.string.term_tab_apps, R.string.term_tab_records};
        for (int t : titles) tabs.addTab(tabs.newTab().setText(t));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showPage(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        showPage(0);

        findViewById(R.id.td_btn_rename).setOnClickListener(v -> rename());
        findViewById(R.id.td_btn_limit).setOnClickListener(v -> openRateLimit());
        blockBtn = findViewById(R.id.td_btn_block);
        updateBlockButton();
        blockBtn.setOnClickListener(v -> toggleBlock());
        if (isLocal) blockBtn.setEnabled(false);
    }

    private static OafApi.FwxUser parseUser(String json) {
        if (json == null) return null;
        try {
            JsonObject raw = JsonParser.parseString(json).getAsJsonObject();
            JsonObject wrap = new JsonObject();
            JsonArray arr = new JsonArray();
            arr.add(raw);
            wrap.add("list", arr);
            List<OafApi.FwxUser> list = OafApi.parseUsers(wrap);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private String displayName() {
        String custom = DeviceNameStore.get(this, user.mac);
        return custom != null ? custom : user.displayName();
    }

    // =====================================================================
    // 页签
    // =====================================================================

    private void showPage(int index) {
        pageContainer.removeAllViews();
        View page = builtPages.get(index);
        if (page == null) {
            page = buildPage(index);
            builtPages.put(index, page);
        }
        pageContainer.addView(page);
    }

    private View buildPage(int index) {
        switch (index) {
            case 1:
                return lazyPage(this::fillRules);
            case 2:
                return lazyPage(this::fillAppStat);
            case 3:
                return lazyPage(container -> fillHourly(container, true));
            case 4:
                return lazyPage(container -> fillHourly(container, false));
            case 5:
                return buildRecordsPage();
            default:
                return buildBasicPage();
        }
    }

    private interface PageFiller {
        void fill(LinearLayout container);
    }

    /** 先放一个「加载中」,数据回来再填 */
    private LinearLayout lazyPage(PageFiller filler) {
        LinearLayout container = pageBox();
        TextView loading = hintText(getString(R.string.term_loading));
        container.addView(loading);
        filler.fill(container);
        return container;
    }

    // ---- 基本信息 ----

    private View buildBasicPage() {
        LinearLayout box = pageBox();
        box.addView(sectionTitle(getString(R.string.term_section_basic)));
        LinearLayout card = cardBox();
        card.addView(kvRow(getString(R.string.detail_mac), user.mac));
        if (!user.ip.isEmpty()) card.addView(kvRow(getString(R.string.detail_ip), user.ip));
        card.addView(kvRow("IPv6", user.ipv6.isEmpty() ? "—" : user.ipv6));
        if (user.isWireless) {
            StringBuilder access = new StringBuilder(getString(R.string.term_wireless));
            if (!user.band.isEmpty()) access.append(" · ").append(user.band);
            Integer signal = getIntent().hasExtra(EXTRA_SIGNAL)
                    ? getIntent().getIntExtra(EXTRA_SIGNAL, 0) : null;
            if (signal != null && signal != 0) {
                access.append(" · ").append(signal).append(" dBm");
            }
            card.addView(kvRow(getString(R.string.term_access), access.toString()));
            if (ssid != null) card.addView(kvRow(getString(R.string.detail_ssid), ssid));
        } else {
            card.addView(kvRow(getString(R.string.term_access),
                    getString(R.string.term_wired)));
        }
        if (!user.currentApp.isEmpty()) {
            card.addView(kvRow(getString(R.string.term_current_app), user.currentApp));
        }
        if (!user.url.isEmpty()) {
            card.addView(kvRow(getString(R.string.term_current_url), user.url));
        }
        box.addView(card);

        box.addView(sectionTitle(getString(R.string.term_section_today)));
        LinearLayout today = cardBox();
        today.addView(kvRow(getString(R.string.term_today_up),
                Formatters.bytes(user.todayUpBytes)));
        today.addView(kvRow(getString(R.string.term_today_down),
                Formatters.bytes(user.todayDownBytes)));
        today.addView(kvRow(getString(R.string.term_today_active),
                DevicesFragment.activeDuration(user.todayActiveSec)));
        box.addView(today);

        List<String> apps = commonAppNames();
        if (!apps.isEmpty()) {
            box.addView(sectionTitle(getString(R.string.term_section_common_apps)));
            TextView chips = new TextView(this);
            chips.setText(String.join("  ·  ", apps));
            chips.setTextSize(14);
            chips.setTextColor(getColor(R.color.label_primary));
            LinearLayout wrap = cardBox();
            wrap.setPadding(dp(14), dp(12), dp(14), dp(12));
            wrap.addView(chips);
            box.addView(wrap);
        }
        return box;
    }

    /** 今日常用应用 = 当前应用 + applist(去重,最多 5 个) */
    private List<String> commonAppNames() {
        List<String> names = new ArrayList<>();
        if (!user.currentApp.isEmpty()) names.add(user.currentApp);
        for (OafApi.App a : user.apps) {
            if (!names.contains(a.name)) names.add(a.name);
            if (names.size() >= 5) break;
        }
        return names;
    }

    // ---- 管控规则 ----

    private void fillRules(LinearLayout container) {
        OafApi.loadParentalRules(user.mac, new ApiCallback<OafApi.ParentalRules>() {
            @Override
            public void onSuccess(OafApi.ParentalRules rules) {
                container.removeAllViews();
                if (rules.afWhitelist) {
                    container.addView(hintText(getString(R.string.term_whitelist_af)));
                }
                if (rules.mfWhitelist) {
                    container.addView(hintText(getString(R.string.term_whitelist_mf)));
                }
                if (rules.list.isEmpty()) {
                    container.addView(hintText(getString(R.string.term_no_rules)));
                    return;
                }
                for (OafApi.ParentalRule r : rules.list) {
                    LinearLayout card = cardBox();
                    card.setPadding(dp(14), dp(12), dp(14), dp(12));
                    TextView title = new TextView(TerminalDeviceActivity.this);
                    title.setText(moduleText(r.module) + " · " + r.ruleName);
                    title.setTextSize(15);
                    title.setTypeface(null, android.graphics.Typeface.BOLD);
                    title.setTextColor(getColor(R.color.label_primary));
                    card.addView(title);
                    String summary = conditionSummary(r) + todaySummary(r);
                    if (!summary.isEmpty()) {
                        TextView body = new TextView(TerminalDeviceActivity.this);
                        body.setText(summary.trim());
                        body.setTextSize(13);
                        body.setTextColor(getColor(R.color.label_secondary));
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                        lp.topMargin = dp(4);
                        body.setLayoutParams(lp);
                        card.addView(body);
                    }
                    container.addView(card);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                container.removeAllViews();
                container.addView(hintText(getString(R.string.term_load_failed)));
            }
        });
    }

    private String moduleText(String module) {
        switch (module == null ? "" : module) {
            case "appfilter":
                return getString(R.string.term_module_appfilter);
            case "macfilter":
                return getString(R.string.term_module_macfilter);
            case "blacklist":
                return getString(R.string.term_module_blacklist);
            default:
                return module == null ? "" : module;
        }
    }

    private String conditionSummary(OafApi.ParentalRule r) {
        try {
            switch (r.conditionType == null ? "" : r.conditionType) {
                case "time_range": {
                    JsonArray arr = r.condition.getAsJsonArray("time_rules");
                    if (arr == null || arr.size() == 0) return "";
                    JsonObject t = arr.get(0).getAsJsonObject();
                    return weekdaysText(t.getAsJsonArray("weekdays")) + " "
                            + t.get("start_time").getAsString() + "–"
                            + t.get("end_time").getAsString() + "\n";
                }
                case "duration": {
                    JsonArray arr = r.condition.getAsJsonArray("duration_rules");
                    if (arr == null || arr.size() == 0) return "";
                    JsonObject t = arr.get(0).getAsJsonObject();
                    return weekdaysText(t.getAsJsonArray("weekdays")) + " "
                            + getString(R.string.term_duration_limit,
                            t.get("duration_minutes").getAsInt()) + "\n";
                }
                case "flow": {
                    JsonArray arr = r.condition.getAsJsonArray("flow_rules");
                    if (arr == null || arr.size() == 0) return "";
                    JsonObject t = arr.get(0).getAsJsonObject();
                    return weekdaysText(t.getAsJsonArray("weekdays")) + " "
                            + getString(R.string.term_flow_limit,
                            trimDouble(t.get("flow_mb").getAsDouble())) + "\n";
                }
                case "blacklist":
                    return getString(R.string.term_in_blacklist) + "\n";
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private String todaySummary(OafApi.ParentalRule r) {
        JsonObject s = r.todayStatus;
        if (s == null) return "";
        try {
            if (s.has("matched")) {
                return s.get("matched").getAsInt() != 0
                        ? getString(R.string.term_today_matched)
                        : getString(R.string.term_today_unmatched);
            }
            if (s.has("limit")) {
                if (s.has("unlimited") && s.get("unlimited").getAsInt() != 0) {
                    return getString(R.string.term_today_unlimited);
                }
                int percent = s.has("progress_percent")
                        ? s.get("progress_percent").getAsInt() : 0;
                if ("flow".equals(r.conditionType)) {
                    return getString(R.string.term_today_used_flow,
                            trimDouble(s.get("used").getAsDouble()),
                            trimDouble(s.get("limit").getAsDouble()), percent);
                }
                return getString(R.string.term_today_used_duration,
                        s.get("used").getAsInt(), s.get("limit").getAsInt(), percent);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String weekdaysText(JsonArray days) {
        if (days == null || days.size() == 0) return "";
        if (days.size() >= 7) return getString(R.string.term_everyday);
        String[] names = getResources().getStringArray(R.array.term_weekdays);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < days.size(); i++) {
            int d = days.get(i).getAsInt();
            if (d >= 0 && d < names.length) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(names[d]);
            }
        }
        return sb.toString();
    }

    // ---- App统计 ----

    private void fillAppStat(LinearLayout container) {
        OafApi.loadDevVisitTime(user.mac, new ApiCallback<List<OafApi.AppTime>>() {
            @Override
            public void onSuccess(List<OafApi.AppTime> list) {
                container.removeAllViews();
                if (list.isEmpty()) {
                    container.addView(hintText(getString(R.string.term_no_data)));
                    return;
                }
                long total = 0;
                for (OafApi.AppTime t : list) total += t.seconds;
                container.addView(hintText(getString(R.string.term_appstat_total,
                        DevicesFragment.activeDuration(total))));
                LinearLayout card = cardBox();
                for (OafApi.AppTime t : list) {
                    int pct = total > 0 ? (int) Math.round(t.seconds * 100.0 / total) : 0;
                    card.addView(kvRow(t.name, DevicesFragment.activeDuration(t.seconds)
                            + " · " + pct + "%"));
                }
                container.addView(card);
            }

            @Override
            public void onFailure(ApiError error) {
                container.removeAllViews();
                container.addView(hintText(getString(R.string.term_load_failed)));
            }
        });
    }

    // ---- 今日流量 / 常用App(共用一次请求) ----

    private void fillHourly(LinearLayout container, boolean trafficTab) {
        if (hourlyCache != null) {
            renderHourly(container, trafficTab, hourlyCache);
            return;
        }
        OafApi.loadHourlyTopApps(user.mac, new ApiCallback<List<OafApi.HourStat>>() {
            @Override
            public void onSuccess(List<OafApi.HourStat> list) {
                hourlyCache = list;
                renderHourly(container, trafficTab, list);
            }

            @Override
            public void onFailure(ApiError error) {
                container.removeAllViews();
                container.addView(hintText(getString(R.string.term_load_failed)));
            }
        });
    }

    private void renderHourly(LinearLayout container, boolean trafficTab,
                              List<OafApi.HourStat> list) {
        container.removeAllViews();
        if (trafficTab) {
            long up = 0;
            long down = 0;
            for (OafApi.HourStat h : list) {
                up += h.upBytes;
                down += h.downBytes;
            }
            container.addView(hintText(getString(R.string.term_hourly_total,
                    Formatters.bytes(up), Formatters.bytes(down))));
            LinearLayout card = cardBox();
            boolean any = false;
            for (OafApi.HourStat h : list) {
                if (h.upBytes == 0 && h.downBytes == 0) continue;
                any = true;
                card.addView(kvRow(String.format(Locale.US, "%02d:00", h.hour),
                        "↑ " + Formatters.bytes(h.upBytes)
                                + "   ↓ " + Formatters.bytes(h.downBytes)));
            }
            if (!any) {
                container.addView(hintText(getString(R.string.term_no_data)));
                return;
            }
            container.addView(card);
        } else {
            boolean any = false;
            for (OafApi.HourStat h : list) {
                if (h.apps.isEmpty()) continue;
                any = true;
                container.addView(sectionTitle(
                        String.format(Locale.US, "%02d:00", h.hour)));
                LinearLayout card = cardBox();
                List<OafApi.HourApp> sorted = new ArrayList<>(h.apps);
                sorted.sort((a, b) -> Long.compare(b.seconds, a.seconds));
                int n = Math.min(3, sorted.size());
                for (int i = 0; i < n; i++) {
                    OafApi.HourApp a = sorted.get(i);
                    card.addView(kvRow(a.name,
                            DevicesFragment.activeDuration(a.seconds)));
                }
                container.addView(card);
            }
            if (!any) container.addView(hintText(getString(R.string.term_no_data)));
        }
    }

    // ---- 应用记录 ----

    private View buildRecordsPage() {
        LinearLayout box = pageBox();
        recordsContainer = box;
        recordsPage = 0;
        loadMoreRecords();
        return box;
    }

    private void loadMoreRecords() {
        if (recordsLoading) return;
        recordsLoading = true;
        final int next = recordsPage + 1;
        OafApi.loadDevRecords(user.mac, next, new ApiCallback<OafApi.RecordPage>() {
            @Override
            public void onSuccess(OafApi.RecordPage result) {
                recordsLoading = false;
                recordsPage = result.page > 0 ? result.page : next;
                recordsTotalPage = Math.max(1, result.totalPage);
                // 先摘掉旧的「加载更多」
                int childCount = recordsContainer.getChildCount();
                if (childCount > 0) {
                    View last = recordsContainer.getChildAt(childCount - 1);
                    if (last.getTag() != null && "more".equals(last.getTag())) {
                        recordsContainer.removeViewAt(childCount - 1);
                    }
                }
                if (result.list.isEmpty() && recordsPage == 1) {
                    recordsContainer.addView(hintText(getString(R.string.term_no_data)));
                    return;
                }
                SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
                for (OafApi.Record r : result.list) {
                    recordsContainer.addView(recordRow(r, fmt));
                }
                if (recordsPage < recordsTotalPage) {
                    MaterialButton more = new MaterialButton(TerminalDeviceActivity.this);
                    more.setTag("more");
                    more.setText(R.string.term_load_more);
                    more.setOnClickListener(v -> loadMoreRecords());
                    recordsContainer.addView(more);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                recordsLoading = false;
                Toast.makeText(TerminalDeviceActivity.this,
                        R.string.term_load_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private View recordRow(OafApi.Record r, SimpleDateFormat fmt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView title = new TextView(this);
        String suffix = r.act == 1 ? "  ·  " + getString(R.string.term_blocked_badge)
                : r.online ? "  ·  " + getString(R.string.term_online) : "";
        title.setText(r.appName + suffix);
        title.setTextSize(14);
        title.setTextColor(getColor(r.act == 1 ? R.color.ios_red : R.color.label_primary));
        row.addView(title);
        TextView sub = new TextView(this);
        String time = r.firstTime > 0
                ? fmt.format(new Date(r.firstTime * 1000)) + " – "
                + fmt.format(new Date(r.latestTime * 1000)) : "";
        sub.setText(time + (r.totalTime > 0
                ? "  ·  " + DevicesFragment.activeDuration(r.totalTime) : ""));
        sub.setTextSize(12);
        sub.setTextColor(getColor(R.color.label_tertiary));
        row.addView(sub);
        return row;
    }

    // =====================================================================
    // 底部动作:重命名 / 限速 / 拉黑
    // =====================================================================

    private void rename() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(user.displayName());
        String current = DeviceNameStore.get(this, user.mac);
        if (current != null) input.setText(current);
        LinearLayout box = new LinearLayout(this);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.device_rename)
                .setMessage(R.string.device_rename_hint)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    DeviceNameStore.set(this, user.mac, input.getText().toString());
                    nameView.setText(displayName());
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void openRateLimit() {
        Intent intent = new Intent(this, DeviceRateLimitActivity.class);
        intent.putExtra(DeviceRateLimitActivity.EXTRA_TARGET,
                user.ip.isEmpty() ? user.mac : user.ip);
        intent.putExtra(DeviceRateLimitActivity.EXTRA_DEVICE_NAME, displayName());
        startActivity(intent);
    }

    private void updateBlockButton() {
        blockBtn.setText(user.inBlacklist ? R.string.term_unblock : R.string.term_block);
    }

    private void toggleBlock() {
        boolean toBlock = !user.inBlacklist;
        new MaterialAlertDialogBuilder(this)
                .setTitle(toBlock ? R.string.term_block : R.string.term_unblock)
                .setMessage(getString(
                        toBlock ? R.string.term_block_msg : R.string.term_unblock_msg,
                        displayName()))
                .setPositiveButton(toBlock ? R.string.term_block : R.string.term_unblock,
                        (d, w) -> doToggleBlock(toBlock))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void doToggleBlock(boolean toBlock) {
        ApiCallback<JsonObject> cb = new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                user.inBlacklist = toBlock;
                updateBlockButton();
                Toast.makeText(TerminalDeviceActivity.this,
                        toBlock ? R.string.term_block_ok : R.string.term_unblock_ok,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(ApiError error) {
                Toast.makeText(TerminalDeviceActivity.this,
                        getString(R.string.msg_action_failed, error.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        };
        if (toBlock) {
            OafApi.addMacBlacklist(user.mac, cb);
        } else {
            OafApi.delMacBlacklist(user.mac, cb);
        }
    }

    // =====================================================================
    // 视图小工具
    // =====================================================================

    private LinearLayout pageBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private LinearLayout cardBox() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(getColor(R.color.label_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView hintText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(getColor(R.color.label_secondary));
        t.setPadding(0, dp(6), 0, dp(10));
        return t;
    }

    /** 一行 label:value(值过长时自动换行,不挤压 label) */
    private View kvRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(2), dp(7), dp(2), dp(7));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(14);
        l.setTextColor(getColor(R.color.label_secondary));
        l.setLayoutParams(new LinearLayout.LayoutParams(dp(92),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(l);
        TextView v = new TextView(this);
        v.setText(value == null ? "" : value);
        v.setTextSize(14);
        v.setTextColor(getColor(R.color.label_primary));
        v.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(v);
        return row;
    }

    private static String trimDouble(double v) {
        if (v == Math.floor(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
