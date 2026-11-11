package com.whykangkang.wrthub.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 自家 luci-app-wanstats 的数据接口。
 *
 * 路由器侧 rpcd 插件(/usr/libexec/rpcd/wanstats)暴露 ubus 对象 "wanstats":
 * <pre>
 *  get_history → {"device":"pppoe-wan","updated":<epoch>,
 *                 "daily":[{"date":"2026-11-11","rx":123,"tx":45}, ...],   // 最近在前
 *                 "monthly":[{"month":"2026-11","rx":...,"tx":...}, ...]}  // 最近在前,最多 13 个月
 *  get_status  → {"running":1,"device":"pppoe-wan","updated":<epoch>,
 *                 "counter_rx":...,"counter_tx":...,"days":<已记录天数>}
 * </pre>
 * rx/tx 单位都是字节。月度由路由器侧按每日数据求和,与 LuCI 页面口径一致。
 */
public final class WanstatsApi {

    /** 一个时间段(一天或一个月)的流量 */
    public static final class Entry {
        /** 天为 "2026-11-11",月为 "2026-11" */
        public final String label;
        public final long rx;
        public final long tx;

        public Entry(String label, long rx, long tx) {
            this.label = label;
            this.rx = rx;
            this.tx = tx;
        }

        public long total() {
            return rx + tx;
        }
    }

    /** get_history 的结果 */
    public static final class History {
        public String device = "";
        /** 数据最后更新时间(epoch 秒) */
        public long updated;
        public final List<Entry> daily = new ArrayList<>();
        public final List<Entry> monthly = new ArrayList<>();
    }

    /** get_status 的结果 */
    public static final class Status {
        public boolean running;
        public String device = "";
        public long updated;
        public int days;
    }

    private WanstatsApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    public static void loadHistory(ApiCallback<History> cb) {
        api().makeUbusCall("wanstats", "get_history", new JsonObject(), 20,
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        cb.onSuccess(parseHistory(result));
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    public static void loadStatus(ApiCallback<Status> cb) {
        api().makeUbusCall("wanstats", "get_status", new JsonObject(), 20,
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        Status s = new Status();
                        s.running = num(result, "running") != 0;
                        s.device = str(result, "device");
                        s.updated = num(result, "updated");
                        s.days = (int) num(result, "days");
                        cb.onSuccess(s);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    /** 可单测:history JSON → History */
    public static History parseHistory(JsonObject result) {
        History h = new History();
        if (result == null) return h;
        h.device = str(result, "device");
        h.updated = num(result, "updated");
        parseEntries(result, "daily", "date", h.daily);
        parseEntries(result, "monthly", "month", h.monthly);
        return h;
    }

    private static void parseEntries(JsonObject result, String arrayKey, String labelKey,
                                     List<Entry> out) {
        if (!result.has(arrayKey) || !result.get(arrayKey).isJsonArray()) return;
        JsonArray arr = result.getAsJsonArray(arrayKey);
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            out.add(new Entry(str(o, labelKey), num(o, "rx"), num(o, "tx")));
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long num(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
