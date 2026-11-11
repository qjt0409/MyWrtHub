package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

/**
 * EqosApi 解析:报文形状是 uci get eqosplus 的返回,字段以 luci-app-eqosplus
 * 1.3.0 的默认配置与源码为准(全局段 type=eqosplus;设备段的启用键是
 * enable、目标键是 mac、速率单位 MB/s 可带小数)。
 */
public class EqosApiTest {

    @Test
    public void parse_readsEqosplusSchema() {
        JsonObject result = JsonParser.parseString("{"
                + "\"values\":{"
                + "\"cfg01a8fc\":{\".type\":\"eqosplus\",\".name\":\"cfg01a8fc\","
                + "\"enabled\":\"1\",\"download\":\"1000\",\"upload\":\"30\",\"ifname\":\"1\"},"
                + "\"cfg02a8fc\":{\".type\":\"device\",\".name\":\"cfg02a8fc\","
                + "\"enable\":\"0\",\"mac\":\"10.0.1.7\",\"download\":\"0.5\",\"upload\":\"0.1\","
                + "\"comment\":\"电视\",\"timestart\":\"08:00\",\"timeend\":\"22:00\","
                + "\"week\":\"1,2,3,4,5\"},"
                + "\"cfg03a8fc\":{\".type\":\"device\",\".name\":\"cfg03a8fc\","
                + "\"enable\":\"1\",\"mac\":\"10.0.1.10-10.0.1.20\",\"download\":\"2\","
                + "\"upload\":\"1\",\"week\":\"0\"}"
                + "}}").getAsJsonObject();
        EqosApi.EqosSettings s = EqosApi.parse(result);

        assertTrue(s.global.enabled);
        assertEquals("cfg01a8fc", s.global.section);
        assertEquals("1000", s.global.download);
        assertEquals("30", s.global.upload);
        assertEquals("1", s.global.ifname);

        assertEquals(2, s.devices.size());
        // 目标排序:单个 IP 在 IP 段前面
        assertEquals("10.0.1.7", s.devices.get(0).target);
        // enable=0 必须如实读成停用(键名是 enable 不是 enabled)
        assertFalse(s.devices.get(0).enabled);
        // 小数速率不能被整数化
        assertEquals("0.5", s.devices.get(0).download);
        assertEquals("0.1", s.devices.get(0).upload);
        assertEquals("电视", s.devices.get(0).comment);
        assertEquals("08:00", s.devices.get(0).timeStart);
        assertEquals("1,2,3,4,5", s.devices.get(0).week);
        assertTrue(s.devices.get(1).enabled);
        // 缺省时段补固件默认值
        assertEquals("00:00", s.devices.get(1).timeStart);
        assertEquals("23:55", s.devices.get(1).timeEnd);
    }

    @Test
    public void parse_skipsDevicelessSections() {
        JsonObject result = JsonParser.parseString("{"
                + "\"values\":{"
                + "\"cfg01\":{\".type\":\"device\",\"enable\":\"1\",\"download\":\"1\"}"
                + "}}").getAsJsonObject();
        EqosApi.EqosSettings s = EqosApi.parse(result);
        // 没有目标(mac)的设备段是坏数据,要跳过
        assertEquals(0, s.devices.size());
    }
}
