package com.rumitx.app;

/**
 * RumiTX ToneDetector — Goertzel algorithm for CTCSS tone detection.
 * Analyzes received audio to detect which of the 50 standard CTCSS tones
 * (67.0-254.1 Hz) is present. Core of Discovery Mode.
 *
 * Ported from validated Python prototype (all tests passed).
 */
public class ToneDetector {

    // 50 standard CTCSS tones (Hz)
    public static final double[] CTCSS_TONES = {
        67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5,
        94.8, 97.4, 100.0, 103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3,
        131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 159.8, 162.2, 165.5, 167.9,
        171.3, 173.8, 177.3, 179.9, 183.5, 186.2, 189.9, 192.8, 196.6, 199.5,
        203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8, 250.3, 254.1,
    };

    public static class DetectionResult {
        public double toneHz;
        public double magnitude;
        public double confidence; // ratio vs 2nd best
        public boolean reliable;   // confidence > threshold
    }

    /**
     * Detect the strongest CTCSS tone in the given audio samples.
     * @param samples 16-bit PCM audio samples
     * @param sampleRate e.g., 8000
     * @return detection result with tone frequency and confidence
     */
    public static DetectionResult detect(short[] samples, int sampleRate) {
        double[] magnitudes = new double[CTCSS_TONES.length];

        for (int i = 0; i < CTCSS_TONES.length; i++) {
            magnitudes[i] = goertzel(samples, sampleRate, CTCSS_TONES[i]);
        }

        // Find best and second-best
        int best = 0, second = 0;
        for (int i = 1; i < magnitudes.length; i++) {
            if (magnitudes[i] > magnitudes[best]) {
                second = best;
                best = i;
            } else if (magnitudes[i] > magnitudes[second]) {
                second = i;
            }
        }

        DetectionResult r = new DetectionResult();
        r.toneHz = CTCSS_TONES[best];
        r.magnitude = magnitudes[best];
        r.confidence = magnitudes[second] > 0
                ? magnitudes[best] / magnitudes[second]
                : Double.POSITIVE_INFINITY;
        r.reliable = r.confidence > 5.0; // threshold: 5x stronger than runner-up
        return r;
    }

    private static double goertzel(short[] samples, int sampleRate, double targetFreq) {
        int n = samples.length;
        int k = (int)(0.5 + (n * targetFreq / sampleRate));
        double omega = 2.0 * Math.PI * k / n;
        double sine = Math.sin(omega);
        double cosine = Math.cos(omega);
        double coeff = 2.0 * cosine;

        double s0, s1 = 0, s2 = 0;
        for (short sample : samples) {
            s0 = sample + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        double real = s1 * cosine - s2;
        double imag = s1 * sine;
        return Math.sqrt(real * real + imag * imag);
    }

    /**
     * Validate a user-entered CTCSS tone (direct entry).
     * @param hz the tone frequency the user typed
     * @return true if it's a valid standard CTCSS tone
     */
    public static boolean isValidTone(double hz) {
        for (double tone : CTCSS_TONES) {
            if (Math.abs(tone - hz) < 0.05) return true;
        }
        return false;
    }

    /**
     * Find the closest standard tone to a detected frequency.
     * Used when the Goertzel result needs snapping to the standard set.
     */
    public static double snapToStandard(double hz) {
        double best = CTCSS_TONES[0];
        double bestDiff = Math.abs(hz - best);
        for (double tone : CTCSS_TONES) {
            double diff = Math.abs(hz - tone);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = tone;
            }
        }
        return best;
    }
}
