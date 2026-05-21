/*
 * Decentralized dashboard observer.
 *
 * This dashboard does NOT decide whether a node is dead.
 * It only asks each node-agent what failure events it has seen.
 */

const NODE_ENDPOINTS = [
    {
        nodeId: "node-a",
        baseUrl: "http://localhost:9001",
    },
    {
        nodeId: "node-b",
        baseUrl: "http://localhost:9002",
    },
    {
        nodeId: "node-c",
        baseUrl: "http://localhost:9003",
    },
    {
        nodeId: "node-d",
        baseUrl: "http://localhost:9004",
    },
];

const REFRESH_INTERVAL_MS = 2000;

async function fetchAllFailureEvents() {
    const allRows = [];
    const reachableNodes = [];
    const unreachableNodes = [];

    for (const endpoint of NODE_ENDPOINTS) {
        try {
            const events = await fetchFailureEventsFromNode(endpoint);

            reachableNodes.push(endpoint.nodeId);

            for (const event of events) {
                allRows.push({
                    ...event,

                    /*
                     * "seenFromNode" means:
                     * We fetched this event from this node's local event log.
                     *
                     * Example:
                     * reporterNodeId = node-a
                     * failedNodeId   = node-d
                     * seenFromNode   = node-b
                     *
                     * Meaning:
                     * node-a reported node-d unreachable,
                     * and node-b has received/stored that event.
                     */
                    seenFromNode: endpoint.nodeId,
                });
            }
        } catch (error) {
            unreachableNodes.push(endpoint.nodeId);
            console.warn(`Endpoint offline: ${endpoint.nodeId} at ${endpoint.baseUrl}`);
            //console.warn(`Could not fetch from ${endpoint.nodeId}:`, error);
        }
    }

    allRows.sort((a, b) => {
        const left = new Date(a.timestamp || 0).getTime();
        const right = new Date(b.timestamp || 0).getTime();
        return right - left;
    });

    updateDashboard(allRows, reachableNodes, unreachableNodes);
}

async function fetchFailureEventsFromNode(endpoint) {
    const response = await fetch(`${endpoint.baseUrl}/failure-events`);

    if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
    }

    const data = await response.json();

    if (!Array.isArray(data)) {
        return [];
    }

    return data;
}

function updateDashboard(rows, reachableNodes, unreachableNodes) {
    updateConnectionStatus(reachableNodes, unreachableNodes);
    updateCards(rows, reachableNodes);
    updateAlert(rows, unreachableNodes);
    updateFailureTable(rows);
}

function updateConnectionStatus(reachableNodes, unreachableNodes) {
    const status = document.getElementById("connectionStatus");

    status.className = "connection-status";

    if (unreachableNodes.length === 0) {
        status.classList.add("connected");
        status.innerText = `All node endpoints reachable: ${reachableNodes.join(", ")}`;
        return;
    }

    if (reachableNodes.length === 0) {
        status.classList.add("disconnected");
        status.innerText = `No node endpoints reachable. Start node-agent instances first.`;
        return;
    }

    status.classList.add("partial");
    status.innerText =
        `Reachable: ${reachableNodes.join(", ")} | ` +
        `Unreachable endpoints: ${unreachableNodes.join(", ")}`;
}

function updateCards(rows, reachableNodes) {
    const failedNodeIds = new Set();
    const reporterNodeIds = new Set();
    const uniqueEventIds = new Set();

    for (const row of rows) {
        if (row.failedNodeId) {
            failedNodeIds.add(row.failedNodeId);
        }

        if (row.reporterNodeId) {
            reporterNodeIds.add(row.reporterNodeId);
        }

        if (row.eventId) {
            uniqueEventIds.add(row.eventId);
        }
    }

    document.getElementById("totalEvents").innerText = rows.length;
    document.getElementById("failedNodes").innerText = failedNodeIds.size;
    document.getElementById("reportingNodes").innerText = reporterNodeIds.size;
    document.getElementById("reachableNodes").innerText = reachableNodes.length;

    const uniqueEventsElement = document.getElementById("uniqueEvents");
    if (uniqueEventsElement) {
        uniqueEventsElement.innerText = uniqueEventIds.size;
    }
}

function updateAlert(rows, unreachableNodes) {
    const alertBox = document.getElementById("alertBox");

    if (rows.length === 0) {
        alertBox.classList.add("hidden");
        alertBox.innerText = "";
        return;
    }

    const failedNodeIds = [...new Set(rows.map((row) => row.failedNodeId))];

    alertBox.classList.remove("hidden");
    alertBox.innerText =
        `Failure event(s) detected for: ${failedNodeIds.join(", ")}. ` +
        `Unreachable endpoints: ${unreachableNodes.join(", ") || "none"}.`;
}

function updateFailureTable(rows) {
    const table = document.getElementById("failureTable");
    table.innerHTML = "";

    if (rows.length === 0) {
        const row = document.createElement("tr");

        row.innerHTML = `
            <td colspan="8" class="empty-table">
                No failure events observed yet.
            </td>
        `;

        table.appendChild(row);
        return;
    }

    for (const event of rows) {
        const row = document.createElement("tr");

        row.title = event.eventId || "";

        row.innerHTML = `
            <td>${safeText(event.failedNodeId)}</td>
            <td>${safeText(event.reporterNodeId)}</td>
            <td>${safeText(event.seenFromNode)}</td>
            <td class="${statusClass(event.status)}">${safeText(event.status)}</td>
            <td>${formatNumber(event.phi)}</td>
            <td>${formatNumber(event.threshold)}</td>
            <td>${formatTimestamp(event.timestamp)}</td>
            <td class="message-cell">${safeText(event.message)}</td>
        `;

        table.appendChild(row);
    }
}

function statusClass(status) {
    const normalized = String(status || "").toUpperCase();

    if (normalized === "UNREACHABLE" || normalized === "FAILED" || normalized === "DOWN") {
        return "status-down";
    }

    if (normalized === "WARNING" || normalized === "SUSPECTED") {
        return "status-warning";
    }

    if (normalized === "ALIVE" || normalized === "UP") {
        return "status-up";
    }

    return "status-unknown";
}

function formatNumber(value) {
    const number = Number(value);

    if (Number.isNaN(number)) {
        return "-";
    }

    return number.toFixed(4);
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

document.getElementById("refreshButton").addEventListener("click", fetchAllFailureEvents);

setInterval(fetchAllFailureEvents, REFRESH_INTERVAL_MS);
fetchAllFailureEvents();