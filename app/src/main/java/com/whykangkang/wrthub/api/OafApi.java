package com.whykangkang.wrthub.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenAppFilter(appfilter 7.0.2 / luci-app-oaf 7.0-r2)的数据接口。
 *
 * 协议与 OPAssistant、LuCI 网页端一致:所有调用都走同一个 ubus 信封
 * —— 对象 "fwx"、方法 "common",真正的接口名放在参数的 api 字段,
 * 业务参数放在 data 里,并固定带 CopyRight 字段(服务端对 class_list 强校验):
 * <pre>
 *  makeUbusCall("fwx", "common",
 *      {"api":"get_filter_rules", "CopyRight":"www.fanchmwrt.com", "data":{}})
 *  → result[1] = {"code":2000, "data":{...}}   // code 2000 成功,4000 失败
 * </pre>
 * 写操作成功时没有 data 字段。单个应用没有独立开关:应用是否被过滤,
 * 取决于它在不在某条启用规则的 app_ids 里,增删应用 = 全量 update_filter_rule。
 */
public final class OafApi {

    private static final String COPYRIGHT = "www.fanchmwrt.com";

    // =====================================================================
    // 数据模型
    // =====================================================================

    /** 特征库里的一个应用 */
    public static final class App {
        public final int id;
        public final String name;

        public App(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** 应用分类(协议没有分类 id,只有名字) */
    public static final class AppClass {
        public String name = "";
        public final List<App> apps = new ArrayList<>();
    }

    /** 一条应用过滤规则;raw 保留服务端下发的完整对象,写回时全量提交 */
    public static final class Rule {
        public long id;
        public String name = "";
        public int mode = 1;
        public String userMac = "";
        public String userName = "";
        public boolean enabled = true;
        public boolean filterQuic;
        /** 每项为 "<appid>" 或区间 "<起>-<止>" */
        public final List<String> appIds = new ArrayList<>();
        public JsonObject raw = new JsonObject();

        /** 把 appIds 里的区间展开成单个 id 集合(给应用勾选框用) */
        public Set<Integer> expandedAppIds() {
            Set<Integer> out = new LinkedHashSet<>();
            for (String token : appIds) {
                addToken(out, token);
            }
            return out;
        }

        private static void addToken(Set<Integer> out, String token) {
            try {
                int dash = token.indexOf('-');
                if (dash > 0) {
                    int from = Integer.parseInt(token.substring(0, dash).trim());
                    int to = Integer.parseInt(token.substring(dash + 1).trim());
                    if (to < from) {
                        int t = from;
                        from = to;
                        to = t;
                    }
                    // 区间异常大时只取两端,避免坏数据把内存撑爆
                    if (to - from > 10000) {
                        out.add(from);
                        out.add(to);
                        return;
                    }
                    for (int i = from; i <= to; i++) out.add(i);
                } else {
                    out.add(Integer.parseInt(token.trim()));
                }
            } catch (Exception ignored) {
                // 非法 token 服务端也会丢弃,这里同样忽略
            }
        }

        /** 时段摘要,如 "08:00–22:00";全天或无时段返回空串 */
        public String timeSummary() {
            if (!raw.has("time_rules") || !raw.get("time_rules").isJsonArray()) return "";
            JsonArray arr = raw.getAsJsonArray("time_rules");
            if (arr.size() == 0) return "";
            JsonObject first = arr.get(0).isJsonObject() ? arr.get(0).getAsJsonObject() : null;
            if (first == null) return "";
            String s = str(first, "start_time");
            String e = str(first, "end_time");
            if (s.isEmpty() || e.isEmpty()) return "";
            if (("00:00".equals(s) && ("23:59".equals(e) || "24:00".equals(e)))) return "";
            String out = s + "–" + e;
            if (arr.size() > 1) out += " +" + (arr.size() - 1);
            return out;
        }
    }

    /** 白名单 / 黑名单里的一台设备 */
    public static final class DeviceEntry {
        public String mac = "";
        public String hostname = "";
        public String nickname = "";

        public String displayName() {
            if (!nickname.isEmpty() && !"--".equals(nickname)) return nickname;
            if (!hostname.isEmpty() && !"--".equals(hostname)) return hostname;
            return mac;
        }
    }

    /** 一条上网记录(某设备访问某 App 的聚合条目,协议不含域名) */
    public static final class Record {
        public String mac = "";
        public String hostname = "";
        public String nickname = "";
        public String appName = "";
        public int appId;
        /** 1 = 被过滤拦截 */
        public int act;
        public boolean online;
        public long firstTime;
        public long latestTime;
        public long totalTime;

        public String deviceName() {
            if (!nickname.isEmpty()) return nickname;
            if (!hostname.isEmpty()) return hostname;
            return mac;
        }
    }

    /** 记录分页结果 */
    public static final class RecordPage {
        public int totalNum;
        public int totalPage;
        public int page;
        public final List<Record> list = new ArrayList<>();
    }

    private OafApi() {
    }

    // =====================================================================
    // 通用信封
    // =====================================================================

    /**
     * 调一个 fwx 接口。成功时回调拿业务 data(写操作无 data 时为空对象);
     * code != 2000 或 ubus 层失败都走 onFailure。
     */
    public static void call(String api, JsonObject data, ApiCallback<JsonObject> cb) {
        JsonObject params = new JsonObject();
        params.addProperty("api", api);
        params.addProperty("CopyRight", COPYRIGHT);
        params.add("data", data != null ? data : new JsonObject());
        OpenWrtApi.getInstance().makeUbusCall("fwx", "common", params, 30,
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        int code = result != null && result.has("code")
                                ? result.get("code").getAsInt() : -1;
                        if (code != 2000) {
                            cb.onFailure(new ApiError(ApiError.Type.UBUS_ERROR,
                                    api + " 返回 code " + code, code));
                            return;
                        }
                        JsonObject d = result.has("data") && result.get("data").isJsonObject()
                                ? result.getAsJsonObject("data") : new JsonObject();
                        cb.onSuccess(d);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    // =====================================================================
    // 应用过滤
    // =====================================================================

    public static void getFilterEnabled(ApiCallback<Boolean> cb) {
        call("get_app_filter_adv", new JsonObject(), new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject data) {
                cb.onSuccess(num(data, "enable") != 0);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    public static void setFilterEnabled(boolean enabled, ApiCallback<JsonObject> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("enable", enabled ? 1 : 0);
        call("set_app_filter_adv", data, cb);
    }

    /** 特征库信息:{"loaded","version","format","app_count",...} 原样返回 */
    public static void getFeatureInfo(ApiCallback<JsonObject> cb) {
        call("get_feature_info", new JsonObject(), cb);
    }

    public static void loadClasses(ApiCallback<List<AppClass>> cb) {
        call("class_list", new JsonObject(), new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject data) {
                cb.onSuccess(parseClasses(data));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 可单测:class_list 的 data → 分类与应用 */
    public static List<AppClass> parseClasses(JsonObject data) {
        List<AppClass> out = new ArrayList<>();
        if (data == null || !data.has("class_list")
                || !data.get("class_list").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("class_list")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            AppClass c = new AppClass();
            c.name = str(o, "name");
            if (o.has("app_list") && o.get("app_list").isJsonArray()) {
                for (JsonElement a : o.getAsJsonArray("app_list")) {
                    if (!a.isJsonPrimitive()) continue;
                    // 元素形如 "<appid>,<应用名>" 或 "<appid>,<应用名>,0"(0 = 无图标)
                    String[] parts = a.getAsString().split(",", 3);
                    if (parts.length < 2) continue;
                    try {
                        c.apps.add(new App(Integer.parseInt(parts[0].trim()), parts[1]));
                    } catch (Exception ignored) {
                    }
                }
            }
            out.add(c);
        }
        return out;
    }

    public static void loadRules(ApiCallback<List<Rule>> cb) {
        call("get_filter_rules", new JsonObject(), new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject data) {
                cb.onSuccess(parseRules(data));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 可单测:get_filter_rules 的 data → 规则列表 */
    public static List<Rule> parseRules(JsonObject data) {
        List<Rule> out = new ArrayList<>();
        if (data == null || !data.has("list") || !data.get("list").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("list")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            Rule r = new Rule();
            r.raw = JsonParser.parseString(o.toString()).getAsJsonObject();
            r.id = num(o, "id");
            r.name = str(o, "name");
            r.mode = (int) num(o, "mode");
            if (r.mode == 0) r.mode = 1;
            r.userMac = str(o, "user_mac");
            r.userName = str(o, "user_name");
            r.enabled = !o.has("enabled") || num(o, "enabled") != 0;
            r.filterQuic = num(o, "filter_quic") != 0;
            if (o.has("app_ids") && o.get("app_ids").isJsonArray()) {
                for (JsonElement a : o.getAsJsonArray("app_ids")) {
                    if (a.isJsonPrimitive()) r.appIds.add(a.getAsString());
                }
            }
            out.add(r);
        }
        return out;
    }

    /** 新增规则(id 由服务端按时间戳生成);timeRules/appIds 由调用方组好 */
    public static void addRule(String name, List<Integer> appIds, ApiCallback<JsonObject> cb) {
        JsonObject data = baseRuleData(name, appIds);
        call("add_filter_rule", data, cb);
    }

    /** 全量修改一条规则(改开关、改应用集合都走这里) */
    public static void updateRule(Rule rule, ApiCallback<JsonObject> cb) {
        JsonObject data = JsonParser.parseString(rule.raw.toString()).getAsJsonObject();
        data.addProperty("id", rule.id);
        data.addProperty("name", rule.name);
        data.addProperty("mode", rule.mode);
        data.addProperty("user_mac", rule.userMac);
        data.addProperty("user_name", rule.userName);
        data.addProperty("enabled", rule.enabled ? 1 : 0);
        data.addProperty("filter_quic", rule.filterQuic ? 1 : 0);
        JsonArray ids = new JsonArray();
        for (String token : rule.appIds) ids.add(token);
        data.add("app_ids", ids);
        if (!data.has("time_rules") || !data.get("time_rules").isJsonArray()
                || data.getAsJsonArray("time_rules").size() == 0) {
            data.add("time_rules", defaultTimeRules());
        }
        call("update_filter_rule", data, cb);
    }

    public static void deleteRule(long id, ApiCallback<JsonObject> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("id", id);
        call("delete_filter_rule", data, cb);
    }

    private static JsonObject baseRuleData(String name, List<Integer> appIds) {
        JsonObject data = new JsonObject();
        data.addProperty("name", name);
        data.addProperty("mode", 1);
        data.addProperty("user_mac", "");
        data.addProperty("user_name", "");
        data.addProperty("enabled", 1);
        data.addProperty("filter_quic", 0);
        data.add("time_rules", defaultTimeRules());
        JsonArray ids = new JsonArray();
        for (Integer id : appIds) ids.add(String.valueOf(id));
        data.add("app_ids", ids);
        return data;
    }

    /** 全周全天时段(新增规则的默认时段,后续可在 LuCI 里细调) */
    private static JsonArray defaultTimeRules() {
        JsonObject t = new JsonObject();
        JsonArray days = new JsonArray();
        for (int i = 0; i <= 6; i++) days.add(i);
        t.add("weekdays", days);
        t.addProperty("start_time", "00:00");
        t.addProperty("end_time", "23:59");
        JsonArray arr = new JsonArray();
        arr.add(t);
        return arr;
    }

    // =====================================================================
    // 白名单(命中的设备不受应用过滤)
    // =====================================================================

    public static void loadWhitelist(ApiCallback<List<DeviceEntry>> cb) {
        call("get_appfilter_whitelist", new JsonObject(), new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject data) {
                cb.onSuccess(parseDeviceList(data));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    public static void addWhitelist(List<String> macs, ApiCallback<JsonObject> cb) {
        JsonObject data = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String m : macs) arr.add(m);
        data.add("mac_list", arr);
        call("add_appfilter_whitelist", data, cb);
    }

    public static void delWhitelist(String mac, ApiCallback<JsonObject> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("mac", mac);
        call("del_appfilter_whitelist", data, cb);
    }

    /** 全部已知设备(加白名单 / 选设备时用) */
    public static void loadAllUsers(ApiCallback<List<DeviceEntry>> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("flag", 2);
        data.addProperty("page", 0);
        call("get_all_users", data, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject d) {
                List<DeviceEntry> out = parseDeviceList(d);
                // get_all_users 的条目还带 ip / online,DeviceEntry 只取展示要用的三项即可
                cb.onSuccess(out);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 可单测:{"list":[{mac,hostname,nickname}]} → 设备列表 */
    public static List<DeviceEntry> parseDeviceList(JsonObject data) {
        List<DeviceEntry> out = new ArrayList<>();
        if (data == null || !data.has("list") || !data.get("list").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("list")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            DeviceEntry d = new DeviceEntry();
            d.mac = str(o, "mac");
            d.hostname = str(o, "hostname");
            d.nickname = str(o, "nickname");
            if (!d.mac.isEmpty()) out.add(d);
        }
        return out;
    }

    // =====================================================================
    // 上网记录
    // =====================================================================

    public static final int PAGE_SIZE = 30;

    public static void loadActiveRecords(int page, ApiCallback<RecordPage> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("page", page);
        data.addProperty("page_size", PAGE_SIZE);
        call("get_active_app_records", data, recordPageCallback(cb));
    }

    /** 历史记录:mac 空 = 全部设备,appid 0 = 全部应用,时间为 Unix 秒 */
    public static void loadHistoryRecords(long startSec, long endSec, int page,
                                          ApiCallback<RecordPage> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("mac", "");
        data.addProperty("appid", 0);
        data.addProperty("start_time", startSec);
        data.addProperty("end_time", endSec);
        data.addProperty("page", page);
        data.addProperty("page_size", PAGE_SIZE);
        call("get_app_history_records", data, recordPageCallback(cb));
    }

    private static ApiCallback<JsonObject> recordPageCallback(ApiCallback<RecordPage> cb) {
        return new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject data) {
                cb.onSuccess(parseRecordPage(data));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        };
    }

    /** 可单测:分页包装 → RecordPage */
    public static RecordPage parseRecordPage(JsonObject data) {
        RecordPage p = new RecordPage();
        if (data == null) return p;
        p.totalNum = (int) num(data, "total_num");
        p.totalPage = (int) num(data, "total_page");
        p.page = (int) num(data, "page");
        if (!data.has("list") || !data.get("list").isJsonArray()) return p;
        for (JsonElement el : data.getAsJsonArray("list")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            Record r = new Record();
            r.mac = str(o, "mac");
            r.hostname = str(o, "hostname");
            r.nickname = str(o, "nickname");
            r.appName = !str(o, "appname").isEmpty() ? str(o, "appname") : str(o, "name");
            r.appId = (int) (num(o, "appid") != 0 ? num(o, "appid") : num(o, "id"));
            r.act = (int) (o.has("act") ? num(o, "act") : num(o, "latest_action"));
            r.online = num(o, "online") != 0;
            r.firstTime = o.has("ft") ? num(o, "ft") : num(o, "first_time");
            r.latestTime = o.has("lt") ? num(o, "lt") : num(o, "latest_time");
            r.totalTime = o.has("tt") ? num(o, "tt") : num(o, "total_time");
            p.list.add(r);
        }
        return p;
    }

    /** 记录总开关:读 get_record_base,写 set_record_base 时其余字段原样带回 */
    public static void getRecordBase(ApiCallback<JsonObject> cb) {
        call("get_record_base", new JsonObject(), cb);
    }

    public static void setRecordEnabled(JsonObject base, boolean enabled,
                                        ApiCallback<JsonObject> cb) {
        JsonObject data = JsonParser.parseString(base.toString()).getAsJsonObject();
        data.remove("status");
        data.addProperty("enable", enabled ? 1 : 0);
        call("set_record_base", data, cb);
    }

    // =====================================================================

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
