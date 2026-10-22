package com.rumitx.app;

import android.content.Context;

import com.rumitx.app.agent.AgentHttpServer;
import com.rumitx.app.agent.AgentInfo;
import com.rumitx.app.agent.AgentStore;
import com.rumitx.app.agent.BearerAuth;
import com.rumitx.app.agent.CommandLayer;
import com.rumitx.app.agent.TailscaleAddresses;
import com.rumitx.app.agent.TxPolicy;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** Foreground owner of the local API. Transmit stays off until the on-device setting is enabled. */
public final class AgentRuntime {
    private static AgentRuntime instance;
    private final File dir;
    private final TxPolicy policy;
    private final BearerAuth auth;
    private final AgentRadioAdapter port;
    private final CommandLayer layer;
    private final CommandLayer.ExecutorScheduler scheduler;
    private final AgentHttpServer server;
    private boolean tailscale;

    private AgentRuntime(Context app, RadioController radio, VoiceTxBridge bridge) {
        dir = new File(app.getFilesDir(), "agent");
        AgentStore.Loaded loaded = AgentStore.load(dir);
        policy = loaded.policy;
        auth = loaded.auth;
        tailscale = loaded.tailscale;
        port = new AgentRadioAdapter(app, radio, bridge);
        scheduler = new CommandLayer.ExecutorScheduler();
        layer = new CommandLayer(port, policy, dir, scheduler, new CommandLayer.Clock() {
            @Override public long now() { return System.currentTimeMillis(); }
        });
        server = new AgentHttpServer(layer, auth);
    }

    public static synchronized void start(Context app, RadioController radio, VoiceTxBridge bridge) {
        if (instance != null) {
            instance.port.setBridge(bridge);
            return;
        }
        instance = new AgentRuntime(app.getApplicationContext(), radio, bridge);
        instance.open();
    }

    public static synchronized void stop() {
        AgentRuntime current = instance;
        instance = null;
        if (current == null) return;
        try { current.server.stop(); }
        catch (RuntimeException ignored) { /* already stopped */ }
        current.scheduler.shutdown();
    }

    public static CommandLayer layer() {
        AgentRuntime current = instance;
        return current == null ? null : current.layer;
    }

    public static TxPolicy policy() {
        AgentRuntime current = instance;
        return current == null ? null : current.policy;
    }

    public static BearerAuth auth() {
        AgentRuntime current = instance;
        return current == null ? null : current.auth;
    }

    public static boolean tailscaleEnabled() {
        AgentRuntime current = instance;
        return current != null && current.tailscale;
    }

    public static File directory(Context app) {
        AgentRuntime current = instance;
        if (current != null) return current.dir;
        return new File(app.getFilesDir(), "agent");
    }

    /** Settings screen. The allowlist is written here and is not exposed as an HTTP write. */
    public static synchronized void apply(Context app, TxPolicy next, boolean tailscaleOn) {
        if (next == null) return;
        AgentRuntime current = instance;
        if (current == null) {
            AgentStore.save(new File(app.getFilesDir(), "agent"), next, tailscaleOn);
            return;
        }
        current.policy.setEnabled(next.enabled());
        current.policy.setMaxKeySeconds(next.maxKeySeconds());
        current.policy.setAllowlist(next.allowlist());
        current.tailscale = tailscaleOn;
        AgentStore.save(current.dir, current.policy, tailscaleOn);
        current.bindTailscale();
    }

    private void open() {
        try {
            int port = server.start(AgentInfo.PORT);
            RadioLog.i("RumiTX-Agent", "API listening on 127.0.0.1:" + port + " tx_enabled=" + policy.enabled());
        } catch (Exception e) {
            RadioLog.e("RumiTX-Agent", "API did not bind", e);
        }
        if (tailscale) bindTailscale();
    }

    private void bindTailscale() {
        List<String> hosts = tailscale ? TailscaleAddresses.ipv4() : Collections.<String>emptyList();
        try {
            server.setExtraHosts(hosts);
            RadioLog.i("RumiTX-Agent", "tailscale hosts=" + hosts.size());
        } catch (Exception e) {
            RadioLog.e("RumiTX-Agent", "tailscale bind failed", e);
        }
    }
}
