package com.innocodes.payflow.webhook;

import java.net.InetAddress;
import java.net.URI;

public class OutboundDestinationPolicy {
    private final boolean allowLocal;

    public OutboundDestinationPolicy(boolean allowLocal) {
        this.allowLocal = allowLocal;
    }

    public void validateDestination(URI uri) {
        validateUri(uri);
        try {
            for (var address : resolve(uri.getHost())) {
                if ("http".equalsIgnoreCase(uri.getScheme()) && !address.isLoopbackAddress()) {
                    throw new IllegalArgumentException(
                            "Plain HTTP requires a loopback destination");
                }
            }
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalArgumentException("Destination DNS is unavailable");
        }
    }

    public void validateUri(URI uri) {
        if (uri == null
                || uri.getHost() == null
                || uri.toString().length() > 2048
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                        || (allowLocal && "http".equalsIgnoreCase(uri.getScheme())))
                || (!allowLocal && uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("UNSAFE_WEBHOOK_DESTINATION");
        }
    }

    public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
        var addresses = InetAddress.getAllByName(host);
        for (var address : addresses) {
            validateAddress(address);
        }
        return addresses;
    }

    public void validateAddress(InetAddress address) {
        if (allowLocal && address.isLoopbackAddress()) {
            return;
        }
        byte[] b = address.getAddress();
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            throw new IllegalArgumentException("UNSAFE_WEBHOOK_ADDRESS");
        }
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            if (a == 0
                    || a >= 224
                    || (a == 100 && c >= 64 && c <= 127)
                    || (a == 192 && (c == 0 || (c == 88 && d == 99)))
                    || (a == 198 && (c == 18 || c == 19 || (c == 51 && d == 100)))
                    || (a == 203 && c == 0 && d == 113)) {
                throw new IllegalArgumentException("UNSAFE_WEBHOOK_ADDRESS");
            }
        } else if ((b[0] & 0xe0) != 0x20
                || (b[0] == 0x20 && b[1] == 0x02)
                || (b[0] == 0x20
                        && b[1] == 0x01
                        && ((b[2] & 0xfe) == 0 || (b[2] == 0x0d && (b[3] & 255) == 0xb8)))) {
            // Only global unicast; reject 6to4, special 2001::/23 and documentation space.
            throw new IllegalArgumentException("UNSAFE_WEBHOOK_ADDRESS");
        }
    }
}
