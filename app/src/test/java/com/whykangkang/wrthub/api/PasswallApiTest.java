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
    public void parseUseTimeMs_readsLuciConnectStatus() {
        assertEquals(Integer.valueOf(37),
                PasswallApi.parseUseTimeMs("{\"use_time\":\"36.51\",\"http_code\":204}"));
        assertNull(PasswallApi.parseUseTimeMs("{\"use_time\":\"\"}"));
        assertNull(PasswallApi.parseUseTimeMs("{\"use_time\":\"0.00\"}"));
        assertNull(PasswallApi.parseUseTimeMs("{}"));
        assertNull(PasswallApi.parseUseTimeMs("not json"));
    }

    @Test
    public void parseComponentStatus_readsIndexStatus() {
        PasswallApi.ComponentStatus c = PasswallApi.parseComponentStatus(
                "{\"tcp_status\":true,\"udp_status\":false,"
                        + "\"dns_mode_status\":true,\"haproxy_status\":\"0\"}");
        assertEquals(Boolean.TRUE, c.tcp);
        assertEquals(Boolean.FALSE, c.udp);
        assertEquals(Boolean.TRUE, c.dns);
        assertEquals(Integer.valueOf(0), c.haproxy);
        // 缺项保持 null(未知),坏报文不抛
        PasswallApi.ComponentStatus empty = PasswallApi.parseComponentStatus("{}");
        assertNull(empty.tcp);
        assertNull(empty.haproxy);
        assertNull(PasswallApi.parseComponentStatus("junk").tcp);
    }

    @Test
    public void parseSubscribeError_distinguishes() {
        assertNull(PasswallApi.parseSubscribeError("{\"success\":true}"));
        assertEquals("Please save first", PasswallApi.parseSubscribeError(
                "{\"success\":false,\"msg\":\"Please save first\"}"));
    }

    @Test
    public void parseWanIp_picksDefaultRouteInterface() {
        JsonObject dump = JsonParser.parseString("{\"interface\":["
                + "{\"interface\":\"lan\",\"ipv4-address\":[{\"address\":\"10.0.1.1\"}],"
                + "\"route\":[{\"target\":\"10.0.1.0\",\"nexthop\":\"0.0.0.0\"}]},"
                + "{\"interface\":\"wan\",\"ipv4-address\":[{\"address\":\"100.64.1.2\"}],"
                + "\"route\":[{\"target\":\"0.0.0.0\",\"nexthop\":\"100.64.0.1\"}]}"
                + "]}").getAsJsonObject();
        assertEquals("100.64.1.2", PasswallApi.parseWanIp(dump));
        assertNull(PasswallApi.parseWanIp(new JsonObject()));
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

    @Test
    public void parseServer_globalEnableIsServerSwitchNotClient() {
        // 服务端总开关是 global 段的 enable 键,与客户端的 enabled 互不影响
        JsonObject result = JsonParser.parseString("{\"values\":{"
                + "\"cfg010000\":{\".type\":\"global\",\"enabled\":\"0\",\"enable\":\"1\"},"
                + "\"srv1\":{\".type\":\"server\",\"enable\":\"1\",\"remarks\":\"家里\","
                + "\"type\":\"Xray\",\"protocol\":\"vless\",\"port\":\"443\"},"
                + "\"srv2\":{\".type\":\"server\",\"remarks\":\"SS\","
                + "\"type\":\"sing-box\",\"protocol\":\"shadowsocks\",\"port\":\"8388\"},"
                + "\"n1\":{\".type\":\"nodes\",\"remarks\":\"节点\",\"address\":\"1.2.3.4\"}"
                + "}}").getAsJsonObject();
        PasswallApi.ServerInfo info = PasswallApi.parseServer(result);
        assertTrue(info.serverEnabled);
        assertEquals("cfg010000", info.globalSection);
        assertEquals(2, info.servers.size());
        assertTrue(info.servers.get(0).enable);
        assertEquals("家里", info.servers.get(0).remarks);
        assertEquals("443", info.servers.get(0).port);
        // 没有 enable 键的实例 = 关
        assertTrue(!info.servers.get(1).enable);
    }

    @Test
    public void parseServer_missingEnableKeyMeansOff() {
        JsonObject result = JsonParser.parseString("{\"values\":{"
                + "\"cfg010000\":{\".type\":\"global\",\"enabled\":\"1\"}"
                + "}}").getAsJsonObject();
        PasswallApi.ServerInfo info = PasswallApi.parseServer(result);
        assertTrue(!info.serverEnabled);
        assertEquals(0, info.servers.size());
    }

    @Test
    public void serverEntry_displayTypeMatchesLuci() {
        PasswallApi.ServerEntry e = new PasswallApi.ServerEntry();
        e.type = "Xray";
        e.protocol = "vless";
        assertEquals("Xray VLESS", e.displayType());
        e.type = "sing-box";
        e.protocol = "shadowsocks";
        assertEquals("Sing-Box SS", e.displayType());
        e.type = "sing-box";
        e.protocol = "hysteria2";
        assertEquals("Sing-Box HY2", e.displayType());
        e.type = "Socks";
        e.protocol = "";
        assertEquals("Socks", e.displayType());
    }

    @Test
    public void parseServerStatus_readsBoolean() {
        assertTrue(PasswallApi.parseServerStatus("{\"index\":\"0\",\"status\":true}"));
        assertTrue(!PasswallApi.parseServerStatus("{\"index\":\"0\",\"status\":false}"));
        assertTrue(!PasswallApi.parseServerStatus("not json"));
        assertTrue(!PasswallApi.parseServerStatus("{}"));
    }

    @Test
    public void serverConfigOf_appendsServerSuffix() {
        // 服务端数据在独立配置里,不是客户端配置
        assertEquals("passwall_server", PasswallApi.serverConfigOf("passwall"));
        assertEquals("passwall2_server", PasswallApi.serverConfigOf("passwall2"));
    }

    @Test
    public void parseServer_usersAndBindings() {
        JsonObject result = JsonParser.parseString("{\"values\":{"
                + "\"global\":{\".type\":\"global\",\"enable\":\"0\"},"
                + "\"u1\":{\".type\":\"user\",\"username\":\"alice\"},"
                + "\"u2\":{\".type\":\"user\",\"username\":\"bob\"},"
                + "\"s1\":{\".type\":\"server\",\"enable\":\"1\",\"remarks\":\"多用户\","
                + "\"type\":\"Xray\",\"protocol\":\"vless\",\"port\":\"443\","
                + "\"users\":[\"u1\",\"u2\"]},"
                + "\"s2\":{\".type\":\"server\",\"enable\":\"0\",\"remarks\":\"单用户\","
                + "\"type\":\"SS-Rust\",\"port\":\"8388\",\"user\":\"u1\","
                + "\"method\":\"aes-256-gcm\"}"
                + "}}").getAsJsonObject();
        PasswallApi.ServerInfo info = PasswallApi.parseServer(result);
        assertEquals(2, info.users.size());
        assertEquals("alice", info.users.get(0).username);
        assertEquals(2, info.servers.get(0).users.size());
        assertEquals("u2", info.servers.get(0).users.get(1));
        assertEquals("u1", info.servers.get(1).user);
        // 编辑回填用的原始字段也在
        assertEquals("aes-256-gcm", info.servers.get(1).fields.get("method"));
    }

    @Test
    public void userModeFor_matchesLuciBinding() {
        assertEquals(PasswallApi.UserMode.SINGLE,
                PasswallApi.userModeFor("Socks", ""));
        assertEquals(PasswallApi.UserMode.SINGLE,
                PasswallApi.userModeFor("SS-Rust", ""));
        assertEquals(PasswallApi.UserMode.SINGLE,
                PasswallApi.userModeFor("SSR", ""));
        assertEquals(PasswallApi.UserMode.MULTI,
                PasswallApi.userModeFor("sing-box", "vmess"));
        assertEquals(PasswallApi.UserMode.NONE,
                PasswallApi.userModeFor("sing-box", "direct"));
        assertEquals(PasswallApi.UserMode.NONE,
                PasswallApi.userModeFor("sing-box", "shadowsocks"));
        assertEquals(PasswallApi.UserMode.NONE,
                PasswallApi.userModeFor("Xray", "shadowsocks"));
        assertEquals(PasswallApi.UserMode.MULTI,
                PasswallApi.userModeFor("Xray", "vless"));
        assertEquals(PasswallApi.UserMode.MULTI,
                PasswallApi.userModeFor("Hysteria2", ""));
    }

    @Test
    public void protocolsFor_onlySingboxAndXray() {
        assertEquals(14, PasswallApi.protocolsFor("sing-box").length);
        assertEquals(10, PasswallApi.protocolsFor("Xray").length);
        assertEquals(0, PasswallApi.protocolsFor("Socks").length);
        assertEquals(0, PasswallApi.protocolsFor("SSR").length);
    }
}
