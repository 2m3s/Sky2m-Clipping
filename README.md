# Sky2M Clipping

Instant replay for [Sky2M](https://github.com/2m3s/Sky2M-26.2): keeps the last few seconds of gameplay recorded and saves them as an MP4 on a key press, with an in-game clip browser and editor.

**Clipify by Vaguestan10.** Sky2M Clipping is a modified version of [Clipify](https://github.com/itamarb2010-jpg/Clipify), used under its MIT with Attribution licence (see [LICENSE](LICENSE)). It is not made or endorsed by the Clipify author.

## Download

Latest: **Sky2M Clipping 1.0.1**. Get the JAR for your Minecraft version from [Releases](https://github.com/2m3s/Sky2m-Clipping/releases/latest) and put it in your `mods` folder next to Sky2M and Fabric API, then restart the game:

- `sky2m-clipping-1.0.1+26.2.jar` for Minecraft 26.2
- `sky2m-clipping-1.0.1+26.3.jar` for Minecraft 26.3

Sky2M can also download it for you: on the title screen it asks once whether to download the clipping mod; answer **Yes, download** and restart when it says so. Later you can use the Download button in **/s2 > Clips** or `/s2 clip download`. It always fetches the JAR for your Minecraft version from the latest release.

Windows only.

Recording works with both Graphics Backends in Video Settings, OpenGL and Vulkan. OpenGL is the lighter of the two for recording: on Vulkan each frame is read back through Minecraft's own GPU API and converted on a background thread.

## With Sky2M

Sky2M finds this mod through its `sky2m-clipping` entrypoint and drives it:

- **/s2 > Clips** turns recording on and off and sets the clip length, quality, frame rate, sound, microphone, volumes and folder (`Videos\Sky2M Clips`).
- The **Save Clip** key and `/s2 clip [name]` save a clip; Sky2M prints a chat message with **[Open]**, **[Folder]** and **[Upload]** links. **[Upload]** gives you the clip's share link with **[Send in /s2c]**, **[Copy Link]** and **[Watch]**.
- Clip links in /s2c show as **[Watch]**, which plays them in this mod's in-game player.
- `/s2 clip status` shows what the recorder is doing (for example downloading FFmpeg).

Sky2M's settings are layered on top of this mod's own settings and are never written to its config. Without Sky2M, the mod works on its own with its settings screen (pause menu > Clips > Settings, or Mod Menu).

Sound: the recorder captures everything your PC plays (WASAPI loopback) plus, optionally, your microphone. Sky2M's "Game only" and "Game + Discord" choices also record all PC sound.

## Features

- Rolling buffer of 5 seconds to 10 minutes, hardware encoding (NVENC, AMF, QuickSync) with a libx264 fallback, near-instant saves without re-encoding.
- Microphone and PC audio with per-source volume.
- Clips screen with search, rename, preview with sound, trimming, and optional share links.
- Watch clip links in game: any link to a video file in chat gets a **[Watch]** button that plays it in a menu with sound (or `/sky2mclip watch <link>`).
- Screenshot hotkey that also copies the picture to the clipboard.
- As many hotkeys as you like, each clip key with its own length.

On first start it downloads a SHA-256 checked FFmpeg build into `.minecraft/sky2m-clipping/bin` (or uses one on `PATH`). PC-audio capture compiles a small WASAPI helper with the C# compiler built into Windows.

## Building

One source tree (`src/`) is built once per Minecraft version. `versions/<mc>/gradle.properties` holds each version's Minecraft and Fabric versions, and `versions/<mc>/src` the few classes whose Minecraft API differs (`compat/`, the frame-capture mixin, `LinearImageTexture`).

```powershell
.\gradlew.bat build
```

Both JARs land in `build/libs/`. Pushes build them on GitHub Actions; pushing a `v*` tag also attaches them to that release.

This replaced the older Sky2M Clips recorder program (a separate .exe), which is no longer used.
