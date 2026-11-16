package com.p2ppvp.mod.client.skin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SkinApiTest {
    @Test
    void parseTokensReadsMap() {
        Map<String, String> m = SkinApi.parseTokens("{\"tokens\": {\"alpha\": \"t1\", \"bravo\": \"t2\"}}");
        assertEquals(Map.of("alpha", "t1", "bravo", "t2"), m);
    }

    @Test
    void parseTokensToleratesGarbage() {
        assertTrue(SkinApi.parseTokens(null).isEmpty());
        assertTrue(SkinApi.parseTokens("not json").isEmpty());
        assertTrue(SkinApi.parseTokens("{}").isEmpty());
        assertTrue(SkinApi.parseTokens("[1,2]").isEmpty());
    }

    @Test
    void parseUploadSuccess() {
        SkinApi.UploadResult r = SkinApi.parseUpload(200, "{\"status\":\"ok\",\"token\":\"abcd1234\"}");
        assertTrue(r.ok());
        assertEquals("abcd1234", r.token());
        assertNull(r.error());
    }

    @Test
    void parseUploadServerErrorMessage() {
        SkinApi.UploadResult r = SkinApi.parseUpload(400, "{\"error\":\"skin must be exactly 64x64\"}");
        assertFalse(r.ok());
        assertEquals("skin must be exactly 64x64", r.error());
    }

    @Test
    void parseUploadFallbackMessages() {
        assertEquals("Upload failed (500)", SkinApi.parseUpload(500, "garbage").error());
        assertFalse(SkinApi.parseUpload(200, "{}").ok());
        assertFalse(SkinApi.parseUpload(429, null).ok());
    }
}
