package com.whykangkang.wrthub.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whykangkang.wrthub.model.EqosDevice;
import com.whykangkang.wrthub.model.EqosGlobal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 设备限速(luci-app-eqosplus)API —— 固件里装的是 eqosplus,不是老 eqos,
 * 两者的 uci 配置名与字段都不同(以 eqosplus 1.3.0 源码为准):
 *
 *  - 配置名 eqosplus;全局段 type=eqosplus{enabled, download, upload, ifname},
 *    download/upload 单位 MB/s(可带小数);
 *  - 设备段 type=device{comment, enable, mac, download, upload,
 *    timestart, timeend, week};mac 字段可填 IP / MAC / IP 段;
 *    启用键是 enable 不是 enabled。
 *
 * 读写都走 uci —— luci-app-eqosplus 的 ACL 只授权了 eqosplus 的 uci scope。
 * 生效靠 uci.apply 触发 /sbin/reload_config,eqosplus 注册了 procd
 * reload trigger(init.d 里 procd_add_reload_trigger 'eqosplus')。
 * 注意 rpcd 的 ubus 方法白名单里只有 apply 没有 commit,直接 commit 会 -32002。
 */
public final class EqosApi {

    public static final String CONFIG = "eqosplus";
    private static final String INIT = "eqosplus";

    /** 全局设置 + 设备列表 */
    public static final class EqosSettings {
        public EqosGlobal global = EqosGlobal.defaults();
        public final List<EqosDevice> devices = new ArrayList<>();
    }

    private EqosApi() {
    }

    private static OpenWrtApi api() {
        return OpenWrtApi.getInstance();
    }

    public static void getSettings(ApiCallback<EqosSettings> cb) {
        api().uciGetConfig(CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                cb.onSuccess(parse(result));
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    static EqosSettings parse(JsonObject result) {
        EqosSettings out = new EqosSettings();
        JsonObject values = result.has("values") && result.get("values").isJsonObject()
                ? result.getAsJsonObject("values") : result;
        for (String key : values.keySet()) {
            JsonElement el = values.get(key);
            if (!el.isJsonObject()) continue;
            JsonObject sec = el.getAsJsonObject();
            String type = str(sec, ".type", "");
            if ("eqosplus".equals(type)) {
                out.global = new EqosGlobal(
                        uciBool(sec, "enabled", false),
                        str(sec, "download", EqosGlobal.DEFAULT_DOWNLOAD),
                        str(sec, "upload", EqosGlobal.DEFAULT_UPLOAD),
                        str(sec, "ifname", EqosGlobal.DEFAULT_IFNAME));
                out.global.section = key;
            } else if ("device".equals(type)) {
                String target = str(sec, "mac", "");
                // 没有目标的段是坏数据,跳过(限速规则离了目标无意义)
                if (target.isEmpty()) continue;
                EqosDevice d = new EqosDevice();
                d.section = key;
                d.enabled = uciBool(sec, "enable", false);
                d.target = target;
                d.download = str(sec, "download", "");
                d.upload = str(sec, "upload", "");
                d.comment = str(sec, "comment", "");
                d.timeStart = str(sec, "timestart", "00:00");
                d.timeEnd = str(sec, "timeend", "23:55");
                d.week = str(sec, "week", "0");
                out.devices.add(d);
            }
        }
        Collections.sort(out.devices, (a, b) -> EqosDevice.compareTarget(a.target, b.target));
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    /** uci 的值一律是字符串,"1"/"true"/"on"/"yes"/"enabled" 都算真 */
    static boolean uciBool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        String s = e.getAsString().toLowerCase();
        return s.equals("1") || s.equals("true") || s.equals("on")
                || s.equals("yes") || s.equals("enabled");
    }

    public static void saveGlobal(EqosGlobal global, ApiCallback<Boolean> cb) {
        JsonObject values = new JsonObject();
        values.addProperty("enabled", global.enabled ? "1" : "0");
        values.addProperty("download", global.download);
        values.addProperty("upload", global.upload);
        values.addProperty("ifname", global.ifname);
        ApiCallback<JsonObject> next = new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                apply(global.enabled, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        };
        // 读到过真实段名就按段名写回(匿名段最稳);没有才按类型新建
        if (global.section != null && !global.section.isEmpty()) {
            api().uciSet(CONFIG, global.section, values, next);
        } else {
            api().uciSetTyped(CONFIG, "config", "eqosplus", values, next);
        }
    }

    /** 新增限速设备,成功后回传 uci 生成的段名 */
    public static void addDevice(EqosDevice device, ApiCallback<String> cb) {
        api().uciAdd(CONFIG, "device", null, deviceValues(device),
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        String section = result.has("section")
                                ? result.get("section").getAsString() : "";
                        apply(null, new ApiCallback<Boolean>() {
                            @Override
                            public void onSuccess(Boolean ok) {
                                cb.onSuccess(section);
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

    public static void updateDevice(EqosDevice device, ApiCallback<Boolean> cb) {
        if (device.section == null || device.section.isEmpty()) {
            cb.onFailure(new ApiError(ApiError.Type.INVALID_RESPONSE, "缺少配置段名"));
            return;
        }
        api().uciSet(CONFIG, device.section, deviceValues(device),
                new ApiCallback<JsonObject>() {
                    @Override
                    public void onSuccess(JsonObject result) {
                        apply(null, cb);
                    }

                    @Override
                    public void onFailure(ApiError error) {
                        cb.onFailure(error);
                    }
                });
    }

    public static void deleteDevice(String section, ApiCallback<Boolean> cb) {
        api().uciDelete(CONFIG, section, null, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                apply(null, cb);
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }

    private static JsonObject deviceValues(EqosDevice d) {
        JsonObject v = new JsonObject();
        // 每条规则自己也有启用开关(键名是 enable),必须显式写回,
        // 否则会把用户在网页端停用的条目悄悄改成启用
        v.addProperty("enable", d.enabled ? "1" : "0");
        v.addProperty("mac", d.target);
        v.addProperty("download", d.download);
        v.addProperty("upload", d.upload);
        v.addProperty("comment", d.comment == null ? "" : d.comment);
        v.addProperty("timestart", d.timeStart == null || d.timeStart.isEmpty()
                ? "00:00" : d.timeStart);
        v.addProperty("timeend", d.timeEnd == null || d.timeEnd.isEmpty()
                ? "23:55" : d.timeEnd);
        v.addProperty("week", d.week == null || d.week.isEmpty() ? "0" : d.week);
        return v;
    }

    /**
     * apply 让配置落盘并触发 reload_config。随后的 init 动作只是给没注册
     * reload trigger 的固件兜底,被 ACL 拒属正常,不算失败。
     */
    private static void apply(Boolean enable, ApiCallback<Boolean> cb) {
        api().uciApplyOrCommit(CONFIG, new ApiCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                Runnable finish = () -> cb.onSuccess(true);
                if (Boolean.TRUE.equals(enable)) {
                    api().bestEffortInitAction(INIT, "enable",
                            () -> api().bestEffortInitAction(INIT, "restart", finish));
                } else {
                    api().bestEffortInitAction(INIT, "restart", finish);
                }
            }

            @Override
            public void onFailure(ApiError error) {
                cb.onFailure(error);
            }
        });
    }
}
