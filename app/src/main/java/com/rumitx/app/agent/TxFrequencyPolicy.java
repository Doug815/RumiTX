package com.rumitx.app.agent;

import java.util.Locale;

/**
 * Hard floor for agent transmit. The on-device allowlist cannot override this.
 * Beloit PD P25 channels are denied by name. Everything else must sit in the
 * US amateur 70 cm band or on a standard GMRS channel center.
 */
public final class TxFrequencyPolicy {
    /** Explicit public-safety deny. These are not transmit targets. */
    public static final double[] BELOIT_PD_P25_MHZ = {
            460.050, 460.075, 460.200, 460.250, 460.350, 460.450, 460.475
    };
    /** Standard US GMRS channel centers (repeater outputs, inputs, and interstitial simplex). */
    public static final double[] GMRS_MHZ = {
            462.5500, 462.5625, 462.5750, 462.5875, 462.6000, 462.6125, 462.6250, 462.6375,
            462.6500, 462.6625, 462.6750, 462.6875, 462.7000, 462.7125, 462.7250,
            467.5500, 467.5625, 467.5750, 467.5875, 467.6000, 467.6125, 467.6250, 467.6375,
            467.6500, 467.6625, 467.6750, 467.6875, 467.7000, 467.7125, 467.7250
    };
    public static final double AMATEUR_LOW_MHZ = 420.0;
    public static final double AMATEUR_HIGH_MHZ = 450.0;
    private static final double PUBLIC_SAFETY_WINDOW_MHZ = 0.0025;
    private static final double CHANNEL_WINDOW_MHZ = 0.0010;

    private TxFrequencyPolicy() {}

    public static String mhz(double value) {
        return String.format(Locale.US, "%.5f", value);
    }

    /** Null when this frequency may be considered for the allowlist. Otherwise a deny reason. */
    public static String denyReason(double mhz) {
        if (!Double.isFinite(mhz)) return "frequency is not a finite MHz value";
        String safety = publicSafety(mhz);
        if (safety != null) return safety;
        if (amateur(mhz) || gmrs(mhz)) return null;
        return String.format(Locale.US,
                "%.5f MHz is outside licensed service (amateur 70 cm 420–450 MHz, or a standard GMRS channel)",
                mhz);
    }

    public static String publicSafety(double mhz) {
        for (double denied : BELOIT_PD_P25_MHZ) {
            if (Math.abs(mhz - denied) <= PUBLIC_SAFETY_WINDOW_MHZ) {
                return String.format(Locale.US,
                        "public-safety deny list: Beloit PD P25 %.3f MHz", denied);
            }
        }
        return null;
    }

    public static boolean amateur(double mhz) {
        return mhz >= AMATEUR_LOW_MHZ && mhz <= AMATEUR_HIGH_MHZ;
    }

    public static boolean gmrs(double mhz) {
        for (double channel : GMRS_MHZ) {
            if (Math.abs(mhz - channel) <= CHANNEL_WINDOW_MHZ) return true;
        }
        return false;
    }
}
