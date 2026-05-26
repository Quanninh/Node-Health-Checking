package com.monitoring.service;

import com.monitoring.model.CrackingRequest;
import com.monitoring.model.CrackingResponse;
import com.monitoring.model.Node;
import com.monitoring.model.PasswordCrackResponse;
import com.monitoring.repository.NodeRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PasswordCrackingService {

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private ResultStore resultStore;

    private static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final int PASSWORD_LENGTH = 5;

    private static final long RANGE_SIZE = 1_000_000;

    private volatile String foundPassword;

    private final Queue<long[]> pendingRanges = new ConcurrentLinkedQueue<>();

    private final AtomicInteger activeTasks = new AtomicInteger();

    private volatile String currentHash;
    private volatile boolean crackingDone = false;

    public PasswordCrackResponse crackPassword(
            String hash) throws Exception {

        long startTime = System.currentTimeMillis();

        foundPassword = null;
        crackingDone = false;
        currentHash = hash;
        activeTasks.set(0);
        resultStore.clear();

        pendingRanges.clear();
        pendingRanges.addAll(buildRanges(getTotalPossiblePasswords(), RANGE_SIZE));

        List<Node> nodes = nodeRepository.findAll()
                .stream()
                .filter(node -> "UP".equalsIgnoreCase(node.getStatus())
                        || "ALIVE".equalsIgnoreCase(node.getStatus()))
                .toList();

        if (nodes.isEmpty()) {

            return new PasswordCrackResponse(
                    false,
                    null,
                    "No active nodes available",
                    0);
        }

        // Seed one range per active node. Follow-up ranges are assigned from
        // handleNodeResult().
        for (Node node : nodes) {

            assignNextRange(node);
        }

        if (activeTasks.get() == 0) {
            crackingDone = true;

            return new PasswordCrackResponse(
                    false,
                    null,
                    "No active nodes accepted cracking tasks",
                    System.currentTimeMillis() - startTime);
        }

        while (foundPassword == null
                && !crackingDone) {

            Thread.sleep(100);
        }

        if (foundPassword != null) {

            return new PasswordCrackResponse(
                    true,
                    foundPassword,
                    "Password found successfully",
                    System.currentTimeMillis() - startTime);
        }

        return new PasswordCrackResponse(
                false,
                null,
                "Password not found",
                System.currentTimeMillis() - startTime);
    }

    private boolean assignNextRange(Node node) {
        if (crackingDone) {
            return false;
        }

        long[] range = pendingRanges.poll();

        if (range == null) {
            System.out.println("No more ranges to assign");

            if (activeTasks.get() == 0) {
                crackingDone = true;
            }

            return false;
        }

        boolean accepted = sendTaskToNode(
                node,
                currentHash,
                range[0],
                range[1]);

        if (accepted) {
            activeTasks.incrementAndGet();
            return true;
        }

        pendingRanges.offer(range);
        return false;
    }

    private boolean sendTaskToNode(
            Node node,
            String hash,
            long rangeStart,
            long rangeEnd) {

        try {

            CrackingRequest request = new CrackingRequest(
                    hash,
                    rangeStart,
                    rangeEnd,
                    Long.MAX_VALUE);
            String url = "http://"
                    + node.getIpAddress()
                    + ":"
                    + node.getCrackingPort()
                    + "/node/crack";

            System.out.println(
                    "Sending cracking task to "
                            + node.getId()
                            + " at "
                            + url
                            + " range "
                            + rangeStart
                            + " -> "
                            + rangeEnd);

            RestClient.post(url, request);
            System.out.println(
                    "Task accepted by node "
                            + node.getId()
                            + " range "
                            + rangeStart
                            + " -> "
                            + rangeEnd);

            return true;

        } catch (Exception e) {

            System.out.println(
                    "Failed sending task to node "
                            + node.getId());

            e.printStackTrace();

            return false;
        }
    }

    public void handleNodeResult(
            CrackingResponse response) {

        System.out.println(
                "Received result from node: "
                        + response.getNodeId());

        if (crackingDone && !response.isFound()) {
            System.out.println("Ignoring late not-found result from node: " + response.getNodeId());
            return;
        }

        resultStore.save(response);

        activeTasks.updateAndGet(value -> Math.max(0, value - 1));

        if (response.isFound()) {

            foundPassword = response.getPassword();
            crackingDone = true;
            pendingRanges.clear();

            System.out.println(
                    "PASSWORD FOUND = "
                            + foundPassword);
            return;
        }

        Node node = nodeRepository
                .findById(response.getNodeId())
                .orElse(null);

        if (node == null) {
            System.out.println("Cannot assign next range. Unknown node: " + response.getNodeId());
            return;
        }

        assignNextRange(node);

        if (pendingRanges.isEmpty() && activeTasks.get() == 0) {
            crackingDone = true;
        }
    }

    private Queue<long[]> buildRanges(
            long totalPasswords,
            long rangeSize) {

        Queue<long[]> queue = new LinkedList<>();

        for (long start = 0; start < totalPasswords; start += rangeSize) {

            long end = Math.min(
                    start + rangeSize - 1,
                    totalPasswords - 1);

            queue.add(
                    new long[] {
                            start,
                            end
                    });
        }

        return queue;
    }

    private long getTotalPossiblePasswords() {

        long total = 1;

        for (int i = 0; i < PASSWORD_LENGTH; i++) {

            total *= CHARSET.length();
        }

        return total;
    }
}
