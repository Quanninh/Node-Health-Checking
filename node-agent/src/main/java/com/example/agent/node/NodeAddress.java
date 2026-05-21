package com.example.agent.node;

import java.net.URI;

record NodeAddress(String nodeId, String host, int port) {

    static NodeAddress from(String value) {
        String[] idAndAddress = value.split("@");

        if (idAndAddress.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid peer format. Expected nodeId@host:port but got: " + value);
        }

        String nodeId = idAndAddress[0];

        String[] hostAndPort = idAndAddress[1].split(":");

        if (hostAndPort.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid peer address. Expected host:port but got: " + idAndAddress[1]);
        }

        String host = hostAndPort[0];
        int port = Integer.parseInt(hostAndPort[1]);

        return new NodeAddress(nodeId, host, port);
    }

    URI pingUri() {
        return URI.create("http://" + host + ":" + port + "/ping");
    }

    URI pingReqUri() {
        return URI.create("http://" + host + ":" + port + "/ping-req");
    }

    URI gossipUri() {
        return URI.create("http://" + host + ":" + port + "/gossip");
    }

    @Override
    public String toString() {
        return nodeId + "@" + host + ":" + port;
    }
}
