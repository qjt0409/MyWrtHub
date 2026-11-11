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

    @Test
    public void parse_readsGlobalOptionsAndQuickModes() {
        JsonObject result = JsonParser.parseString("{\"values\":{"
                + "\"cfg010000\":{\".type\":\"global\",\"enabled\":\"1\","
                + "\"tcp_proxy_mode\":\"proxy\",\"udp_proxy_mode\":\"proxy\","
                + "\"chn_list\":\"direct\",\"use_gfw_list\":\"1\","
                + "\"dns_shunt\":\"chinadns-ng\",\"dns_mode\":\"tcp\","
                + "\"remote_dns\":\"1.1.1.1\",\"localhost_proxy\":\"1\","
                + "\"client_proxy\":\"0\"}"
                + "}}").getAsJsonObject();
        PasswallApi.PwSettings s = PasswallApi.parse(result);

        assertEquals("proxy", s.tcpProxyMode);
        assertEquals("direct", s.chnList);
        assertEquals(Boolean.TRUE, s.useGfwList);
        assertEquals("chinadns-ng", s.dnsShunt);
        assertEquals("tcp", s.dnsMode);
        assertEquals("1.1.1.1", s.remoteDns);
        assertEquals(Boolean.TRUE, s.localhostProxy);
        assertEquals(Boolean.FALSE, s.clientProxy);
        // gfw=1 + chn=direct + tcp/udp=proxy → 大陆白名单
        assertEquals(PasswallApi.QuickMode.CHNROUTE, PasswallApi.quickModeOf(s));

        // 改一个键就对不上预设 → CUSTOM
        s.udpProxyMode = "disable";
        assertEquals(PasswallApi.QuickMode.CUSTOM, PasswallApi.quickModeOf(s));

        // 快捷模式写回的四个键与 LuCI 的 switch_*_mode 一致
        java.util.Map<String, String> g = PasswallApi.quickModeValues(
                PasswallApi.QuickMode.GLOBAL);
        assertEquals("0", g.get("use_gfw_list"));
        assertEquals("0", g.get("chn_list"));
        assertEquals("proxy", g.get("tcp_proxy_mode"));
        assertEquals("proxy", g.get("udp_proxy_mode"));
    }

    @Test
    public void parse_groupsSubscriptionsAndSocksPort() {
        JsonObject result = JsonParser.parseString("{\"values\":{"
                + "\"cfg010000\":{\".type\":\"global\",\"enabled\":\"1\","
                + "\"node_socks_port\":\"1071\"},"
                + "\"cfg020000\":{\".type\":\"nodes\",\"remarks\":\"节点A\","
                + "\"address\":\"1.2.3.4\",\"port\":\"443\",\"group\":\"机场甲\"},"
                + "\"cfg030000\":{\".type\":\"subscribe_list\","
                + "\"remark\":\"机场甲\",\"url\":\"https://example.com/sub\"},"
                + "\"cfg040000\":{\".type\":\"subscribe_list\","
                + "\"remark\":\"空订阅\",\"url\":\"\"}"
                + "}}").getAsJsonObject();
        PasswallApi.PwSettings s = PasswallApi.parse(result);
        assertEquals("1071", s.socksPort);
        assertEquals("机场甲", s.nodes.get(0).group);
        // url 为空的订阅段跳过
        assertEquals(1, s.subscriptions.size());
        assertEquals("机场甲", s.subscriptions.get(0).remark);
        assertEquals("cfg030000", s.subscriptions.get(0).section);
    }

    @Test
    public void parseCurlSeconds_convertsAndRejects() {
        assertEquals(Integer.valueOf(24), PasswallApi.parseCurlSeconds("0.024"));
        assertEquals(Integer.valueOf(1500), PasswallApi.parseCurlSeconds(" 1.5 "));
        assertNull(PasswallApi.parseCurlSeconds("0.000"));
        assertNull(PasswallApi.parseCurlSeconds(""));
        assertNull(PasswallApi.parseCurlSeconds(null));
        assertNull(PasswallApi.parseCurlSeconds("abc"));
    }

    @Test
    public void parse_passwall2GlobalHasOnlyCommonOptions() {
        // passwall2 全局段没有代理模式/DNS 分流键:对应字段必须为 null
        JsonObject result = JsonParser.parseString("{\"values\":{"
                + "\"cfg010000\":{\".type\":\"global\",\"enabled\":\"1\","
                + "\"node\":\"rulenode\",\"localhost_proxy\":\"1\","
                + "\"client_proxy\":\"1\",\"remote_dns\":\"8.8.8.8\"}"
                + "}}").getAsJsonObject();
        PasswallApi.PwSettings s = PasswallApi.parse(result);
        assertNull(s.tcpProxyMode);
        assertNull(s.chnList);
        assertNull(s.dnsShunt);
        assertEquals("8.8.8.8", s.remoteDns);
        assertEquals(Boolean.TRUE, s.clientProxy);
        assertEquals(PasswallApi.QuickMode.CUSTOM, PasswallApi.quickModeOf(s));
    }
}
