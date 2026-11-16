package com.p2ppvp.mod.client.social;

import static org.junit.jupiter.api.Assertions.*;

import com.p2ppvp.mod.client.social.SocialModels.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SocialApiTest {
    private HttpServer server;
    private SocialApi api;
    private volatile String lastBody = "";
    private volatile String lastPath = "";

    private void route(String path, int status, String reply) {
        server.createContext(path, ex -> {
            lastPath = ex.getRequestURI().toString();
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
    }

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        api = new SocialApi("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void onlinePlayersParsesAndEncodesId() throws Exception {
        route("/api/players/online", 200, "{\"players\":[{\"player_id\":\"B\"}]}");
        ApiResult<List<OnlinePlayer>> r = api.onlinePlayers("a b").get(5, TimeUnit.SECONDS);
        assertTrue(r.ok());
        assertEquals(1, r.value().size());
        assertTrue(lastPath.contains("player_id=a+b"), lastPath);
    }

    @Test void sendChallengePostsKit() throws Exception {
        route("/api/challenge/send", 200, "{\"challenge_id\":\"c1\"}");
        ApiResult<String> r = api.sendChallenge("A", "B", new KitRef("official", "Pot")).get(5, TimeUnit.SECONDS);
        assertTrue(r.ok());
        assertEquals("c1", r.value());
        assertTrue(lastBody.contains("\"name\":\"Pot\""), lastBody);
    }

    @Test void cooldownErrorSurfacesRetryAfter() throws Exception {
        route("/api/challenge/send", 429, "{\"error\":\"cooldown\",\"code\":\"cooldown\",\"retry_after\":12.5}");
        ApiResult<String> r = api.sendChallenge("A", "B", new KitRef("official", "Pot")).get(5, TimeUnit.SECONDS);
        assertFalse(r.ok());
        assertEquals("cooldown", r.code());
        assertEquals(12.5, r.retryAfter());
    }

    @Test void unreachableServerFailsSoft() throws Exception {
        SocialApi dead = new SocialApi("http://127.0.0.1:1");
        ApiResult<List<LiveMatch>> r = dead.liveMatches().get(8, TimeUnit.SECONDS);
        assertFalse(r.ok());
        assertEquals("network", r.code());
    }

    @Test void spectateJoinReturnsRawPayload() throws Exception {
        route("/api/spectate/join", 200, "{\"role\":\"spectator\",\"host_ip\":\"100.1.2.3\"}");
        ApiResult<String> r = api.spectateJoin("S", "m1").get(5, TimeUnit.SECONDS);
        assertTrue(r.ok());
        assertEquals("100.1.2.3", SocialJson.field(r.value(), "host_ip"));
    }
}
