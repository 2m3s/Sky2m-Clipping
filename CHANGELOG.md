# Changelog

All notable changes to Sky2M Clipping are listed here, newest first.

## Unreleased

### Added
- Watch clip links in game: any link to a video file in chat (the share links clips get, x0.at, Catbox, tmpfiles and others) gets a [Watch] button that plays it in a menu with sound. Click the video or press Space to pause, drag the bar to skip, Copy Link, and Esc to close. Also `/sky2mclip watch <link>`.
- Sky2M can upload clips: its "Clip saved" message gets an [Upload] button that uses this mod's share link (uploading the clip if it has none yet), and its [Watch] buttons in /s2c open the player above.

### Changed
- Fix the choppy, broken last second of saved clips: saving now waits for the second being recorded to finish (up to about a second) instead of copying it half-written, so clips end cleanly and include the moment you pressed the key.
- Fix clips that were only a few seconds of choppy video at 1080p: when the encoder cannot keep up, the replay buffer now restarts at a smaller size instead of dropping most frames.
- Reduce choppy stretches in saved clips by giving the capture pipeline more spare frame buffers, and log how many frames capture skipped.
- Record clips on the Vulkan graphics backend too, in both the 26.2 and 26.3 JARs. OpenGL keeps its existing, lighter capture path.
- Only warn that clips are getting no frames when none reach the mod on any backend, instead of telling Vulkan players to switch to OpenGL.

## 1.0

### Changed
- Replace the early recorder with a port of Clipify by Vaguestan10: a rolling replay buffer with hardware video encoding (NVENC, AMF, QuickSync, or libx264), microphone and PC sound, an in-game Clips browser and editor (search, rename, trim, preview with sound, share links), a screenshot key and your own hotkeys. It downloads a checked FFmpeg build the first time it starts.
- One repository builds two JARs, `+26.2` and `+26.3`, for Minecraft 26.2 and 26.3.
- Sky2M's Clips settings (on/off, length, quality, frame rate, sound, microphone, volumes, folder), Save Clip key and `/s2 clip [name]` drive the mod through the `sky2m-clipping` entrypoint, and `/s2 clip status` shows what it is doing. With Sky2M installed, recording follows Sky2M's Clips switch.
- Say when the Vulkan graphics backend is in use: clips need OpenGL.

### Removed
- Clipify's Discord presence, its watermark on shared clips, and uploads to Clipify's own clip server (share links use the free hosts).
