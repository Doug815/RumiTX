package com.rumitx.rumiai;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;

/** Hardened synchronous HTTP transport. Call only from a worker thread. */
public final class ApiClient {
    public static final class Response {
        public final int code;
        public final String body;
        public final long elapsedMs;
        Response(int c, String b, long t) { code=c; body=b; elapsedMs=t; }
        public boolean ok() { return code >= 200 && code < 300; }
        public JSONObject json() {
            try { return new JSONObject(body); }
            catch (Exception e) { return new JSONObject(); }
        }
    }

    private final String base;
    private final String readToken;
    private final String commandToken;

    public ApiClient(String base, String readToken, String commandToken) {
        if (!isSafeBase(base)) throw new IllegalArgumentException("HTTPS server URL is required");
        this.base = base.replaceAll("/+$", "");
        this.readToken = readToken == null ? "" : readToken.trim();
        this.commandToken = commandToken == null ? "" : commandToken.trim();
    }

    public static boolean isSafeBase(String input) {
        try {
            if (input == null || !input.equals(input.trim())) return false;
            URI u = new URI(input);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null
                && u.getUserInfo() == null && (u.getRawPath() == null || u.getRawPath().isEmpty() || "/".equals(u.getRawPath()))
                && u.getRawQuery() == null && u.getRawFragment() == null && u.getPort() == -1;
        } catch (Exception e) { return false; }
    }

    public static boolean isSafePath(String path) {
        return path != null && path.matches("^/[A-Za-z0-9_/-]{1,80}$") && !path.contains("//")
            && !path.contains("..");
    }

    public Response call(String method, String path, JSONObject json, boolean command) throws Exception {
        if (!isSafePath(path)) throw new IllegalArgumentException("Invalid API path");
        String token = command ? commandToken : readToken;
        if (token.length() < 32) throw new IllegalStateException("Missing or short " + (command ? "command" : "read") + " token");
        long start = android.os.SystemClock.elapsedRealtime();
        URL target = new URL(base + path);
        HttpsURLConnection conn = (HttpsURLConnection) target.openConnection();
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(25000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setUseCaches(false);
            if ("POST".equals(method)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                if (command) conn.setRequestProperty("Idempotency-Key", UUID.randomUUID().toString());
                byte[] data = (json == null ? "{}" : json.toString()).getBytes(StandardCharsets.UTF_8);
                if (data.length > 4096) throw new IllegalArgumentException("Request too large");
                try (OutputStream out = conn.getOutputStream()) { out.write(data); }
            }
            int code = conn.getResponseCode();
            InputStream source = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String body = readBounded(source);
            return new Response(code, body, android.os.SystemClock.elapsedRealtime() - start);
        } finally { conn.disconnect(); }
    }

    private static String readBounded(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream input = in; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) != -1) {
                if (output.size() + n > 65536) throw new IllegalStateException("Server response exceeds 64 KiB");
                output.write(buf, 0, n);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
