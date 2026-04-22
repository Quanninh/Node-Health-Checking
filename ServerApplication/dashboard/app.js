const API = "https://node-health-checking-10.onrender.com/api/nodes";

let chart;

// =====================
// FETCH NODES
// =====================
async function fetchNodes() {
    const res = await fetch(API);
    const nodes = await res.json();

    updateTable(nodes);
    updateCards(nodes);
    checkAlerts(nodes);
}

// =====================
// UPDATE TABLE
// =====================
function updateTable(nodes) {
    const table = document.getElementById("nodeTable");
    table.innerHTML = "";

    nodes.forEach(node => {

        const row = document.createElement("tr");

        row.innerHTML = `
            <td>${node.id}</td>
            <td>${node.ipAddress || '-'}</td>
            <td>${node.cpuUsage.toFixed(1)}%</td>
            <td>${node.memoryUsage.toFixed(1)}%</td>
            <td class="${node.status === 'UP' ? 'status-up' : 'status-down'}">
                ${node.status}
            </td>
        `;

        // CLICK → LOAD HISTORY
        row.onclick = () => loadHistory(node.id);

        table.appendChild(row);
    });
}

// =====================
// UPDATE CARDS
// =====================
function updateCards(nodes) {
    let up = nodes.filter(n => n.status === "UP").length;
    let down = nodes.length - up;

    document.getElementById("totalNodes").innerText = nodes.length;
    document.getElementById("upNodes").innerText = up;
    document.getElementById("downNodes").innerText = down;
}

// =====================
// ALERT SYSTEM
// =====================
function checkAlerts(nodes) {
    const alertBox = document.getElementById("alertBox");

    const downNodes = nodes.filter(n => n.status === "DOWN");

    if (downNodes.length > 0) {
        alertBox.classList.remove("hidden");
        alertBox.innerText = `⚠️ ${downNodes.length} node(s) DOWN!`;
    } else {
        alertBox.classList.add("hidden");
    }
}

// =====================
// LOAD HISTORY
// =====================
async function loadHistory(nodeId) {

    document.getElementById("chartTitle").innerText =
        "CPU History for " + nodeId;

    const res = await fetch(`${API}/${nodeId}/history`);
    const data = await res.json();

    const labels = data.map(d => new Date(d.timestamp).toLocaleTimeString());
    const cpu = data.map(d => d.cpuUsage);

    drawChart(labels, cpu);
}

// =====================
// DRAW CHART
// =====================
function drawChart(labels, data) {

    const ctx = document.getElementById("cpuChart").getContext("2d");

    if (chart) chart.destroy();

    chart = new Chart(ctx, {
        type: "line",
        data: {
            labels: labels,
            datasets: [{
                label: "CPU %",
                data: data,
                borderColor: "#22c55e",
                fill: false
            }]
        },
        options: {
            responsive: true
        }
    });
}

// =====================
// AUTO REFRESH
// =====================
setInterval(fetchNodes, 5000);
fetchNodes();