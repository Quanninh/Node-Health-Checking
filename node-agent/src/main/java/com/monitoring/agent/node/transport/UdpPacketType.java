package com.monitoring.agent.node.transport;

/**
 * The UDP Packet type.
 */
public enum UdpPacketType {
    /** UDP packets to join the network. */
    MEMBERSHIP,
    /** UDP packets to recovery from deficiency. */
    RECOVERY
}
