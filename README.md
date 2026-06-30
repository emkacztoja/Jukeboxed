# Jukeboxed

A client-side Minecraft mod that acts as a context-aware algorithmic DJ using the Spotify API. Jukeboxed watches what's happening in your world and queues up music that fits the moment.

> **Status:** Pre-alpha. Architecture phase.

## Features

- **Context-aware track selection** — reads in-game state (biome, weather, time of day, player activity) to pick tracks that match the vibe.
- **Spotify integration** — full PKCE OAuth flow, no client secret, all auth happens client-side.
- **HUD overlay** — minimal album art + track info, drawn with `DrawContext`.
- **In-game music ducking** — lowers Minecraft's BGM while a Spotify track is active (planned for V1).

## Tech Stack

- Minecraft 26.1.2 + Fabric Modloader
- Java 25 (Virtual Threads for all Spotify I/O)
- Yet Another Config Lib (YACL) for configuration
- AES-encrypted token storage (key derived from hardware UUID)

## Building & Running

```bash
./gradlew build         # build the mod JAR
./gradlew runClient     # launch the test client
./gradlew generateMixins
```

## Contributing

See [`AGENTS.md`](./AGENTS.md) for architectural rules, package layout, threading policy, and contribution conventions. AI coding assistants and human contributors should both read it before opening a PR.

## License

[MIT](./LICENSE) — © 2026 Aleksander Kowalczuk