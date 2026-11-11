package com.whykangkang.wrthub.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

        public PasswallNode currentNode() {
            for (PasswallNode n : nodes) {
                if (n.section.equals(nodeId)) return n;
            }
            return null;
        }
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
                }
            } else if ("nodes".equals(type)) {
                PasswallNode n = new PasswallNode();
                n.section = key;
                n.remarks = str(sec, "remarks", "");
                n.type = str(sec, "type", "");
                n.protocol = str(sec, "protocol", "");
                n.address = str(sec, "address", "");
                n.port = str(sec, "port", "");
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

    /** 总开关写回全局段,apply 后重启服务让开关生效 */
    public static void setEnabled(PwSettings settings, boolean enabled,
                                  ApiCallback<Boolean> cb) {
        if (settings.globalSection.isEmpty()) {
            cb.onFailure(new ApiError(ApiError.Type.INVALID_RESPONSE, "缺少全局配置段"));
            return;
        }
        JsonObject values = new JsonObject();
        values.addProperty("enabled", enabled ? "1" : "0");
        api().uciSet(settings.config, settings.globalSection, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                apply(settings.config, enabled, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    /** 切换当前节点:把全局段 node 改为目标段名,apply 后重启服务 */
    public static void setNode(PwSettings settings, String nodeSection,
                               ApiCallback<Boolean> cb) {
        if (settings.globalSection.isEmpty()) {
            cb.onFailure(new ApiError(ApiError.Type.INVALID_RESPONSE, "缺少全局配置段"));
            return;
        }
        JsonObject values = new JsonObject();
        values.addProperty("node", nodeSection);
        api().uciSet(settings.config, settings.globalSection, values, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                apply(settings.config, null, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
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
