package com.jukeboxed;

import com.jukeboxed.auth.AuthAgent;
import com.jukeboxed.config.JukeboxedConfig;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jukeboxed entry point. All mod initialization runs from here on the physical client.
 * <p>
 * Lifecycle (init order):
 *   1. Load {@link JukeboxedConfig} (persisted YAML in {@code .minecraft/config/jukeboxed/}).
 *   2. {@link AuthAgent#loadFromDisk()} attempts to restore a previously authorized session.
 * <p>
 * Server-side environments are explicitly opt-out via {@code environment: "client"} in
 * {@code fabric.mod.json}, so there is no need to gate initialization on
 * {@code FabricLoader.getInstance().getEnvironmentType()}.
 */
public final class JukeboxedMod implements ClientModInitializer {

    public static final String MOD_ID = "jukeboxed";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Jukeboxed] Initializing…");

        // Load YAML/YACL config (creates with defaults on first launch).
        JukeboxedConfig.get().load();

        // Try to resume an existing Spotify session from encrypted tokens.bin.
        // On a fresh install the file is absent — AuthAgent stays in LOGGED_OUT.
        AuthAgent.get().loadFromDisk();

        LOGGER.info("[Jukeboxed] Ready. Auth state: {}", AuthAgent.get().getState());
    }
}
