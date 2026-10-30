"""Real recordings (CC0 previews from freesound.org) cut into the mod's sounds; gen_assets.py calls main() after
its synthesised ones, so these replace them. The mp3s are cached in ../models/sounds (not in the repo)."""
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.request

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_assets as ga

SR = 44100
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOUNDS_DIR = os.environ.get("SOUNDS_DIR", os.path.join(os.path.dirname(ROOT), "models", "sounds"))
SOURCES = {
    677833: "Tiny Hawk 2 short flight.wav, Sadiquecat",
    683300: "Dji Mavic Mini 2 take-off, Sadiquecat",
    412819: "two-stroke Trabant engine 01.wav, blukotek",
    437888: "G08-25_4-E Jet Constant Noise.wav, craigsmith",
    187564: "Truck Engine Idle Loops.flac, qubodup",
    146770: "Rocket Boost Engine Loop.wav, qubodup",
    854186: "Autocannon Three Shot Burst, qubodup",
    184274: "Launching Anti-Tank Missiles.flac, qubodup",
    521377: "Rocket Launch, Jarusca",
}
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"}


def fetch(sid):
    path = os.path.join(SOUNDS_DIR, f"{sid}.mp3")
    if os.path.exists(path):
        return path
    os.makedirs(SOUNDS_DIR, exist_ok=True)
    for attempt in range(4):
        try:
            page = urllib.request.urlopen(urllib.request.Request(f"https://freesound.org/s/{sid}/", headers=UA),
                                          timeout=40).read()
            m = re.search(rb"previews/(\d+)/%d_(\d+)-(?:lq|hq)\.mp3" % sid, page)
            url = f"https://cdn.freesound.org/previews/{m.group(1).decode()}/{sid}_{m.group(2).decode()}-hq.mp3"
            data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()
            break
        except Exception:
            if attempt == 3:
                raise
            time.sleep(40)
    open(path, "wb").write(data)
    time.sleep(3)
    return path


def load(sid):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", fetch(sid), "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, "<i2").astype(np.float64) / 32768


def cut(x, a, b, fade=0.25):
    y = x[int(a * SR):int(b * SR)].copy()
    n = len(y)
    y[:64] *= np.linspace(0, 1, min(64, n))
    k = int(n * fade)
    if k:
        y[-k:] *= np.linspace(1, 0, k) ** 1.5
    return y


def loop(x, a, length, xfade=0.25):
    """a seamless loop of {length} seconds from a: the tail past the end is crossfaded into the start"""
    n, f = int(length * SR), int(xfade * SR)
    y = x[int(a * SR):int(a * SR) + n + f].copy()
    w = np.sin(np.linspace(0, np.pi / 2, f)) ** 2
    y[:f] = y[:f] * w + y[n:n + f] * (1 - w)
    return y[:n]


def flatten(x, window=0.25):
    """even out the level (a drone flying off gets quieter): divide by the moving RMS"""
    n = max(1, int(window * SR))
    env = np.sqrt(np.convolve(x * x, np.ones(n) / n, mode="same"))
    return x / np.maximum(env, env.max() * 0.05)


def steady(x, a, b, seed=7):
    """the spectrum of a..b with random phases: a seamless loop of b - a seconds (the inverse FFT is circular)
    with no event in it to recognise each time it comes round, the rotors' tones are kept"""
    y = x[int(a * SR):int(b * SR)]
    spec = np.abs(np.fft.rfft(y))
    phase = np.random.default_rng(seed).uniform(0, 2 * np.pi, len(spec))
    phase[0] = 0
    return np.fft.irfft(spec * np.exp(1j * phase), len(y))


def highpass(x, hz):
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / SR)
    spec[freqs < hz] *= (freqs[freqs < hz] / hz) ** 2
    return np.fft.irfft(spec, len(x))


def pitch(x, k):
    idx = np.arange(0, len(x) - 1, k)
    return np.interp(idx, np.arange(len(x)), x)


def write(name, sig):
    sig = sig / (np.abs(sig).max() + 1e-9) * 0.95
    out = os.path.join(ga.ASSETS, "sounds", name + ".ogg")
    with tempfile.NamedTemporaryFile(suffix=".raw", delete=False) as f:
        f.write((sig * 32767).astype("<i2").tobytes())
        raw = f.name
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(SR), "-ac", "1", "-i", raw,
                    "-c:a", "libvorbis", "-q:a", "6", out], check=True)
    os.unlink(raw)
    print(f"  {name}: {len(sig) / SR:.2f} s")


def main():
    try:
        write("fpv_motor", loop(highpass(load(677833), 120), 13.0, 2.0))
        # the hover after the take-off, 3..11 s, turned into a steady sound
        write("mavic_motor", steady(flatten(highpass(load(683300), 150)), 3.0, 11.0))
        write("shahed_engine", loop(load(412819), 12.0, 2.0))
        write("jet_engine", loop(load(437888), 12.0, 3.0, 0.4))
        write("truck_engine", loop(load(187564), 1.0, 2.0))
        write("missile_motor", loop(load(146770), 0.3, 3.5, 0.4))
        write("cannon_30", cut(load(854186), 0.0, 0.27, 0.35))
        write("atgm_launch", cut(load(184274), 9.98, 13.5, 0.4))
        # the Shahed's booster: a rocket kick, then the engine catching
        rocket = cut(load(521377), 0.0, 2.2, 0.45)
        write("shahed_launch", rocket)
    except Exception as e:
        print("real sounds skipped (synthesised ones stay):", e)


if __name__ == "__main__":
    main()
