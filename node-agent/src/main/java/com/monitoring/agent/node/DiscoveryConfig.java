package com.monitoring.agent.node;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;

/**
 * Configures Discovery protocol
 * 
 * @param multicastGroup   the multicast group
 * @param multicastPort    the multicast port
 * @param networkInterface en0 for MacOS, wireless_32768 for Windows (not
 *                         certain, needs more testing)
 * @param maxNeighbors     maximum number of neighbors for each node
 * @param retryCount
 * @param retryInterval
 * @param collectionWindow
 * @param packetBufferSize buffer size
 */
public record DiscoveryConfig(
        InetAddress multicastGroup,
        int multicastPort,
        NetworkInterface networkInterface,
        int maxNeighbors,
        int retryCount,
        Duration retryInterval,
        Duration collectionWindow,
        int packetBufferSize) {

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

}