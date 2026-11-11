package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

/** PasswallApi.parse:全局段 enabled/node + nodes 段解析(现行 passwall 字段) */
public class PasswallApiTest {

    @Test
    public void parse_readsGlobalAndNodes() {
        JsonObject result = JsonParser.parseString("{\"values\":{"
                + "\"cfg010000\":{\".type\":\"global\",\".name\":\"cfg010000\","
                + "\"enabled\":\"1\",\"node\":\"cfg020000\"},"
                + "\"cfg020000\":{\".type\":\"nodes\",\".name\":\"cfg020000\","
                + "\"remarks\":\"香港 01\",\"type\":\"sing-box\",\"protocol\":\"vless\","
                + "\"address\":\"hk.example.com\",\"port\":\"443\"},"
                + "\"cfg030000\":{\".type\":\"nodes\",\".name\":\"cfg030000\","
                + "\"remarks\":\"\",\"type\":\"Xray\",\"protocol\":\"vmess\","
                + "\"address\":\"10.0.1.9\",\"port\":\"10086\"},"
                + "\"cfg040000\":{\".type\":\"nodes\",\".name\":\"cfg040000\","
                + "\"remarks\":\"\",\"address\":\"\"},"
                + "\"cfg050000\":{\".type\":\"global_rules\",\"list\":\"x\"}"
                + "}}").getAsJsonObject();
        PasswallApi.PwSettings s = PasswallApi.parse(result);

        assertEquals("cfg010000", s.globalSection);
        assertTrue(s.enabled);
        assertEquals("cfg020000", s.nodeId);
        // 地址、备注都空的坏段被跳过
        assertEquals(2, s.nodes.size());
        assertEquals("香港 01", s.nodes.get(0).displayName());
        assertEquals("hk.example.com:443", s.nodes.get(0).endpoint());
        // 没备注时主标题退回地址:端口
        assertEquals("10.0.1.9:10086", s.nodes.get(1).displayName());
        assertEquals("香港 01", s.currentNode().displayName());
    }

    @Test
    public void parse_defaultsWhenGlobalMissing() {
        JsonObject result = JsonParser.parseString(
                "{\"values\":{}}").getAsJsonObject();
        PasswallApi.PwSettings s = PasswallApi.parse(result);
        assertEquals("", s.globalSection);
        assertFalse(s.enabled);
        assertEquals("", s.nodeId);
        assertTrue(s.nodes.isEmpty());
        assertNull(s.currentNode());
    }
}
