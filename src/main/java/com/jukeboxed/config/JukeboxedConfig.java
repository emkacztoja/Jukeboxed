package com.jukeboxed.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Top-level Jukeboxed configuration, persisted as JSON in
 * {@code .minecraft/config/jukeboxed/jukeboxed.json}.
 * <p>
 * Three top-level sections ({@link Auth}, {@link Playback}, {@link HUD}) mirror the
 * YACL screen layout. UI rendering is handled separately by
 * {@code JukeboxedConfigScreen}; this class only owns the data and round-trip I/O.
 */
public final class JukeboxedConfig {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private static final JukeboxedConfig INSTANCE = new JukeboxedConfig();

    public Auth auth = new Auth();
    public Playback playback = new Playback();
    public HUD hud = new HUD();

    private JukeboxedConfig() {}

    public static JukeboxedConfig get() {
        return INSTANCE;
    }

    /** Load the config from disk. Falls back to defaults if the file is absent or unreadable. */
    public void load() {
        Path file = configFile();
        if (!Files.exists(file)) {
            save();
            return;
        }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JukeboxedConfig loaded = GSON.fromJson(reader, JukeboxedConfig.class);
            if (loaded != null) {
                this.auth = loaded.auth != null ? loaded.auth : new Auth();
                this.playback = loaded.playback != null ? loaded.playback : new Playback();
                this.hud = loaded.hud != null ? loaded.hud : new HUD();
            }
        } catch (IOException ex) {
            // Swallow — defaults remain in effect. AuthAgent will surface a clearer error later.
            System.err.println("[Jukeboxed] Failed to read config, using defaults: " + ex.getMessage());
        }
    }

    /** Atomically write the current config to disk (write-temp + rename). */
    public void save() {
        Path file = configFile();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write Jukeboxed config", ex);
        }
    }

    private static Path configFile() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("jukeboxed")
                .resolve("jukeboxed.json");
    }

    /** Auth section — Phase 1: Spotify client_id; Phase 5 will add a Connect action. */
    public static final class Auth {
        /**
         * Spotify application client_id (public; safe to commit). Obtain one at
         * https://developer.spotify.com/dashboard — register a new app with the
         * redirect URI set to {@code http://127.0.0.1:<port>/callback}.
         */
        public String clientId = "YOUR_SPOTIFY_CLIENT_ID_HERE";
        /** Spotify scopes. Keep in sync with AuthAgent.SCOPES. */
        public String scopes = "user-read-playback-state user-modify-playback-state user-read-currently-playing streaming";
        public boolean autoplayOnLogin = true;
    }

    /** Playback section — Phase 4 will populate. */
    public static final class Playback {
        public int pollIntervalSeconds = 30;
        public int spotifyVolumePercent = 80;
        public boolean autoplay = true;
    }

    /** HUD section — Phase 5 will populate. */
    public static final class HUD {
        public boolean enabled = true;
        public int cornerX = 16;
        public int cornerY = 16;
        public int scalePercent = 100;
    }
}
