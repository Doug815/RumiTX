package com.rumitx.app.agent;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** IPv4 addresses on Tailscale or tun interfaces. Carrier CGNAT on wlan or rmnet is ignored. */
public final class TailscaleAddresses {
    private TailscaleAddresses() {}

    public static List<String> ipv4() {
        try {
            return ipv4(NetworkInterface.getNetworkInterfaces());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    static List<String> ipv4(Enumeration<NetworkInterface> interfaces) {
        List<String> found = new ArrayList<String>();
        if (interfaces == null) return found;
        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            if (iface == null) continue;
            String name = iface.getName() == null ? "" : iface.getName().toLowerCase(Locale.US);
            if (!eligible(name)) continue;
            Enumeration<InetAddress> addresses = iface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                String host = address.getHostAddress();
                if (host != null && found.size() < 4) found.add(host);
            }
        }
        return found;
    }

    static boolean eligible(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.contains("wlan") || name.contains("rmnet") || name.contains("eth")) return false;
        return name.contains("tailscale") || name.startsWith("tun");
    }
}
