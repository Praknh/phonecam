#!/usr/bin/env python3
"""
PhoneCam receiver for Windows.

Reads the MJPEG stream served by the PhoneCam Android app, shows a preview,
and (optionally) outputs it as a virtual webcam through OBS Virtual Camera so
Zoom / Meet / Teams can use it.

Examples
--------
  python receiver.py 192.168.1.42                 # Wi-Fi, preview only
  python receiver.py --usb                        # USB (runs adb forward for you)
  python receiver.py 192.168.1.42 --virtualcam    # also feed the virtual camera
  python receiver.py --usb --virtualcam --width 1280 --height 720 --flip
"""

import argparse
import shutil
import subprocess
import sys
import threading
import time
import urllib.request

import cv2
import numpy as np

SOI = b"\xff\xd8"  # JPEG start of image
EOI = b"\xff\xd9"  # JPEG end of image


class MjpegReader(threading.Thread):
    """Reads the stream in the background and keeps only the newest frame,
    so the preview never falls behind the phone."""

    def __init__(self, url, timeout=5.0):
        super().__init__(daemon=True)
        self.url = url
        self.timeout = timeout
        self.stop_event = threading.Event()
        self._lock = threading.Lock()
        self._frame = None
        self._seq = 0
        self.connected = False

    def latest(self):
        """Returns (frame, sequence_number). frame is None until the first frame."""
        with self._lock:
            return self._frame, self._seq

    def stop(self):
        self.stop_event.set()

    def run(self):
        while not self.stop_event.is_set():
            try:
                self._stream_once()
            except Exception as exc:  # network errors, decode errors, ...
                if not self.stop_event.is_set():
                    print(f"[reader] {exc!s}; retrying in 1s")
            self.connected = False
            self.stop_event.wait(1.0)

    def _stream_once(self):
        request = urllib.request.Request(self.url, headers={"User-Agent": "PhoneCam-receiver"})
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            print(f"[reader] connected to {self.url}")
            self.connected = True
            buf = bytearray()
            while not self.stop_event.is_set():
                chunk = response.read1(65536)
                if not chunk:
                    raise ConnectionError("stream closed by phone")
                buf.extend(chunk)

                # Pull out every complete JPEG, but only decode the newest one.
                newest = None
                while True:
                    start = buf.find(SOI)
                    if start < 0:
                        del buf[:-1]  # keep 1 byte in case SOI is split across chunks
                        break
                    end = buf.find(EOI, start + 2)
                    if end < 0:
                        if start > 0:
                            del buf[:start]
                        break
                    newest = bytes(buf[start:end + 2])
                    del buf[:end + 2]

                if newest is not None:
                    frame = cv2.imdecode(np.frombuffer(newest, np.uint8), cv2.IMREAD_COLOR)
                    if frame is not None:
                        with self._lock:
                            self._frame = frame
                            self._seq += 1


def letterbox(frame, width, height):
    """Resize keeping aspect ratio, padding with black bars."""
    h, w = frame.shape[:2]
    if (w, h) == (width, height):
        return frame
    scale = min(width / w, height / h)
    new_w, new_h = max(1, int(w * scale)), max(1, int(h * scale))
    resized = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_AREA)
    canvas = np.zeros((height, width, 3), dtype=np.uint8)
    x, y = (width - new_w) // 2, (height - new_h) // 2
    canvas[y:y + new_h, x:x + new_w] = resized
    return canvas


def setup_usb(port):
    adb = shutil.which("adb")
    if not adb:
        sys.exit(
            "adb was not found on PATH. Install Android platform-tools "
            "(https://developer.android.com/tools/releases/platform-tools), "
            "add it to PATH, enable USB debugging on the phone, and try again."
        )
    result = subprocess.run(
        [adb, "forward", f"tcp:{port}", f"tcp:{port}"], capture_output=True, text=True
    )
    if result.returncode != 0:
        sys.exit(f"adb forward failed: {result.stderr.strip() or result.stdout.strip()}")
    print(f"[usb] adb forward tcp:{port} -> phone tcp:{port}")


def parse_args():
    p = argparse.ArgumentParser(description="PhoneCam receiver")
    p.add_argument("host", nargs="?", help="phone IP address (omit when using --usb)")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--usb", action="store_true", help="connect over USB using adb port forwarding")
    p.add_argument("--virtualcam", action="store_true", help="output to the OBS Virtual Camera")
    p.add_argument("--width", type=int, help="output width (default: same as phone)")
    p.add_argument("--height", type=int, help="output height (default: same as phone)")
    p.add_argument("--fps", type=int, default=30)
    p.add_argument("--flip", action="store_true", help="mirror the image horizontally")
    p.add_argument("--no-preview", action="store_true", help="do not open a preview window")
    args = p.parse_args()

    if not args.usb and not args.host:
        p.error("give the phone's IP address, or use --usb")
    if (args.width is None) != (args.height is None):
        p.error("--width and --height must be given together")
    if args.no_preview and not args.virtualcam:
        p.error("--no-preview only makes sense together with --virtualcam")
    return args


def main():
    args = parse_args()

    host = args.host
    if args.usb:
        setup_usb(args.port)
        host = "127.0.0.1"
    url = f"http://{host}:{args.port}/video"

    reader = MjpegReader(url)
    reader.start()

    cam = None
    window = "PhoneCam  (press q or Esc to quit)"
    if not args.no_preview:
        cv2.namedWindow(window, cv2.WINDOW_NORMAL)

    frame_interval = 1.0 / max(1, args.fps)
    last_seq = -1
    last_output = None
    fps_count, fps_start = 0, time.time()

    try:
        while True:
            started = time.time()
            frame, seq = reader.latest()

            if frame is not None:
                if seq != last_seq:
                    last_seq = seq
                    out = cv2.flip(frame, 1) if args.flip else frame
                    if args.width:
                        out = letterbox(out, args.width, args.height)
                    last_output = out
                    fps_count += 1
                out = last_output

                if args.virtualcam:
                    if cam is None:
                        import pyvirtualcam  # imported late so preview-only needs no extra setup

                        h, w = out.shape[:2]
                        try:
                            cam = pyvirtualcam.Camera(width=w, height=h, fps=args.fps)
                        except Exception as exc:
                            sys.exit(
                                f"Could not start the virtual camera: {exc}\n"
                                "Install OBS Studio (https://obsproject.com), open it once, "
                                "click 'Start Virtual Camera', then close that and retry."
                            )
                        print(f"[virtualcam] started: {cam.device} {w}x{h}@{args.fps}")
                    elif out.shape[1] != cam.width or out.shape[0] != cam.height:
                        out = letterbox(out, cam.width, cam.height)
                    cam.send(cv2.cvtColor(out, cv2.COLOR_BGR2RGB))

                if not args.no_preview:
                    cv2.imshow(window, out)
            elif not args.no_preview:
                waiting = np.zeros((360, 640, 3), dtype=np.uint8)
                cv2.putText(waiting, "Waiting for phone...", (150, 190),
                            cv2.FONT_HERSHEY_SIMPLEX, 1, (255, 255, 255), 2)
                cv2.imshow(window, waiting)

            if not args.no_preview:
                key = cv2.waitKey(1) & 0xFF
                if key in (ord("q"), 27):
                    break
                if cv2.getWindowProperty(window, cv2.WND_PROP_VISIBLE) < 1:
                    break

            now = time.time()
            if now - fps_start >= 5:
                print(f"[stats] {fps_count / (now - fps_start):.1f} fps in, "
                      f"{'connected' if reader.connected else 'disconnected'}")
                fps_count, fps_start = 0, now

            # Pace the loop to the target FPS.
            remaining = frame_interval - (time.time() - started)
            if remaining > 0:
                time.sleep(remaining)
    except KeyboardInterrupt:
        pass
    finally:
        reader.stop()
        if cam is not None:
            cam.close()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
