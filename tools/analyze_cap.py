#!/usr/bin/env python3
"""Analyze an rtl_sdr .cu8 capture for a TX carrier during the key-down window.

Usage: analyze_cap.py <file.cu8> <sample_rate> <keydown_start_s> <keydown_end_s>
Prints mean power per 0.5 s block (dBFS) and flags blocks above threshold.
"""
import sys
import numpy as np

path, fs, kd0, kd1 = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])

raw = np.fromfile(path, dtype=np.uint8)
n = (len(raw) // 2) * 2
iq = (raw[:n].astype(np.float32).reshape(-1, 2) - 127.5)
pwr = (iq ** 2).sum(axis=1)  # instantaneous power

blk = int(fs * 0.5)  # 0.5 s blocks
nb = len(pwr) // blk
mean = pwr[: nb * blk].reshape(nb, blk).mean(axis=1)
db = 10 * np.log10(mean + 1e-12)

print("samples: %d (%.1f s)" % (len(pwr), len(pwr) / fs))
print("key-down window: %.1f-%.1f s" % (kd0, kd1))
base = np.median(db)
print("median block power: %.1f dBFS" % base)
print()
print("blk  t(s)    dBFS   flag")
for i, v in enumerate(db):
    t = i * 0.5
    flag = ""
    if kd0 <= t < kd1:
        flag += "KEYDOWN"
    if v > base + 6:
        flag += (" " if flag else "") + "HOT(+%.0fdB)" % (v - base)
    print("%3d  %5.1f  %6.1f   %s" % (i, t, v, flag))

hot = [i for i, v in enumerate(db) if v > base + 6]
kd_hot = [i for i in hot if kd0 <= i * 0.5 < kd1]
print()
print("hot blocks total: %d, hot blocks inside key-down window: %d" % (len(hot), len(kd_hot)))
if kd_hot:
    print("RESULT: CARRIER DETECTED during key-down")
else:
    print("RESULT: no carrier detected during key-down")
