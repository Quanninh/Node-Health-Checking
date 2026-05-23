# Node-Health-Checking

# Running the Project with PostgreSQL

## 1. Install PostgreSQL

Install PostgreSQL on your machine.

### macOS (Homebrew)

```bash
brew install postgresql
brew services start postgresql
```

### Verify installation

```bash
psql --version
```

---

## 2. Create the Database

Open PostgreSQL:

```bash
psql postgres
```

Create the database:

```sql
CREATE DATABASE dist_sys;
```

---

## 3. Create Database User and Grant Permissions

Inside the PSQL tool, run:

```sql
CREATE USER nodes_health WITH PASSWORD 'yourPass';

GRANT ALL PRIVILEGES
ON DATABASE dist_sys
TO nodes_health;
```

Connect to the database:

```sql
\c dist_sys
```

Grant schema permissions:

```sql
GRANT ALL ON SCHEMA public TO nodes_health;
```

---

## 4. Configure Environment Variables

Take a look at:

```text
.env.example
```

Copy it and create a new file:

```text
.env
```

inside the ServerApplication directory:

```text
Node-Health-Checking/ServerApplication
```

---

## 5. Fill in Your Database Credentials

Example `.env` file:

```env
DB_URL=jdbc:postgresql://localhost:5432/dist_sys
DB_USERNAME=nodes_health
DB_PASSWORD=yourPass
```

---

## 6. Run the Spring Boot Server

```bash
mvn spring-boot:run
```

If everything is configured correctly, Spring Boot will automatically:

- connect to PostgreSQL
- create/update tables
- start the backend server

---

## 7. Verify Database Connection

Inside PSQL:

```sql
\c dist_sys
```

Show tables:

```sql
\dt
```

You should see tables similar to:

```text
node
node_history
failure_report
```

# UPDATED: simulate nodes to communicate with each other
# NodeAgent Cluster Setup Guide

This guide explains how to spin up a local 3-node decentralized cluster using the `NodeAgent`. The nodes communicate with each other peer-to-peer (P2P) for health monitoring while reporting state changes back to a centralized dashboard server.

---

## CLI Configuration Flags

When launching a `NodeAgent`, the following parameters are used to configure its identity, networking, failure detection, and peer discovery:

### Core Networking Flags

| Flag | Description | Default |
| :--- | :--- | :--- |
| `--node-id` | A unique identifier for the node (e.g., `A`, `B`, `C`). | Random UUID (node-{8-char}) |
| `--bind-host` | The local network interface/IP address the node listens on. | `127.0.0.1` |
| `--advertise-host` | The IP address that this node tells its peers to use when reaching back to it. | Same as `--bind-host` |
| `--p2p-port` | The port dedicated to P2P node-to-node communication (Gossip/SWIM). | `9001` |
| `--dashboard-url` | The API endpoint of the centralized server used for demo, testing, and state visualization. | `http://localhost:6789/api` |
| `--neighbors` | A comma-separated list of known bootstrap peers formatted as `ID@IP:PORT`. | Empty |

<!-- ### Cluster & Neighbor Management

| Flag | Description | Default |
| :--- | :--- | :--- |
| `--join-timeout-seconds` | Timeout (in seconds) for joining the network. | `30` |
| `--join-min-probability` | Minimum probability threshold for join operations. | `0.1` |
| `--join-max-probability` | Maximum probability threshold for join operations. | `0.5` | -->

### Gossip & Failure Detection Intervals

| Flag | Description | Default |
| :--- | :--- | :--- |
| `--probe-interval-seconds` (or `--gossip-interval-seconds`) | Interval (in seconds) between gossip/probe messages. | `3` |
| `--ack-timeout-seconds` | Timeout (in seconds) for ACK responses from peer nodes. | `1` |

### Phi Accrual Failure Detection

| Flag | Description | Default |
| :--- | :--- | :--- |
| `--phi-window-size` | Size of the sliding window for phi accrual failure detection. | `100` |
| `--phi-warning-threshold` | Phi threshold for warning state. | `1.0` |
| `--phi-suspected-threshold` | Phi threshold for suspected failure state. | `3.0` |
| `--phi-unreachable-threshold` | Phi threshold for unreachable/failed state. | `5.0` |
| `--phi-min-std-deviation` | Minimum standard deviation for phi calculations. | `10` |
| `--phi-min-probability` | Minimum probability for phi calculations. | `0.001` |

---

## Local Deployment Steps

To simulate the decentralized network on your local machine, open four separate terminal windows and run the following commands sequentially.

### Step 1: Start Node A
Node A will listen on port `9001` and attempt to connect with Node B and Node C.
```bash
java -cp target/classes com.monitoring.agent.App \
  --node-id node-a \
  --bind-host 0.0.0.0 \
  --advertise-host [ip addr of Internet] \
  --p2p-port 9001 \
  --max-neighbors 4 \
  --multicast-interface en0
```
For Window
```bash
java -cp target/classes com.monitoring.agent.App `
  --bind-host 0.0.0.0 `
  --advertise-host 192.168.1.4 `
  --max-neighbors 4 `
  --multicast-interface en0 `
  --p2p-port 9001 
```

### Step 2: Start Node B
Node B will listen on port `9002` and attempt to connect with Node A and Node C.
```bash
java -cp target/classes com.monitoring.agent.App \
  --node-id node-b \
  --bind-host 0.0.0.0 \
  --advertise-host [ip addr of Internet] \
  --p2p-port 9002 \
  --max-neighbors 4 \
  --multicast-interface en0
```
For Window
```bash
java -cp target/classes com.monitoring.agent.App `
  --node-id node-b `
  --bind-host 0.0.0.0 `
  --advertise-host 192.168.1.4 `
  --p2p-port 9002 `
  --max-neighbors 4 `
  --multicast-interface en0
```

### Step 3: Start Node C
Node C will listen on port 9003 and attempt to connect with Node A and Node B.
```bash
java -cp target/classes com.monitoring.agent.App \
  --node-id node-c \
  --bind-host 0.0.0.0 \
  --advertise-host [ip addr of Internet] \
  --p2p-port 9003 \
  --max-neighbors 4 \
  --multicast-interface en0
```
For Window
```bash
java -cp target/classes com.monitoring.agent.App `
  --node-id node-c `
  --bind-host 0.0.0.0 `
  --advertise-host 192.168.1.4 `
  --p2p-port 9003 `
  --max-neighbors 4 `
  --multicast-interface en0
```

### Step 4: Start Node D
Node D will listen on port 9004 and attempt to connect with Node A and Node B.
```bash
java -cp target/classes com.monitoring.agent.App \
  --node-id node-d \
  --bind-host 0.0.0.0 \
  --advertise-host [ip addr of Internet] \
  --p2p-port 9004 \
  --max-neighbors 4 \
  --multicast-interface en0
```
For Window
```bash
java -cp target/classes com.monitoring.agent.App `
  --node-id node-d `
  --bind-host 0.0.0.0 `
  --advertise-host [ip addr of Internet] `
  --p2p-port 9004 `
  --max-neighbors 4 `
  --multicast-interface en0
```

### Step 5: Observe failure_report table
If nothing is observed, consider Ctrl-C for one node and wait for a short period of time before running it again.

---

## Workflow
```
[Many Computers (Nodes)]
        ↓
  send data (CPU, Memory)
        ↓
[Central Server]
        ↓
  process + store data
        ↓
[GUI Dashboard]
        ↓
  show status to users
```


## Directory structure
```
monitoring-system/monitoring-system/
│
├── ServerApplication/
│   ├── src/main/java/com/monitoring/
│   │   ├── ServerApplication.java
│   │   │
│   │   ├── controller/
│   │   │   └── NodeController.java
│   │   │
│   │   ├── service/
│   │   │   └── NodeService.java
│   │   │
│   │   ├── repository/
│   │   │   └── NodeRepository.java
│   │   │   └── NodeHistoryRepository.java
│   │   ├── model/
│   │   │   ├── Node.java
│   │   │   └── NodeHistory.java
│   │   │
│   │   └── config/
│   │       └── WebConfig.java
│   │
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   └── data.sql
│   │
│   └── pom.xml
│
├── node-agent/
│   └── NodeAgent.java
│
└── dashboard/
    └── index.html
```

## Web service for running server on the cloud
https://node-health-checking-10.onrender.com


