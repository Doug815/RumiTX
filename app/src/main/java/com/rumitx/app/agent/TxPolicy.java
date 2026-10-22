package com.rumitx.app.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Owner transmit gate. Default off, empty allowlist, 15 s key limit, rate limited. */
public final class TxPolicy {
    public static final int DEFAULT_MAX_KEY_SECONDS = 15;
    public static final int HELPER_WATCHDOG_SECONDS = 120;
    public static final int MIN_GAP_MS = 2000;
    public static final int MAX_KEYS_PER_MINUTE = 6;
    public static final int MAX_ALLOWLIST = 64;

    private boolean enabled;
    private int maxKeySeconds = DEFAULT_MAX_KEY_SECONDS;
    private final LinkedHashSet<String> allow = new LinkedHashSet<String>();
    private final ArrayDeque<Long> recentKeys = new ArrayDeque<Long>();
    private long lastIdleMs;

    public static final class Decision {
        public final boolean allowed;
        public final String reason;

        Decision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
    }

    public synchronized boolean enabled() { return enabled; }

    public synchronized void setEnabled(boolean enabled) { this.enabled = enabled; }

    public synchronized int maxKeySeconds() { return maxKeySeconds; }

    public synchronized long maxKeyMillis() { return maxKeySeconds * 1000L; }

    /** Clamps to 1..120 s, the helper watchdog ceiling. */
    public synchronized void setMaxKeySeconds(int seconds) {
        if (seconds < 1) seconds = 1;
        if (seconds > HELPER_WATCHDOG_SECONDS) seconds = HELPER_WATCHDOG_SECONDS;
        maxKeySeconds = seconds;
    }

    public synchronized List<String> allowlist() { return new ArrayList<String>(allow); }

    public synchronized void setAllowlist(List<String> frequencies) {
        LinkedHashSet<String> next = new LinkedHashSet<String>();
        if (frequencies != null) {
            for (String raw : frequencies) {
                if (raw == null || raw.trim().isEmpty() || raw.trim().startsWith("#")) continue;
                double mhz = parseMhz(raw.trim());
                next.add(TxFrequencyPolicy.mhz(mhz));
                if (next.size() > MAX_ALLOWLIST) {
                    throw new IllegalArgumentException("allowlist is limited to " + MAX_ALLOWLIST + " channels");
                }
            }
        }
        allow.clear();
        allow.addAll(next);
    }

    public synchronized Decision evaluate(double txMhz, double rxMhz, boolean commandAuthorized, long nowMs) {
        if (!commandAuthorized) return new Decision(false, "command token required");
        if (!enabled) return new Decision(false, "tx_enabled is off");
        String txSafety = TxFrequencyPolicy.denyReason(txMhz);
        if (txSafety != null) return new Decision(false, "TX " + txSafety);
        String rxSafety = TxFrequencyPolicy.denyReason(rxMhz);
        if (rxSafety != null) return new Decision(false, "RX " + rxSafety);
        if (!listed(txMhz)) {
            return new Decision(false, "TX " + TxFrequencyPolicy.mhz(txMhz) + " is not on the on-device allowlist");
        }
        prune(nowMs);
        if (lastIdleMs > 0 && nowMs - lastIdleMs < MIN_GAP_MS) {
            return new Decision(false, "transmit cooldown (" + MIN_GAP_MS + " ms)");
        }
        if (recentKeys.size() >= MAX_KEYS_PER_MINUTE) {
            return new Decision(false, "transmit rate limit (" + MAX_KEYS_PER_MINUTE + " per minute)");
        }
        return new Decision(true, "allowed");
    }

    public synchronized void markKeyed(long nowMs) {
        prune(nowMs);
        recentKeys.addLast(Long.valueOf(nowMs));
    }

    public synchronized void markIdle(long nowMs) { lastIdleMs = nowMs; }

    public synchronized String save() {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(enabled).append('\n');
        sb.append("max_key_seconds=").append(maxKeySeconds).append('\n');
        for (String item : allow) sb.append("allowed_channels=").append(item).append('\n');
        return sb.toString();
    }

    public static TxPolicy load(String text) {
        TxPolicy policy = new TxPolicy();
        List<String> items = new ArrayList<String>();
        if (text != null) {
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (key.equals("enabled")) policy.setEnabled(value.equals("true"));
                else if (key.equals("max_key_seconds")) policy.setMaxKeySeconds(Integer.parseInt(value));
                else if (key.equals("allowed_channels") || key.equals("allow")) items.add(value);
            }
        }
        policy.setAllowlist(items);
        return policy;
    }

    public static double parseMhz(String raw) {
        if (raw == null || !raw.matches("[0-9]{3}\\.[0-9]{1,5}")) {
            throw new IllegalArgumentException("frequency must look like 446.00000");
        }
        return Double.parseDouble(raw);
    }

    private boolean listed(double txMhz) {
        String key = TxFrequencyPolicy.mhz(txMhz);
        if (allow.contains(key)) return true;
        for (String item : allow) {
            if (Math.abs(Double.parseDouble(item) - txMhz) <= 0.00002) return true;
        }
        return false;
    }

    private void prune(long nowMs) {
        while (!recentKeys.isEmpty() && nowMs - recentKeys.peekFirst().longValue() > 60000L) {
            recentKeys.removeFirst();
        }
    }

    /** Settings UI uses this so a public-safety frequency cannot be saved. */
    public static String allowlistEntryProblem(String raw) {
        double mhz;
        try { mhz = parseMhz(raw.trim()); }
        catch (RuntimeException e) { return e.getMessage(); }
        return TxFrequencyPolicy.denyReason(mhz);
    }

    public synchronized Set<String> allowView() { return new LinkedHashSet<String>(allow); }

    @Override public synchronized String toString() {
        return String.format(Locale.US, "tx_enabled=%s max_key=%ds allow=%d", enabled, maxKeySeconds, allow.size());
    }
}
