#!/usr/bin/env python3
"""
PhoneCam receiver for Windows.

Finds the PhoneCam app on your Wi-Fi automatically, shows a preview, and
outputs the video as a virtual webcam (OBS Virtual Camera) for Zoom / Meet / Teams.

Normal use: just run PhoneCam.bat. Advanced options:

  python receiver.py                    # auto-find phone on Wi-Fi
  python receiver.py 192.168.1.42       # use a specific phone IP
  python receiver.py --usb              # connect over USB (needs adb)
  python receiver.py --preview-only     # do not create a virtual camera
"""

import argparse
import concurrent.futures
import ipaddress
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request

import cv2
import numpy as np

import gestures
from effects import EffectEngine

SOI = b"\xff\xd8"  # JPEG start of image
EOI = b"\xff\xd9"  # JPEG end of image

CACHE_DIR = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "PhoneCam")
CACHE_FILE = os.path.join(CACHE_DIR, "last_phone.txt")

# Keys in the preview window that start an effect (handy for testing, and as a manual trigger).
EFFECT_KEYS = {ord("1"): "hearts", ord("2"): "balloons", ord("3"): "confetti",
               ord("4"): "fireworks", ord("5"): "rain"}


# --------------------------------------------------------------- discovery

def local_ipv4s():
    """This PC's private IPv4 addresses (Wi-Fi / Ethernet)."""
    found = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # no packet is sent; just picks the main interface
        found.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.add(info[4][0])
    except OSError:
        pass
    result = []
    for ip in sorted(found):
        addr = ipaddress.ip_address(ip)
        if addr.is_private and not addr.is_loopback and not addr.is_link_local:
            result.append(ip)
    return result


def probe(ip, port, timeout=0.5):
    """True if a PhoneCam app is answering at ip:port."""
    try:
        with socket.create_connection((ip, port), timeout=timeout) as s:
            s.settimeout(1.5)
            s.sendall(b"GET / HTTP/1.0\r\nHost: phonecam\r\n\r\n")
            data = b""
            while len(data) < 4096:
                chunk = s.recv(2048)
                if not chunk:
                    break
                data += chunk
            return b"<title>PhoneCam</title>" in data
    except OSError:
        return False


def load_cached_ip():
    try:
        with open(CACHE_FILE, "r") as f:
            return f.read().strip() or None
    except OSError:
        return None


def save_cached_ip(ip):
    try:
        os.makedirs(CACHE_DIR, exist_ok=True)
        with open(CACHE_FILE, "w") as f:
            f.write(ip)
    except OSError:
        pass


def find_phone(port, try_cached=True):
    """Looks for the phone: last known IP first, then a scan of the local network."""
    if try_cached:
        cached = load_cached_ip()
        if cached and probe(cached, port):
            return cached

    mine = local_ipv4s()
    candidates = []
    seen = set()
    for ip in mine:
        for host in ipaddress.ip_network(f"{ip}/24", strict=False).hosts():
            h = str(host)
            if h != ip and h not in seen:
                seen.add(h)
                candidates.append(h)
    if not candidates:
        return None

    print(f"[find] scanning Wi-Fi network ({', '.join(mine)}) for the PhoneCam app...")
    with concurrent.futures.ThreadPoolExecutor(max_workers=128) as pool:
        results = list(pool.map(lambda h: (h, probe(h, port, timeout=0.4)), candidates))
    hits = [h for h, ok in results if ok]
    if not hits:
        return None
    if len(hits) > 1:
        print(f"[find] more than one phone found: {', '.join(hits)}; using {hits[0]}")
    return hits[0]


# ------------------------------------------------------------------ stream

class MjpegReader(threading.Thread):
    """Reads the stream in the background and keeps only the newest frame,
    so the preview never falls behind the phone.

    `resolve(rescan)` returns the stream URL (or None if the phone can't be
    found yet). It is called again with rescan=True after repeated failures,
    which covers the phone's IP address changing."""

    def __init__(self, resolve, timeout=5.0):
        super().__init__(daemon=True)
        self.resolve = resolve
        self.timeout = timeout
        self.stop_event = threading.Event()
        self._lock = threading.Lock()
        self._frame = None
        self._seq = 0
        self.connected = False
        self.status = "Looking for your phone..."
        self.fails = 0
        self.new_frame = threading.Event()  # set whenever a fresh frame arrives

    def latest(self):
        """Returns (frame, sequence_number). frame is None until the first frame."""
        with self._lock:
            return self._frame, self._seq

    def stop(self):
        self.stop_event.set()

    def run(self):
        url = None
        while not self.stop_event.is_set():
            if url is None or self.fails >= 3:
                self.status = "Looking for your phone..."
                try:
                    url = self.resolve(rescan=self.fails >= 3)
                except Exception as exc:
                    print(f"[find] {exc!s}")
                    url = None
                if url is None:
                    self.stop_event.wait(2.0)
                    continue
                self.fails = 0
            try:
                self._stream_once(url)
            except Exception as exc:  # network errors, decode errors, ...
                if not self.stop_event.is_set():
                    self.fails += 1
                    print(f"[reader] {exc!s}; retrying")
            self.connected = False
            self.status = "Connection lost. Reconnecting..."
            self.stop_event.wait(1.0)

    def _stream_once(self, url):
        request = urllib.request.Request(url, headers={"User-Agent": "PhoneCam-receiver"})
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            print(f"[reader] connected to {url}")
            self.connected = True
            self.fails = 0
            self.status = "Connected"
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
                        self.new_frame.set()


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


ADB_DIR = os.path.join(CACHE_DIR, "platform-tools")
ADB_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"


def find_adb():
    """adb on PATH, or our own copy (downloaded automatically on Windows the first time)."""
    adb = shutil.which("adb")
    if adb:
        return adb
    local = os.path.join(ADB_DIR, "adb.exe")
    if os.path.exists(local):
        return local
    if os.name != "nt":
        return None
    print("[usb] downloading Android platform-tools (adb), about 7 MB, one time only...")
    try:
        import io
        import zipfile

        data = urllib.request.urlopen(ADB_URL, timeout=60).read()
        os.makedirs(CACHE_DIR, exist_ok=True)
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            z.extractall(CACHE_DIR)
    except Exception as exc:
        print(f"[usb] download failed: {exc}")
        return None
    return local if os.path.exists(local) else None


def setup_usb(port, wait_seconds=5):
    """Runs `adb forward` so the phone's port is reachable at 127.0.0.1. Raises RuntimeError with a
    plain-language message if it can't."""
    adb = find_adb()
    if not adb:
        raise RuntimeError(
            "Could not get adb (needed for USB mode). Check your internet connection, "
            "or install Android platform-tools and add it to PATH."
        )
    state = ""
    for _ in range(max(1, wait_seconds)):
        out = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=30).stdout
        lines = [ln.split() for ln in out.splitlines()[1:] if ln.strip()]
        states = [parts[1] for parts in lines if len(parts) >= 2]
        if "device" in states:
            state = "device"
            break
        state = states[0] if states else "none"
        time.sleep(1)
    if state == "unauthorized":
        raise RuntimeError("Phone found but not allowed yet: tap 'Allow USB debugging' on the phone.")
    if state != "device":
        raise RuntimeError(
            "No phone found over USB. Plug it in with a data cable and turn on "
            "Settings > Developer options > USB debugging."
        )
    result = subprocess.run(
        [adb, "forward", f"tcp:{port}", f"tcp:{port}"], capture_output=True, text=True, timeout=30
    )
    if result.returncode != 0:
        raise RuntimeError(f"adb forward failed: {result.stderr.strip() or result.stdout.strip()}")
    print(f"[usb] connected over USB (port {port})")


def parse_args():
    p = argparse.ArgumentParser(description="PhoneCam receiver")
    p.add_argument("host", nargs="?", help="phone IP address (default: find it automatically)")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--usb", action="store_true", help="connect over USB using adb port forwarding")
    p.add_argument("--preview-only", action="store_true",
                   help="do not create a virtual camera (preview window only)")
    p.add_argument("--virtualcam", action="store_true", help=argparse.SUPPRESS)  # old option, now default
    p.add_argument("--width", type=int, help="output width (default 1280 for the virtual camera)")
    p.add_argument("--height", type=int, help="output height (default 720 for the virtual camera)")
    p.add_argument("--fps", type=int, default=30)
    p.add_argument("--flip", action="store_true", help="mirror the image horizontally")
    p.add_argument("--no-preview", action="store_true", help="do not open a preview window")
    p.add_argument("--no-effects", action="store_true", help="turn off the hearts/balloons/confetti effects")
    p.add_argument("--no-gestures", action="store_true", help="no hand-gesture detection (keys 1-5 still work)")
    args = p.parse_args()

    if (args.width is None) != (args.height is None):
        p.error("--width and --height must be given together")
    if args.no_preview and args.preview_only:
        p.error("--no-preview and --preview-only together would show nothing")
    args.virtualcam = not args.preview_only
    if args.virtualcam and args.width is None:
        args.width, args.height = 1280, 720
    return args


def make_resolver(args):
    """Returns resolve(rescan) -> stream URL or None."""
    if args.usb:
        usb_url = f"http://127.0.0.1:{args.port}/video"

        def resolve_usb(rescan=False):
            # Runs again after failures, so unplugging and re-plugging the cable recovers.
            try:
                setup_usb(args.port)
            except (RuntimeError, subprocess.SubprocessError, OSError) as exc:
                print(f"[usb] {exc}")
                return None
            return usb_url

        return resolve_usb
    if args.host:
        fixed = f"http://{args.host}:{args.port}/video"
        return lambda rescan=False: fixed

    def resolve(rescan=False):
        ip = find_phone(args.port, try_cached=not rescan)
        if ip is None:
            print("[find] phone not found yet. Is the PhoneCam app open and streaming (tap Start), "
                  "on the same Wi-Fi?")
            return None
        save_cached_ip(ip)
        print(f"[find] phone found at {ip}")
        return f"http://{ip}:{args.port}/video"

    return resolve


def main():
    args = parse_args()
    reader = MjpegReader(make_resolver(args))
    reader.start()

    effects_on = not args.no_effects
    engine = EffectEngine(args.width or 1280, args.height or 720)
    gesture_thread = None
    if effects_on and not args.no_gestures:
        try:
            detector = gestures.HandDetector(CACHE_DIR)
            gesture_thread = gestures.GestureThread(reader, engine, detector)
            gesture_thread.start()
            print("[gestures] on. Hold for half a second: thumbs up = fireworks, peace sign = balloons, "
                  "two peace signs = confetti, I-love-you sign = hearts, thumbs down = rain")
        except ImportError:
            print("[gestures] hand detection is not installed. Run Install-Gestures.bat to enable it. "
                  "Effects still work: press keys 1-5 in the preview window.")
        except Exception as exc:
            print(f"[gestures] could not start hand detection: {exc}\n"
                  "  Effects still work: press keys 1-5 in the preview window.")

    cam = None
    window = "PhoneCam  (q = quit, keys 1-5 = effects)"
    if not args.no_preview:
        cv2.namedWindow(window, cv2.WINDOW_NORMAL)

    frame_interval = 1.0 / max(1, args.fps)
    last_seq = -1
    last_output = None
    last_sent = 0.0
    fps_count, fps_start = 0, time.time()

    try:
        while True:
            # Wake the moment a new frame arrives (lowest delay). If none comes within one
            # frame time, carry on anyway so the virtual camera keeps getting the last picture.
            reader.new_frame.wait(frame_interval)
            reader.new_frame.clear()
            frame, seq = reader.latest()

            if frame is not None:
                fresh = seq != last_seq
                if fresh:
                    last_seq = seq
                    out = cv2.flip(frame, 1) if args.flip else frame
                    if args.width:
                        out = letterbox(out, args.width, args.height)
                    last_output = out
                    fps_count += 1
                out = last_output

                playing = False
                if effects_on:
                    engine.set_size(out.shape[1], out.shape[0])
                    playing = engine.active
                    out = engine.process(out)  # copy with effects drawn on, or `out` itself if idle
                shown = out

                if args.virtualcam:
                    if cam is None:
                        import pyvirtualcam  # imported late so preview-only needs no extra setup

                        h, w = out.shape[:2]
                        try:
                            cam = pyvirtualcam.Camera(width=w, height=h, fps=args.fps)
                            print(f"[virtualcam] started: {cam.device} {w}x{h}@{args.fps}")
                        except Exception as exc:
                            print(
                                f"[virtualcam] could not start: {exc}\n"
                                "  Zoom/Meet/Teams will not see the camera yet. To fix it, install OBS Studio, "
                                "open it, click 'Start Virtual Camera' once, close OBS, then run this again.\n"
                                "  Continuing with the preview window only."
                            )
                            args.virtualcam = False
                            if args.no_preview:
                                sys.exit(1)
                    # Send each new frame once; repeat the last one only if the stream stalls
                    # (some apps show black if a camera goes quiet).
                    if cam is not None and (fresh or playing or time.time() - last_sent > 0.1):
                        if out.shape[1] != cam.width or out.shape[0] != cam.height:
                            out = letterbox(out, cam.width, cam.height)
                        cam.send(cv2.cvtColor(out, cv2.COLOR_BGR2RGB))
                        last_sent = time.time()

                if not args.no_preview:
                    cv2.imshow(window, shown)
            elif not args.no_preview:
                waiting = np.zeros((360, 640, 3), dtype=np.uint8)
                cv2.putText(waiting, reader.status, (40, 180),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2)
                cv2.putText(waiting, "Open the PhoneCam app and tap Start", (40, 230),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.6, (180, 180, 180), 1)
                cv2.imshow(window, waiting)

            if not args.no_preview:
                key = cv2.waitKey(1) & 0xFF
                if key in (ord("q"), 27):
                    break
                if effects_on and key in EFFECT_KEYS:
                    engine.trigger(EFFECT_KEYS[key], force=True)
                if cv2.getWindowProperty(window, cv2.WND_PROP_VISIBLE) < 1:
                    break

            now = time.time()
            if now - fps_start >= 5:
                print(f"[stats] {fps_count / (now - fps_start):.1f} fps in, "
                      f"{'connected' if reader.connected else 'disconnected'}")
                fps_count, fps_start = 0, now
    except KeyboardInterrupt:
        pass
    finally:
        reader.stop()
        if gesture_thread is not None:
            gesture_thread.stop()
        if cam is not None:
            cam.close()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
