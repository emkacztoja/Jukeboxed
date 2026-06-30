package com.jukeboxed.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tiny HTTP server that catches the OAuth redirect from Spotify's browser flow.
 * <p>
 * Spotify redirects the user's browser to {@code http://127.0.0.1:<port>/callback?code=...&state=...}
 * after a successful authorization. This server blocks until that callback arrives
 * (or the timeout elapses), validates the state parameter against the expected
 * CSRF token, and completes a future with the authorization code.
 * <p>
 * Listens on the loopback interface only — external interfaces are explicitly
 * disabled to limit attack surface during the brief login window.
 */
final class CallbackServer {

    private static final String CALLBACK_PATH = "/callback";

    private final int requestedPort;
    private final String expectedState;
    private final long timeoutMillis;
    private HttpServer server;
    private CompletableFuture<String> result;

    CallbackServer(int port, String expectedState) {
        this(port, expectedState, TimeUnit.MINUTES.toMillis(5));
    }

    /** Visible for tests that need a tighter timeout. */
    CallbackServer(int port, String expectedState, long timeoutMillis) {
        if (expectedState == null || expectedState.isBlank()) {
            throw new IllegalArgumentException("expectedState must be a non-empty CSRF token");
        }
        this.requestedPort = port;
        this.expectedState = expectedState;
        this.timeoutMillis = timeoutMillis;
    }

    int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        result = new CompletableFuture<>();
        server.createContext(CALLBACK_PATH, this::handle);
        server.setExecutor(null); // default executor
        server.start();
        return server.getAddress().getPort();
    }

    int getPort() {
        if (server == null) {
            throw new IllegalStateException("Server has not been started");
        }
        return server.getAddress().getPort();
    }

    /** Block until the callback arrives, or the timeout elapses, completing the future exceptionally. */
    String awaitCode() {
        try {
            return result.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            stop();
            throw new AuthException("OAuth callback timed out after " + timeoutMillis + "ms", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            stop();
            throw new AuthException("Interrupted while waiting for OAuth callback", ex);
        } catch (ExecutionException ex) {
            // Unwrap the inner cause so callers see the AuthException, not CompletionException.
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new AuthException("OAuth callback failed", cause);
        }
    }

    void stop() {
        if (server != null) {
            // Stop accepting new connections immediately; in-flight handlers finish.
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) {
        try {
            URI uri = exchange.getRequestURI();
            String query = uri.getRawQuery();
            String code = extractParam(query, "code");
            String state = extractParam(query, "state");
            String error = extractParam(query, "error");

            String body;
            int status;
            if (error != null) {
                result.completeExceptionally(new AuthException("Spotify returned error: " + error));
                body = "<h1>Spotify authorization failed</h1><p>" + error + "</p>";
                status = 400;
            } else if (code == null) {
                result.completeExceptionally(new AuthException("Callback missing 'code' parameter"));
                body = "<h1>Missing authorization code</h1>";
                status = 400;
            } else if (!expectedState.equals(state)) {
                result.completeExceptionally(new AuthException("State mismatch — possible CSRF"));
                body = "<h1>State mismatch</h1>";
                status = 400;
            } else {
                result.complete(code);
                body = """
                        <html><body style="font-family:sans-serif;text-align:center;padding:48px">
                        <h1>You can close this tab and return to Minecraft.</h1>
                        <p style="color:#666">Jukeboxed has captured the authorization code.</p>
                        </body></html>
                        """;
                status = 200;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ex) {
            result.completeExceptionally(new AuthException("Failed to respond to OAuth callback", ex));
        } catch (RuntimeException ex) {
            result.completeExceptionally(ex);
        } finally {
            exchange.close();
        }
    }

    private static String extractParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq);
            if (!key.equals(name)) continue;
            String value = pair.substring(eq + 1);
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return null;
    }
}
