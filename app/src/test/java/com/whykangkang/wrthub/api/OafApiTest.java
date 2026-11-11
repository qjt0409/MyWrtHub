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
}
