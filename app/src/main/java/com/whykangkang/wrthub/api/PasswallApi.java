package com.whykangkang.wrthub.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whykangkang.wrthub.model.PasswallNode;

import java.util.ArrayList;
import java.util.List;

/**
 * PassWall(luci-app-passwall)API —— 以固件 26.11.11-r1 的上游源码为准:
 *
 *  - 配置名 passwall;全局段 type=global{enabled, node},node 的值是
 *    节点段的段名(现行版本 UDP 跟随 TCP,老键 tcp_node/udp_node 已被
 *    官方 uci-defaults 迁移删除,勿用);
 *  - 节点段 type=nodes{remarks, type, protocol, address, port};
 *  - rpcd ACL(luci-app-passwall.json)授权 uci passwall 读写。
 *
 * 读写走 uci + apply,之后补一发 init restart 让服务按新配置重跑
 * (与 EqosApi 同一路子;rpcd 的 ubus 白名单里只有 apply 没有 commit)。
 */
public final class PasswallApi {

    public static final String CONFIG = "passwall";
    public static final String CONFIG2 = "passwall2";

    /** 全局设置 + 节点列表 */
    public static final class PwSettings {
        /** 配置名(passwall / passwall2,两者结构同构) */
        public String config = CONFIG;
        /** 全局段的段名(uci 写回要用);读不到时为空 */
        public String globalSection = "";
        public boolean enabled;
        /** 当前节点段名(全局段 node 字段);未选时为空 */
        public String nodeId = "";
        public final List<PasswallNode> nodes = new ArrayList<>();

        // ===== 全局页常用项;null = 配置里没这个键,该项不展示 =====
        // (PassWall 有全套;PassWall2 只有 localhost/client/remoteDns 三项,
        //  它是分流节点模型,没有代理模式与 DNS 分流这些键)
        /** TCP 默认代理模式:disable / proxy(PassWall) */
        public String tcpProxyMode;
        /** UDP 默认代理模式:disable / proxy(PassWall) */
        public String udpProxyMode;
        /** 大陆列表处理:0 关闭 / direct 直连 / proxy 代理(PassWall) */
        public String chnList;
        /** 是否使用 GFW 列表(PassWall) */
        public Boolean useGfwList;
        /** DNS 分流:dnsmasq / chinadns-ng / smartdns(PassWall) */
        public String dnsShunt;
        /** DNS 过滤模式:udp / tcp / dns2socks / sing-box / xray(PassWall) */
        public String dnsMode;
        /** 远程 DNS(两者都有) */
        public String remoteDns;
        /** 本机透明代理(两者都有) */
        public Boolean localhostProxy;
        /** 局域网设备透明代理(两者都有) */
        public Boolean clientProxy;
        /** 节点 Socks 监听端口(国外检测/国外 IP 走它),默认 1070 */
        public String socksPort = "1070";
        /** 订阅列表(subscribe_list 段) */
        public final List<Subscription> subscriptions = new ArrayList<>();

        public PasswallNode currentNode() {
            for (PasswallNode n : nodes) {
                if (n.section.equals(nodeId)) return n;
            }
            return null;
        }
    }

    /** 一条订阅(subscribe_list 段:remark + url,url 可多行) */
    public static final class Subscription {
        public String section = "";
        public String remark = "";
        public String url = "";
    }

    /** LuCI 全局页的快捷模式:四个预设都是 use_gfw_list/chn_list/TCP/UDP 的组合 */
    public enum QuickMode { GFW, CHNROUTE, GLOBAL, RETURNHOME, CUSTOM }

    /** 由当前组合反推快捷模式;对不上任何预设就是 CUSTOM(网页版同样不亮按钮) */
    public static QuickMode quickModeOf(PwSettings s) {
        if (s.tcpProxyMode == null || s.udpProxyMode == null || s.chnList == null) {
            return QuickMode.CUSTOM;
        }
        boolean gfw = Boolean.TRUE.equals(s.useGfwList);
        boolean proxy = "proxy".equals(s.tcpProxyMode) && "proxy".equals(s.udpProxyMode);
        boolean disable = "disable".equals(s.tcpProxyMode) && "disable".equals(s.udpProxyMode);
        if (gfw && "0".equals(s.chnList) && disable) return QuickMode.GFW;
        if (gfw && "direct".equals(s.chnList) && proxy) return QuickMode.CHNROUTE;
        if (!gfw && "proxy".equals(s.chnList) && disable) return QuickMode.RETURNHOME;
        if (!gfw && "0".equals(s.chnList) && proxy) return QuickMode.GLOBAL;
        return QuickMode.CUSTOM;
    }

    /** 快捷模式对应的四个键值(与 LuCI proxy.htm 的 switch_*_mode 逐项一致) */
    public static java.util.Map<String, String> quickModeValues(QuickMode mode) {
        java.util.Map<String, String> v = new java.util.LinkedHashMap<>();
        switch (mode) {
            case GFW:
                v.put("use_gfw_list", "1");
                v.put("chn_list", "0");
                v.put("tcp_proxy_mode", "disable");
                v.put("udp_proxy_mode", "disable");
                break;
            case CHNROUTE:
                v.put("use_gfw_list", "1");
                v.put("chn_list", "direct");
                v.put("tcp_proxy_mode", "proxy");
                v.put("udp_proxy_mode", "proxy");
                break;
            case RETURNHOME:
                v.put("use_gfw_list", "0");
                v.put("chn_list", "proxy");
                v.put("tcp_proxy_mode", "disable");
                v.put("udp_proxy_mode", "disable");
                break;
            case GLOBAL:
                v.put("use_gfw_list", "0");
                v.put("chn_list", "0");
                v.put("tcp_proxy_mode", "proxy");
                v.put("udp_proxy_mode", "proxy");
                break;
            default:
                break;
        }
        return v;
    }

    private PasswallApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    public static void getSettings(String config, ApiCallback<PwSettings> cb) {
        api().uciGetConfig(config, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                PwSettings parsed = parse(result);
                parsed.config = config;
                cb.onSuccess(parsed);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    static PwSettings parse(JsonObject result) {
        PwSettings out = new PwSettings();
        JsonObject values = result.has("values") && result.get("values").isJsonObject()
                ? result.getAsJsonObject("values") : result;
        for (String key : values.keySet()) {
            JsonElement el = values.get(key);
            if (!el.isJsonObject()) continue;
            JsonObject sec = el.getAsJsonObject();
            String type = str(sec, ".type", "");
            if ("global".equals(type)) {
                // 全局段理论上只有一个;万一有多个,认第一个
                if (out.globalSection.isEmpty()) {
                    out.globalSection = key;
                    out.enabled = EqosApi.uciBool(sec, "enabled", false);
                    out.nodeId = str(sec, "node", "");
                    out.tcpProxyMode = optStr(sec, "tcp_proxy_mode");
                    out.udpProxyMode = optStr(sec, "udp_proxy_mode");
                    out.chnList = optStr(sec, "chn_list");
                    out.useGfwList = optBool(sec, "use_gfw_list");
                    out.dnsShunt = optStr(sec, "dns_shunt");
                    out.dnsMode = optStr(sec, "dns_mode");
                    out.remoteDns = optStr(sec, "remote_dns");
                    out.localhostProxy = optBool(sec, "localhost_proxy");
                    out.clientProxy = optBool(sec, "client_proxy");
                    String socks = optStr(sec, "node_socks_port");
                    if (socks != null) out.socksPort = socks;
                }
            } else if ("subscribe_list".equals(type)) {
                Subscription sub = new Subscription();
                sub.section = key;
                sub.remark = str(sec, "remark", "");
                sub.url = str(sec, "url", "");
                if (!sub.url.isEmpty()) out.subscriptions.add(sub);
            } else if ("nodes".equals(type)) {
                PasswallNode n = new PasswallNode();
                n.section = key;
                n.remarks = str(sec, "remarks", "");
                n.type = str(sec, "type", "");
                n.protocol = str(sec, "protocol", "");
                n.address = str(sec, "address", "");
                n.port = str(sec, "port", "");
                n.group = str(sec, "group", "");
                // 地址、备注都空的是坏数据,展示出来也没法用,跳过
                if (n.address.isEmpty() && n.remarks.isEmpty()) continue;
                out.nodes.add(n);
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    /** 可选字符串键:没这个键或空串返回 null(界面据此决定显不显示该项) */
    private static String optStr(JsonObject o, String key) {
        String v = str(o, key, "");
        return v.isEmpty() ? null : v;
    }

    /** 可选布尔键:没这个键返回 null */
    private static Boolean optBool(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return EqosApi.uciBool(o, key, false);
    }

    /** 服务进程是否在跑(ubus service list) */
    public static void serviceRunning(String config, ApiCallback<Boolean> cb) {
        api().serviceList(config, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(OpenWrtApi.serviceIsRunning(result, config));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /**
     * 通用写回:把一组键值写进全局段,apply 后重启服务生效。
     * enableHint 非 null 时按总开关语义处理(开→多一步 enable)。
     */
    public static void setOptions(PwSettings settings, java.util.Map<String, String> options,
                                  Boolean enableHint, ApiCallback<Boolean> cb) {
        if (settings.globalSection.isEmpty()) {
            cb.onFailure(new ApiError(ApiError.Type.INVALID_RESPONSE, "缺少全局配置段"));
            return;
        }
        JsonObject values = new JsonObject();
        for (java.util.Map.Entry<String, String> e : options.entrySet()) {
            values.addProperty(e.getKey(), e.getValue());
        }
        api().uciSet(settings.config, settings.globalSection, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                apply(settings.config, enableHint, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 总开关写回全局段,apply 后重启服务让开关生效 */
    public static void setEnabled(PwSettings settings, boolean enabled,
                                  ApiCallback<Boolean> cb) {
        java.util.Map<String, String> v = new java.util.LinkedHashMap<>();
        v.put("enabled", enabled ? "1" : "0");
        setOptions(settings, v, enabled, cb);
    }

    /** 切换当前节点:把全局段 node 改为目标段名,apply 后重启服务 */
    public static void setNode(PwSettings settings, String nodeSection,
                               ApiCallback<Boolean> cb) {
        java.util.Map<String, String> v = new java.util.LinkedHashMap<>();
        v.put("node", nodeSection);
        setOptions(settings, v, null, cb);
    }

    /** 应用快捷模式(四个键一次写回) */
    public static void setQuickMode(PwSettings settings, QuickMode mode,
                                    ApiCallback<Boolean> cb) {
        setOptions(settings, quickModeValues(mode), null, cb);
    }

    // =====================================================================
    // 订阅管理(subscribe_list 段增删 + 调路由器侧 subscribe.lua 更新)
    // =====================================================================

    /** 新增一条订阅(remark + url),落盘即可,不需要重启服务 */
    public static void addSubscription(String config, String remark, String url,
                                       ApiCallback<Boolean> cb) {
        JsonObject values = new JsonObject();
        values.addProperty("remark", remark);
        values.addProperty("url", url);
        api().uciAdd(config, "subscribe_list", null, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                api().uciApplyOrCommit(config, new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        cb.onSuccess(true);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 删除一条订阅(它名下的节点由订阅脚本在下次更新时清理,这里只删订阅本身) */
    public static void deleteSubscription(String config, String section,
                                          ApiCallback<Boolean> cb) {
        api().uciDelete(config, section, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                api().uciApplyOrCommit(config, new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject r) {
                        cb.onSuccess(true);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /**
     * 更新一条订阅:走 LuCI 网页版同款 CGI 端点 subscribe_manual
     * (服务端触发 subscribe.lua 并立即返回),不依赖 file.exec——
     * 该通道在部分固件上会被 rpcd ACL 拒绝(2026-11-11 实机坐实)。
     * url 要带上当前值:与配置不一致时 LuCI 会先帮存一遍。
     */
    public static void updateSubscription(String config, String section, String url,
                                          ApiCallback<Boolean> cb) {
        java.util.Map<String, String> form = new java.util.HashMap<>();
        form.put("section", section);
        form.put("url", url == null ? "" : url);
        api().cgiPost("/admin/services/" + config + "/subscribe_manual", form, 30,
                new ApiCallback<String>() {
                    @Override
                    public void onSuccess(String body) {
                        String error = parseSubscribeError(body);
                        if (error == null) {
                            cb.onSuccess(true);
                        } else {
                            cb.onFailure(new ApiError(ApiError.Type.UBUS_ERROR, error));
                        }
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    /** 全部更新:LuCI 的 subscribe_manual_all(sections/urls 逗号拼接) */
    public static void updateAllSubscriptions(String config,
                                              java.util.List<Subscription> subs,
                                              ApiCallback<Boolean> cb) {
        if (subs == null || subs.isEmpty()) {
            cb.onSuccess(true);
            return;
        }
        StringBuilder sections = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        for (Subscription s : subs) {
            if (sections.length() > 0) {
                sections.append(',');
                urls.append(',');
            }
            sections.append(s.section);
            urls.append(s.url == null ? "" : s.url);
        }
        java.util.Map<String, String> form = new java.util.HashMap<>();
        form.put("sections", sections.toString());
        form.put("urls", urls.toString());
        api().cgiPost("/admin/services/" + config + "/subscribe_manual_all", form, 30,
                new ApiCallback<String>() {
                    @Override
                    public void onSuccess(String body) {
                        String error = parseSubscribeError(body);
                        if (error == null) {
                            cb.onSuccess(true);
                        } else {
                            cb.onFailure(new ApiError(ApiError.Type.UBUS_ERROR, error));
                        }
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    /** subscribe_manual 的返回:{"success":true} 或 {"success":false,"msg":..};成功返回 null */
    static String parseSubscribeError(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("success") && o.get("success").getAsBoolean()) return null;
            if (o.has("msg")) return o.get("msg").getAsString();
            return "unknown response";
        } catch (Exception e) {
            return "unparsable response";
        }
    }

    // =====================================================================
    // 组件运行状态(LuCI 状态页的 index_status,CGI 直读)
    // =====================================================================

    /** TCP/UDP/DNS/HAPROXY 四个组件的运行状态;null = 状态页没给这一项 */
    public static final class ComponentStatus {
        public Boolean tcp;
        public Boolean udp;
        public Boolean dns;
        /** -1 未装,0 运行中,1 未运行;null 未知 */
        public Integer haproxy;
    }

    public static void getComponentStatus(String config, ApiCallback<ComponentStatus> cb) {
        api().cgiGetAsBrowser("/admin/services/" + config + "/index_status", 10,
                new ApiCallback<String>() {
                    @Override
                    public void onSuccess(String body) {
                        cb.onSuccess(parseComponentStatus(body));
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    static ComponentStatus parseComponentStatus(String body) {
        ComponentStatus out = new ComponentStatus();
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            out.tcp = optBool(o, "tcp_status");
            out.udp = optBool(o, "udp_status");
            out.dns = optBool(o, "dns_mode_status");
            if (o.has("haproxy_status") && o.get("haproxy_status").isJsonPrimitive()) {
                try {
                    out.haproxy = Integer.parseInt(
                            o.get("haproxy_status").getAsString().trim());
                } catch (NumberFormatException ignored) {
                    out.haproxy = null;
                }
            }
        } catch (Exception ignored) {
            // 解析失败全部保持 null(未知)
        }
        return out;
    }


    // =====================================================================
    // 连通性与 IP 检测
    // 延迟走 LuCI 的 connect_status(服务端 curl,与状态页点我检测同一端点);
    // 国内 IP 取 WAN 口地址(ubus),国外 IP 由手机经路由器 Socks 出口实测。
    // =====================================================================

    /** 检测结果;null = 该项没测通/不可用 */
    public static final class NetCheck {
        public Integer baiduMs;
        public Integer googleMs;
        public String cnIp;
        public String globalIp;
    }

    public static void netCheck(String config, String socksPort, ApiCallback<NetCheck> cb) {
        NetCheck out = new NetCheck();
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(4);
        Runnable done = () -> {
            if (remaining.decrementAndGet() == 0) cb.onSuccess(out);
        };
        connectDelay(config, "https://www.baidu.com/favicon.ico",
                ms -> {
                    out.baiduMs = ms;
                    done.run();
                });
        connectDelay(config, "https://www.google.com/generate_204",
                ms -> {
                    out.googleMs = ms;
                    done.run();
                });
        api().getNetworkInterfaceDump(new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                out.cnIp = parseWanIp(result);
                done.run();
            }

            @Override
            public void onFailure(ApiError error) {
                done.run();
            }
        });
        fetchGlobalIpViaSocks(socksPort, ip -> {
            out.globalIp = ip;
            done.run();
        });
    }

    private interface IntSink {
        void put(Integer v);
    }

    private interface StrSink {
        void put(String v);
    }

    /** LuCI connect_status:返回 JSON 的 use_time 是毫秒(字符串),空 = 不通 */
    private static void connectDelay(String config, String url, IntSink sink) {
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(url, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            encoded = url;
        }
        String path = "/admin/services/" + config + "/connect_status?url=" + encoded;
        api().cgiGetAsBrowser(path, 12, new ApiCallback<String>() {
            @Override
            public void onSuccess(String body) {
                sink.put(parseUseTimeMs(body));
            }

            @Override
            public void onFailure(ApiError error) {
                sink.put(null);
            }
        });
    }

    static Integer parseUseTimeMs(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (!o.has("use_time")) return null;
            String text = o.get("use_time").getAsString().trim();
            if (text.isEmpty()) return null;
            double ms = Double.parseDouble(text);
            return ms > 0 ? (int) Math.round(ms) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 network.interface.dump 里挑默认路由接口的 IPv4(WAN 口地址) */
    static String parseWanIp(JsonObject dump) {
        try {
            com.google.gson.JsonArray ifaces = dump.getAsJsonArray("interface");
            if (ifaces == null) return null;
            String fallback = null;
            for (com.google.gson.JsonElement el : ifaces) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String ip = firstIpv4(o);
                if (ip == null) continue;
                if (fallback == null && !"loopback".equals(str(o, "interface", ""))) {
                    fallback = ip;
                }
                if (o.has("route") && o.get("route").isJsonArray()) {
                    for (com.google.gson.JsonElement r : o.getAsJsonArray("route")) {
                        if (r.isJsonObject() && "0.0.0.0".equals(
                                str(r.getAsJsonObject(), "target", ""))) {
                            return ip;
                        }
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstIpv4(JsonObject iface) {
        if (!iface.has("ipv4-address") || !iface.get("ipv4-address").isJsonArray()) {
            return null;
        }
        com.google.gson.JsonArray arr = iface.getAsJsonArray("ipv4-address");
        if (arr.size() == 0 || !arr.get(0).isJsonObject()) return null;
        String ip = str(arr.get(0).getAsJsonObject(), "address", "");
        return ip.isEmpty() ? null : ip;
    }

    /**
     * 国外 IP:手机经路由器 Socks(节点出口)访问 ipify。
     * Socks 只绑 localhost 时从手机连不上,返回 null(卡上显示 —)。
     */
    private static void fetchGlobalIpViaSocks(String socksPort, StrSink sink) {
        String host = api().getHost();
        int port;
        try {
            port = Integer.parseInt(socksPort == null || socksPort.isEmpty()
                    ? "1070" : socksPort);
        } catch (NumberFormatException e) {
            port = 1070;
        }
        final int socksPortNum = port;
        Thread thread = new Thread(() -> {
            String ip = null;
            try {
                java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress(host, socksPortNum));
                okhttp3.OkHttpClient client = api().clientWithTimeout(8)
                        .newBuilder().proxy(proxy).build();
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url("https://api.ipify.org").build();
                try (okhttp3.Response response = client.newCall(request).execute()) {
                    String text = response.body() != null
                            ? response.body().string().trim() : "";
                    if (text.matches("[0-9a-fA-F.:]+")) ip = text;
                }
            } catch (Exception ignored) {
                ip = null;
            }
            final String result = ip;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> sink.put(result));
        });
        thread.setDaemon(true);
        thread.start();
    }

    /** 手动重启服务(用户点的按钮,结果如实回调) */
    public static void restart(String config, ApiCallback<JsonObject> cb) {
        api().initdControl(config, "restart", cb);
    }

    /**
     * apply 落盘并触发 reload_config,再补一发 init restart 兜底
     * (best effort:被 ACL 拒不算失败,与 EqosApi 一致)。
     */
    private static void apply(String config, Boolean enable, ApiCallback<Boolean> cb) {
        api().uciApplyOrCommit(config, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                Runnable finish = () -> cb.onSuccess(true);
                if (Boolean.TRUE.equals(enable)) {
                    api().bestEffortInitAction(config, "enable",
                            () -> api().bestEffortInitAction(config, "restart", finish));
                } else {
                    api().bestEffortInitAction(config, "restart", finish);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }
}
