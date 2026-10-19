# Sky2M Clips

The clipping program for the [Sky2M](https://github.com/2m3s/Sky2M-26.2) Minecraft mod. It keeps the last few seconds of
the game recorded and saves them as an MP4 when the mod asks (the Save Clip key, or `/s2 clip`).

It is a separate program so the mod stays small for everyone who doesn't clip: Sky2M asks once whether to download it,
checks the download against the SHA-256 that version of Sky2M expects, keeps it in `config/sky2m/clips`, starts it while
Clips is on and stops it with the game. All its settings are in Sky2M (`/s2` > Clips).

## What it does

- **Video:** captures the Minecraft window with Windows.Graphics.Capture (what OBS and the Xbox Game Bar use) and
  encodes it on the graphics card (NVIDIA NVENC, AMD AMF, Intel Quick Sync; Microsoft's software encoder if there is
  none), so it costs very little FPS. 720p, 1080p, 1440p or the window's size, 30 or 60 FPS, a keyframe every second.
  When the window doesn't change (a paused game), the last picture is repeated so clips keep their length.
- **Sound**, mixed and encoded to AAC 192 kbit/s:
  - `game` - only Minecraft (Windows' per-program capture, Windows 10 2004 or newer),
  - `game_discord` - Minecraft and Discord (picked up when Discord starts),
  - `desktop` - everything you hear,
  - `none`,
  - plus the microphone if wanted; every source has its own volume.
- **Replay buffer:** the encoded video and sound stay in memory; saving writes them into an MP4 as they are (no
  re-encoding), so it takes a moment even for a long clip. Clips go to `Videos\Sky2M Clips` by default.

Its log is `%LOCALAPPDATA%\Sky2MClips\log.txt`.

## Talking to it

The mod starts `Sky2MClips.exe` and sends one JSON object per line on stdin; it answers one per line on stdout. It exits
when stdin closes or the game's process (`pid`) ends.

```
mod -> clips  {"type":"config","pid":1234,"seconds":30,"fps":60,"height":1080,"audio":"game_discord","mic":false,
               "gameVolume":100,"discordVolume":100,"desktopVolume":100,"micVolume":100,"folder":"C:\\Users\\me\\Videos\\Sky2M Clips"}
               (sent again whenever a setting changes; height 0 = the window's size)
              {"type":"save","id":"7","name":"Hotkey"}
              {"type":"quit"}

clips -> mod  {"type":"ready","version":"1.0.0"}
              {"type":"status","recording":true,"encoder":"NVIDIA H.264 Encoder MFT","width":1920,"height":1080}
              {"type":"saved","id":"7","path":"C:\\...\\Sky2M 2026-10-19 15-30-12 Hotkey.mp4","seconds":30.0}
              {"type":"error","id":"7","message":"..."}
```

## Building

Needs the .NET 8 SDK on Windows.

```
dotnet build src/Sky2MClips
dotnet publish src/Sky2MClips -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:EnableCompressionInSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:DebugType=none -o publish
```

`publish/Sky2MClips.exe` is the release file (one file, no .NET install needed, about 42 MB). Each Sky2M version pins the
SHA-256 of the release it downloads (`Clips.HELPER_SHA256`), so a new release needs a Sky2M update that names it.

Testing without the mod:

```
Sky2MClips.exe --test "window title" 10 [folder] [game|game_discord|desktop|none] [sound pid]
Sky2MClips.exe --probe clip.mp4 [frame.bmp]
```

`--test` records a window for that many seconds and saves one clip; `--probe` prints a clip's streams, length and sound
level, and can save a frame to check the picture.

## Libraries

- [Vortice.Windows](https://github.com/amerkoleci/Vortice.Windows) (MIT): Direct3D 11 and Media Foundation.
- The Windows SDK projections that come with .NET (Windows.Graphics.Capture).
