package com.rumitx.app.agent;

/** Caps on-device chat memory so preferences and prompts cannot grow without bound. */
public final class RumiMemory {
    public static final int MAX_TRANSCRIPT_CHARS = 16000;
    public static final int PROMPT_CHARS = 850;
    public static final int MAX_FACTS = 48;
    public static final int MAX_FACT_CHARS = 240;

    private RumiMemory() {}

    public static String bound(String transcript) {
        return tail(transcript, MAX_TRANSCRIPT_CHARS);
    }

    public static String tail(String text, int maxChars) {
        if (text == null) return "";
        if (maxChars < 1) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(text.length() - maxChars);
    }

    public static String fact(String raw) {
        if (raw == null) return "";
        String clean = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > MAX_FACT_CHARS) clean = clean.substring(0, MAX_FACT_CHARS);
        return clean;
    }
}
