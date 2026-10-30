package com.boss.pvp.flag;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlagConfigDefaultsTest {
    @Test
    void reportsRequireLocalConfiguration() {
        assertEquals("", FlagConfig.DEFAULT_WEBHOOK);
        assertEquals("", FlagConfig.DUAL_WEBHOOK);
    }
}
