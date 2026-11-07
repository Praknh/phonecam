import argparse
import concurrent.futures
import ipaddress
import json
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

import av
from effects import EffectEngine
import gestures

CACHE_DIR = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "PhoneCam")
CACHE_FILE = os.path.join(CACHE_DIR, "last_phone.txt")


def primary_lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def probe(ip, port=8080):
    try:
        url = f"http://{ip}:{port}/status"
        req = urllib.request.Request(url, headers={"User-Agent": "PhoneCamProbe"})
        with urllib.request.urlopen(req, timeout=0.35) as res:
            return b"streaming" in res.read()
    except Exception:
        return False


def find_phone(port=8080):
    if os.path.exists(CACHE_FILE):
        try:
            with open(CACHE_FILE, "r") as f:
                cached = f.read().strip()
            if cached and probe(cached, port):
                return cached
        except Exception:
            pass

    lan_ip = primary_lan_ip()
    network = ipaddress.ip_network(f"{lan_ip}/24", strict=False)
    candidates = [str(h) for h in network.hosts() if str(h) != lan_ip]

    print(f"[find] Scanning network ({lan_ip}/24) for PhoneCam...")
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as pool:
        results = list(pool.map(lambda h: (h, probe(h, port)), candidates))

    for h, ok in results:
        if ok:
            os.makedirs(CACHE_DIR, exist_ok=True)
            with open(CACHE_FILE, "w") as f: f.write(h)
            return h
    return None


class CenterStage:
    def __init__(self, target_w=1280, target_h=720):
        self.target_w = target_w
        self.target_h = target_h
        self.cx, self.cy = 0.5, 0.5
        self.scale = 1.0
        self.face_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
        self.last_detect = 0.0

    def apply(self, frame):
        h, w = frame.shape[:2]
        now = time.monotonic()
        if now - self.last_detect > 0.12:
            self.last_detect = now
            small = cv2.resize(frame, (320, int(h * 320 / w)))
            gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
            faces = self.face_cascade.detectMultiScale(gray, 1.2, 4, minSize=(30, 30))
            if len(faces) > 0:
                fx, fy, fw, fh = max(faces, key=lambda f: f[2] * f[3])
                target_cx = (fx + fw / 2) / 320.0
                target_cy = (fy + fh / 2) / float(small.shape[0])
                self.cx += (target_cx - self.cx) * 0.10
                self.cy += (target_cy - self.cy) * 0.10
                self.scale += (1.3 - self.scale) * 0.05
            else:
                self.scale += (1.0 - self.scale) * 0.05
                self.cx += (0.5 - self.cx) * 0.05
                self.cy += (0.5 - self.cy) * 0.05

        crop_w = int(w / self.scale)
        crop_h = int(h / self.scale)
        x1 = max(0, min(w - crop_w, int(self.cx * w - crop_w / 2)))
        y1 = max(0, min(h - crop_h, int(self.cy * h - crop_h / 2)))
        cropped = frame[y1:y1 + crop_h, x1:x1 + crop_w]
        return cv2.resize(cropped, (self.target_w, self.target_h), interpolation=cv2.INTER_LINEAR)


def send_control_command(ip, port, param_query):
    try:
        url = f"http://{ip}:{port}/control?{param_query}"
        req = urllib.request.Request(url, headers={"User-Agent": "PhoneCamReceiver"})
        with urllib.request.urlopen(req, timeout=1.0) as res:
            return res.read()
    except Exception as e:
        print(f"[control] Failed to send {param_query}: {e}")
        return None


def fetch_status(ip, port=8080):
    try:
        url = f"http://{ip}:{port}/status"
        req = urllib.request.Request(url, headers={"User-Agent": "PhoneCamReceiver"})
        with urllib.request.urlopen(req, timeout=1.0) as res:
            return json.loads(res.read().decode("utf-8"))
    except Exception:
        return None


def main():
    parser = argparse.ArgumentParser(description="PhoneCam HD Windows Receiver & Virtual Camera")
    parser.add_argument("--ip", type=str, default=None, help="Phone IP address (auto-discovered if omitted)")
    parser.add_argument("--port", type=int, default=8080, help="PhoneCam stream port (default: 8080)")
    parser.add_argument("--usb", action="store_true", help="Enable USB mode via ADB reverse port forwarding")
    parser.add_argument("--no-virtualcam", action="store_true", help="Disable OBS Virtual Camera output")
    parser.add_argument("--width", type=int, default=1280, help="Output target width (default: 1280)")
    parser.add_argument("--height", type=int, default=720, help="Output target height (default: 720)")
    parser.add_argument("--fps", type=int, default=30, help="Virtual camera target FPS (default: 30)")
    args = parser.parse_args()

    phone_ip = args.ip
    port = args.port

    if args.usb:
        print("[usb] Enabling USB mode: running adb forward tcp:8080 tcp:8080...")
        try:
            subprocess.run(["adb", "forward", f"tcp:{port}", f"tcp:{port}"], check=True)
            phone_ip = "127.0.0.1"
            print("[usb] Port forwarding successful! Connected via USB cable.")
        except Exception as e:
            print(f"[usb] Warning: ADB forward failed ({e}). Ensure ADB is installed and USB debugging is enabled.")
            if not phone_ip:
                phone_ip = "127.0.0.1"

    if not phone_ip:
        print("[discovery] Searching for PhoneCam on local network...")
        phone_ip = find_phone(port)
        if not phone_ip:
            print(f"\n[error] Could not auto-discover PhoneCam on your network.")
            print(f"Make sure:")
            print(f"  1. The PhoneCam Android app is running and 'Start Streaming' is tapped.")
            print(f"  2. Phone and PC are connected to the same Wi-Fi network.")
            print(f"  3. Or run with explicit IP: python receiver.py --ip 192.168.1.XXX")
            print(f"  4. Or run with USB cable: python receiver.py --usb\n")
            sys.exit(1)

    stream_url = f"http://{phone_ip}:{port}/video.h264"
    print(f"\n=======================================================")
    print(f" PhoneCam HD Receiver Connected!")
    print(f" Stream Source: {stream_url}")
    print(f" Controls: 1-5 Reactions | C: Center Stage | T: Torch | F: Flip Cam | +/-: Zoom | O: OLED Save | Q: Quit")
    print(f"=======================================================\n")

    effect_engine = EffectEngine(args.width, args.height)
    center_stage = CenterStage(args.width, args.height)
    use_center_stage = False

    # Initialize Gesture Detector if available
    latest_frame = [None]
    frame_lock = threading.Lock()

    def get_latest_frame():
        with frame_lock:
            return latest_frame[0].copy() if latest_frame[0] is not None else None

    gesture_thread = None
    try:
        detector = gestures.HandDetector(CACHE_DIR)
        gesture_thread = gestures.GestureThread(get_latest_frame, effect_engine, detector)
        gesture_thread.start()
        print("[gestures] Hand gesture recognition loaded successfully.")
    except Exception as e:
        print(f"[gestures] Note: Gesture detection disabled ({e}). Run Install-Gestures.bat to enable.")

    # Virtual Camera output
    vcam = None
    if not args.no_virtualcam:
        try:
            import pyvirtualcam
            vcam = pyvirtualcam.Camera(width=args.width, height=args.height, fps=args.fps, fmt=pyvirtualcam.PixelFormat.BGR)
            print(f"[vcam] Virtual Camera active: '{vcam.device}'. Select this in Zoom, Teams, or Meet.")
        except Exception as e:
            print(f"[vcam] Virtual Camera could not start ({e}). Running in window preview mode only.")

    window_name = f"PhoneCam HD Preview [{phone_ip}]"
    cv2.namedWindow(window_name, cv2.WINDOW_NORMAL)
    cv2.resizeWindow(window_name, 960, 540)

    # Open low-latency H.264 stream
    container = None
    try:
        container = av.open(stream_url, format="h264", options={"fflags": "nobuffer", "flags": "low_delay", "analyzeduration": "0", "probesize": "32"})
    except Exception as e:
        print(f"[av] Direct stream open failed ({e}), attempting standard connection...")
        try:
            container = av.open(stream_url)
        except Exception as e2:
            print(f"[error] Cannot connect to {stream_url}: {e2}")
            if gesture_thread:
                gesture_thread.stop()
            sys.exit(1)

    last_fps_calc = time.monotonic()
    frame_count = 0
    current_fps = 0.0
    current_zoom = 1.0

    try:
        for frame in container.decode(video=0):
            img = frame.to_ndarray(format="bgr24")
            frame_count += 1
            now = time.monotonic()
            if now - last_fps_calc >= 1.0:
                current_fps = frame_count / (now - last_fps_calc)
                frame_count = 0
                last_fps_calc = now

            if use_center_stage:
                img = center_stage.apply(img)
            elif img.shape[1] != args.width or img.shape[0] != args.height:
                img = cv2.resize(img, (args.width, args.height))

            # Apply animated reactions
            img = effect_engine.process(img)

            with frame_lock:
                latest_frame[0] = img

            # Send to virtual webcam
            if vcam:
                vcam.send(img)

            # Draw HUD on preview window
            hud = img.copy()
            cs_text = "ON" if use_center_stage else "OFF"
            cv2.putText(hud, f"PhoneCam HD | {current_fps:.1f} FPS | CenterStage: {cs_text}", (16, 28), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 128), 2, cv2.LINE_AA)
            cv2.putText(hud, "1-5: Effects  C: CenterStage  T: Torch  F: Flip  +/-: Zoom  O: OLED  Q: Quit", (16, hud.shape[0] - 16), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (220, 220, 220), 1, cv2.LINE_AA)

            cv2.imshow(window_name, hud)
            key = cv2.waitKey(1) & 0xFF

            if key in (ord('q'), ord('Q'), 27):  # ESC or Q
                break
            elif key == ord('1'):
                effect_engine.trigger("hearts")
            elif key == ord('2'):
                effect_engine.trigger("balloons")
            elif key == ord('3'):
                effect_engine.trigger("confetti")
            elif key == ord('4'):
                effect_engine.trigger("fireworks")
            elif key == ord('5'):
                effect_engine.trigger("rain")
            elif key in (ord('c'), ord('C')):
                use_center_stage = not use_center_stage
                print(f"[control] Center Stage: {'ENABLED' if use_center_stage else 'DISABLED'}")
            elif key in (ord('t'), ord('T')):
                threading.Thread(target=send_control_command, args=(phone_ip, port, "torch=toggle"), daemon=True).start()
                print("[control] Toggled torch")
            elif key in (ord('f'), ord('F')):
                threading.Thread(target=send_control_command, args=(phone_ip, port, "camera=switch"), daemon=True).start()
                print("[control] Switched camera")
            elif key in (ord('+'), ord('=')):
                current_zoom = min(8.0, current_zoom + 0.25)
                threading.Thread(target=send_control_command, args=(phone_ip, port, f"zoom={current_zoom:.2f}"), daemon=True).start()
                print(f"[control] Zoom: {current_zoom:.2f}x")
            elif key in (ord('-'), ord('_')):
                current_zoom = max(1.0, current_zoom - 0.25)
                threading.Thread(target=send_control_command, args=(phone_ip, port, f"zoom={current_zoom:.2f}"), daemon=True).start()
                print(f"[control] Zoom: {current_zoom:.2f}x")
            elif key in (ord('o'), ord('O')):
                threading.Thread(target=send_control_command, args=(phone_ip, port, "amoled=toggle"), daemon=True).start()
                print("[control] Toggled AMOLED screen save")

    except KeyboardInterrupt:
        pass
    finally:
        if gesture_thread:
            gesture_thread.stop()
        if vcam:
            vcam.close()
        cv2.destroyAllWindows()
        print("[receiver] PhoneCam HD receiver closed.")


if __name__ == "__main__":
    main()
