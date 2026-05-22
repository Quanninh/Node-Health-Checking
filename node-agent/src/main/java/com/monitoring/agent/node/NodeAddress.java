package com.monitoring.agent.node;

import java.net.URI;

/**
 * Node Address. Normally refered to as nodeId@host:port.
 */
public record NodeAddress(String nodeId, String host, int port) {

    /**
     * Converts string to node address.
     * 
     * @param value the string
     * @return the node address associated with the string
     */
    public static NodeAddress fromString(String value) {
        String[] idAndAddress = value.split("@");

        if (idAndAddress.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid node format. Expected nodeId@host:port but got: " + value);
        }

        String nodeId = idAndAddress[0];

        String[] hostAndPort = idAndAddress[1].split(":");

        if (hostAndPort.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid node address. Expected host:port but got: " + idAndAddress[1]);
        }

        String host = hostAndPort[0];
        int port = Integer.parseInt(hostAndPort[1]);

        return new NodeAddress(nodeId, host, port);
    }

    /**
     * URI for ping.
     * 
     * @return ping URI
     */
    public URI pingUri() {
        return URI.create("http://" + host + ":" + port + "/ping");
    }

    /**
     * URI for ping request (asks node to ping another node).
     * 
     * @return ping request URI
     */
    public URI pingReqUri() {
        return URI.create("http://" + host + ":" + port + "/ping-req");
    }

    /**
     * URI for gossipping.
     * 
     * @return gossip URI
     */
    public URI gossipUri() {
        return URI.create("http://" + host + ":" + port + "/gossip");
    }

    @Override
    public String toString() {
        return nodeId + "@" + host + ":" + port;
    }
}