package com.p2ppvp.mod.client.account;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountSessionTest {
    @TempDir Path dir;

    @Test void startsSignedOutWhenNoFile() {
        assertFalse(new AccountSession(dir.resolve("a.json")).signedIn());
    }

    @Test void savesAndReloads() {
        Path f = dir.resolve("sub/a.json");
        AccountSession s = new AccountSession(f);
        s.set("Steve_1", "tok123");
        AccountSession again = new AccountSession(f);
        assertTrue(again.signedIn());
        assertEquals("Steve_1", again.username());
        assertEquals("tok123", again.token());
    }

    @Test void clearSignsOutAndDeletesFile() {
        Path f = dir.resolve("a.json");
        AccountSession s = new AccountSession(f);
        s.set("Steve_1", "tok");
        s.clear();
        assertFalse(s.signedIn());
        assertFalse(Files.exists(f));
    }

    @Test void corruptFileMeansSignedOut() throws Exception {
        Path f = dir.resolve("a.json");
        Files.writeString(f, "{not json");
        assertFalse(new AccountSession(f).signedIn());
    }

    @Test void authHeaderOnlyWhenSignedIn() {
        AccountSession s = new AccountSession(dir.resolve("a.json"));
        HttpRequest r1 = s.auth(HttpRequest.newBuilder(URI.create("http://x/"))).build();
        assertTrue(r1.headers().firstValue("Authorization").isEmpty());
        s.set("Steve_1", "tok");
        HttpRequest r2 = s.auth(HttpRequest.newBuilder(URI.create("http://x/"))).build();
        assertEquals("Bearer tok", r2.headers().firstValue("Authorization").orElse(""));
    }
}
