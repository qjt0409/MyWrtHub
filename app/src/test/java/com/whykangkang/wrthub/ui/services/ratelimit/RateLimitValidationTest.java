package com.whykangkang.wrthub.ui.services.ratelimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** eqosplus 限速表单的输入校验:目标形状、时钟、速率文本规整 */
public class RateLimitValidationTest {

    @Test
    public void isValidTarget_acceptsIpMacRangeAndCidr() {
        assertTrue(DeviceRateLimitActivity.isValidTarget("10.0.1.7"));
        assertTrue(DeviceRateLimitActivity.isValidTarget("08:fa:79:57:43:1d"));
        assertTrue(DeviceRateLimitActivity.isValidTarget("10.0.1.10-10.0.1.20"));
        assertTrue(DeviceRateLimitActivity.isValidTarget("10.0.1.0/24"));
    }

    @Test
    public void isValidTarget_rejectsGarbage() {
        assertFalse(DeviceRateLimitActivity.isValidTarget(""));
        assertFalse(DeviceRateLimitActivity.isValidTarget("10.0.1.300"));
        assertFalse(DeviceRateLimitActivity.isValidTarget("10.0.1"));
        assertFalse(DeviceRateLimitActivity.isValidTarget("hello"));
        assertFalse(DeviceRateLimitActivity.isValidTarget("10.0.1.0/33"));
        assertFalse(DeviceRateLimitActivity.isValidTarget(null));
    }

    @Test
    public void isValidClock_checksHhMm() {
        assertTrue(DeviceRateLimitActivity.isValidClock("00:00"));
        assertTrue(DeviceRateLimitActivity.isValidClock("23:55"));
        assertFalse(DeviceRateLimitActivity.isValidClock("25:00"));
        assertFalse(DeviceRateLimitActivity.isValidClock("8点"));
        assertFalse(DeviceRateLimitActivity.isValidClock(""));
    }

    @Test
    public void normalizeRate_trimsTrailingZeros() {
        assertEquals("2", DeviceRateLimitActivity.normalizeRate("2.0"));
        assertEquals("0.5", DeviceRateLimitActivity.normalizeRate("0.50"));
        assertEquals("0.1", DeviceRateLimitActivity.normalizeRate("0.1"));
        assertEquals("1000", DeviceRateLimitActivity.normalizeRate("1000"));
    }
}
