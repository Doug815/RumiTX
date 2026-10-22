package com.rumitx.app.agent;

/**
 * Shared on-device model slot. The chat screen attaches the loaded engine.
 * The local API and the chat screen take turns so two generations cannot overlap.
 */
public final class LlamaRuntime {
    public interface Generator {
        boolean loaded();
        String complete(String prompt);
    }

    private static volatile Generator generator;
    private static final Object LOCK = new Object();
    private static boolean busy;

    private LlamaRuntime() {}

    public static void attach(Generator next) { generator = next; }

    public static boolean loaded() {
        Generator current = generator;
        return current != null && current.loaded();
    }

    public static boolean tryAcquire() {
        synchronized (LOCK) {
            if (busy) return false;
            busy = true;
            return true;
        }
    }

    public static void release() {
        synchronized (LOCK) { busy = false; }
    }

    /** Plain chat completion. A tool-shaped reply is returned as text and is not executed here. */
    public static String complete(String message) {
        Generator current = generator;
        if (current == null || !current.loaded()) {
            return "The on-device model is not loaded. Open Offline AI and wait until it says READY.";
        }
        if (message == null || message.trim().isEmpty()) return "";
        if (!tryAcquire()) return "The on-device model is busy with another request.";
        try {
            String prompt = "<|im_start|>system\n"
                    + "You are RumiTX on an S80 radio. Answer briefly in plain English. "
                    + "Do not claim the radio changed.\n/no_think<|im_end|>\n"
                    + "<|im_start|>user\n" + message + "\n/no_think<|im_end|>\n"
                    + "<|im_start|>assistant\n<think>\n\n</think>\n\n";
            String raw = current.complete(prompt);
            if (raw == null || raw.trim().isEmpty()) return "The on-device model returned an empty answer.";
            if (ToolCall.parse(raw.trim()) != null) {
                return "The model returned a tool call. Chat does not transmit. Use a command token on the tool endpoint.";
            }
            if (raw.length() > 3000) return raw.substring(0, 3000);
            return raw;
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "The on-device model could not answer: " + detail;
        } finally {
            release();
        }
    }
}
