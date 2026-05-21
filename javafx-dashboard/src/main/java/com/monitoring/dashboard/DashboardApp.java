package com.monitoring.dashboard;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;
import java.util.List;

public class DashboardApp extends Application {

    private final NodeApiService nodeApiService = new NodeApiService();

    private VBox nodeListBox;
    private VBox alertListBox;

    private Label totalNodesValue;
    private Label upNodesValue;
    private Label warningNodesValue;
    private Label failedNodesValue;
    private Label connectionStatusLabel;

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();

        root.setTop(createHeader());
        root.setLeft(createSidebar());
        root.setCenter(createMainContent());

        Scene scene = new Scene(root, 1250, 760);

        stage.setTitle("Decentralised Node Health Dashboard");
        stage.setScene(scene);
        stage.show();

        refreshDashboard();
    }

    private HBox createHeader() {
        HBox header = new HBox();
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setSpacing(20);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #1f2937;");

        Label title = new Label("Decentralised Node Health Dashboard");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Arial", 24));

        connectionStatusLabel = new Label("Backend: checking...");
        connectionStatusLabel.setTextFill(Color.LIGHTGRAY);
        connectionStatusLabel.setFont(Font.font("Arial", 13));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshButton = new Button("Refresh");
        refreshButton.setStyle("""
                -fx-background-color: #2563eb;
                -fx-text-fill: white;
                -fx-font-size: 14px;
                -fx-background-radius: 8;
                -fx-padding: 8 16 8 16;
                """);
        refreshButton.setOnAction(event -> refreshDashboard());

        header.getChildren().addAll(title, connectionStatusLabel, spacer, refreshButton);
        return header;
    }

    private VBox createSidebar() {
        VBox sidebar = new VBox();
        sidebar.setPadding(new Insets(24, 16, 24, 16));
        sidebar.setSpacing(14);
        sidebar.setPrefWidth(230);
        sidebar.setStyle("-fx-background-color: #111827;");

        Label menuTitle = new Label("MONITORING");
        menuTitle.setTextFill(Color.web("#9ca3af"));
        menuTitle.setFont(Font.font("Arial", 13));

        Button dashboardButton = createSidebarButton("Dashboard");
        Button nodesButton = createSidebarButton("Nodes");
        Button alertsButton = createSidebarButton("Failure Reports");
        Button settingsButton = createSidebarButton("Settings");

        sidebar.getChildren().addAll(
                menuTitle,
                dashboardButton,
                nodesButton,
                alertsButton,
                settingsButton
        );

        return sidebar;
    }

    private Button createSidebarButton(String text) {
        Button button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setStyle("""
                -fx-background-color: transparent;
                -fx-text-fill: white;
                -fx-font-size: 15px;
                -fx-padding: 10 12 10 12;
                """);

        button.setOnMouseEntered(event -> button.setStyle("""
                -fx-background-color: #374151;
                -fx-text-fill: white;
                -fx-font-size: 15px;
                -fx-padding: 10 12 10 12;
                -fx-background-radius: 8;
                """));

        button.setOnMouseExited(event -> button.setStyle("""
                -fx-background-color: transparent;
                -fx-text-fill: white;
                -fx-font-size: 15px;
                -fx-padding: 10 12 10 12;
                """));

        return button;
    }

    private VBox createMainContent() {
        VBox mainContent = new VBox();
        mainContent.setPadding(new Insets(24));
        mainContent.setSpacing(22);
        mainContent.setStyle("-fx-background-color: #f3f4f6;");

        HBox statsSection = createStatsSection();

        HBox dashboardBody = new HBox();
        dashboardBody.setSpacing(20);

        VBox nodesSection = createNodesSection();
        VBox alertsSection = createAlertsSection();

        HBox.setHgrow(nodesSection, Priority.ALWAYS);

        dashboardBody.getChildren().addAll(nodesSection, alertsSection);

        mainContent.getChildren().addAll(statsSection, dashboardBody);
        VBox.setVgrow(dashboardBody, Priority.ALWAYS);

        return mainContent;
    }

    private HBox createStatsSection() {
        HBox statsBox = new HBox();
        statsBox.setSpacing(16);

        totalNodesValue = new Label("0");
        upNodesValue = new Label("0");
        warningNodesValue = new Label("0");
        failedNodesValue = new Label("0");

        statsBox.getChildren().addAll(
                createStatCard("Total Nodes", totalNodesValue, "#374151"),
                createStatCard("Up / Alive", upNodesValue, "#16a34a"),
                createStatCard("Warning / Suspected", warningNodesValue, "#f59e0b"),
                createStatCard("Failed / Unreachable", failedNodesValue, "#dc2626")
        );

        return statsBox;
    }

    private VBox createStatCard(String title, Label valueLabel, String valueColor) {
        VBox card = new VBox();
        card.setPadding(new Insets(18));
        card.setSpacing(8);
        card.setPrefWidth(210);
        card.setStyle("""
                -fx-background-color: white;
                -fx-background-radius: 14;
                -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.10), 10, 0, 0, 4);
                """);

        Label titleLabel = new Label(title);
        titleLabel.setTextFill(Color.GRAY);
        titleLabel.setFont(Font.font("Arial", 14));

        valueLabel.setTextFill(Color.web(valueColor));
        valueLabel.setFont(Font.font("Arial", 30));

        card.getChildren().addAll(titleLabel, valueLabel);
        return card;
    }

    private VBox createNodesSection() {
        VBox section = new VBox();
        section.setSpacing(14);

        Label title = new Label("Live Node Status");
        title.setFont(Font.font("Arial", 22));

        nodeListBox = new VBox();
        nodeListBox.setSpacing(14);

        ScrollPane scrollPane = new ScrollPane(nodeListBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent;");
        scrollPane.setPadding(new Insets(0));

        section.getChildren().addAll(title, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        return section;
    }

    private VBox createAlertsSection() {
        VBox section = new VBox();
        section.setSpacing(14);
        section.setPrefWidth(360);

        Label title = new Label("Failure Reports");
        title.setFont(Font.font("Arial", 22));

        alertListBox = new VBox();
        alertListBox.setSpacing(12);

        ScrollPane scrollPane = new ScrollPane(alertListBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent;");

        section.getChildren().addAll(title, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        return section;
    }

    private void refreshDashboard() {
        connectionStatusLabel.setText("Backend: loading...");
        connectionStatusLabel.setTextFill(Color.LIGHTGRAY);

        Thread worker = new Thread(() -> {
            try {
                List<NodeDto> nodes = nodeApiService.getAllNodes();
                List<FailureReportDto> failureReports = nodeApiService.getFailureReports();

                Platform.runLater(() -> {
                    updateNodeList(nodes);
                    updateFailureReports(failureReports);
                    updateStats(nodes);

                    connectionStatusLabel.setText("Backend: connected");
                    connectionStatusLabel.setTextFill(Color.web("#86efac"));
                });
            } catch (Exception error) {
                Platform.runLater(() -> {
                    connectionStatusLabel.setText("Backend: disconnected");
                    connectionStatusLabel.setTextFill(Color.web("#fca5a5"));

                    nodeListBox.getChildren().clear();
                    nodeListBox.getChildren().add(createMessageCard(
                            "Could not connect to Spring Boot backend.\n\n" +
                                    "Make sure ServerApplication is running on port 6789."
                    ));

                    alertListBox.getChildren().clear();
                });
            }
        });

        worker.setDaemon(true);
        worker.start();
    }

    private void updateNodeList(List<NodeDto> nodes) {
        nodeListBox.getChildren().clear();

        if (nodes.isEmpty()) {
            nodeListBox.getChildren().add(createMessageCard(
                    "No nodes found yet.\n\nStart your node-agent instances and let them report to the dashboard."
            ));
            return;
        }

        for (NodeDto node : nodes) {
            nodeListBox.getChildren().add(createNodeCard(node));
        }
    }

    private HBox createNodeCard(NodeDto node) {
        HBox card = new HBox();
        card.setPadding(new Insets(18));
        card.setSpacing(20);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setStyle("""
                -fx-background-color: white;
                -fx-background-radius: 14;
                -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 8, 0, 0, 3);
                """);

        VBox identityBox = new VBox(6);

        Label nodeId = new Label(nullSafe(node.getId()));
        nodeId.setFont(Font.font("Arial", 18));

        Label ipAddress = new Label("Address: " + nullSafe(node.getIpAddress()));
        ipAddress.setTextFill(Color.GRAY);

        Label heartbeat = new Label("Last heartbeat: " + formatDateTime(node.getLastHeartbeat()));
        heartbeat.setTextFill(Color.GRAY);

        identityBox.getChildren().addAll(nodeId, ipAddress, heartbeat);

        VBox cpuBox = createUsageBox("CPU", node.getCpuUsage());
        VBox memoryBox = createUsageBox("Memory", node.getMemoryUsage());

        Label statusBadge = createStatusBadge(node.getStatus());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        card.getChildren().addAll(identityBox, spacer, cpuBox, memoryBox, statusBadge);

        return card;
    }

    private VBox createUsageBox(String title, double value) {
        VBox box = new VBox(6);
        box.setPrefWidth(160);

        Label label = new Label(title + ": " + String.format("%.1f", value) + "%");
        label.setFont(Font.font("Arial", 13));

        ProgressBar progressBar = new ProgressBar(value / 100.0);
        progressBar.setPrefWidth(150);

        box.getChildren().addAll(label, progressBar);
        return box;
    }

    private Label createStatusBadge(String status) {
        String safeStatus = status == null ? "UNKNOWN" : status.toUpperCase();

        Label badge = new Label(safeStatus);
        badge.setTextFill(Color.WHITE);
        badge.setPadding(new Insets(8, 14, 8, 14));
        badge.setStyle(
                "-fx-background-radius: 20;" +
                        "-fx-background-color: " + getStatusColor(safeStatus) + ";"
        );

        return badge;
    }

    private String getStatusColor(String status) {
        return switch (status) {
            case "UP", "ALIVE" -> "#16a34a";
            case "WARNING", "SUSPECTED" -> "#f59e0b";
            case "FAILED", "DOWN", "UNREACHABLE" -> "#dc2626";
            default -> "#6b7280";
        };
    }

    private void updateFailureReports(List<FailureReportDto> failureReports) {
        alertListBox.getChildren().clear();

        if (failureReports.isEmpty()) {
            alertListBox.getChildren().add(createMessageCard("No failure reports yet."));
            return;
        }

        for (FailureReportDto report : failureReports) {
            alertListBox.getChildren().add(createFailureReportCard(report));
        }
    }

    private VBox createFailureReportCard(FailureReportDto report) {
        VBox card = new VBox();
        card.setPadding(new Insets(14));
        card.setSpacing(6);
        card.setStyle("""
                -fx-background-color: white;
                -fx-background-radius: 12;
                -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 8, 0, 0, 3);
                """);

        Label title = new Label("Failed Node: " + nullSafe(report.getFailedNodeId()));
        title.setFont(Font.font("Arial", 15));

        Label reporter = new Label("Reported by: " + nullSafe(report.getReporterNodeId()));
        reporter.setTextFill(Color.GRAY);

        Label phi = new Label("Phi: " + String.format("%.4f", report.getPhi()));
        phi.setTextFill(Color.GRAY);

        Label time = new Label("Time: " + formatDateTime(report.getTimestamp()));
        time.setTextFill(Color.GRAY);

        Label message = new Label(nullSafe(report.getMessage()));
        message.setWrapText(true);

        card.getChildren().addAll(title, reporter, phi, time, message);
        return card;
    }

    private void updateStats(List<NodeDto> nodes) {
        int total = nodes.size();
        int up = 0;
        int warning = 0;
        int failed = 0;

        for (NodeDto node : nodes) {
            String status = node.getStatus() == null ? "UNKNOWN" : node.getStatus().toUpperCase();

            switch (status) {
                case "UP", "ALIVE" -> up++;
                case "WARNING", "SUSPECTED" -> warning++;
                case "FAILED", "DOWN", "UNREACHABLE" -> failed++;
                default -> {
                    // Unknown nodes are counted only in total.
                }
            }
        }

        totalNodesValue.setText(String.valueOf(total));
        upNodesValue.setText(String.valueOf(up));
        warningNodesValue.setText(String.valueOf(warning));
        failedNodesValue.setText(String.valueOf(failed));
    }

    private VBox createMessageCard(String messageText) {
        VBox card = new VBox();
        card.setPadding(new Insets(16));
        card.setStyle("""
                -fx-background-color: white;
                -fx-background-radius: 12;
                -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.06), 8, 0, 0, 3);
                """);

        Label message = new Label(messageText);
        message.setWrapText(true);
        message.setTextFill(Color.GRAY);

        card.getChildren().add(message);
        return card;
    }

    private String formatDateTime(java.time.LocalDateTime dateTime) {
        if (dateTime == null) {
            return "N/A";
        }

        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String nullSafe(String value) {
        if (value == null || value.isBlank()) {
            return "N/A";
        }

        return value;
    }

    public static void main(String[] args) {
        launch(args);
    }
}