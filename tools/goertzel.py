#!/usr/bin/env python3
"""
RumiTX CTCSS Tone Detector — Goertzel algorithm prototype.
Detects which of the 50 standard CTCSS tones (67.0-254.1 Hz) is present
in an audio sample. This is the core of the Discovery Mode tone decoder.

Test: python3 goertzel.py
"""

import math
import struct

# 50 standard CTCSS tones (Hz)
CTCSS_TONES = [
    67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5,
    94.8, 97.4, 100.0, 103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3,
    131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 159.8, 162.2, 165.5, 167.9,
    171.3, 173.8, 177.3, 179.9, 183.5, 186.2, 189.9, 192.8, 196.6, 199.5,
    203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8, 250.3, 254.1,
]

def goertzel(samples, sample_rate, target_freq):
    """
    Goertzel algorithm: efficient detection of a single frequency component.
    Returns the magnitude of target_freq in the samples.
    """
    n = len(samples)
    # Normalized frequency
    k = int(0.5 + (n * target_freq / sample_rate))
    omega = 2.0 * math.pi * k / n
    sine = math.sin(omega)
    cosine = math.cos(omega)
    coeff = 2.0 * cosine

    s0, s1, s2 = 0.0, 0.0, 0.0
    for sample in samples:
        s0 = sample + coeff * s1 - s2
        s2 = s1
        s1 = s0

    real = s1 * cosine - s2
    imag = s1 * sine
    return math.sqrt(real * real + imag * imag)

def detect_ctcss(samples, sample_rate=8000):
    """
    Run Goertzel on all 50 CTCSS tones, return the strongest match.
    Returns (tone_freq, magnitude, all_magnitudes).
    """
    results = []
    for tone in CTCSS_TONES:
        mag = goertzel(samples, sample_rate, tone)
        results.append((tone, mag))

    results.sort(key=lambda x: x[1], reverse=True)
    best_tone, best_mag = results[0]
    # Second best for confidence ratio
    _, second_mag = results[1] if len(results) > 1 else (0, 0)

    confidence = best_mag / second_mag if second_mag > 0 else float('inf')
    return best_tone, best_mag, confidence, results

def generate_test_tone(freq, duration_sec=1.0, sample_rate=8000, amplitude=0.5, noise_level=0.05):
    """Generate a test signal: sine wave at freq + white noise (simulates radio audio)."""
    import random
    n = int(duration_sec * sample_rate)
    samples = []
    for i in range(n):
        t = i / sample_rate
        signal = amplitude * math.sin(2 * math.pi * freq * t)
        # Add some voice-like noise (higher freq content)
        voice = 0.1 * math.sin(2 * math.pi * 440 * t) * math.sin(2 * math.pi * 3 * t)
        noise = noise_level * (random.random() * 2 - 1)
        samples.append(signal + voice + noise)
    return samples


def main():
    print("RumiTX CTCSS Detector — Goertzel Prototype")
    print("=" * 50)

    # Test each of a few representative tones
    test_tones = [67.0, 100.0, 127.3, 167.9, 254.1]
    sample_rate = 8000

    all_pass = True
    for target in test_tones:
        samples = generate_test_tone(target, duration_sec=1.0, sample_rate=sample_rate)
        detected, mag, conf, _ = detect_ctcss(samples, sample_rate)

        match = "PASS" if abs(detected - target) < 0.1 else "FAIL"
        if match == "FAIL":
            all_pass = False
        print(f"  Target: {target:6.1f} Hz -> Detected: {detected:6.1f} Hz "
              f"(confidence: {conf:.1f}x) [{match}]")

    print("=" * 50)
    print("ALL TESTS PASSED" if all_pass else "SOME TESTS FAILED")
    return 0 if all_pass else 1

if __name__ == "__main__":
    exit(main())
