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

    /**
     * 一台终端(get_all_users,flag=3 的完整字段),对应 OPAssistant「终端」页。
     * 速率单位是字节/秒(内核每 2 秒采样差值的一半);协议里没有「历史累计
     * 总流量」,OPAssistant 卡片上的流量数字就是今日上下行之和,不要自创口径。
     */
    public static final class FwxUser {
        public String mac = "";
        public boolean online;
        public boolean active;
        public boolean isWireless;
        public int session;
        /** 家长控制状态:unlimited / app_limited / mac_blocked */
        public String pcStatusKey = "";
        public boolean inBlacklist;
        public boolean afWhitelist;
        public boolean mfWhitelist;
        public String ip = "";
        public String ipv6 = "";
        public String hostname = "";
        public String nickname = "";
        public long upRate;
        public long downRate;
        public int rssi;
        public long rxRate;
        public long txRate;
        public String band = "";
        public long todayUpBytes;
        public long todayDownBytes;
        public long todayActiveSec;
        /** 当前正在访问的应用(没有时为空串) */
        public String currentApp = "";
        public int currentAppId;
        /** 当前正在访问的地址(没有时为空串) */
        public String url = "";
        /** 常用 App Top5(按累计访问时长排序) */
        public final List<App> apps = new ArrayList<>();
        /** 服务端原始对象,传给详情页免得再拼一遍 */
        public JsonObject raw = new JsonObject();

        /** 显示名优先级与 OPAssistant 一致:nickname → hostname → mac */
        public String displayName() {
            if (!nickname.isEmpty() && !"--".equals(nickname)) return nickname;
            if (!hostname.isEmpty() && !"--".equals(hostname)) return hostname;
            return mac;
        }

        public long todayTotalBytes() {
            return todayUpBytes + todayDownBytes;
        }
    }

    /** 一条命中某设备的管控规则(家长控制聚合,只读展示) */
    public static final class ParentalRule {
        /** appfilter / macfilter / blacklist */
        public String module = "";
        public String ruleName = "";
        /** time_range / duration / flow / blacklist */
        public String conditionType = "";
        public JsonObject condition = new JsonObject();
        public JsonObject todayStatus = new JsonObject();
    }

    /** 某设备的管控规则聚合(白名单标记 + 规则列表) */
    public static final class ParentalRules {
        public boolean afWhitelist;
        public boolean mfWhitelist;
        public final List<ParentalRule> list = new ArrayList<>();
    }

    /** App统计:某设备对某 App 的累计访问时长(秒) */
    public static final class AppTime {
        public int id;
        public String name = "";
        public long seconds;
    }

    /** 今日流量里某小时的一个 Top 应用 */
    public static final class HourApp {
        public int appid;
        public String name = "";
        public long seconds;
    }

    /** 今日流量:一个小时桶的上下行字节 + Top 应用 */
    public static final class HourStat {
        public int hour;
        public long upBytes;
        public long downBytes;
        public final List<HourApp> apps = new ArrayList<>();
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

    // =====================================================================
    // 终端(OPAssistant 同款:get_all_users flag=3 + 设备详情各接口)
    // =====================================================================

    /** 终端列表:flag=3 才带速率/今日统计/常用应用,page_size 与 OPAssistant 一致取 50 */
    public static void loadUsers(ApiCallback<List<FwxUser>> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("flag", 3);
        data.addProperty("page", 1);
        data.addProperty("page_size", 50);
        call("get_all_users", data, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject d) {
                cb.onSuccess(parseUsers(d));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 可单测:get_all_users 的 data → 终端列表(保持服务端顺序,排序在界面层做) */
    public static List<FwxUser> parseUsers(JsonObject data) {
        List<FwxUser> out = new ArrayList<>();
        if (data == null || !data.has("list") || !data.get("list").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("list")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            FwxUser u = new FwxUser();
            u.raw = JsonParser.parseString(o.toString()).getAsJsonObject();
            u.mac = str(o, "mac");
            u.online = num(o, "online") != 0;
            u.active = num(o, "active") != 0;
            u.isWireless = num(o, "is_wireless") != 0;
            u.session = (int) num(o, "session");
            u.pcStatusKey = str(o, "pc_status_key");
            u.inBlacklist = num(o, "in_blacklist") != 0;
            u.afWhitelist = num(o, "af_whitelist") != 0;
            u.mfWhitelist = num(o, "mf_whitelist") != 0;
            u.ip = str(o, "ip");
            u.ipv6 = str(o, "ipv6");
            u.hostname = str(o, "hostname");
            u.nickname = str(o, "nickname");
            u.upRate = num(o, "up_rate");
            u.downRate = num(o, "down_rate");
            u.rssi = (int) num(o, "rssi");
            u.rxRate = num(o, "rx_rate");
            u.txRate = num(o, "tx_rate");
            u.band = str(o, "band");
            u.todayUpBytes = num(o, "today_up_bytes");
            u.todayDownBytes = num(o, "today_down_bytes");
            u.todayActiveSec = num(o, "today_active_time");
            u.currentApp = str(o, "app");
            u.currentAppId = (int) num(o, "app_id");
            u.url = str(o, "url");
            if (o.has("applist") && o.get("applist").isJsonArray()) {
                for (JsonElement a : o.getAsJsonArray("applist")) {
                    if (!a.isJsonObject()) continue;
                    JsonObject ao = a.getAsJsonObject();
                    u.apps.add(new App((int) num(ao, "id"), str(ao, "name")));
                }
            }
            if (!u.mac.isEmpty()) out.add(u);
        }
        return out;
    }

    /** 详情·管控规则:命中该设备且启用的规则 + 今日执行状态 */
    public static void loadParentalRules(String mac, ApiCallback<ParentalRules> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("mac", mac);
        call("get_user_parental_control_rules", data, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject d) {
                cb.onSuccess(parseParentalRules(d));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 可单测:get_user_parental_control_rules 的 data → 聚合对象 */
    public static ParentalRules parseParentalRules(JsonObject data) {
        ParentalRules out = new ParentalRules();
        if (data == null) return out;
        out.afWhitelist = num(data, "af_whitelist") != 0;
        out.mfWhitelist = num(data, "mf_whitelist") != 0;
        if (!data.has("list") || !data.get("list").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("list")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            ParentalRule r = new ParentalRule();
            r.module = str(o, "module");
            r.ruleName = str(o, "rule_name");
            r.conditionType = str(o, "condition_type");
            if (o.has("condition") && o.get("condition").isJsonObject()) {
                r.condition = o.getAsJsonObject("condition");
            }
            if (o.has("today_status") && o.get("today_status").isJsonObject()) {
                r.todayStatus = o.getAsJsonObject("today_status");
            }
            out.list.add(r);
        }
        return out;
    }

    /** 详情·App统计:各 App 累计访问时长,按时长降序返回 */
    public static void loadDevVisitTime(String mac, ApiCallback<List<AppTime>> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("mac", mac);
        call("dev_visit_time", data, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject d) {
                cb.onSuccess(parseDevVisitTime(d));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 可单测:dev_visit_time 的 data → 时长列表(降序) */
    public static List<AppTime> parseDevVisitTime(JsonObject data) {
        List<AppTime> out = new ArrayList<>();
        if (data == null || !data.has("list") || !data.get("list").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("list")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            AppTime t = new AppTime();
            t.id = (int) num(o, "id");
            t.name = str(o, "name");
            t.seconds = num(o, "t");
            out.add(t);
        }
        out.sort((a, b) -> Long.compare(b.seconds, a.seconds));
        return out;
    }

    /** 详情·今日流量 + 常用App:24 个小时桶(两页签共用一次请求) */
    public static void loadHourlyTopApps(String mac, ApiCallback<List<HourStat>> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("mac", mac);
        call("get_hourly_top_apps", data, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject d) {
                cb.onSuccess(parseHourly(d));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 可单测:get_hourly_top_apps 的 data → 小时桶列表 */
    public static List<HourStat> parseHourly(JsonObject data) {
        List<HourStat> out = new ArrayList<>();
        if (data == null || !data.has("hourly_stats")
                || !data.get("hourly_stats").isJsonArray()) return out;
        for (JsonElement el : data.getAsJsonArray("hourly_stats")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            HourStat h = new HourStat();
            h.hour = (int) num(o, "hour");
            if (o.has("traffic") && o.get("traffic").isJsonObject()) {
                JsonObject t = o.getAsJsonObject("traffic");
                h.upBytes = num(t, "up_bytes");
                h.downBytes = num(t, "down_bytes");
            }
            if (o.has("apps") && o.get("apps").isJsonArray()) {
                for (JsonElement a : o.getAsJsonArray("apps")) {
                    if (!a.isJsonObject()) continue;
                    JsonObject ao = a.getAsJsonObject();
                    HourApp ha = new HourApp();
                    ha.appid = (int) num(ao, "appid");
                    ha.name = str(ao, "name");
                    ha.seconds = num(ao, "time");
                    h.apps.add(ha);
                }
            }
            out.add(h);
        }
        return out;
    }

    /** 详情·应用记录:单设备的访问记录分页(字段与全局记录同构) */
    public static void loadDevRecords(String mac, int page, ApiCallback<RecordPage> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("mac", mac);
        data.addProperty("page", page);
        data.addProperty("page_size", 15);
        call("dev_visit_list", data, recordPageCallback(cb));
    }

    // =====================================================================
    // 上网黑名单(终端页「拉黑」走 fwx,不是防火墙规则)
    // =====================================================================

    public static void loadMacBlacklist(ApiCallback<List<DeviceEntry>> cb) {
        call("get_mac_blacklist", new JsonObject(), new ApiCallback<JsonObject>() {
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

    /** 拉黑:入参是 mac_list 数组(与解除的单值 mac 不同,别混) */
    public static void addMacBlacklist(String mac, ApiCallback<JsonObject> cb) {
        JsonObject data = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(mac);
        data.add("mac_list", arr);
        call("add_mac_blacklist", data, cb);
    }

    public static void delMacBlacklist(String mac, ApiCallback<JsonObject> cb) {
        JsonObject data = new JsonObject();
        data.addProperty("mac", mac);
        call("del_mac_blacklist", data, cb);
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
