# Sky2M Clipping — Fabric mod

This branch replaces the standalone .NET executable project with a companion Fabric mod for Minecraft 26.2. Sky2M keeps the settings UI and save key; this mod provides the `sky2m-clipping` entrypoint. Recording runs inside Minecraft's JVM; no separate recorder executable is launched.

## Early Java port — known limitations

- Captures the Minecraft window with FFmpeg's Windows `gdigrab` input and maintains a rolling buffer of short MP4 segments, then exports them to an MP4 when Sky2M requests a clip.
- Attempts FFmpeg's `h264_nvenc` encoder; automatic software fallback is not implemented.
- **Audio is not implemented yet.** Game-only, Discord, desktop audio, microphone and volume settings are accepted for API compatibility but are not currently recorded.
- The requested output-height/quality setting is accepted but not yet applied.
- Must be built and tested on Windows with Minecraft 26.2. CI compilation alone does not prove in-game capture works.

This is an early port, not feature parity with the former recorder.

## Install

Build with JDK 25 and Gradle 9.5.1, then put `sky2m-clipping-2.0.0.jar` in the Minecraft `mods` folder alongside Sky2M. The JAR packages JavaCV, FFmpeg's Windows x86-64 native libraries, and JNA as nested dependencies.

## Sky2M API

- `configure(Map<String, Object>)`
- `save(String)` → `CompletableFuture<Map<String, Object>>` with `path` and `seconds`
- `status()` → `recording`, `encoder`, and `error`
- `shutdown()`

## Build

```powershell
gradle build
```

The JAR is output under `build/libs/`.
