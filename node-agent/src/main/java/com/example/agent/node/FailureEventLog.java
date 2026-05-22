package com.example.agent.node;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Local in-memory event log.
 *
 * This keeps failure evidence even after an unreachable neighbor is removed
 * from NeighborDirectory.
 *
 * Later, broadcasted failure events can also be stored here.
 */
class FailureEventLog {

    private final ConcurrentMap<String, FailureEvent> eventsById = new ConcurrentHashMap<>();

    /**
     * Adds an event only once.
     *
     * This duplicate check will matter later when failure events are broadcast
     * between nodes and the same event may arrive more than once.
     */
    boolean add(FailureEvent event) {
        if (event == null) {
            return false;
        }

        return eventsById.putIfAbsent(event.eventId(), event) == null;
    }

    /**
     * Returns newest events first so the dashboard can show recent failures first.
     */
    List<FailureEvent> events() {
        return eventsById.values()
                .stream()
                .sorted(Comparator.comparing(FailureEvent::timestamp).reversed())
                .toList();
    }

    /**
     * Manual JSON array for the read-only dashboard endpoint.
     */
    String toJson() {
        return events()
                .stream()
                .map(FailureEvent::toJson)
                .collect(Collectors.joining(",\n", "[\n", "\n]"));
    }
}