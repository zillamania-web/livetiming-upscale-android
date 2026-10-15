# LiveTiming Upscale for Android (yt-dlp)

YouTube → **yt-dlp on device** → stream proxy → **raw pixels** → canvas upscale/sharpen.

## Build APK

Push to `main` or run **Actions → Build APK → Run workflow**.
Download artifact **LiveTiming-Upscale-debug**.

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## First launch

1. Wait for toast: `yt-dlp ready`
2. Paste a YouTube URL or Share from the YouTube app
3. Status: `yt-dlp stream → pixel upscale`
4. Use **Legibility Max**
