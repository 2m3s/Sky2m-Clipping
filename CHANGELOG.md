# Changelog

All notable changes to Sky2M Clipping are listed here, newest first.

## 3.0.0

### Changed
- Replace the early recorder with a port of Clipify by Vaguestan10: a rolling replay buffer with hardware video encoding (NVENC, AMF, QuickSync, or libx264), microphone and PC sound, an in-game Clips browser and editor (search, rename, trim, preview with sound, share links), a screenshot key and your own hotkeys. It downloads a checked FFmpeg build the first time it starts.
- One repository builds two JARs, `+26.2` and `+26.3`, for Minecraft 26.2 and 26.3.
- Sky2M's Clips settings (on/off, length, quality, frame rate, sound, microphone, volumes, folder), Save Clip key and `/s2 clip [name]` drive the mod through the `sky2m-clipping` entrypoint, and `/s2 clip status` shows what it is doing. With Sky2M installed, recording follows Sky2M's Clips switch.
- Say when the Vulkan graphics backend is in use: clips need OpenGL.

### Removed
- Clipify's Discord presence, its watermark on shared clips, and uploads to Clipify's own clip server (share links use the free hosts).
