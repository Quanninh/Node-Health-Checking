// const API = "https://node-health-checking-10.onrender.com/api/nodes";
const API = "http://localhost:6789/api/nodes";

let chart;
let selectedNodeId = null;
let isLoadingHistory = false;

async function fetchNodes() {
    try {
        const res = await fetch(API);

        if (!res.ok) {
            throw new Error(`Server returned ${res.status}`);
        }

        const nodes = await res.json();

        updateConnectionStatus("Connected to server", true);
        updateTable(nodes);
        updateCards(nodes);
        checkAlerts(nodes);

        if (selectedNodeId) {
            loadHistory(selectedNodeId);
        }
    } catch (error) {
        updateConnectionStatus(
            "Cannot connect to server. Start Spring Boot first.",
            false,
        );
        console.error(error);
    }
}

function updateTable(nodes) {
    const table = document.getElementById("nodeTable");
    const scrollX = window.scrollX;
    const scrollY = window.scrollY;
    table.innerHTML = "";

    nodes.forEach((node) => {
        const row = document.createElement("tr");

        row.innerHTML = `
            <td>${node.id}</td>
            <td>${node.ipAddress || "-"}</td>
            <td>${Number(node.cpuUsage).toFixed(1)}%</td>
            <td>${Number(node.memoryUsage).toFixed(1)}%</td>
            <td class="${node.status === "UP" ? "status-up" : "status-down"}">
                ${node.status}
            </td>
        `;

        if (node.id === selectedNodeId) {
            row.classList.add("selected-row");
        }

        row.onclick = () => {
            selectRow(row, node.id);
            loadHistory(node.id);
        };

        table.appendChild(row);
    });

    window.scrollTo(scrollX, scrollY);
}

function updateCards(nodes) {
    const up = nodes.filter((n) => n.status === "UP").length;
    const down = nodes.length - up;

    document.getElementById("totalNodes").innerText = nodes.length;
    document.getElementById("upNodes").innerText = up;
    document.getElementById("downNodes").innerText = down;
}

function checkAlerts(nodes) {
    const alertBox = document.getElementById("alertBox");
    const downNodes = nodes.filter((n) => n.status === "DOWN");

    if (downNodes.length > 0) {
        alertBox.classList.remove("hidden");
        alertBox.innerText = `${downNodes.length} node(s) DOWN!`;
    } else {
        alertBox.classList.add("hidden");
    }
}

function selectRow(row, nodeId) {
    selectedNodeId = nodeId;
    document.querySelectorAll("#nodeTable tr").forEach((tableRow) => {
        tableRow.classList.remove("selected-row");
    });
    row.classList.add("selected-row");
}

async function loadHistory(nodeId) {
    if (isLoadingHistory) {
        return;
    }

    selectedNodeId = nodeId;
    isLoadingHistory = true;

    document.getElementById("chartTitle").innerText =
        "CPU History for " + nodeId;

    try {
        const res = await fetch(`${API}/${nodeId}/history`);

        if (!res.ok) {
            throw new Error(`Server returned ${res.status}`);
        }

        const data = await res.json();
        const labels = data.map((d) =>
            new Date(d.timestamp).toLocaleTimeString(),
        );
        const cpu = data.map((d) => d.cpuUsage);

        drawChart(labels, cpu);
    } catch (error) {
        console.error(error);
    } finally {
        isLoadingHistory = false;
    }
}

function drawChart(labels, data) {
    const ctx = document.getElementById("cpuChart").getContext("2d");

    if (chart) {
        chart.data.labels = labels;
        chart.data.datasets[0].data = data;
        chart.update("none");
        return;
    }

    chart = new Chart(ctx, {
        type: "line",
        data: {
            labels: labels,
            datasets: [
                {
                    label: "CPU %",
                    data: data,
                    borderColor: "#22c55e",
                    fill: false,
                },
            ],
        },
        options: {
            responsive: true,
        },
    });
}

function updateConnectionStatus(message, isConnected) {
    const status = document.getElementById("connectionStatus");
    status.innerText = message;
    status.className = isConnected
        ? "connection-status connected"
        : "connection-status disconnected";
}

setInterval(fetchNodes, 2000);
fetchNodes();
