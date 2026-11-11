package com.whykangkang.wrthub.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

/**
 * wanstats 数据解析。报文形状取自自家 rpcd 插件
 * (qjt0409/luci-app-bandwidthd 的 root/usr/libexec/rpcd/wanstats)。
 */
public class WanstatsApiTest {

    private static final String HISTORY_JSON = "{"
            + "\"device\":\"pppoe-wan\",\"updated\":1794120000,"
            + "\"daily\":["
            + "{\"date\":\"2026-11-11\",\"rx\":128849018,\"tx\":85899345},"
            + "{\"date\":\"2026-11-10\",\"rx\":214748364,\"tx\":107374182}],"
            + "\"monthly\":["
            + "{\"month\":\"2026-11\",\"rx\":343597382,\"tx\":193273527},"
            + "{\"month\":\"2026-10\",\"rx\":1073741824,\"tx\":536870912}]"
            + "}";

    @Test
    public void parseHistory_readsDeviceAndEntries() {
        JsonObject json = JsonParser.parseString(HISTORY_JSON).getAsJsonObject();
        WanstatsApi.History h = WanstatsApi.parseHistory(json);

        assertEquals("pppoe-wan", h.device);
        assertEquals(1794120000L, h.updated);
        assertEquals(2, h.daily.size());
        assertEquals(2, h.monthly.size());

        WanstatsApi.Entry today = h.daily.get(0);
        assertEquals("2026-11-11", today.label);
        assertEquals(128849018L, today.rx);
        assertEquals(85899345L, today.tx);
        assertEquals(128849018L + 85899345L, today.total());

        WanstatsApi.Entry month = h.monthly.get(0);
        assertEquals("2026-11", month.label);
        assertEquals(343597382L, month.rx);
    }

    @Test
    public void parseHistory_emptyAndMissingFieldsAreSafe() {
        WanstatsApi.History empty = WanstatsApi.parseHistory(new JsonObject());
        assertTrue(empty.daily.isEmpty());
        assertTrue(empty.monthly.isEmpty());
        assertEquals("", empty.device);
        assertEquals(0L, empty.updated);

        WanstatsApi.History nullSafe = WanstatsApi.parseHistory(null);
        assertTrue(nullSafe.daily.isEmpty());
    }
}
