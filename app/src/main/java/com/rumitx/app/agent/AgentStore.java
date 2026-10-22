package com.rumitx.app.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** App-private token and policy files. The HTTP API cannot rewrite the allowlist. */
public final class AgentStore {
    public static final class Loaded {
        public final TxPolicy policy;
        public final BearerAuth auth;
        public final boolean tailscale;

        Loaded(TxPolicy policy, BearerAuth auth, boolean tailscale) {
            this.policy = policy;
            this.auth = auth;
            this.tailscale = tailscale;
        }
    }

    private AgentStore() {}

    public static Loaded load(File dir) {
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("cannot create agent directory");
        TxPolicy policy = TxPolicy.load(read(new File(dir, "policy.txt")));
        boolean tailscale = false;
        String policyText = read(new File(dir, "policy.txt"));
        if (policyText != null && policyText.contains("\ntailscale=true") || (policyText != null && policyText.startsWith("tailscale=true"))) {
            tailscale = true;
        }
        BearerAuth auth = new BearerAuth();
        String readToken = read(new File(dir, "read.token"));
        String commandToken = read(new File(dir, "command.token"));
        if (!BearerAuth.valid(readToken) || !BearerAuth.valid(commandToken) || readToken.equals(commandToken)) {
            auth = BearerAuth.generate();
            write(new File(dir, "read.token"), auth.readToken());
            write(new File(dir, "command.token"), auth.commandToken());
        } else {
            auth.replace(readToken.trim(), commandToken.trim());
        }
        return new Loaded(policy, auth, tailscale);
    }

    public static void save(File dir, TxPolicy policy, boolean tailscale) {
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("cannot create agent directory");
        String body = "tailscale=" + tailscale + "\n" + policy.save();
        write(new File(dir, "policy.txt"), body);
    }

    public static void regenerate(File dir, BearerAuth auth) {
        BearerAuth fresh = BearerAuth.generate();
        auth.replace(fresh.readToken(), fresh.commandToken());
        write(new File(dir, "read.token"), auth.readToken());
        write(new File(dir, "command.token"), auth.commandToken());
    }

    private static String read(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            byte[] buf = new byte[(int) Math.min(file.length(), 16384)];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                int n = in.read(buf);
                if (n <= 0) return "";
                return new String(buf, 0, n, StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File file, String text) {
        File tmp = new File(file.getPath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (file.exists() && !file.delete()) throw new IllegalStateException("cannot replace " + file.getName());
            if (!tmp.renameTo(file)) throw new IllegalStateException("cannot rename " + file.getName());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot write " + file.getName());
        }
    }
}
