package com.monitoring.agent.node;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;

public record DiscoveryConfig(
        InetAddress multicastGroup,
        int multicastPort,
        NetworkInterface networkInterface,
        int maxNeighbors,
        int retryCount,
        Duration retryInterval,
        Duration collectionWindow,
        int packetBufferSize
) {
    public DiscoveryConfig {
        if (!multicastGroup.isMulticastAddress()) {
            throw new IllegalArgumentException("multicastGroup must be a multicast address.");
        }

        if (maxNeighbors <= 0 || maxNeighbors % 2 != 0) {
            throw new IllegalArgumentException("maxNeighbors must be a positive even number.");
        }

        if (retryCount <= 0) {
            throw new IllegalArgumentException("retryCount must be greater than 0.");
        }
    }

    public static DiscoveryConfig defaultLanConfig(
            int maxNeighbors,
            NetworkInterface networkInterface
    ) throws Exception {
        return new DiscoveryConfig(
                InetAddress.getByName("239.10.20.30"),
                50505,
                networkInterface,
                maxNeighbors,
                3,
                Duration.ofMillis(400),
                Duration.ofSeconds(3),
                8192
        );
    }
}