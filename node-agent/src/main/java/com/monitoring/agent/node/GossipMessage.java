package com.monitoring.agent.node;

/**
 * A gossip message.
 * 
 * @param messageId         message id
 * @param sourceNodeId      source node id
 * @param subjectNodeId     target node id
 * @param messageType       message type
 * @param incarnationNumber incarnation number
 * @param timestamp         timestamp
 * @param ttl               time to live (hops)
 * @param details           extra details
 */
public record GossipMessage(
        String messageId,
        String sourceNodeId,
        String subjectNodeId,
        GossipMessageType messageType,
        int incarnationNumber,
        long timestamp,
        int ttl,
        String details) {

    /**
     * Decreases time to live of a gossip message.
     */
    public GossipMessage decrementTtl() {
        return new GossipMessage(
                messageId,
                sourceNodeId,
                subjectNodeId,
                messageType,
                incarnationNumber,
                timestamp,
                ttl - 1,
                details);
    }

    /**
     * Converts JSON into a gossip message
     * 
     * @param json JSON message
     * @return gossip message
     */
    public static GossipMessage fromJson(String json) {
        return new GossipMessage(
                P2pJson.stringValue(json, "messageId"),
                P2pJson.stringValue(json, "sourceNodeId"),
                P2pJson.stringValue(json, "subjectNodeId"),
                GossipMessageType.valueOf(P2pJson.stringValue(json, "messageType")),
                P2pJson.intValue(json, "incarnationNumber"),
                Long.parseLong(P2pJson.stringValue(json, "timestamp")),
                P2pJson.intValue(json, "ttl"),
                P2pJson.stringValue(json, "details"));
    }

}
