"""
Live video effects drawn on top of the picture before it goes to the virtual camera
(hearts, balloons, confetti, fireworks, rain), similar to the video reactions on a Mac.

Pure OpenCV + numpy. Trigger them with EffectEngine.trigger(name); call render(frame)
for every output frame. Safe to call trigger() from another thread.
"""

import collections
import math
import random
import time

import cv2
import numpy as np

EFFECTS = ("hearts", "balloons", "confetti", "fireworks", "rain")

COOLDOWN = 2.5  # seconds before the same effect can be started again (unless forced)

# BGR colours
_PINKS = [(147, 20, 255), (180, 105, 255), (60, 60, 235), (203, 192, 255)]
_BALLOONS = [(60, 60, 230), (230, 140, 50), (60, 210, 240), (90, 200, 90), (200, 90, 170), (50, 150, 250)]
_CONFETTI = [(60, 60, 235), (60, 200, 250), (90, 210, 90), (240, 150, 60), (200, 90, 200), (250, 250, 250)]
_SPARKS = [(60, 200, 255), (80, 80, 255), (255, 180, 80), (120, 255, 160), (255, 120, 220), (255, 255, 255)]


def _heart_outline():
    t = np.linspace(0, 2 * np.pi, 36, endpoint=False)
    x = 16 * np.sin(t) ** 3
    y = 13 * np.cos(t) - 5 * np.cos(2 * t) - 2 * np.cos(3 * t) - np.cos(4 * t)
    return (np.stack([x, -y], axis=1) / 17.0).astype(np.float32)  # image y points down


_HEART = _heart_outline()


class _P:
    __slots__ = ("kind", "x", "y", "vx", "vy", "size", "color", "age", "life", "rot", "vrot", "phase", "target")

    def __init__(self, kind, x, y, vx=0.0, vy=0.0, size=10.0, color=(255, 255, 255),
                 life=6.0, rot=0.0, vrot=0.0, phase=0.0, target=0.0):
        self.kind = kind
        self.x = x
        self.y = y
        self.vx = vx
        self.vy = vy
        self.size = size
        self.color = color
        self.age = 0.0
        self.life = life
        self.rot = rot
        self.vrot = vrot
        self.phase = phase
        self.target = target


class EffectEngine:
    def __init__(self, width, height, seed=None):
        self.w = int(width)
        self.h = int(height)
        self.rng = random.Random(seed)
        self.particles = []
        self._emitters = []   # [name, seconds_left, rate_per_second, accumulator, spawn_fn]
        self._scheduled = []  # [seconds_until, fn]
        self._queue = collections.deque()
        self._last_time = None
        self._last_started = {}

    # ------------------------------------------------------------ public API

    @property
    def active(self):
        return bool(self.particles or self._emitters or self._scheduled or self._queue)

    @property
    def scale(self):
        return self.h / 720.0

    def set_size(self, width, height):
        if (int(width), int(height)) != (self.w, self.h):
            self.w, self.h = int(width), int(height)
            self.clear()

    def clear(self):
        self.particles = []
        self._emitters = []
        self._scheduled = []
        self._queue.clear()

    def trigger(self, name, force=False, now=None):
        """Queue an effect. Returns False if the name is unknown or it is still cooling down."""
        if name not in EFFECTS:
            return False
        now = time.monotonic() if now is None else now
        last = self._last_started.get(name)
        if not force and last is not None and now - last < COOLDOWN:
            return False
        self._last_started[name] = now
        self._queue.append(name)
        return True

    def process(self, frame, now=None):
        """Returns `frame` untouched when nothing is playing, otherwise an edited copy."""
        if not self.active:
            self._last_time = None
            return frame
        return self.render(frame.copy(), now)

    def render(self, frame, now=None):
        """Advance the animation and draw it onto `frame` (in place). Returns `frame`."""
        now = time.monotonic() if now is None else now
        dt = 0.0 if self._last_time is None else min(0.1, max(0.0, now - self._last_time))
        self._last_time = now
        while self._queue:
            self._start(self._queue.popleft())
        self._update(dt)
        self._draw(frame)
        return frame

    # ----------------------------------------------------------- starting

    def _start(self, name):
        s = self.scale
        r = self.rng
        if name == "hearts":
            self._emitters.append([name, 3.0, 14.0, 0.0, self._spawn_heart])
        elif name == "balloons":
            self._emitters.append([name, 2.5, 7.0, 0.0, self._spawn_balloon])
        elif name == "confetti":
            self._emitters.append([name, 1.8, 110.0, 0.0, self._spawn_confetti])
        elif name == "fireworks":
            for delay in (0.0, 0.45, 0.9, 1.4):
                self._scheduled.append([delay, self._spawn_rocket])
        elif name == "rain":
            self.particles.append(_P("cloud", self.w * 0.5, 0, life=4.0))
            self._emitters.append([name, 3.2, 150.0, 0.0, self._spawn_drop])

    def _spawn_heart(self):
        s, r = self.scale, self.rng
        size = r.uniform(18, 46) * s
        self.particles.append(_P(
            "heart", r.uniform(0.05, 0.95) * self.w, self.h + size,
            vx=0.0, vy=-r.uniform(120, 230) * s, size=size, color=r.choice(_PINKS),
            life=8.0, phase=r.uniform(0, 6.28)))

    def _spawn_balloon(self):
        s, r = self.scale, self.rng
        size = r.uniform(34, 58) * s
        self.particles.append(_P(
            "balloon", r.uniform(0.06, 0.94) * self.w, self.h + size * 1.5,
            vy=-r.uniform(110, 190) * s, size=size, color=r.choice(_BALLOONS),
            life=10.0, phase=r.uniform(0, 6.28)))

    def _spawn_confetti(self):
        s, r = self.scale, self.rng
        self.particles.append(_P(
            "confetti", r.uniform(0, self.w), -10 * s,
            vx=r.uniform(-60, 60) * s, vy=r.uniform(150, 300) * s, size=r.uniform(8, 16) * s,
            color=r.choice(_CONFETTI), life=8.0, rot=r.uniform(0, 6.28),
            vrot=r.uniform(-6, 6), phase=r.uniform(0, 6.28)))

    def _spawn_rocket(self):
        s, r = self.scale, self.rng
        self.particles.append(_P(
            "rocket", r.uniform(0.2, 0.8) * self.w, self.h,
            vy=-r.uniform(650, 800) * s, size=3 * s, color=(120, 220, 255),
            life=4.0, target=r.uniform(0.2, 0.45) * self.h))

    def _spawn_drop(self):
        s, r = self.scale, self.rng
        self.particles.append(_P(
            "drop", r.uniform(0, self.w), self.h * 0.12,
            vy=r.uniform(700, 900) * s, size=r.uniform(14, 24) * s, color=(235, 200, 160), life=3.0))

    def _explode(self, rocket, out):
        s, r = self.scale, self.rng
        palette = r.choice([_SPARKS, [_SPARKS[0], _SPARKS[5]], [_SPARKS[1], _SPARKS[2]], [_SPARKS[3], _SPARKS[4]]])
        for _ in range(95):
            angle = r.uniform(0, 2 * math.pi)
            speed = r.uniform(120, 380) * s
            out.append(_P(
                "spark", rocket.x, rocket.y,
                vx=math.cos(angle) * speed, vy=math.sin(angle) * speed,
                size=r.uniform(4.0, 7.5) * s, color=r.choice(palette), life=r.uniform(1.0, 1.7)))

    # ----------------------------------------------------------- updating

    def _update(self, dt):
        s = self.scale

        still = []
        for item in self._scheduled:
            item[0] -= dt
            if item[0] <= 0:
                item[1]()
            else:
                still.append(item)
        self._scheduled = still

        running = []
        for em in self._emitters:
            em[1] -= dt
            em[3] += em[2] * dt
            count = int(em[3])
            em[3] -= count
            for _ in range(count):
                em[4]()
            if em[1] > 0:
                running.append(em)
        self._emitters = running

        alive = []
        spawned = []
        for p in self.particles:
            p.age += dt
            keep = p.age < p.life
            k = p.kind
            if k == "heart":
                p.x += (math.sin(p.phase + p.age * 2.2) * 35 * s) * dt
                p.y += p.vy * dt
                keep = keep and p.y > -p.size * 2
            elif k == "balloon":
                p.x += (math.sin(p.phase + p.age * 1.6) * 28 * s) * dt
                p.y += p.vy * dt
                keep = keep and p.y > -p.size * 3.5
            elif k == "confetti":
                p.x += p.vx * dt + math.sin(p.age * 5 + p.phase) * 40 * s * dt
                p.y += p.vy * dt
                p.rot += p.vrot * dt
                keep = keep and p.y < self.h + 30
            elif k == "rocket":
                p.y += p.vy * dt
                p.vy += 120 * s * dt
                if p.y <= p.target or p.vy >= -100 * s:
                    self._explode(p, spawned)
                    keep = False
            elif k == "spark":
                p.vy += 220 * s * dt
                p.x += p.vx * dt
                p.y += p.vy * dt
            elif k == "drop":
                p.y += p.vy * dt
                keep = keep and p.y < self.h + 30
            if keep:
                alive.append(p)
        alive.extend(spawned)
        self.particles = alive

    # ------------------------------------------------------------ drawing

    def _draw(self, frame):
        s = self.scale
        aa = cv2.LINE_AA
        for p in self.particles:
            k = p.kind
            x, y, size = p.x, p.y, p.size
            if k == "heart":
                pts = (_HEART * size + np.array([x, y], np.float32)).astype(np.int32)
                cv2.fillPoly(frame, [pts], p.color, aa)
                cv2.circle(frame, (int(x - size * 0.4), int(y - size * 0.35)), max(1, int(size * 0.14)), (255, 255, 255), -1, aa)
            elif k == "balloon":
                a, b = max(2, int(size * 0.8)), max(2, int(size))
                cx, cy = int(x), int(y)
                cv2.ellipse(frame, (cx, cy), (a, b), 0, 0, 360, p.color, -1, aa)
                cv2.ellipse(frame, (int(x - a * 0.35), int(y - b * 0.4)), (max(1, a // 5), max(1, b // 4)), -20, 0, 360, (255, 255, 255), -1, aa)
                knot = np.array([[cx, cy + b], [cx - 4 * s, cy + b + 8 * s], [cx + 4 * s, cy + b + 8 * s]], np.float32).astype(np.int32)
                cv2.fillConvexPoly(frame, knot, p.color, aa)
                sway = math.sin(p.phase + p.age * 3) * 7 * s
                string = np.array([
                    [cx, cy + b + 8 * s], [cx + sway, cy + b + size * 1.0],
                    [cx - sway, cy + b + size * 1.7], [cx, cy + b + size * 2.3]], np.float32).astype(np.int32)
                cv2.polylines(frame, [string], False, (225, 225, 225), 1, aa)
            elif k == "confetti":
                flip = max(0.15, abs(math.cos(p.age * 8 + p.phase)))
                hw, hh = size / 2, size * 0.5 * flip
                c, sn = math.cos(p.rot), math.sin(p.rot)
                corners = np.array(
                    [[x + dx * c - dy * sn, y + dx * sn + dy * c] for dx, dy in ((-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh))],
                    np.float32).astype(np.int32)
                cv2.fillConvexPoly(frame, corners, p.color, aa)
            elif k == "spark":
                radius = max(1, int(p.size * (1.0 - p.age / p.life)))
                cv2.circle(frame, (int(x), int(y)), radius, p.color, -1, aa)
            elif k == "rocket":
                cv2.line(frame, (int(x), int(y)), (int(x), int(y + 28 * s)), (60, 140, 255), max(1, int(2 * s)), aa)
                cv2.circle(frame, (int(x), int(y)), max(2, int(size)), (255, 255, 255), -1, aa)
            elif k == "drop":
                cv2.line(frame, (int(x), int(y)), (int(x), int(y + size)), p.color, max(1, int(2 * s)), aa)
            elif k == "cloud":
                slide_in = min(1.0, p.age / 0.6)
                slide_out = min(1.0, max(0.0, (p.life - p.age) / 0.6))
                cy = int((-0.06 + 0.2 * min(slide_in, slide_out)) * self.h)
                for off, rad, shade in ((-0.26, 0.09, 105), (-0.13, 0.12, 120), (0.0, 0.14, 135), (0.13, 0.12, 120), (0.26, 0.09, 105)):
                    cv2.ellipse(frame, (int(self.w * (0.5 + off)), cy),
                                (int(self.w * rad), int(self.h * rad * 0.6)), 0, 0, 360,
                                (shade, shade, shade + 12), -1, aa)
