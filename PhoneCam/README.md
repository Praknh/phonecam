# PhoneCam

Use your Android phone as a webcam for a Windows PC.

- `android/`  – Kotlin app (CameraX). Streams the camera as MJPEG over HTTP on port 8080.
- `windows/`  – Python receiver. Shows a preview and can feed the OBS Virtual Camera, so Zoom / Meet / Teams see it as a webcam.

> **Status: untested first version.** It was written without the ability to compile the
> Android app or run it on a phone. Expect to fix a few small things. Report any Android
> Studio error or crash log and it can be corrected quickly.

## 1. Build the Android app

1. Install [Android Studio](https://developer.android.com/studio).
2. `File > Open` and choose the `android/` folder. Accept the prompt to let it set up Gradle and let it sync
   (it downloads Gradle, the Android SDK platform 34 and the CameraX libraries on first run).
3. On your phone: enable **Developer options** and **USB debugging**.
4. Connect the phone by USB, pick it in the device dropdown, press **Run**.
   (Or `Build > Build APK(s)` and install the APK manually.)

## 2. Set up the Windows receiver

1. Install [Python 3.10+](https://www.python.org/downloads/) (tick "Add to PATH").
2. In a terminal:

   ```
   cd windows
   pip install -r requirements.txt
   ```

## 3. Run it

Open the PhoneCam app, allow camera access, tap **Start**. The app shows the address to use.

**Wi-Fi** (phone and PC on the same network):

```
python receiver.py 192.168.1.42
```

**USB** (steadier, lower lag; needs [adb platform-tools](https://developer.android.com/tools/releases/platform-tools) on PATH):

```
python receiver.py --usb
```

Press `q` or `Esc` in the preview window to quit.

You can also check the stream from any browser: open `http://PHONE_IP:8080/`.

## 4. Use it in Zoom / Meet / Teams

1. Install [OBS Studio](https://obsproject.com/). Open it once, click **Start Virtual Camera**, then close OBS
   (this registers the virtual camera driver).
2. Run the receiver with `--virtualcam`:

   ```
   python receiver.py --usb --virtualcam --width 1280 --height 720
   ```
3. In Zoom/Meet/Teams, choose **OBS Virtual Camera** as your camera.

## Receiver options

| Option | What it does |
| --- | --- |
| `--usb` | Connect over USB using `adb forward` |
| `--virtualcam` | Send video to OBS Virtual Camera |
| `--width W --height H` | Output size (keeps aspect ratio, adds black bars) |
| `--fps N` | Target frame rate (default 30) |
| `--flip` | Mirror horizontally |
| `--no-preview` | No window (only with `--virtualcam`) |
| `--port N` | Phone port (default 8080) |

## App controls

- **Start / Stop** – starts or stops the stream server. The camera only does work while someone is connected.
- **Back cam / Front cam** – switch cameras.
- **540p / 720p / 1080p** – capture size. Lower it if the picture lags.
- **Mirror** – flips the streamed image.

## Troubleshooting

- **Receiver says "retrying" / nothing appears**: check the phone is streaming, same Wi-Fi, and that your
  Windows firewall/router isn't blocking port 8080 (USB mode avoids this).
- **Laggy video**: use USB, lower to 540p/720p, or move closer to the router / use 5 GHz Wi-Fi.
- **`adb forward failed`**: unlock the phone, accept the "Allow USB debugging" prompt, run `adb devices`.
- **Virtual camera won't start**: OBS must be installed and its virtual camera started once (step 4.1).
- **Phone gets warm**: lower resolution; keep it charging.

## Known limitations (version 1)

- **No audio.** Use your PC mic or a headset.
- The app must stay open on screen (the screen is kept awake). Running it in the background needs a foreground service, which is a planned next step.
- MJPEG uses more bandwidth than H.264 and is limited by the phone's JPEG encoding speed; 720p30 should be fine on most phones.
- Landscape only.
- No autofocus/exposure/zoom controls or auto-reconnect UI on the phone yet (the receiver does auto-reconnect).

## Next steps

Foreground service with a persistent notification, tap-to-focus and zoom, torch, H.264/WebRTC for lower latency,
audio, and a native Windows virtual camera that doesn't need OBS.
