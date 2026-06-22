/*
 * Centralized observability dashboard.
 *
 * Health checking remains decentralized:
 * nodes ping/check each other through P2P.
 *
 * Dashboard reporting is centralized:
 * node-agents send heartbeats and failure reports to Spring Boot,
 * then this dashboard reads from Spring Boot REST API.
 */

const DASHBOARD_URL =
    window.location.protocol === "file:"
        ? "http://localhost:6789/api"
        : `${window.location.protocol}//${window.location.hostname}:6789/api`;
const API = `${DASHBOARD_URL}/nodes`;
const FAILURE_REPORTS_API = `${DASHBOARD_URL}/failure-reports`;
const REFRESH_INTERVAL_MS = 2000;

let selectedNodeId = null;

async function refreshDashboard() {
    try {
        const nodes = await fetchNodes();
        const failureReports = await fetchFailureReports();

        updateConnectionStatus("Connected to Spring Boot API", true);
        updateCards(nodes, failureReports);
        updateNodeTable(nodes);
        updateFailureReportTable(failureReports);
        updateAlert(nodes, failureReports);
    } catch (error) {
        updateConnectionStatus(
            "Cannot connect to Spring Boot API. Start ServerApplication first.",
            false,
        );
        console.error(error);
    }
}

async function fetchNodes() {
    const response = await fetch(API);

    if (!response.ok) {
        throw new Error(`GET /api/nodes returned HTTP ${response.status}`);
    }

    const data = await response.json();

    if (!Array.isArray(data)) {
        return [];
    }

    return data;
}

async function fetchFailureReports() {
    const response = await fetch(FAILURE_REPORTS_API);

    if (!response.ok) {
        throw new Error(
            `GET /api/failure-reports returned HTTP ${response.status}`,
        );
    }

    const data = await response.json();

    if (!Array.isArray(data)) {
        return [];
    }

    return data;
}

function updateCards(nodes, failureReports) {
    const upNodes = nodes.filter(
        (node) => normalizeStatus(node.status) === "UP",
    ).length;

    const failedNodes = nodes.filter((node) => {
        const status = normalizeStatus(node.status);
        return (
            status === "FAILED" || status === "DOWN" || status === "UNREACHABLE"
        );
    }).length;

    document.getElementById("totalNodes").innerText = nodes.length;
    document.getElementById("upNodes").innerText = upNodes;
    document.getElementById("failedNodes").innerText = failedNodes;
    document.getElementById("failureReports").innerText = failureReports.length;
}

function updateNodeTable(nodes) {
    const table = document.getElementById("nodeTable");

    const scrollX = window.scrollX;
    const scrollY = window.scrollY;

    table.innerHTML = "";

    if (nodes.length === 0) {
        const row = document.createElement("tr");
        row.innerHTML = `
            <td colspan="4" class="empty-table">
                No nodes received yet.
            </td>
        `;
        table.appendChild(row);
        return;
    }

    const sortedNodes = [...nodes].sort((a, b) =>
        String(a.id || "").localeCompare(String(b.id || ""), undefined, {
            numeric: true,
            sensitivity: "base",
        }),
    );

    for (const node of sortedNodes) {
        const row = document.createElement("tr");

        if (node.id === selectedNodeId) {
            row.classList.add("selected-row");
        }

        row.innerHTML = `
            <td>${safeText(node.id)}</td>
            <td>${safeText(node.ipAddress)}</td>
            <td>${safeText((node.neighbors || []).join(", "))}</td>
            <td class="${statusClass(node.status)}">
                ${safeText(node.status)}
            </td>
            <td>${formatTimestamp(node.lastHeartbeat)}</td>
        `;

        table.appendChild(row);
    }

    window.scrollTo(scrollX, scrollY);
}

function updateFailureReportTable(failureReports) {
    const table = document.getElementById("failureReportTable");
    table.innerHTML = "";

    if (failureReports.length === 0) {
        const row = document.createElement("tr");
        row.innerHTML = `
            <td colspan="7" class="empty-table">
                No failure reports received yet.
            </td>
        `;
        table.appendChild(row);
        return;
    }

    const sortedReports = [...failureReports].sort((a, b) => {
        const left = new Date(a.timestamp || 0).getTime();
        const right = new Date(b.timestamp || 0).getTime();
        return right - left;
    });

    for (const report of sortedReports) {
        const row = document.createElement("tr");

        row.innerHTML = `
            <td>${safeText(report.failedNodeId)}</td>
            <td>${safeText(report.reporterNodeId)}</td>
            <td class="${statusClass(report.status)}">${safeText(report.status)}</td>
            <td>${formatNumber(report.phi)}</td>
            <td>${formatNumber(report.threshold)}</td>
            <td>${formatTimestamp(report.timestamp)}</td>
            <td class="message-cell">${safeText(report.message)}</td>
        `;

        table.appendChild(row);
    }
}

function updateAlert(nodes, failureReports) {
    const alertBox = document.getElementById("alertBox");

    const failedNodeIds = new Set();

    for (const node of nodes) {
        const status = normalizeStatus(node.status);

        if (
            status === "FAILED" ||
            status === "DOWN" ||
            status === "UNREACHABLE"
        ) {
            failedNodeIds.add(node.id);
        }
    }

    for (const report of failureReports) {
        if (report.failedNodeId) {
            failedNodeIds.add(report.failedNodeId);
        }
    }

    if (failedNodeIds.size === 0) {
        alertBox.classList.add("hidden");
        alertBox.innerText = "";
        return;
    }

    alertBox.classList.remove("hidden");
    alertBox.innerText =
        `Failure detected for: ${[...failedNodeIds].join(", ")}. ` +
        `Reports received: ${failureReports.length}.`;
}

function updateConnectionStatus(message, isConnected) {
    const status = document.getElementById("connectionStatus");

    status.innerText = message;
    status.className = isConnected
        ? "connection-status connected"
        : "connection-status disconnected";
}

function statusClass(status) {
    const normalized = normalizeStatus(status);

    if (normalized === "UP" || normalized === "ALIVE") {
        return "status-up";
    }

    if (normalized === "WARNING" || normalized === "SUSPECTED") {
        return "status-warning";
    }

    if (
        normalized === "DOWN" ||
        normalized === "FAILED" ||
        normalized === "UNREACHABLE"
    ) {
        return "status-down";
    }

    return "status-unknown";
}

function normalizeStatus(status) {
    return String(status || "")
        .trim()
        .toUpperCase();
}

function formatNumber(value) {
    const number = Number(value);

    if (Number.isNaN(number)) {
        return "0.0";
    }

    return number.toFixed(1);
}

function formatTimestamp(value) {
    if (!value) {
        return "-";
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return date.toLocaleString();
}

function safeText(value) {
    if (value === null || value === undefined || value === "") {
        return "-";
    }

    return String(value);
}

document
    .getElementById("refreshButton")
    .addEventListener("click", refreshDashboard);

setInterval(refreshDashboard, REFRESH_INTERVAL_MS);
refreshDashboard();
