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

## Implementation Roadmap

Progress is tracked inline via markdown checkboxes. Tick a phase once its **Goal** is met and merged to `main`. Each phase ends at a verifiable milestone before the next starts.

### Phase 0 — Fabric Scaffold + Config Skeleton
- [ ] Set up `build.gradle` (Fabric Loom), `gradle.properties`, `settings.gradle`, `fabric.mod.json`
- [ ] Create empty `com.jukeboxed.JukeboxedMod` implementing `ClientModInitializer`
- [ ] Stub `com.jukeboxed.config.JukeboxedConfig` with empty YACL categories (Auth, Playback, HUD)
- [ ] Add token-aware logger helper (no `access_token` / `refresh_token` ever logged)
- [ ] Stub `jukeboxed.client.mixins.json`
- **Goal:** `./gradlew build` produces a JAR; `./gradlew runClient` launches Minecraft with the mod loaded; the mod menu opens to an empty (but present) Jukeboxed config screen.

### Phase 1 — Auth Agent + Encrypted Token Storage
- [ ] `com.jukeboxed.auth.AuthAgent`: PKCE code-verifier + S256 challenge generation
- [ ] Local HTTP server (built-in `com.sun.net.httpserver.HttpServer`) on a random port for the OAuth redirect callback
- [ ] `MachineIdProvider`: cross-platform hardware UUID lookup
  - Windows: `wmic csproduct get uuid`
  - Linux: `/var/lib/dbus/machine-id` or `/etc/machine-id`
  - macOS: `ioreg -rd1 -c IOPlatformExpertDevice | grep IOPlatformUUID`
- [ ] `TokenStorage`: AES-GCM encryption; key derived via PBKDF2 (≥100k iterations, SHA-256) from the machine UUID
- [ ] Auth state machine: `LOGGED_OUT → AWAITING_CALLBACK → LOGGED_IN`
- [ ] Unit tests for token encrypt/decrypt round-trip (no live network)
- **Goal:** clicking "Connect Spotify" in the config screen completes PKCE in the browser, returns to Minecraft, reaches `LOGGED_IN`, and the encrypted token survives a client restart.

### Phase 2 — Spotify API Client
- [ ] `com.jukeboxed.spotify.SpotifyClient` using `java.net.http.HttpClient` (no OkHttp — virtual threads replace reactive stacks)
- [ ] Endpoints: `getCurrentlyPlaying`, `searchTracks`, `getRecommendations`, `play`, `pause`, `skipNext`, `transferPlayback`
- [ ] Token refresh interceptor: on 401, refresh and retry once
- [ ] Retry/backoff: 429 honors `Retry-After`; 5xx retries up to 3x with exponential backoff
- [ ] JSON via Gson
- [ ] WireMock-based unit tests covering happy-path, 401-refresh, 429, and 5xx
- **Goal:** the client can fetch the current track, search by keyword, and control playback; all behavior is covered by mocked unit tests (no live API calls in CI).

### Phase 3 — Watcher + DJ Agents + Event Bus
- [ ] `com.jukeboxed.events.JukeboxedEvents`: lightweight custom event bus (typed event records, no Fabric dependency)
- [ ] `com.jukeboxed.agent.WatcherAgent` registers `ClientTickEvents.END_CLIENT_TICK`; reads biome, weather, dimension, time-of-day, player activity (held item + movement)
- [ ] State throttling: emit `GameStateChangedEvent` only when state meaningfully changes (not every tick)
- [ ] `com.jukeboxed.agent.DJAgent` subscribes to `GameStateChangedEvent` on a virtual thread; maps state → "vibe" (e.g., forest+day → chill acoustic, nether+storm → heavy)
- [ ] Debounce: do not switch tracks more than once per N minutes, except on dimension change
- [ ] Unit tests for vibe mapping (pure function over `GameState` records)
- **Goal:** walking from plains → forest triggers a track swap within ~10s; sitting still does not thrash the queue.

### Phase 4 — Playback Manager + Album Art Cache
- [ ] `com.jukeboxed.playback.PlaybackManager` virtual-thread loop: polls current track every N seconds, detects track-end, advances queue
- [ ] Album art download on a virtual thread: HTTP GET → write to `.minecraft/config/jukeboxed/cache/{trackId}.jpg` if missing
- [ ] LRU eviction on cache directory once it exceeds configurable size (default 500 MB)
- [ ] Config: autoplay, Spotify volume, crossfade duration
- **Goal:** playback continues unattended, album art files accumulate on disk and are available to the HUD without re-downloading.

### Phase 5 — HUD Overlay
- [ ] `HudRenderEvents` registration; render with `DrawContext` (no `MatrixStack`)
- [ ] Layout: bottom-left; album art + track name + artist
- [ ] Album art loaded into `DynamicTexture` off the main thread (read cached JPEG, decode, hand to renderer)
- [ ] Position, scale, and visibility toggle in `JukeboxedConfig`
- [ ] No HUD rendering when no active Spotify session
- **Goal:** track info appears in-game within ~1s of a new track starting; configurable to disable.

### Phase 6 — Sound Engine Ducking (Mixin)
- [ ] `jukeboxed.client.mixins.json` + mixin into `SoundEngine` for master-volume control
- [ ] When Spotify is playing, scale `SoundEngine` master to configurable value (default 30%); restore on pause/disconnect
- [ ] Manual test in-game: with and without Spotify active
- **Goal:** in-game music ducks under Spotify; no audio regressions on disconnect.

### Phase 7 — Polish & Release
- [ ] Error toasts for auth failures and API outages
- [ ] Disconnect button in the config screen
- [ ] README screenshots
- [ ] GitHub release with attached JAR from `./gradlew build`
- [ ] Modrinth / CurseForge listing (optional)
- **Goal:** a fresh user can install the JAR, connect Spotify, and have a working DJ in under 5 minutes.

## Architectural Decisions (locked in for the project)
- **HTTP:** `java.net.http.HttpClient` (built-in). Virtual threads make reactive stacks (OkHttp + coroutines, Reactor) unnecessary.
- **JSON:** Gson. Moshi is faster but Gson is fine for a mod and has wider tutorial coverage.
- **OAuth callback server:** built-in `com.sun.net.httpserver.HttpServer` (no Jetty/Undertow dependency).
- **Crypto:** AES-GCM (authenticated encryption, prevents tampering); PBKDF2-HMAC-SHA256, ≥100k iterations for key derivation.
- **State sampling:** `ClientTickEvents.END_CLIENT_TICK` (post-tick, safe to read world state).