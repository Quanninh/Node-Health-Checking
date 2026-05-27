package com.monitoring.service;

import com.monitoring.model.CrackingRequest;
import com.monitoring.model.CrackingResponse;
import com.monitoring.model.Node;
import com.monitoring.model.PasswordCrackResponse;
import com.monitoring.repository.NodeRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;

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

    private final Map<String, long[]> assignedRanges = new ConcurrentHashMap<>();


        

    public PasswordCrackResponse crackPassword(
            String hash) throws Exception {

        long startTime = System.currentTimeMillis();

        foundPassword = null;
        crackingDone = false;
        currentHash = hash;
        activeTasks.set(0);
        resultStore.clear();
        assignedRanges.clear();

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

    /**
     * Assigns the next available password range to a node.
     *
     * <p>If the node accepts the task:
     * <ul>
     *     <li>The range is recorded as assigned</li>
     *     <li>The active task counter is incremented</li>
     * </ul>
     *
     * <p>If no ranges remain and no active tasks exist,
     * the cracking process is marked as complete.
     *
     * @param node The node
     * that will receive the next range
     * @return {@code true} if a range was successfully assigned,
     *         otherwise {@code false}
     */

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

            assignedRanges.put(
                    node.getId(),
                    range);

            activeTasks.incrementAndGet();

            return true;
        }

        pendingRanges.offer(range);
        return false;
    }

    /**
     * Sends a password cracking task to a node through HTTP.
     *
     * <p>The request contains:
     * <ul>
     *     <li>The target hash</li>
     *     <li>The assigned range start</li>
     *     <li>The assigned range end</li>
     * </ul>
     *
     * @param node The destination node
     * @param hash The password hash to crack
     * @param rangeStart The start index of the assigned range
     * @param rangeEnd The end index of the assigned range
     * @return {@code true} if the node accepted the task,
     *         otherwise {@code false}
     */

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

    /**
     * Handles the cracking result returned by a node.
     *
     * <p>This method:
     * <ul>
     *     <li>Removes the node's assigned range</li>
     *     <li>Stores the cracking result</li>
     *     <li>Updates active task tracking</li>
     *     <li>Stops all cracking if the password is found</li>
     *     <li>Assigns a new range if more work exists</li>
     * </ul>
     *
     * @param response The cracking result returned by a node
     */

    public void handleNodeResult(
            CrackingResponse response) {

        System.out.println(
                "Received result from node: "
                        + response.getNodeId());
        
        assignedRanges.remove(response.getNodeId());

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

    /**
     * Builds randomized password search ranges.
     *
     * <p>The total password space is divided into smaller chunks
     * of fixed size to distribute work evenly across nodes.
     *
     * <p>The resulting ranges are shuffled to improve load balancing.
     *
     * @param totalPasswords Total number of possible passwords
     * @param rangeSize Number of password attempts per range
     * @return A queue containing randomized password ranges
     */

    private Queue<long[]> buildRanges(
        long totalPasswords,
        long rangeSize) {

        List<long[]> ranges = new ArrayList<>();

        for (long start = 0; start < totalPasswords; start += rangeSize) {

            long end = Math.min(
                    start + rangeSize - 1,
                    totalPasswords - 1);

            ranges.add(
                    new long[] {
                            start,
                            end
                    });
        }

        Collections.shuffle(ranges);

        return new ConcurrentLinkedQueue<>(ranges);
    }

    /**
     * Handles node failure during distributed cracking.
     *
     * <p>If the failed node had an assigned range:
     * <ul>
     *     <li>The range is returned to the pending queue</li>
     *     <li>The active task counter is decremented</li>
     * </ul>
     *
     * <p>If no pending ranges and no active tasks remain,
     * the cracking process is marked as complete.
     *
     * @param nodeId The ID of the failed node
     */

    public void handleNodeFailure(
        String nodeId) {

        long[] range = assignedRanges.remove(nodeId);

        if (range != null) {

            System.out.println(
                    "Reassigning lost range from failed node "
                            + nodeId);

            pendingRanges.offer(range);

            activeTasks.updateAndGet(
                    value -> Math.max(0, value - 1));
        }

        if (pendingRanges.isEmpty()
                && activeTasks.get() == 0) {

            crackingDone = true;
        }
    }

    /**
     * Calculates the total number of possible passwords
     * based on the configured character set and password length.
     *
     * <p>Formula:
     * <pre>
     * total = CHARSET.length() ^ PASSWORD_LENGTH
     * </pre>
     *
     * @return Total number of possible password combinations
     */

    private long getTotalPossiblePasswords() {

        long total = 1;

        for (int i = 0; i < PASSWORD_LENGTH; i++) {

            total *= CHARSET.length();
        }

        return total;
    }
}
