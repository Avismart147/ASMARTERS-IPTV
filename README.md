# Asmarter IPTV (Android + MX Player Features)

Hybrid Android app combining the **Asmarter IPTV** web UI with:
- **Native Kotlin HTTP Bridge (`AndroidBridge.httpGet`)**: Connects directly to any `http://` or `https://` Xtream Codes server with zero CORS or proxy needed.
- **Native Media3 ExoPlayer (`PlayerActivity`) with MX Player gestures**:
  - Left-side vertical swipe: Brightness
  - Right-side vertical swipe: Volume
  - Horizontal swipe & double-tap: Seek / ±10s
  - Aspect ratio cycling (Fit / Stretch / Zoom / 16:9), Playback speed (0.5x–2.0x), Audio & Subtitle track picker, Screen Lock, and Picture-in-Picture (PiP).

## Build APK (No PC Needed via GitHub Actions)
1. Upload this folder to a GitHub repository.
2. Open the **Actions** tab → **Build Asmarter IPTV APK** → download `Asmarter-IPTV-debug.apk` from the build artifacts.
