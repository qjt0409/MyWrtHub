package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.List;

/**
 * OafApi 解析。报文形状取自 OPAssistant 与 luci-app-oaf 7.0-r2 的
 * fwx/common 协议实测规格(见仓库外规格文件 oaf-protocol.md)。
 */
public class OafApiTest {

    @Test
    public void parseClasses_splitsIdAndName() {
        JsonObject data = JsonParser.parseString("{"
                + "\"class_list\":[{\"name\":\"聊天社交\",\"app_list\":["
                + "\"1001,微信\",\"1002,QQ\",\"1003,Telegram,0\"]},"
                + "{\"name\":\"游戏\",\"app_list\":[\"2001,王者荣耀\"]}]}").getAsJsonObject();
        List<OafApi.AppClass> classes = OafApi.parseClasses(data);

        assertEquals(2, classes.size());
        assertEquals("聊天社交", classes.get(0).name);
        assertEquals(3, classes.get(0).apps.size());
        assertEquals(1001, classes.get(0).apps.get(0).id);
        assertEquals("微信", classes.get(0).apps.get(0).name);
        // 带 ,0 后缀的(无图标)也要正确取名
        assertEquals("Telegram", classes.get(0).apps.get(2).name);
        assertEquals(2001, classes.get(1).apps.get(0).id);
    }

    @Test
    public void parseRules_keepsFieldsAndExpandsRanges() {
        JsonObject data = JsonParser.parseString("{"
                + "\"list\":[{\"id\":1712345678,\"name\":\"晚上禁游戏\",\"mode\":2,"
                + "\"user_mac\":\"AA:BB:CC:DD:EE:FF\",\"user_name\":\"孩子的平板\","
                + "\"enabled\":1,\"filter_quic\":0,"
                + "\"time_rules\":[{\"weekdays\":[1,2,3,4,5],"
                + "\"start_time\":\"08:00\",\"end_time\":\"22:00\"}],"
                + "\"app_ids\":[\"1001\",\"2001-2003\"]}]}").getAsJsonObject();
        List<OafApi.Rule> rules = OafApi.parseRules(data);

        assertEquals(1, rules.size());
        OafApi.Rule r = rules.get(0);
        assertEquals("晚上禁游戏", r.name);
        assertEquals(2, r.mode);
        assertTrue(r.enabled);
        // 1001 + 2001,2002,2003
        assertEquals(4, r.expandedAppIds().size());
        assertTrue(r.expandedAppIds().contains(2002));
        assertEquals("08:00–22:00", r.timeSummary());
    }

    @Test
    public void parseRecordPage_readsWrapperAndShortFields() {
        JsonObject data = JsonParser.parseString("{"
                + "\"total_num\":2,\"total_page\":1,\"page\":1,\"page_size\":30,"
                + "\"list\":["
                + "{\"mac\":\"AA:BB:CC:DD:EE:FF\",\"hostname\":\"iphone\","
                + "\"nickname\":\"\",\"appname\":\"抖音\",\"appid\":3001,\"act\":1,"
                + "\"online\":1,\"ft\":1794000000,\"lt\":1794003600,\"tt\":1800},"
                + "{\"mac\":\"AA:BB:CC:DD:EE:FF\",\"hostname\":\"iphone\","
                + "\"nickname\":\"\",\"name\":\"微信\",\"id\":1001,\"latest_action\":0,"
                + "\"online\":1,\"first_time\":1794000000,\"latest_time\":1794003000,"
                + "\"total_time\":600}]}").getAsJsonObject();
        OafApi.RecordPage p = OafApi.parseRecordPage(data);

        assertEquals(2, p.totalNum);
        assertEquals(2, p.list.size());
        assertEquals("抖音", p.list.get(0).appName);
        assertEquals(1, p.list.get(0).act);
        assertEquals(1800, p.list.get(0).totalTime);
        // name/id/latest_action 与长字段名都要认
        assertEquals("微信", p.list.get(1).appName);
        assertEquals(1001, p.list.get(1).appId);
        assertEquals(0, p.list.get(1).act);
    }

    @Test
    public void parseUsers_readsTerminalFields() {
        // 形状取自终端页规格:get_all_users flag=3(速率 B/s,今日量为当日小时桶求和)
        JsonObject data = JsonParser.parseString("{"
                + "\"total_num\":2,\"list\":["
                + "{\"mac\":\"08:FA:79:57:43:1D\",\"online\":1,\"active\":1,"
                + "\"is_wireless\":1,\"session\":3,\"pc_status_key\":\"unlimited\","
                + "\"in_blacklist\":0,\"af_whitelist\":0,\"mf_whitelist\":0,"
                + "\"ip\":\"10.0.1.7\",\"ipv6\":\"\",\"hostname\":\"VIVO-Y5s\",\"nickname\":\"\","
                + "\"up_rate\":295,\"down_rate\":408,\"rssi\":-52,\"band\":\"5G\","
                + "\"today_up_bytes\":175127264,\"today_down_bytes\":376798822,"
                + "\"today_active_time\":57540,\"app\":\"YouTube\",\"app_id\":16100,"
                + "\"url\":\"\",\"applist\":[{\"id\":16100,\"name\":\"YouTube\"},"
                + "{\"id\":16001,\"name\":\"Google\",\"icon\":0}]},"
                + "{\"mac\":\"AA:BB:CC:DD:EE:01\",\"online\":0,\"active\":0,"
                + "\"pc_status_key\":\"mac_blocked\",\"in_blacklist\":1,"
                + "\"ip\":\"10.0.1.9\",\"hostname\":\"\",\"nickname\":\"客厅电视\"}"
                + "]}").getAsJsonObject();
        List<OafApi.FwxUser> users = OafApi.parseUsers(data);

        assertEquals(2, users.size());
        OafApi.FwxUser u = users.get(0);
        assertEquals("VIVO-Y5s", u.displayName());
        assertTrue(u.online && u.active && u.isWireless);
        assertEquals(295, u.upRate);
        assertEquals(408, u.downRate);
        // 卡片上的流量数字 = 今日上下行之和
        assertEquals(175127264L + 376798822L, u.todayTotalBytes());
        assertEquals(57540, u.todayActiveSec);
        assertEquals("YouTube", u.currentApp);
        assertEquals(2, u.apps.size());
        assertEquals("Google", u.apps.get(1).name);

        OafApi.FwxUser blocked = users.get(1);
        assertTrue(blocked.inBlacklist);
        // 显示名优先级:nickname > hostname > mac
        assertEquals("客厅电视", blocked.displayName());
    }

    @Test
    public void parseParentalRules_readsModulesAndStatus() {
        JsonObject data = JsonParser.parseString("{"
                + "\"mac\":\"08:FA:79:57:43:1D\",\"af_whitelist\":0,\"mf_whitelist\":1,"
                + "\"list\":["
                + "{\"module\":\"appfilter\",\"rule_id\":12,\"rule_name\":\"禁游戏\","
                + "\"condition_type\":\"time_range\",\"condition\":{\"time_rules\":["
                + "{\"weekdays\":[1,2,3,4,5],\"start_time\":\"08:00\",\"end_time\":\"22:00\"}]},"
                + "\"today_status\":{\"matched\":1}},"
                + "{\"module\":\"macfilter\",\"rule_id\":9,\"rule_name\":\"每天两小时\","
                + "\"condition_type\":\"duration\",\"condition\":{\"duration_rules\":["
                + "{\"weekdays\":[0,1,2,3,4,5,6],\"duration_minutes\":120}]},"
                + "\"today_status\":{\"used\":45,\"limit\":120,\"progress_percent\":37,"
                + "\"unlimited\":0}}]}").getAsJsonObject();
        OafApi.ParentalRules rules = OafApi.parseParentalRules(data);

        assertTrue(rules.mfWhitelist);
        assertEquals(2, rules.list.size());
        assertEquals("appfilter", rules.list.get(0).module);
        assertEquals("time_range", rules.list.get(0).conditionType);
        assertEquals(1, rules.list.get(0).todayStatus.get("matched").getAsInt());
        assertEquals("duration", rules.list.get(1).conditionType);
        assertEquals(120, rules.list.get(1).condition.getAsJsonArray("duration_rules")
                .get(0).getAsJsonObject().get("duration_minutes").getAsInt());
    }

    @Test
    public void parseDevVisitTime_sortsDescending() {
        JsonObject data = JsonParser.parseString("{"
                + "\"total_num\":2,\"list\":["
                + "{\"id\":1001,\"name\":\"微信\",\"t\":600},"
                + "{\"id\":3001,\"name\":\"抖音\",\"t\":3600}]}").getAsJsonObject();
        List<OafApi.AppTime> list = OafApi.parseDevVisitTime(data);
        assertEquals(2, list.size());
        assertEquals("抖音", list.get(0).name);
        assertEquals(3600, list.get(0).seconds);
    }

    @Test
    public void parseHourly_readsTrafficAndApps() {
        JsonObject data = JsonParser.parseString("{"
                + "\"mac\":\"08:FA:79:57:43:1D\",\"is_today\":1,\"hourly_stats\":["
                + "{\"hour\":8,\"traffic\":{\"up_bytes\":1024,\"down_bytes\":2048},"
                + "\"apps\":[{\"appid\":16100,\"name\":\"YouTube\",\"time\":1500}]},"
                + "{\"hour\":9,\"traffic\":{\"up_bytes\":0,\"down_bytes\":0},\"apps\":[]}"
                + "]}").getAsJsonObject();
        List<OafApi.HourStat> hours = OafApi.parseHourly(data);
        assertEquals(2, hours.size());
        assertEquals(8, hours.get(0).hour);
        assertEquals(2048, hours.get(0).downBytes);
        assertEquals("YouTube", hours.get(0).apps.get(0).name);
        assertEquals(1500, hours.get(0).apps.get(0).seconds);
    }
}
