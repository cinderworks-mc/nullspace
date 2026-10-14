"""Synthesises Nullspace's sound effects (original, procedural). Needs: pip install numpy soundfile.
Run from the project root: python3 assets/scripts/make_sounds.py
Writes mono 44.1 kHz Ogg Vorbis files to src/main/resources/assets/nullspace/sounds/. Restrained by design: short, soft, warm."""
import os
import numpy as np
import soundfile as sf

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "nullspace", "sounds")
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(7)


def t(d): return np.linspace(0, d, int(SR * d), endpoint=False)

def env(n, a=0.01, r=0.12, curve=2.0):
    e = np.ones(n); na, nr = int(SR * a), int(SR * r)
    e[:na] = np.linspace(0, 1, na) ** 1.5
    e[-nr:] *= np.linspace(1, 0, nr) ** curve
    return e

def tone(freq, d, shape="sine", vib=0.0, detune=0.0):
    x = t(d); f = freq * (1 + vib * np.sin(2 * np.pi * 5.5 * x))
    ph = 2 * np.pi * np.cumsum(f) / SR
    s = np.sin(ph)
    if shape == "tri": s = 2 / np.pi * np.arcsin(np.sin(ph))
    if shape == "soft": s = np.sin(ph) + 0.25 * np.sin(2 * ph) + 0.08 * np.sin(3 * ph)
    if detune: s = 0.5 * (s + np.sin(2 * np.pi * np.cumsum(f * (1 + detune)) / SR))
    return s

def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR); y = np.zeros_like(x); p = 0.0
    for i, v in enumerate(x): p = (1 - a) * v + a * p; y[i] = p
    return y

def place(buf, sig, start):
    i = int(SR * start); buf[i:i + len(sig)] += sig[:max(0, len(buf) - i)]

TARGET_RMS_DB = -19.5  # RMS over the audible part. Vanilla block/UI sounds (random/click, orb pickup, wooden door) measure -21 to -23 dB; the game plays ours at volume 0.7 (ArchiveService.play), which lands near them.

def save(name, x, gain=0.5, rms_db=None):
    """gain = peak level (linear) when rms_db is None; otherwise the audible-part RMS is set to rms_db (peak stays below -3 dBFS)."""
    x = x / (np.max(np.abs(x)) + 1e-9)
    if rms_db is None: x = x * gain
    else:
        m = np.abs(x) > 10 ** (-50 / 20)
        x = x * 10 ** (rms_db / 20) / np.sqrt(np.mean(x[m] ** 2))
        x = x * min(1.0, 0.7 / np.max(np.abs(x)))
    sf.write(os.path.join(OUT, name + ".ogg"), x.astype(np.float32), SR, format="OGG", subtype="VORBIS")

def blip(f, d, shape="soft"): return tone(f, d, shape, vib=0.004) * env(int(SR * d), 0.008, d * 0.7)

# terminal open: two soft rising blips
b = np.zeros(int(SR * 0.45)); place(b, blip(523.25, 0.16), 0); place(b, blip(784.0, 0.22), 0.1); save("terminal_open", lowpass(b, 5000), 0.5, rms_db=TARGET_RMS_DB)
# confirm: warm three-note arpeggio
b = np.zeros(int(SR * 0.7))
for i, f in enumerate((523.25, 659.25, 783.99)): place(b, blip(f, 0.3, "tri"), i * 0.11)
save("terminal_confirm", lowpass(b, 4500), 0.5, rms_db=TARGET_RMS_DB)
# deny: low double buzz
b = np.zeros(int(SR * 0.35))
for i in range(2): place(b, tone(150, 0.12, "tri", detune=0.01) * env(int(SR * 0.12), 0.005, 0.08), i * 0.15)
save("terminal_deny", lowpass(b, 1800), 0.5, rms_db=TARGET_RMS_DB)
# exit: warm swell hum with a soft chime on top
d = 1.1; x = tone(110, d, "soft", detune=0.006) * 0.8 + tone(165, d, "sine") * 0.3
x = x * (np.sin(np.pi * np.linspace(0, 1, len(x))) ** 1.5)
b = x.copy(); place(b, blip(880, 0.5, "sine") * 0.25, 0.35); save("exit_open", lowpass(b, 3000), 0.5, rms_db=TARGET_RMS_DB)
# applicator: soft brush stroke (filtered noise sweep)
n = rng.normal(size=int(SR * 0.32)); n = lowpass(n, 2600) - lowpass(n, 500)
n *= np.sin(np.pi * np.linspace(0, 1, len(n))) ** 1.2; save("applicator", n, 0.4)
# room change: deep settle + chime
b = np.zeros(int(SR * 1.5)); place(b, tone(55, 1.2, "soft") * env(int(SR * 1.2), 0.05, 0.9) * 0.9, 0)
for i, f in enumerate((392.0, 523.25, 659.25)): place(b, blip(f, 0.6, "sine") * 0.35, 0.25 + i * 0.16)
save("room_change", lowpass(b, 3500), 0.5, rms_db=TARGET_RMS_DB)
# anchor: slow pulse with shimmer
b = np.zeros(int(SR * 1.3))
for i in range(2): place(b, tone(82.4, 0.5, "soft") * env(int(SR * 0.5), 0.02, 0.4) * 0.8, i * 0.4)
place(b, tone(1318.5, 0.9, "sine", vib=0.01) * env(int(SR * 0.9), 0.2, 0.6) * 0.12, 0.2)
save("anchor_install", lowpass(b, 3000), 0.5, rms_db=TARGET_RMS_DB)
print("wrote", sorted(os.listdir(OUT)))
