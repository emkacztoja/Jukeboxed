package com.jukeboxed.auth;

import com.google.gson.Gson;
import com.jukeboxed.JukeboxedMod;
import com.jukeboxed.config.JukeboxedConfig;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates the Spotify OAuth (PKCE) flow, token persistence, and silent refresh.
 * <p>
 * <strong>State machine:</strong>
 * <pre>
 *     LOGGED_OUT  ──login()──&gt;  AWAITING_CALLBACK  ──callback──&gt;  LOGGED_IN
 *         ▲                                                       │
 *         └──────────────────── logout() / token loss ─────────────┘
 * </pre>
 * <p>
 * All network calls run on virtual threads via
 * {@link Thread#ofVirtual()}. The {@link HttpClient} uses a 10-second connect
 * timeout to avoid hanging the login flow on a wedged DNS lookup.
 * <p>
 * Tokens are never logged — see {@link SafeLog}.
 */
public final class AuthAgent {

    /** Spotify application client_id. Override before shipping by editing {@link JukeboxedConfig}. */
    public static final String CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID_HERE";

    public static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    public static final String TOKEN_URL_DEFAULT = "https://accounts.spotify.com/api/token";
    public static final String REDIRECT_SCHEME = "http";
    public static final String REDIRECT_HOST   = "127.0.0.1";

    public static final String SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing streaming";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private static final AuthAgent INSTANCE = new AuthAgent();

    private final AtomicReference<AuthState> state = new AtomicReference<>(AuthState.LOGGED_OUT);
    private final AtomicReference<SpotifyToken> token = new AtomicReference<>();
    /** Lazy: never call FabricLoader during class-load, since JUnit tests don't initialize it. */
    private TokenStorage storage;
    /** Default token endpoint; redirected to a mock by tests. */
    private String tokenUrl = TOKEN_URL_DEFAULT;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Gson gson = new Gson();

    /** Holds the most recently constructed authorize URL so the UI can surface it when no browser is available. */
    private final AtomicReference<String> lastAuthorizeUrl = new AtomicReference<>();

    private AuthAgent() {
    }

    public static AuthAgent get() {
        return INSTANCE;
    }

    /** Test-only: swap the token-storage backend. Production code never calls this. */
    void swapStorageForTest(TokenStorage replacement) {
        this.storage = replacement;
    }

    /** Lazy accessor so the singleton's class-load doesn't trip on FabricLoader. */
    private TokenStorage storage() {
        TokenStorage s = storage;
        if (s == null) {
            synchronized (this) {
                s = storage;
                if (s == null) {
                    s = new TokenStorage();
                    storage = s;
                }
            }
        }
        return s;
    }

    /** Test-only: redirect token endpoint URL so we can point at a mock server. */
    void setTokenUrlForTest(String url) {
        this.tokenUrl = url;
    }

    /** Test-only: invoke the post-callback half of the login flow with a fake code. */
    SpotifyToken exchangeCodeForTest(String code, String verifier, String redirectUri) {
        return exchangeCode(code, verifier, redirectUri, effectiveClientId());
    }

    /** Test-only: reset singleton state between tests. */
    void resetForTest() {
        if (storage != null) {
            storage.delete();
        }
        token.set(null);
        state.set(AuthState.LOGGED_OUT);
        lastAuthorizeUrl.set(null);
    }

    public AuthState getState() {
        return state.get();
    }

    /** Currently valid access token, refreshing it first if it's within 60s of expiry. */
    public String getAccessToken() {
        SpotifyToken current = token.get();
        if (current == null) {
            throw new AuthException("Not logged in");
        }
        if (System.currentTimeMillis() + 60_000 >= current.expires_at()) {
            current = refreshBlocking(current);
            token.set(current);
        }
        return current.access_token();
    }

    /** Surface the URL the user must visit to complete login (useful when browser failed to launch). */
    public String getLastAuthorizeUrl() {
        return lastAuthorizeUrl.get();
    }

    /**
     * Drive the full PKCE login flow on a virtual thread. Returns once the user has
     * authorized (or the callback times out). State transitions are visible via
     * {@link #getState()}.
     */
    public CompletableFuture<Void> login() {
        if (!state.compareAndSet(AuthState.LOGGED_OUT, AuthState.AWAITING_CALLBACK)) {
            return CompletableFuture.failedFuture(
                    new AuthException("Cannot start login from state " + state.get()));
        }
        lastAuthorizeUrl.set(null);
        Thread vt = Thread.ofVirtual().name("jukeboxed-auth").unstarted(() -> {
            try {
                String verifier = PkceChallenge.generateCodeVerifier();
                String challenge = PkceChallenge.deriveCodeChallenge(verifier);
                String csrf = PkceChallenge.generateCodeVerifier(); // reuse secure random helper
                String clientId = effectiveClientId();

                CallbackServer callback = new CallbackServer(0, csrf);
                int port;
                try {
                    port = callback.start();
                } catch (IOException ex) {
                    throw new AuthException("Could not bind local callback server", ex);
                }
                String redirectUri = "%s://%s:%d/callback".formatted(REDIRECT_SCHEME, REDIRECT_HOST, port);

                String authorizeUrl = AUTHORIZE_URL
                        + "?client_id=" + enc(clientId)
                        + "&response_type=code"
                        + "&redirect_uri=" + enc(redirectUri)
                        + "&code_challenge_method=S256"
                        + "&code_challenge=" + enc(challenge)
                        + "&state=" + enc(csrf)
                        + "&scope=" + enc(SCOPES);
                lastAuthorizeUrl.set(authorizeUrl);

                openBrowser(authorizeUrl);

                String code = callback.awaitCode();
                callback.stop();

                SpotifyToken fresh = exchangeCode(code, verifier, redirectUri, clientId);
                storage().save(fresh);
                token.set(fresh);
                state.set(AuthState.LOGGED_IN);
                SafeLog.info("Login completed. Token expires at {}", fresh.expires_at());
            } catch (RuntimeException ex) {
                state.set(AuthState.LOGGED_OUT);
                SafeLog.warn("Login aborted: {}", ex.getMessage());
                throw ex;
            }
        });
        vt.start();
        return CompletableFuture.runAsync(() -> {
            try {
                vt.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AuthException("Interrupted while waiting for login to complete", ex);
            }
        });
    }

    /** Attempt to load a previously persisted session. */
    public void loadFromDisk() {
        try {
            SpotifyToken saved = storage().load();
            token.set(saved);
            // Refresh silently if already expired; otherwise mark logged in.
            if (System.currentTimeMillis() >= saved.expires_at()) {
                SpotifyToken refreshed = refreshBlocking(saved);
                storage().save(refreshed);
                token.set(refreshed);
            }
            state.set(AuthState.LOGGED_IN);
            SafeLog.info("Restored session; access token expires at {}", saved.expires_at());
        } catch (TokenStorage.TokenStorageException ex) {
            // Missing file, or token encrypted under a different machine UUID — both treated as "logged out".
            SafeLog.info("No usable session on disk: {}", ex.getMessage());
            state.set(AuthState.LOGGED_OUT);
        }
    }

    /** Clear persisted credentials and return to LOGGED_OUT. */
    public void logout() {
        storage().delete();
        token.set(null);
        state.set(AuthState.LOGGED_OUT);
    }

    private SpotifyToken exchangeCode(String code, String verifier, String redirectUri, String clientId) {
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(clientId)
                + "&code_verifier=" + enc(verifier);
        return postForm(form).asToken();
    }

    private SpotifyToken refreshBlocking(SpotifyToken current) {
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + enc(current.refresh_token())
                + "&client_id=" + enc(effectiveClientId());
        TokenResponse resp = postForm(form);
        SpotifyToken refreshed = new SpotifyToken(
                resp.access_token(),
                resp.refresh_token() != null ? resp.refresh_token() : current.refresh_token(),
                System.currentTimeMillis() + resp.expires_in() * 1000L
        );
        storage().save(refreshed);
        return refreshed;
    }

    private TokenResponse postForm(String formBody) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new AuthException("Spotify token endpoint returned HTTP " + resp.statusCode() + ": " + resp.body());
            }
            TokenResponse parsed = gson.fromJson(resp.body(), TokenResponse.class);
            if (parsed == null || parsed.access_token() == null || parsed.expires_in() <= 0) {
                throw new AuthException("Spotify token endpoint returned an unexpected payload");
            }
            return parsed;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AuthException("Token endpoint request failed", ex);
        }
    }

    /** Fall back to the placeholder constant if the config field still reads the placeholder. */
    private static String effectiveClientId() {
        String configured = JukeboxedConfig.get().auth.clientId;
        if (configured != null && !configured.isBlank() && !"YOUR_SPOTIFY_CLIENT_ID_HERE".equals(configured)) {
            return configured;
        }
        return CLIENT_ID;
    }

    private static void openBrowser(String url) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            SafeLog.warn("No desktop browser available — print this URL manually: {}", url);
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (IOException ex) {
            SafeLog.warn("Desktop.browse failed: {}", ex.getMessage());
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public enum AuthState { LOGGED_OUT, AWAITING_CALLBACK, LOGGED_IN }

    /** DTO for the Spotify token-endpoint JSON. */
    record TokenResponse(String access_token, String refresh_token, int expires_in, String scope, String token_type) {
        SpotifyToken asToken() {
            return new SpotifyToken(access_token, refresh_token, System.currentTimeMillis() + expires_in * 1000L);
        }
    }
}
