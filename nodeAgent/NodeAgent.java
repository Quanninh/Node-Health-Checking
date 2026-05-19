import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NodeAgent {

    // =========================
    // CONFIGURATION
    // =========================

    private static final String SERVER_URL =
            "https://node-health-checking-10.onrender.com/heartbeat";

    private static final int HEARTBEAT_INTERVAL_SECONDS = 10;

    private static final int MAX_RETRIES = 3;

    // =========================
    // NODE INFO
    // =========================

    private static String nodeId;
    private static String hostname;

    // =========================
    // HTTP CLIENT
    // =========================

    private static final HttpClient httpClient =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

    // =========================
    // MAIN
    // =========================

    public static void main(String[] args) {

        initializeNodeInfo();

        System.out.println("====================================");
        System.out.println("Node Agent Started");
        System.out.println("Node ID  : " + nodeId);
        System.out.println("Hostname : " + hostname);
        System.out.println("Server   : " + SERVER_URL);
        System.out.println("====================================");

        startMonitoringLoop();
    }

    // =========================
    // INITIALIZATION
    // =========================

    private static void initializeNodeInfo() {

        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown-host";
        }

        // If user provides node ID from command line
        if (argsProvided()) {
            nodeId = getArgumentNodeId();
        } else {
            nodeId = hostname + "-" +
                    UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private static boolean argsProvided() {
        return savedArgs != null && savedArgs.length > 0;
    }

    private static String getArgumentNodeId() {
        return savedArgs[0];
    }

    // Save args globally
    private static String[] savedArgs;

    static {
        savedArgs = new String[0];
    }

    // =========================
    // MONITORING LOOP
    // =========================

    private static void startMonitoringLoop() {

        ScheduledExecutorService scheduler =
                Executors.newScheduledThreadPool(1);

        scheduler.scheduleAtFixedRate(() -> {

            try {

                double cpuUsage = getCpuUsage();
                double memoryUsage = getMemoryUsage();

                logMetrics(cpuUsage, memoryUsage);

                sendHeartbeatWithRetry(cpuUsage, memoryUsage);

            } catch (Exception e) {

                System.out.println(
                        "[" + LocalDateTime.now() + "] "
                                + "Unexpected Error: "
                                + e.getMessage()
                );
            }

        }, 0, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // =========================
    // METRICS
    // =========================

    private static double getCpuUsage() {

    OperatingSystemMXBean osBean =
            (OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();

    double cpuLoad = osBean.getCpuLoad();

    if (cpuLoad < 0) {
        return 0.0;
    }

    return cpuLoad * 100;
    }


    private static double getMemoryUsage() {

        Runtime runtime = Runtime.getRuntime();

        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();

        long usedMemory = totalMemory - freeMemory;

        return ((double) usedMemory / totalMemory) * 100;
    }

    // =========================
    // HEARTBEAT
    // =========================

    private static void sendHeartbeatWithRetry(
            double cpuUsage,
            double memoryUsage
    ) {

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {

            try {

                sendHeartbeat(cpuUsage, memoryUsage);

                System.out.println(
                        "[" + LocalDateTime.now() + "] "
                                + "Heartbeat sent successfully."
                );

                return;

            } catch (Exception e) {

                System.out.println(
                        "[" + LocalDateTime.now() + "] "
                                + "Heartbeat failed (Attempt "
                                + attempt + "/" + MAX_RETRIES + ")"
                );

                if (attempt == MAX_RETRIES) {

                    System.out.println(
                            "[" + LocalDateTime.now() + "] "
                                    + "Max retries reached."
                    );
                }

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private static void sendHeartbeat(
            double cpuUsage,
            double memoryUsage
    ) throws IOException, InterruptedException {

        String json = createHeartbeatJson(cpuUsage, memoryUsage);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SERVER_URL))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        System.out.println(
                "[" + LocalDateTime.now() + "] "
                        + "Server Response: "
                        + response.statusCode()
        );
    }

    // =========================
    // JSON CREATION
    // =========================

    private static String createHeartbeatJson(
            double cpuUsage,
            double memoryUsage
    ) {

        return String.format("""
                {
                    "nodeId": "%s",
                    "hostname": "%s",
                    "cpuUsage": %.2f,
                    "memoryUsage": %.2f,
                    "timestamp": "%s"
                }
                """,
                nodeId,
                hostname,
                cpuUsage,
                memoryUsage,
                LocalDateTime.now()
        );
    }

    // =========================
    // LOGGING
    // =========================

    private static void logMetrics(
            double cpuUsage,
            double memoryUsage
    ) {

        System.out.println("------------------------------------");
        System.out.println("Time       : " + LocalDateTime.now());
        System.out.println("Node ID    : " + nodeId);
        System.out.printf("CPU Usage  : %.2f%%%n", cpuUsage);
        System.out.printf("Memory Use : %.2f%%%n", memoryUsage);
        System.out.println("------------------------------------");
    }
}