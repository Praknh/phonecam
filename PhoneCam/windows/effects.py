import collections
import math
import random
import time
import cv2
import numpy as np
import sounds

EFFECTS = ("hearts", "balloons", "confetti", "fireworks", "rain")

_PINKS = [(147, 20, 255), (180, 105, 255), (60, 60, 235), (203, 192, 255)]
_BALLOONS = [(60, 60, 230), (230, 140, 50), (60, 210, 240), (90, 200, 90), (200, 90, 170)]
_CONFETTI = [(60, 60, 235), (60, 200, 250), (90, 210, 90), (240, 150, 60), (200, 90, 200)]
_SPARKS = [(60, 200, 255), (80, 80, 255), (255, 180, 80), (120, 255, 160), (255, 120, 220)]


def _heart_poly():
    t = np.linspace(0, 2 * np.pi, 36, endpoint=False)
    x = 16 * np.sin(t) ** 3
    y = 13 * np.cos(t) - 5 * np.cos(2 * t) - 2 * np.cos(3 * t) - np.cos(4 * t)
    return (np.stack([x, -y], axis=1) / 17.0).astype(np.float32)

_HEART = _heart_poly()


class Particle:
    __slots__ = ("kind", "x", "y", "vx", "vy", "size", "color", "age", "life", "phase", "target", "rot", "vrot")

    def __init__(self, kind, x, y, vx=0.0, vy=0.0, size=10.0, color=(255, 255, 255), life=5.0, phase=0.0, target=0.0, rot=0.0, vrot=0.0):
        self.kind = kind
        self.x = x
        self.y = y
        self.vx = vx
        self.vy = vy
        self.size = size
        self.color = color
        self.age = 0.0
        self.life = life
        self.phase = phase
        self.target = target
        self.rot = rot
        self.vrot = vrot


class EffectEngine:
    def __init__(self, width=1280, height=720):
        self.w = int(width)
        self.h = int(height)
        self.particles = []
        self._emitters = []
        self._scheduled = []
        self._last_time = None
        self._last_trigger = {}

    @property
    def active(self):
        return bool(self.particles or self._emitters or self._scheduled)

    @property
    def scale(self):
        return self.h / 720.0

    def set_size(self, width, height):
        self.w, self.h = int(width), int(height)

    def trigger(self, name: str, force=False):
        if name not in EFFECTS:
            return False
        now = time.monotonic()
        if not force and now - self._last_trigger.get(name, 0.0) < 2.5:
            return False
        self._last_trigger[name] = now

        sounds.play_sound(name)

        s = self.scale
        if name == "hearts":
            self._emitters.append([2.8, 14.0, 0.0, self._spawn_heart])
        elif name == "balloons":
            self._emitters.append([2.5, 8.0, 0.0, self._spawn_balloon])
        elif name == "confetti":
            self._emitters.append([1.8, 120.0, 0.0, self._spawn_confetti])
        elif name == "fireworks":
            for d in (0.0, 0.4, 0.8, 1.2):
                self._scheduled.append([d, self._spawn_rocket])
        elif name == "rain":
            self.particles.append(Particle("cloud", self.w * 0.5, 0, life=4.0))
            self._emitters.append([3.0, 160.0, 0.0, self._spawn_drop])
        return True

    def process(self, frame):
        if not self.active:
            self._last_time = None
            return frame
        now = time.monotonic()
        dt = 0.0 if self._last_time is None else min(0.1, max(0.0, now - self._last_time))
        self._last_time = now
        self._update(dt)
        return self._draw(frame)

    def _spawn_heart(self):
        s = self.scale
        self.particles.append(Particle(
            "heart", random.uniform(0.1, 0.9) * self.w, self.h + 30 * s,
            vy=-random.uniform(140, 220) * s, size=random.uniform(20, 42) * s,
            color=random.choice(_PINKS), life=7.0, phase=random.uniform(0, 6.28)
        ))

    def _spawn_balloon(self):
        s = self.scale
        self.particles.append(Particle(
            "balloon", random.uniform(0.1, 0.9) * self.w, self.h + 50 * s,
            vy=-random.uniform(120, 180) * s, size=random.uniform(32, 54) * s,
            color=random.choice(_BALLOONS), life=9.0, phase=random.uniform(0, 6.28)
        ))

    def _spawn_confetti(self):
        s = self.scale
        self.particles.append(Particle(
            "confetti", random.uniform(0, self.w), -10,
            vx=random.uniform(-50, 50) * s, vy=random.uniform(160, 320) * s,
            size=random.uniform(8, 16) * s, color=random.choice(_CONFETTI),
            rot=random.uniform(0, 6.28), vrot=random.uniform(-5, 5), life=7.0
        ))

    def _spawn_rocket(self):
        s = self.scale
        self.particles.append(Particle(
            "rocket", random.uniform(0.2, 0.8) * self.w, self.h,
            vy=-random.uniform(700, 850) * s, size=3 * s, color=(120, 220, 255),
            life=4.0, target=random.uniform(0.2, 0.45) * self.h
        ))

    def _spawn_drop(self):
        s = self.scale
        self.particles.append(Particle(
            "drop", random.uniform(0, self.w), self.h * 0.1,
            vy=random.uniform(750, 950) * s, size=random.uniform(14, 24) * s,
            color=(235, 200, 160), life=3.0
        ))

    def _explode(self, rk):
        s = self.scale
        for _ in range(80):
            ang = random.uniform(0, 2 * math.pi)
            spd = random.uniform(100, 350) * s
            self.particles.append(Particle(
                "spark", rk.x, rk.y, vx=math.cos(ang) * spd, vy=math.sin(ang) * spd,
                size=random.uniform(3, 6) * s, color=random.choice(_SPARKS), life=random.uniform(0.8, 1.5)
            ))

    def _update(self, dt):
        s = self.scale

        rem_sched = []
        for item in self._scheduled:
            item[0] -= dt
            if item[0] <= 0: item[1]()
            else: rem_sched.append(item)
        self._scheduled = rem_sched

        rem_emit = []
        for em in self._emitters:
            em[0] -= dt
            em[2] += em[1] * dt
            c = int(em[2])
            em[2] -= c
            for _ in range(c): em[3]()
            if em[0] > 0: rem_emit.append(em)
        self._emitters = rem_emit

        alive = []
        for p in self.particles:
            p.age += dt
            if p.age >= p.life: continue
            k = p.kind
            if k == "heart":
                p.x += math.sin(p.phase + p.age * 2.2) * 30 * s * dt
                p.y += p.vy * dt
            elif k == "balloon":
                p.x += math.sin(p.phase + p.age * 1.5) * 25 * s * dt
                p.y += p.vy * dt
            elif k == "confetti":
                p.x += p.vx * dt
                p.y += p.vy * dt
                p.rot += p.vrot * dt
            elif k == "rocket":
                p.y += p.vy * dt
                p.vy += 150 * s * dt
                if p.y <= p.target:
                    self._explode(p)
                    continue
            elif k == "spark":
                p.x += p.vx * dt
                p.y += p.vy * dt
                p.vy += 200 * s * dt
            elif k == "drop":
                p.y += p.vy * dt
            alive.append(p)
        self.particles = alive

    def _draw(self, frame):
        s = self.scale
        aa = cv2.LINE_AA
        for p in self.particles:
            k = p.kind
            x, y = int(p.x), int(p.y)
            if k == "heart":
                pts = (_HEART * p.size + np.array([p.x, p.y], np.float32)).astype(np.int32)
                cv2.fillPoly(frame, [pts], p.color, aa)
            elif k == "balloon":
                a, b = max(2, int(p.size * 0.8)), max(2, int(p.size))
                cv2.ellipse(frame, (x, y), (a, b), 0, 0, 360, p.color, -1, aa)
                cv2.line(frame, (x, y + b), (x, y + b + int(p.size * 1.5)), (220, 220, 220), 1, aa)
            elif k == "confetti":
                hw, hh = p.size / 2, p.size / 2 * abs(math.cos(p.rot))
                cv2.rectangle(frame, (int(p.x - hw), int(p.y - hh)), (int(p.x + hw), int(p.y + hh)), p.color, -1)
            elif k == "spark":
                r = max(1, int(p.size * (1.0 - p.age / p.life)))
                cv2.circle(frame, (x, y), r, p.color, -1, aa)
            elif k == "drop":
                cv2.line(frame, (x, y), (x, y + int(p.size)), p.color, 2, aa)
            elif k == "cloud":
                cy = int(0.08 * self.h)
                for off, rad in ((-0.2, 0.10), (0.0, 0.14), (0.2, 0.10)):
                    cv2.ellipse(frame, (int(self.w * (0.5 + off)), cy), (int(self.w * rad), int(self.h * rad * 0.6)), 0, 0, 360, (120, 120, 130), -1, aa)
        return frame
