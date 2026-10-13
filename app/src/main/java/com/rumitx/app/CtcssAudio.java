package com.rumitx.app;
import java.io.*;
/** Mix into a validated canonical 16k mono PCM16 WAV; preserve the signed original. */
final class CtcssAudio {
    static void mix(File source, File target, double hz) throws IOException {
        ToneIndex.ctcss(hz);
        try (DataInputStream in = new DataInputStream(new FileInputStream(source));
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] header = new byte[44]; in.readFully(header); out.write(header);
            long frame = 0;
            while (in.available() > 0) {
                int lo = in.readUnsignedByte(), hi = in.readUnsignedByte();
                short pcm = (short)(lo | (hi << 8));
                // Reserve headroom: 75% voice, 15% continuous CTCSS, no clipping.
                int mixed = (int)Math.round(pcm * 0.75 + 32767 * 0.15 *
                    Math.sin(2 * Math.PI * hz * frame++ / 16000.0));
                out.write(mixed & 255); out.write((mixed >>> 8) & 255);
            }
            out.getFD().sync();
        }
    }
}
