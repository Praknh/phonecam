# PhoneCam HD

Use your Android phone as a high-definition, lag-free webcam (or share its screen) on Windows PC, with hardware H.264 encoding and live gesture effects.

- `android/`               Kotlin app. Hardware H.264 streaming over Wi-Fi/USB (port 8080).
- `windows/`               Python receiver. Auto-discovery, low-latency H.264 decoder, virtual webcam, Center Stage, reaction effects.
- `PhoneCam.bat`           One-click launcher (Wi-Fi).
- `PhoneCam-USB.bat`       Same, over USB cable (lowest latency, 0% Wi-Fi jitter).
- `Install-Gestures.bat`   Installs MediaPipe hand-gesture detection.

## Quick Start
1. **Phone:** Open the PhoneCam app, grant Camera/Notification permissions, and tap **Start Streaming**.
2. **PC:** Double-click `PhoneCam.bat`. It auto-discovers your phone and starts the virtual camera automatically.
3. **Meetings:** Select **OBS Virtual Camera** in Zoom, Teams, Google Meet, or Discord.

## Keyboard Hotkeys (in Preview Window)
- **1:** Hearts Reaction Effect (with audio)
- **2:** Balloons Reaction Effect (with audio)
- **3:** Confetti Celebration (with audio)
- **4:** Fireworks Explosion (with audio)
- **5:** Rain Cloud Effect (with audio)
- **C:** Toggle Center Stage (Face tracking / auto-framing)
- **T:** Toggle Phone Flashlight / Ring Light remotely
- **F:** Switch Front / Back Camera remotely
- **+ / -:** Remotely Zoom In / Out
- **O:** Toggle Phone OLED AMOLED Screen Saver (saves battery and keeps phone cool)
- **Q / ESC:** Quit Receiver

## USB Tethering Mode (Lowest Latency)
Connect your phone with a USB-C cable and enable USB Debugging in Developer Options. Run `PhoneCam-USB.bat`.
This runs `adb forward tcp:8080 tcp:8080` automatically for zero-packet-drop, sub-40ms latency video.
