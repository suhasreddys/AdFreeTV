# AdFreeTV — YouTube Leanback with Ad Blocking for Android TV

A lightweight, dedicated Android TV application that hosts the YouTube TV (Leanback) interface (`https://www.youtube.com/tv`) while stripping ad requests and auto-skipping video ads.

## Architecture

1. **TV UI Delivery**: Sets a Smart TV User-Agent (`Mozilla/5.0 (Linux; GoogleTV 14)... SmartTV`) so YouTube serves the remote-friendly Leanback TV interface.
2. **Network Request Interception**: Overrides `WebViewClient.shouldInterceptRequest` to intercept and return empty responses (`200 OK`) for ad tracking networks (`googleads.g.doubleclick.net`, `/pagead/`, `/api/stats/ads`, etc.).
3. **DOM & Video Player Injection (`AdBlocker.kt`)**:
   - Injects custom CSS to hide banner promotions, overlay containers, and companion ad cards.
   - Runs a 200ms background interval detecting active ad states (`.ad-showing`, `.ad-interrupting`).
   - Automatically mutes audio, ramps playback speed to `16.0x`, fast-forwards `video.currentTime` to `video.duration`, and clicks any visible skip buttons (`.ytp-ad-skip-button`).
   - Restores volume and normal 1.0x playback speed as soon as video content resumes.
4. **Remote Control (D-Pad) Integration**:
   - Full keyboard/D-pad navigation forward to WebView.
   - Hardware media keys (`PLAY_PAUSE`, `FAST_FORWARD`, `REWIND`) mapped directly to HTML5 video element controls.
   - Double-tap BACK button protection to prevent accidental app exits while navigating.

---

## How to Build & Install

### Option A: Open with Android Studio
1. Open **Android Studio**.
2. Select **File -> Open...** and navigate to:
   `C:\Users\suhas\.gemini\antigravity\scratch\AdFreeTV`
3. Wait for Gradle to sync dependencies.
4. Connect your Android TV via Wireless ADB (or USB), or use an Android TV emulator.
5. Click **Run** (`Shift + F10`) or go to **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.

### Option B: Command Line Build (if Gradle / JDK is installed)
In this directory:
```bash
./gradlew assembleDebug
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Installing the APK onto your TV

### Method 1: Via ADB over Wi-Fi
1. On your Android TV, go to **Settings -> Device Preferences -> About** and click **Build** 7 times to enable Developer Options.
2. In **Developer Options**, turn on **Network Debugging** / **USB Debugging** and note your TV's IP address (e.g., `192.168.1.50:5555`).
3. From your computer terminal:
   ```bash
   adb connect 192.168.1.50:5555
   adb install app-debug.apk
   ```

### Method 2: Via "Send Files to TV" or USB Drive
1. Copy `app-debug.apk` to a USB flash drive or send it over Wi-Fi using the **Send Files to TV** app from the Google Play Store.
2. Install the APK using any file manager on your TV (such as **File Commander** or **Downloader**).
