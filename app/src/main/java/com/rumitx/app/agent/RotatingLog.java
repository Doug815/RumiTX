package com.rumitx.app.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Append-only log with a fixed number of rotated generations. Never grows without bound. */
public final class RotatingLog {
    private final File file;
    private final int maxBytes;
    private final int generations;
    private final boolean sync;

    public RotatingLog(File file, int maxBytes, int generations, boolean sync) {
        if (maxBytes < 1024) throw new IllegalArgumentException("log cap too small");
        if (generations < 1 || generations > 8) throw new IllegalArgumentException("bad rotation count");
        this.file = file;
        this.maxBytes = maxBytes;
        this.generations = generations;
        this.sync = sync;
    }

    public synchronized void append(String line) {
        if (line == null) return;
        String clean = line.replace('\n', ' ').replace('\r', ' ');
        if (clean.length() > 2000) clean = clean.substring(0, 2000);
        byte[] bytes = (clean + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            if (file.exists() && file.length() + bytes.length > maxBytes) rotate();
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write(bytes);
                if (sync) out.getFD().sync();
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
            // Logging must not take down the radio service.
        }
    }

    public synchronized List<String> tail(int maxLines) {
        int limit = maxLines < 1 ? 1 : Math.min(maxLines, 400);
        List<String> lines = new ArrayList<String>();
        for (int gen = generations; gen >= 1; gen--) addLines(new File(file.getPath() + "." + gen), lines);
        addLines(file, lines);
        if (lines.size() > limit) return new ArrayList<String>(lines.subList(lines.size() - limit, lines.size()));
        return lines;
    }

    private void addLines(File source, List<String> lines) {
        if (source == null || !source.isFile() || source.length() <= 0) return;
        try {
            RandomAccessFile raf = new RandomAccessFile(source, "r");
            try {
                long start = Math.max(0, raf.length() - maxBytes);
                raf.seek(start);
                byte[] buf = new byte[(int) (raf.length() - start)];
                raf.readFully(buf);
                String text = new String(buf, StandardCharsets.UTF_8);
                if (start > 0) {
                    int nl = text.indexOf('\n');
                    if (nl >= 0) text = text.substring(nl + 1);
                }
                String[] parts = text.split("\n");
                for (int i = 0; i < parts.length; i++) {
                    if (!parts[i].isEmpty()) lines.add(parts[i]);
                }
            } finally {
                raf.close();
            }
        } catch (Exception ignored) {
            // Skip a damaged generation.
        }
    }

    private void rotate() {
        File oldest = new File(file.getPath() + "." + generations);
        if (oldest.exists() && !oldest.delete()) return;
        for (int gen = generations - 1; gen >= 1; gen--) {
            File src = new File(file.getPath() + "." + gen);
            File dst = new File(file.getPath() + "." + (gen + 1));
            if (src.exists()) src.renameTo(dst);
        }
        file.renameTo(new File(file.getPath() + ".1"));
    }
}
