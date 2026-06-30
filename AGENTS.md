# AGENTS.md - Jukeboxed AI Coding Guidelines

You are an AI coding assistant working on **Jukeboxed**, a client-side Minecraft mod that acts as a context-aware algorithmic DJ using the Spotify API.

## Core Tech Stack
* **Target Environment:** Minecraft 26.1.2, Fabric Modloader.
* **Language:** Java 25.
* **Build System:** Gradle (Fabric Loom).
* **UI/Config:** Yet Another Config Lib (YACL).

## Package Layout & Entry Points
* **Mod ID:** `jukeboxed`
* **Root Package:** `com.jukeboxed`
* **Entry Point:** `com.jukeboxed.JukeboxedMod` (Implements `ClientModInitializer`). All initialization logic starts here.

## Architecture & Threading Rules
* **Strict Render Rule:** NEVER block the main Minecraft render thread. Any network requests, image processing, or heavy computations must be offloaded.
* **Virtual Threads:** Leverage Java 25 Virtual Threads (`Thread.ofVirtual().start()`) for all Spotify REST API calls and background polling.
* **Event Bus Routing:** 
  * Use standard Fabric `ClientTickEvents` for the WatcherAgent to read game state.
  * For internal cross-agent communication (e.g., sending state changes to the DJ Agent), use a custom lightweight event bus located at `com.jukeboxed.events.JukeboxedEvents`.

## Configuration & Auth (YACL)
* **Config Location:** Configuration logic lives in `com.jukeboxed.config.JukeboxedConfig`. 
* **Auth Agent Location:** `com.jukeboxed.auth.AuthAgent` (owns the `client_id` constant and all PKCE flow logic).
* **Auth Setup (PKCE):** Jukeboxed uses the PKCE flow. There is **no** `client_secret`. The `client_id` should be stored as a `public static final String` constant in the AuthAgent. User tokens (`access_token`, `refresh_token`) must be persisted locally in the `.minecraft/config/jukeboxed/` directory, **encrypted with AES using a key derived from a machine-specific identifier (e.g., the OS hardware UUID)**. This blocks casual scraping by other mods or scripts — note that client-side obfuscation cannot stop a dedicated reverse-engineer, and that is an accepted threat-model boundary.
* **Do not** log user tokens to the Minecraft console under any circumstances.

## Mixins
* **Mixin Config:** `jukeboxed.client.mixins.json` (client-only — Jukeboxed has no server-side footprint; do not inject into dedicated server environments).
* Only use mixins when standard Fabric API events fall short. Expected mixin targets for V1 will likely revolve around `SoundEngine` (if we need to duck in-game music while Spotify plays). Keep mixins strictly isolated in `com.jukeboxed.mixin`.

## Rendering (Minecraft 26.1.2)
* Use `DrawContext` for all HUD rendering, as `MatrixStack` is deprecated for direct drawing in this version.
* **Async Textures:** Album art must be downloaded asynchronously, cached to disk (`.minecraft/config/jukeboxed/cache/`), and loaded into a `DynamicTexture` to prevent UI stuttering.

## Testing & PR Guidelines
* **Testing Stack:** JUnit 5 for unit tests, Mockito for mocking the Spotify API responses. 
* Do not write tests that hit the live Spotify API.
* **Commits:** Use conventional commits (e.g., `feat:`, `fix:`, `refactor:`). Squash and merge on PRs.

## Development Commands
* Build the mod: `./gradlew build`
* Run the test client: `./gradlew runClient`
* Update Mixins: `./gradlew generateMixins`