"""
Hand gesture detection that triggers the video effects.

  thumbs up            -> fireworks
  peace sign (one hand)-> balloons
  peace sign (2 hands) -> confetti
  "I love you" sign    -> hearts      (thumb + index + little finger out)
  thumbs down          -> rain

Hold the gesture still for about half a second. The hand-tracking itself uses the optional
`mediapipe` package (see Install-Gestures.bat). The gesture rules below need nothing extra.
"""

import math
import os
import threading
import time
import urllib.request

import cv2

MODEL_URLS = (
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task",
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task",
)


def _d(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def finger_states(lm):
    """lm: 21 (x, y) hand landmarks in pixels (MediaPipe order).
    Returns [thumb, index, middle, ring, pinky] as True when the finger is stretched out."""
    wrist = lm[0]
    palm = max(1e-6, _d(lm[0], lm[9]))
    thumb = _d(lm[4], lm[5]) > 0.55 * palm and _d(lm[4], lm[17]) > _d(lm[3], lm[17])
    states = [thumb]
    for pip, tip in ((6, 8), (10, 12), (14, 16), (18, 20)):
        states.append(_d(lm[tip], wrist) > _d(lm[pip], wrist) * 1.12)
    return states


def classify_hand(lm):
    """Returns 'thumbs_up', 'thumbs_down', 'peace', 'love', 'open', 'fist' or None."""
    thumb, idx, mid, ring, pinky = finger_states(lm)
    palm = max(1e-6, _d(lm[0], lm[9]))
    if thumb and not (idx or mid or ring or pinky):
        dx = lm[4][0] - lm[0][0]
        dy = lm[4][1] - lm[0][1]  # image y grows downward
        if abs(dx) > 1.2 * abs(dy):  # thumb is more than ~50 degrees off vertical
            return None
        if dy < -0.5 * palm:
            return "thumbs_up"
        if dy > 0.5 * palm:
            return "thumbs_down"
        return None
    if idx and mid and not ring and not pinky:
        return "peace"
    if thumb and idx and pinky and not mid and not ring:
        return "love"
    if thumb and idx and mid and ring and pinky:
        return "open"
    if not (thumb or idx or mid or ring or pinky):
        return "fist"
    return None


def effect_for(names):
    """Which effect the gestures currently shown ask for (or None)."""
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
    """Turns a stream of per-frame detections into one-off triggers: the gesture must be held
    for `hold` seconds, and the same effect will not fire again for `cooldown` seconds."""

    def __init__(self, hold=0.6, cooldown=3.0, grace=0.25):
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
            return None  # brief detection dropout: keep counting
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
    """MediaPipe hand tracking. Raises ImportError if mediapipe is not installed."""

    def __init__(self, cache_dir):
        import mediapipe as mp  # noqa: WPS433 (optional dependency)

        self._mp = mp
        self._hands = None
        self._landmarker = None
        solutions = getattr(mp, "solutions", None)
        if solutions is not None and hasattr(solutions, "hands"):
            self._hands = solutions.hands.Hands(
                static_image_mode=False, max_num_hands=2, model_complexity=0,
                min_detection_confidence=0.6, min_tracking_confidence=0.5)
        else:
            self._landmarker = self._make_landmarker(cache_dir)

    def _make_landmarker(self, cache_dir):
        from mediapipe.tasks import python as mp_python
        from mediapipe.tasks.python import vision

        os.makedirs(cache_dir, exist_ok=True)
        path = os.path.join(cache_dir, "hand_landmarker.task")
        if not os.path.exists(path) or os.path.getsize(path) < 100_000:
            print("[gestures] downloading the hand model (about 8 MB, one time)...")
            last_error = None
            for url in MODEL_URLS:
                try:
                    urllib.request.urlretrieve(url, path + ".part")
                    os.replace(path + ".part", path)
                    last_error = None
                    break
                except Exception as exc:
                    last_error = exc
            if last_error is not None:
                raise RuntimeError(f"could not download the hand model ({last_error})")
        options = vision.HandLandmarkerOptions(
            base_options=mp_python.BaseOptions(model_asset_path=path),
            num_hands=2, running_mode=vision.RunningMode.IMAGE)
        return vision.HandLandmarker.create_from_options(options)

    def detect(self, bgr):
        """Returns a list of hands; each hand is 21 (x, y) points in pixels."""
        h, w = bgr.shape[:2]
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        hands = []
        if self._hands is not None:
            result = self._hands.process(rgb)
            for hand in (result.multi_hand_landmarks or []):
                hands.append([(p.x * w, p.y * h) for p in hand.landmark])
        else:
            image = self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=rgb)
            result = self._landmarker.detect(image)
            for hand in result.hand_landmarks:
                hands.append([(p.x * w, p.y * h) for p in hand])
        return hands


class GestureThread(threading.Thread):
    """Watches the newest video frame about 12 times a second and starts effects."""

    def __init__(self, reader, engine, detector, tracker=None, interval=0.08):
        super().__init__(daemon=True)
        self.reader = reader
        self.engine = engine
        self.detector = detector
        self.tracker = tracker or GestureTracker()
        self.interval = interval
        self.stop_event = threading.Event()

    def stop(self):
        self.stop_event.set()

    def run(self):
        last_seq = -1
        while not self.stop_event.wait(self.interval):
            frame, seq = self.reader.latest()
            if frame is None or seq == last_seq:
                continue
            last_seq = seq
            try:
                h, w = frame.shape[:2]
                if w > 480:
                    frame = cv2.resize(frame, (480, max(1, int(h * 480 / w))), interpolation=cv2.INTER_AREA)
                names = [classify_hand(hand) for hand in self.detector.detect(frame)]
                effect = self.tracker.update(names, time.monotonic())
                if effect and self.engine.trigger(effect):
                    print(f"[gestures] {effect}")
            except Exception as exc:  # never let a detector hiccup kill the video
                print(f"[gestures] {exc!s}")
                self.stop_event.wait(1.0)


if __name__ == "__main__":
    # Used by Install-Gestures.bat: make sure hand tracking loads (and fetch its model).
    import numpy as np

    _cache = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "PhoneCam")
    HandDetector(_cache).detect(np.zeros((240, 320, 3), np.uint8))
    print("Hand tracking is ready.")
