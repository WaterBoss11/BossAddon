package com.boss.pvp.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests the dependency-free {@code tag_name} extraction from a GitHub release response ({@link ReleaseJson}). */
class ReleaseJsonTest {

    @Test
    void extractsPlainTag() {
        assertEquals("v1.9.0", ReleaseJson.extractTagName("{\"tag_name\":\"v1.9.0\",\"name\":\"1.9.0\"}"));
    }

    @Test
    void toleratesWhitespaceAroundColon() {
        assertEquals("v1.2.3", ReleaseJson.extractTagName("{ \"tag_name\" :  \"v1.2.3\" }"));
    }

    @Test
    void picksTagNameNotOtherFields() {
        String json = "{\"url\":\"https://api.github.com/x\",\"id\":42,\"tag_name\":\"v2.0.0\",\"draft\":false}";
        assertEquals("v2.0.0", ReleaseJson.extractTagName(json));
    }

    @Test
    void returnsNullWhenAbsent() {
        assertNull(ReleaseJson.extractTagName("{\"message\":\"Not Found\"}"));
        assertNull(ReleaseJson.extractTagName(""));
        assertNull(ReleaseJson.extractTagName(null));
    }

    @Test
    void returnsNullOnJsonNullTag() {
        assertNull(ReleaseJson.extractTagName("{\"tag_name\":null}"));
    }

    @Test
    void handlesEscapedCharactersInValue() {
        // Not realistic for a tag, but must not break the scan.
        assertEquals("v1.0.0-a/b", ReleaseJson.extractTagName("{\"tag_name\":\"v1.0.0-a\\/b\"}"));
    }
}
