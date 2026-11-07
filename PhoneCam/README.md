# PhoneCam

Use your Android phone as a webcam (or share its screen) on a Windows PC, with live gesture effects.

- `android/`               Kotlin app. Streams the phone camera or screen over Wi-Fi/USB (MJPEG, port 8080).
- `windows/`               Python receiver. Finds the phone, shows a preview, outputs a virtual webcam, draws effects.
- `PhoneCam.bat`           One-click launcher (Wi-Fi). Sets everything up on first run.
- `PhoneCam-USB.bat`       Same, over the USB cable (lowest delay).
- `Install-Gestures.bat`   Optional: turns on hand-gesture detection for the effects.

> **Status: early version.** The PC side is tested against a simulated phone. Version 0.2 of the Android app
> (background streaming, zoom, rotate, screen share) could not be compiled or run where it was written,
> so report any build error or crash and it will be fixed quickly.

## Quick start

1. **Phone:** install the PhoneCam APK, open it, allow the camera (and notifications), tap **Start**.
2. **PC:** extract the whole zip, double-click **PhoneCam.bat**.
   - First run: it installs what it needs (Python, libraries, optionally OBS). Click Yes on any
     Windows permission prompts. This happens once.
   - After that: it finds your phone on the Wi-Fi automatically and opens the preview.
3. **Zoom / Meet / Teams:** choose **OBS Virtual Camera** as the camera.

The phone and PC must be on the same Wi-Fi. If Windows SmartScreen says "Windows protected your PC",
click **More info**, then **Run anyway**.

## App features

| Control | What it does |
| --- | --- |
| **Start / Stop** | Starts or stops streaming. |
| **Source: Camera / Screen** | Stream the camera, or share the phone's screen (Android asks permission each time). Change it while stopped. |
| **Rotate** | Turns the picture 90 degrees each tap (0, 90, 180, 270). Use it if the video is sideways or upside down. |
| **Mirror** | Flips the picture left to right. |
| **Back cam / Front cam, 540p / 720p / 1080p** | Camera and size. Lower size means less lag. |
| **Zoom** | Pinch the preview, or use the slider (camera mode). |

**Keeps running when minimised:** once streaming, you can switch to other apps or turn off the screen.
A notification shows that PhoneCam is streaming; tap **Stop** in it, or in the app, to end it.
The preview only shows while the app is open.

**Screen sharing notes:** the shared picture keeps the orientation the phone had when you started
(start sharing in the orientation you want). When the screen is still, the picture refreshes about once a
second, which is normal. There is no audio.

## Gesture effects

Effects are drawn on the video on the PC, so everyone on your call sees them.

**Try them straight away:** with the preview window selected, press keys **1 to 5**:
1 hearts, 2 balloons, 3 confetti, 4 fireworks, 5 rain.

**By hand gesture (optional install):** double-click **Install-Gestures.bat** once (downloads about
200-400 MB; needs internet). Then hold a gesture in front of the camera for half a second:

| Gesture | Effect |
| --- | --- |
| Thumbs up | Fireworks |
| Peace sign (one hand) | Balloons |
| Peace signs (both hands) | Confetti |
| "I love you" sign (thumb, index and little finger out) | Hearts |
| Thumbs down | Rain |

Keep the hand clearly visible and not too small in the picture. Drop your hand and raise it again to repeat an
effect. Use `--no-gestures` to switch detection off, or `--no-effects` to switch all effects off.

## Less delay: USB mode

1. On the phone: Settings > About phone > tap **Build number** 7 times, then Settings > Developer options >
   turn on **USB debugging**.
2. Plug the phone into the PC with a data cable and tap **Allow** on the phone when asked.
3. Open the PhoneCam app, tap **Start**, then double-click **PhoneCam-USB.bat** on the PC.

The first run downloads a small Google tool (adb, about 7 MB) by itself.

## Reducing lag on Wi-Fi

- Use a 5 GHz Wi-Fi network, and keep the phone and PC close to the router.
- Lower the resolution in the app (540p or 720p).
- Close other heavy downloads or streams on the same network.

## Troubleshooting

- **"Looking for your phone..." forever:** the app must be streaming (tap **Start**) on the same Wi-Fi.
  Some routers block devices from seeing each other (often guest Wi-Fi). Use USB mode, or the main Wi-Fi.
- **Camera not listed in Zoom/Meet:** OBS must be installed and its virtual camera registered. Open OBS,
  click **Start Virtual Camera** once, close OBS, run PhoneCam.bat again.
- **Gestures do nothing:** run Install-Gestures.bat and check the messages in the black window start with
  `[gestures] on`. Light the room well and show the hand clearly. Keys 1-5 always work.
- **Streaming stops when the app is minimised (some brands):** in Android settings, set PhoneCam's battery use
  to "Unrestricted" / "Don't optimise".
- **Phone gets warm:** lower the resolution and keep it charging.

## Options (advanced)

Run from a terminal in the PhoneCam folder, for example `PhoneCam.bat --preview-only`.

| Option | What it does |
| --- | --- |
| `192.168.1.42` | Use this phone IP instead of searching |
| `--usb` | Connect over USB using `adb forward` |
| `--preview-only` | Preview window only, no virtual camera |
| `--width W --height H` | Output size (default 1280x720 for the virtual camera) |
| `--fps N` | Frame rate (default 30) |
| `--flip` | Mirror horizontally on the PC |
| `--no-preview` | No window |
| `--no-effects`, `--no-gestures` | Turn effects, or only gesture detection, off |
| `--port N` | Phone port (default 8080) |

## Known limitations

- No audio. Use your PC mic or a headset.
- MJPEG uses more bandwidth than H.264, so latency is higher than the big webcam apps.
- Gesture detection is untested on real hands so far; thresholds may need tuning.
- Rotated (portrait) video is shown with black bars in a 16:9 virtual camera.
- Windows only for the receiver.
