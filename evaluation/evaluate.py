import csv
import subprocess
import time
import requests
from pathlib import Path

API_BASE = "http://localhost:6789/api"

JAR_PATH = Path("node-agent-1.0.jar")

MAX_NEIGHBORS = 4
NODE_COUNT = 10

processes = {}

def start_node(node_id):
    cmd = [
        "java",
        "-jar",
        str(JAR_PATH),
        "--bind-host", "0.0.0.0",
        "--advertise-host", "192.168.1.6",
        "--max-neighbors", str(MAX_NEIGHBORS),
        "--multicast-interface", "wireless_32768",
        "--node-id", node_id
    ]

    p = subprocess.Popen(
        cmd,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )

    processes[node_id] = p
    print(node_id + " started")

def kill_node(node_id):
    p = processes[node_id]
    p.kill()
    p.wait()


def get_node(node_id):
    r = requests.get(
        f"{API_BASE}/nodes/{node_id}",
        timeout=5
    )

    if r.status_code != 200:
        return None

    return r.json()


def wait_until_failed(node_id):
    while True:
        node = get_node(node_id)
        if node:
            status = node["status"]
            if status in ("DOWN", "FAILED"):
                return
        time.sleep(0.5)


def kill_all_nodes():
    print("\nKilling all nodes...")
    for node_id, p in processes.items():
        try:
            if p.poll() is None:  # still running
                print(f" - killing {node_id}")
                p.kill()
                p.wait(timeout=3)
        except Exception as e:
            print(f"Failed to kill {node_id}: {e}")

def main():
    for i in range(NODE_COUNT):
        start_node(f"node-{i}")
    print("Waiting for cluster...")
    time.sleep(30)
    victim = "node-5"
    t0 = time.time()
    kill_node(victim)
    wait_until_failed(victim)
    t1 = time.time()
    latency_ms = (t1 - t0) * 1000
    print(f"Detection latency = {latency_ms:.0f} ms")
    with open("results.csv", "a", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([
            NODE_COUNT,
            victim,
            latency_ms
        ])


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nCTRL+C detected!")
    finally:
        kill_all_nodes()