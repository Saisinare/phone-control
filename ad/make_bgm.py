"""Generates the ad's background music (original, synthesized) -> public/bgm.wav.

96 bpm, 13 bars: soft pad and plucked arpeggio from the start, kick and bass from bar 3
(the "one command" scene), hats and snaps from the showcase on, drums drop for the last bar.
"""
import os, wave
import numpy as np

SR = 44100
BEAT = 60 / 96
BARS = 13
N = int(SR * BEAT * 4 * BARS)
L = np.zeros(N)
R = np.zeros(N)
rng = np.random.default_rng(7)


def freq(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


def add(start, sig, pan=0.5, gain=1.0):
    i = int(start * SR)
    n = min(len(sig), N - i)
    if n > 0:
        L[i:i + n] += sig[:n] * gain * (1 - pan)
        R[i:i + n] += sig[:n] * gain * pan


def pluck(midi):
    t = np.arange(int(0.9 * SR)) / SR
    f = freq(midi)
    tone = np.sin(2 * np.pi * f * t) + 0.35 * np.sin(4 * np.pi * f * t) + 0.12 * np.sin(6 * np.pi * f * t)
    return tone * np.exp(-t / 0.16) * np.minimum(1, t / 0.004)


def pad(midi, dur):
    t = np.arange(int(dur * SR)) / SR
    f = freq(midi)
    tone = np.sin(2 * np.pi * f * t) + 0.5 * np.sin(2 * np.pi * f * 1.004 * t) + 0.22 * np.sin(4 * np.pi * f * t)
    return tone * np.minimum(1, t / 0.5) * np.minimum(1, (dur - t) / 0.6)


def kick():
    t = np.arange(int(0.28 * SR)) / SR
    f = 45 + 80 * np.exp(-t / 0.03)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.12)


def bass(midi, dur=0.55):
    t = np.arange(int(dur * SR)) / SR
    f = freq(midi)
    return (np.sin(2 * np.pi * f * t) + 0.25 * np.sin(4 * np.pi * f * t)) * np.exp(-t / 0.3) * np.minimum(1, t / 0.01)


def noise(dur, tau):
    t = np.arange(int(dur * SR)) / SR
    n = rng.standard_normal(len(t))
    n = np.diff(n, prepend=0.0)  # crude high-pass
    return n * np.exp(-t / tau)


CHORDS = [
    (45, [57, 60, 64, 67]),  # Am7
    (41, [57, 60, 64, 65]),  # Fmaj7
    (48, [55, 59, 60, 64]),  # Cmaj7
    (43, [55, 59, 62, 64]),  # G6
]
ORDER = [0, 2, 1, 3, 2, 3, 1, 2]

for bar in range(BARS):
    root, notes = CHORDS[bar % 4]
    t0 = bar * 4 * BEAT
    for k, m in enumerate(notes):
        add(t0, pad(m, 4 * BEAT + 0.6), pan=0.35 + 0.1 * k, gain=0.07)
    for s in range(8):
        vel = (1.0 if s % 2 == 0 else 0.7) * ((s + 1) / 8 if bar == 0 else 1.0)
        sig = pluck(notes[ORDER[s]] + 12)
        start = t0 + s * BEAT / 2
        add(start, sig, pan=0.3 if s % 2 == 0 else 0.7, gain=0.22 * vel)
        add(start + BEAT * 0.75, sig, pan=0.8 if s % 2 == 0 else 0.2, gain=0.07 * vel)  # echo
    if 2 <= bar < BARS - 1:
        for b in (0, 2):
            add(t0 + b * BEAT, kick(), gain=0.75)
        for b in (0, 1.5, 2.5):
            add(t0 + b * BEAT, bass(root - 12 if root > 44 else root), gain=0.42)
    for s in range(8):
        when = bar * 4 + s / 2  # in beats
        if 14 <= when < (BARS - 1) * 4:
            if s % 2 == 1:
                add(t0 + s * BEAT / 2, noise(0.06, 0.012), pan=0.6, gain=0.05)
            if s in (2, 6):
                add(t0 + s * BEAT / 2, noise(0.14, 0.035), pan=0.45, gain=0.075)

mix = np.stack([L, R], axis=1)
fade = np.ones(N)
fade[: int(0.3 * SR)] = np.linspace(0, 1, int(0.3 * SR))
fade[-int(3 * SR):] = np.linspace(1, 0, int(3 * SR))
mix *= fade[:, None]
mix = np.tanh(mix / np.max(np.abs(mix)) * 1.4) / np.tanh(1.4) * 0.85

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "public", "bgm.wav")
with wave.open(out, "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((mix * 32767).astype("<i2").tobytes())
print(f"{out}: {N / SR:.1f}s")
