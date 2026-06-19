package com.monitoring.agent.node.recovery;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.monitoring.agent.node.NodeAddress;
import com.monitoring.agent.util.Console;

/**
 * Temporary cache for saving network topology. The cache can know which nodes
 * are adjacent to another, and whether a node has enough neighbors (i.e.
 * sufficient)
 * or not (i.e. deficient).
 */
public class NetworkTopologyCache {

    /** Deficient nodes discovered through recovery gossip, keyed by node id. */
    private final Map<String, DeficientNodeRecord> deficientNodes = new ConcurrentHashMap<>();

    /**
     * Stores the latest deficient-node record for a node.
     * 
     * @param record the deficient-node record
     */
    public void markDeficient(DeficientNodeRecord record) {
        if (record == null || record.node() == null) {
            Console.log("Invalid record");
            return;
        }

        deficientNodes.merge(record.nodeId(), record, (existing, incoming) -> {
            if (incoming.incarnationNumber() > existing.incarnationNumber()) {
                return incoming;
            }

            if (incoming.incarnationNumber() == existing.incarnationNumber()
                    && incoming.timestamp().isAfter(existing.timestamp())) {
                return incoming;
            }

            return existing;
        });
    }

    /**
     * Marks a node as sufficient (not deficient).
     * 
     * @param node the sufficient node
     */
    public void clearDeficient(NodeAddress node) {
        if (node != null) {
            deficientNodes.remove(node.nodeId());
        }
    }

    /**
     * Gets the deficient-node records discovered by this node.
     * 
     * @return deficient-node records
     */
    public Set<DeficientNodeRecord> getDeficientNodeRecords() {
        return Set.copyOf(deficientNodes.values());
    }

}
