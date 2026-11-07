import math
import os
import threading
import time
import urllib.request
import cv2

MODEL_URL = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"


def _d(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def finger_states(lm):
    wrist = lm[0]
    palm = max(1e-5, _d(lm[0], lm[9]))
    thumb_extended = _d(lm[4], lm[5]) > 0.52 * palm and _d(lm[4], lm[17]) > _d(lm[3], lm[17])
    states = [thumb_extended]
    for mcp, pip, tip in ((5, 6, 8), (9, 10, 12), (13, 14, 16), (17, 18, 20)):
        extended = _d(lm[tip], wrist) > _d(lm[pip], wrist) * 1.10 and _d(lm[tip], lm[mcp]) > _d(lm[pip], lm[mcp])
        states.append(extended)
    return states


def classify_hand(lm):
    thumb, idx, mid, ring, pinky = finger_states(lm)
    palm = max(1e-5, _d(lm[0], lm[9]))

    if thumb and not (idx or mid or ring or pinky):
        dy = lm[4][1] - lm[0][1]
        dx = abs(lm[4][0] - lm[0][0])
        if dx < 1.4 * abs(dy):
            return "thumbs_up" if dy < -0.4 * palm else "thumbs_down"
        return None
    if idx and mid and not ring and not pinky:
        return "peace"
    if thumb and idx and pinky and not mid and not ring:
        return "love"
    return None


def effect_for(names):
    names = [n for n in names if n]
    if not names:
        return None
    if names.count("peace") >= 2:
        return "confetti"
    if "thumbs_up" in names:
        return "fireworks"
    if "love" in names:
        return "hearts"
    if "thumbs_down" in names:
        return "rain"
    if "peace" in names:
        return "balloons"
    return None


class GestureTracker:
    def __init__(self, hold=0.5, cooldown=2.8, grace=0.25):
        self.hold = hold
        self.cooldown = cooldown
        self.grace = grace
        self._candidate = None
        self._since = 0.0
        self._last_seen = 0.0
        self._fired = False
        self._last_fired = {}

    def update(self, names, now):
        cand = effect_for(names)
        if cand is None and self._candidate is not None and now - self._last_seen < self.grace:
            return None
        if cand != self._candidate:
            self._candidate = cand
            self._since = now
            self._fired = False
        if cand is None:
            return None
        self._last_seen = now
        if self._fired or now - self._since < self.hold:
            return None
        if now - self._last_fired.get(cand, -1e9) < self.cooldown:
            return None
        self._fired = True
        self._last_fired[cand] = now
        return cand


class HandDetector:
    def __init__(self, cache_dir):
        import mediapipe as mp
        self._mp = mp
        self._hands = None
        self._landmarker = None

        solutions = getattr(mp, "solutions", None)
        if solutions is not None and hasattr(solutions, "hands"):
            self._hands = solutions.hands.Hands(
                static_image_mode=False, max_num_hands=2, model_complexity=0,
                min_detection_confidence=0.6, min_tracking_confidence=0.5
            )
        else:
            self._landmarker = self._make_landmarker(cache_dir)

    def _make_landmarker(self, cache_dir):
        from mediapipe.tasks import python as mp_python
        from mediapipe.tasks.python import vision

        os.makedirs(cache_dir, exist_ok=True)
        path = os.path.join(cache_dir, "hand_landmarker.task")
        if not os.path.exists(path) or os.path.getsize(path) < 100_000:
            print("[gestures] Downloading MediaPipe hand model...")
            urllib.request.urlretrieve(MODEL_URL, path)
        options = vision.HandLandmarkerOptions(
            base_options=mp_python.BaseOptions(model_asset_path=path),
            num_hands=2, running_mode=vision.RunningMode.IMAGE
        )
        return vision.HandLandmarker.create_from_options(options)

    def detect(self, bgr):
        h, w = bgr.shape[:2]
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        hands = []
        if self._hands is not None:
            res = self._hands.process(rgb)
            for hand in (res.multi_hand_landmarks or []):
                hands.append([(p.x * w, p.y * h) for p in hand.landmark])
        elif self._landmarker is not None:
            image = self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=rgb)
            res = self._landmarker.detect(image)
            for hand in res.hand_landmarks:
                hands.append([(p.x * w, p.y * h) for p in hand])
        return hands


class GestureThread(threading.Thread):
    def __init__(self, frame_getter, engine, detector, tracker=None):
        super().__init__(daemon=True)
        self.frame_getter = frame_getter
        self.engine = engine
        self.detector = detector
        self.tracker = tracker or GestureTracker()
        self.stop_event = threading.Event()

    def stop(self):
        self.stop_event.set()

    def run(self):
        while not self.stop_event.wait(0.08):
            frame = self.frame_getter()
            if frame is None:
                continue
            try:
                h, w = frame.shape[:2]
                scaled = cv2.resize(frame, (320, max(1, int(h * 320 / w))))
                hands = self.detector.detect(scaled)
                names = [classify_hand(hnd) for hnd in hands]
                effect = self.tracker.update(names, time.monotonic())
                if effect and self.engine.trigger(effect):
                    print(f"[gestures] Activated: {effect}")
            except Exception:
                pass
