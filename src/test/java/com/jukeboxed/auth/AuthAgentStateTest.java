package com.jukeboxed.auth;

import com.jukeboxed.JukeboxedMod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;

class AuthAgentStateTest {

    @TempDir
    Path tmp;

    /** Mocked Spotify token endpoint — returns a valid token JSON. */
    private HttpServer mockTokenEndpoint;

    @BeforeEach
    void setUp() throws IOException {
        // Pin the machine UUID so PBKDF2 is deterministic across the test classpath.
        MachineIdProvider.setOverride("machine-uuid-test");

        // Spin up a mock Spotify token endpoint on a random port.
        mockTokenEndpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = mockTokenEndpoint.getAddress().getPort();
        mockTokenEndpoint.createContext("/api/token", exchange -> {
            try (exchange) {
                byte[] body = """
                        {"access_token":"BQD-test-access","refresh_token":"AQD-test-refresh","expires_in":3600,"token_type":"Bearer"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        mockTokenEndpoint.start();

        // Point AuthAgent's token-url field at our mock. The login() flow still uses
        // the real authorize URL shape but resolves the code locally — see DirectLogin.
        AuthAgent.get().setTokenUrlForTest("http://127.0.0.1:" + port + "/api/token");
    }

    @AfterEach
    void tearDown() {
        if (mockTokenEndpoint != null) {
            mockTokenEndpoint.stop(0);
        }
        AuthAgent.get().resetForTest();
        MachineIdProvider.invalidateCache();
    }

    @Test
    void initialStateIsLoggedOut() {
        AuthAgent.get().resetForTest();
        assertEquals(AuthAgent.AuthState.LOGGED_OUT, AuthAgent.get().getState());
    }

    @Test
    void directCodeExchangeLandsInLoggedIn() throws Exception {
        AuthAgent.get().resetForTest();
        AuthAgent.get().swapStorageForTest(new TokenStorage(tmp.resolve("direct-flow.bin")));

        // Drive only the post-callback half of login() — this test doesn't need a real browser.
        // exchangeCode() is package-private; the test sits in the same package.
        SpotifyToken fresh = AuthAgent.get().exchangeCodeForTest(
                "dummy-code", "verifier", "http://127.0.0.1/callback");
        assertEquals("BQD-test-access", fresh.access_token());
        assertFalse(fresh.isExpired());
    }

    @Test
    void loadFromDiskRecoversLoggedInState() {
        AuthAgent.get().resetForTest();
        Path blob = tmp.resolve("tokens-restored.bin");
        TokenStorage prep = new TokenStorage(blob);
        long expires = System.currentTimeMillis() + 3_600_000L;
        prep.save(new SpotifyToken("restored-access", "restored-refresh", expires));
        AuthAgent.get().swapStorageForTest(prep);

        AuthAgent.get().loadFromDisk();
        assertEquals(AuthAgent.AuthState.LOGGED_IN, AuthAgent.get().getState());
        assertEquals("restored-access", AuthAgent.get().getAccessToken());
    }

    @Test
    void loadFromDiskWithNoFileLeavesStateLoggedOut() {
        AuthAgent.get().resetForTest();
        AuthAgent.get().swapStorageForTest(new TokenStorage(tmp.resolve("nothere.bin")));
        AuthAgent.get().loadFromDisk();
        assertEquals(AuthAgent.AuthState.LOGGED_OUT, AuthAgent.get().getState());
    }

    @Test
    void logoutClearsStateAndFile() {
        AuthAgent.get().resetForTest();
        Path blob = tmp.resolve("tokens-logout.bin");
        TokenStorage prep = new TokenStorage(blob);
        long expires = System.currentTimeMillis() + 3_600_000L;
        prep.save(new SpotifyToken("access", "refresh", expires));
        AuthAgent.get().swapStorageForTest(prep);
        AuthAgent.get().loadFromDisk(); // makes state LOGGED_IN
        assertEquals(AuthAgent.AuthState.LOGGED_IN, AuthAgent.get().getState());
        AuthAgent.get().logout();
        assertEquals(AuthAgent.AuthState.LOGGED_OUT, AuthAgent.get().getState());
        assertFalse(blob.toFile().exists(), "logout must remove the persisted token file");
    }

    @Test
    void getAccessTokenWithoutLoginThrows() {
        AuthAgent.get().resetForTest();
        assertThrows(AuthException.class, () -> AuthAgent.get().getAccessToken());
    }

    /** Suppress SLF4J NoBinding warnings — touching the logger forces a binding initialization. */
    static { JukeboxedMod.LOGGER.atInfo().log("AuthAgentStateTest loaded"); }
}
