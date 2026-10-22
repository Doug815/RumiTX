package com.rumitx.app.agent;

/** Canonical 16 kHz mono 16-bit PCM WAV, matching VoiceTxBridge. */
public final class WavCheck {
    public static final int MAX_BYTES = 480044;
    public static final int MAX_MS = 15000;

    private WavCheck() {}

    public static int durationMs(byte[] wav) {
        if (wav == null || wav.length < 45 || wav.length > MAX_BYTES) {
            throw new IllegalArgumentException("WAV size outside allowed limits");
        }
        if (!ascii(wav, 0, 4).equals("RIFF") || !ascii(wav, 8, 4).equals("WAVE")
                || !ascii(wav, 12, 4).equals("fmt ") || !ascii(wav, 36, 4).equals("data")
                || le32(wav, 16) != 16 || le16(wav, 20) != 1
                || le16(wav, 22) != 1 || le32(wav, 24) != 16000
                || le32(wav, 28) != 32000 || le16(wav, 32) != 2
                || le16(wav, 34) != 16) {
            throw new IllegalArgumentException("WAV must be canonical 16 kHz mono 16-bit PCM");
        }
        int dataBytes = le32(wav, 40);
        if (dataBytes <= 0 || dataBytes + 44 != wav.length || (dataBytes % 2) != 0
                || le32(wav, 4) != wav.length - 8) {
            throw new IllegalArgumentException("invalid WAV data chunk length");
        }
        int durationMs = (int) (dataBytes * 1000L / 32000L);
        if (durationMs < 1 || durationMs > MAX_MS) throw new IllegalArgumentException("audio exceeds 15 seconds");
        return durationMs;
    }

    public static byte[] silence(int durationMs) {
        if (durationMs < 1 || durationMs > MAX_MS) throw new IllegalArgumentException("bad duration");
        int dataBytes = durationMs * 32;
        byte[] wav = new byte[44 + dataBytes];
        write(wav, 0, "RIFF");
        put32(wav, 4, wav.length - 8);
        write(wav, 8, "WAVE");
        write(wav, 12, "fmt ");
        put32(wav, 16, 16);
        put16(wav, 20, 1);
        put16(wav, 22, 1);
        put32(wav, 24, 16000);
        put32(wav, 28, 32000);
        put16(wav, 32, 2);
        put16(wav, 34, 16);
        write(wav, 36, "data");
        put32(wav, 40, dataBytes);
        return wav;
    }

    private static void write(byte[] a, int off, String s) {
        for (int i = 0; i < s.length(); i++) a[off + i] = (byte) s.charAt(i);
    }

    private static void put16(byte[] a, int off, int v) {
        a[off] = (byte) v;
        a[off + 1] = (byte) (v >> 8);
    }

    private static void put32(byte[] a, int off, int v) {
        a[off] = (byte) v;
        a[off + 1] = (byte) (v >> 8);
        a[off + 2] = (byte) (v >> 16);
        a[off + 3] = (byte) (v >> 24);
    }

    private static String ascii(byte[] a, int off, int len) {
        return new String(a, off, len, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int le16(byte[] b, int i) { return (b[i] & 255) | ((b[i + 1] & 255) << 8); }

    private static int le32(byte[] b, int i) {
        return (b[i] & 255) | ((b[i + 1] & 255) << 8) | ((b[i + 2] & 255) << 16) | ((b[i + 3] & 255) << 24);
    }
}
