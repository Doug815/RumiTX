package com.rumitx.app.agent;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** Separate read and command bearer tokens. Tokens are generated on the device. */
public final class BearerAuth {
    private String readToken = "";
    private String commandToken = "";

    public static final class Session {
        public final boolean command;
        public final String tokenId;

        Session(boolean command, String tokenId) {
            this.command = command;
            this.tokenId = tokenId;
        }
    }

    public static BearerAuth generate() {
        BearerAuth auth = new BearerAuth();
        SecureRandom random = new SecureRandom();
        String read = token(random);
        String command;
        do { command = token(random); } while (command.equals(read));
        auth.replace(read, command);
        return auth;
    }

    public synchronized void replace(String readToken, String commandToken) {
        if (!valid(readToken) || !valid(commandToken)) {
            throw new IllegalArgumentException("tokens must be 32+ characters and distinct");
        }
        if (readToken.equals(commandToken)) throw new IllegalArgumentException("read and command tokens must differ");
        this.readToken = readToken;
        this.commandToken = commandToken;
    }

    public synchronized String readToken() { return readToken; }

    public synchronized String commandToken() { return commandToken; }

    public synchronized boolean ready() { return valid(readToken) && valid(commandToken); }

    /** Null when the header is missing or wrong. Does not throw. */
    public synchronized Session authorize(String header) {
        if (header == null) return null;
        String value = header.trim();
        if (!value.startsWith("Bearer ")) return null;
        String token = value.substring(7).trim();
        if (token.isEmpty()) return null;
        if (equalsToken(token, commandToken)) return new Session(true, fingerprint(token));
        if (equalsToken(token, readToken)) return new Session(false, fingerprint(token));
        return null;
    }

    public static boolean valid(String token) {
        return token != null && token.length() >= 32 && token.length() <= 128
                && token.matches("[A-Za-z0-9_-]+");
    }

    public static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) sb.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static boolean equalsToken(String presented, String expected) {
        if (!valid(expected)) return false;
        byte[] a;
        byte[] b;
        try {
            a = presented.getBytes("UTF-8");
            b = expected.getBytes("UTF-8");
        } catch (Exception e) {
            return false;
        }
        if (a.length != b.length) return false;
        return MessageDigest.isEqual(a, b);
    }

    private static String token(SecureRandom random) {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < buf.length; i++) sb.append(String.format(Locale.US, "%02x", buf[i] & 0xff));
        return sb.toString();
    }
}
