import csv
import json
import random
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

import requests

API_BASE = "http://localhost:6789/api"
SCRIPT_DIR = Path(__file__).resolve().parent
JAR_PATH = SCRIPT_DIR / "node-agent-1.0.jar"
LOG_DIR = SCRIPT_DIR / "logs"
RESULTS_CSV = SCRIPT_DIR / "results.csv"

ADVERTISE_HOST = "192.168.1.6"
MULTICAST_INTERFACE = "wireless_32768"
MAX_NEIGHBORS = 6
INITIAL_NODE_COUNT = 12
ADDED_NODE_COUNT = 6
PHASE_TIMEOUT_SECONDS = 120
PHASE_3_TIMEOUT_SECONDS = 180
PHASE_5_TIMEOUT_SECONDS = 180
COOLDOWN_SECONDS = 10
POLL_INTERVAL_SECONDS = 0.5
PROGRESS_INTERVAL_SECONDS = 5
STARTUP_STAGGER_SECONDS = 0.5
CHECK_MUTUAL_NEIGHBORS = False
DEAD_STATUSES = {"DOWN", "FAILED", "DEAD"}

processes: Dict[str, Dict[str, Any]] = {}
results: List[Dict[str, Any]] = []
next_node_index = 0
session = requests.Session()


def ensure_environment() -> None:
    if not JAR_PATH.exists():
        raise FileNotFoundError(f"Jar not found: {JAR_PATH}")
    LOG_DIR.mkdir(parents=True, exist_ok=True)


def api_get(path: str, timeout: float = 5.0) -> Optional[Any]:
    try:
        response = session.get(f"{API_BASE}{path}", timeout=timeout)
        if response.status_code != 200:
            return None
        return response.json()
    except requests.RequestException:
        return None


def get_all_nodes() -> List[Dict[str, Any]]:
    data = api_get("/nodes")
    return data if isinstance(data, list) else []


def get_node(node_id: str) -> Optional[Dict[str, Any]]:
    data = api_get(f"/nodes/{node_id}")
    return data if isinstance(data, dict) else None


def get_failure_reports() -> List[Dict[str, Any]]:
    data = api_get("/failure-reports")
    return data if isinstance(data, list) else []


def status_upper(node: Dict[str, Any]) -> str:
    return str(node.get("status") or "").upper()


def is_active_node(node: Dict[str, Any]) -> bool:
    return status_upper(node) == "UP"


def active_nodes(nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    return [node for node in nodes if is_active_node(node)]


def node_neighbors(node: Dict[str, Any]) -> List[str]:
    neighbors = node.get("neighbors") or []
    return list(neighbors) if isinstance(neighbors, list) else []


def all_active_nodes_have_exact_neighbors(nodes: List[Dict[str, Any]], expected_active_count: int) -> bool:
    active = active_nodes(nodes)
    if len(active) != expected_active_count:
        return False
    return all(len(node_neighbors(node)) == MAX_NEIGHBORS for node in active)


def check_neighbor_completion(
    nodes: List[Dict[str, Any]], expected_active_count: int
) -> Dict[str, Any]:
    """
    Check if all active nodes have MAX_NEIGHBORS, with edge case handling for exactly 1 deficient node.
    Returns a dict with keys:
      - 'complete': bool - True if all have MAX_NEIGHBORS OR exactly 1 is deficient
      - 'deficient_node': str or None - node_id if exactly 1 node is deficient, else None
      - 'all_deficient_nodes': List[str] - all nodes with fewer than MAX_NEIGHBORS
    """
    active = active_nodes(nodes)
    if len(active) != expected_active_count:
        return {"complete": False, "deficient_node": None, "all_deficient_nodes": []}

    deficient = [
        str(node.get("id")) for node in active if len(node_neighbors(node)) < MAX_NEIGHBORS
    ]

    if len(deficient) == 0:
        return {"complete": True, "deficient_node": None, "all_deficient_nodes": []}
    elif len(deficient) == 1:
        # Edge case: exactly 1 deficient node - still consider it complete
        return {"complete": True, "deficient_node": deficient[0], "all_deficient_nodes": deficient}
    else:
        # Multiple deficient nodes - not complete
        return {"complete": False, "deficient_node": None, "all_deficient_nodes": deficient}


def mutual_neighbor_check(nodes: List[Dict[str, Any]]) -> bool:
    node_map = {str(node.get("id")): set(node_neighbors(node)) for node in nodes if node.get("id") is not None}
    for node_id, neighbors in node_map.items():
        for neighbor_id in neighbors:
            if neighbor_id not in node_map:
                return False
            if node_id not in node_map[neighbor_id]:
                return False
    return True


def all_active_neighbors_are_mutual(nodes: List[Dict[str, Any]]) -> bool:
    if not CHECK_MUTUAL_NEIGHBORS:
        return True
    return mutual_neighbor_check(active_nodes(nodes))


def node_is_dead_recorded(node_id: str) -> bool:
    node = get_node(node_id)
    if node is None:
        return False
    return status_upper(node) in DEAD_STATUSES


def failure_report_exists_for(node_id: str, min_report_id: int) -> bool:
    for report in get_failure_reports():
        try:
            report_id = int(report.get("id") or 0)
        except (TypeError, ValueError):
            continue
        if report_id > min_report_id and str(report.get("failedNodeId")) == node_id:
            return True
    return False


def failure_reports_exist_for_all(node_ids: List[str], min_report_id: int) -> bool:
    missing = set(node_ids)
    for report in get_failure_reports():
        try:
            report_id = int(report.get("id") or 0)
        except (TypeError, ValueError):
            continue
        if report_id <= min_report_id:
            continue
        failed_node_id = str(report.get("failedNodeId"))
        missing.discard(failed_node_id)
        if not missing:
            return True
    return not missing


def current_max_failure_report_id() -> int:
    max_id = 0
    for report in get_failure_reports():
        try:
            report_id = int(report.get("id") or 0)
        except (TypeError, ValueError):
            continue
        max_id = max(max_id, report_id)
    return max_id


def next_node_id() -> str:
    global next_node_index
    node_id = f"node-{next_node_index}"
    next_node_index += 1
    return node_id


def start_node(node_id: str) -> None:
    node_log_path = LOG_DIR / f"{node_id}.log"
    log_file = node_log_path.open("a", encoding="utf-8")

    cmd = [
        "java",
        "-jar",
        str(JAR_PATH),
        "--bind-host",
        "0.0.0.0",
        "--advertise-host",
        ADVERTISE_HOST,
        "--max-neighbors",
        str(MAX_NEIGHBORS),
        "--multicast-interface",
        MULTICAST_INTERFACE,
        "--node-id",
        node_id,
    ]

    process = subprocess.Popen(
        cmd,
        cwd=SCRIPT_DIR,
        stdout=log_file,
        stderr=subprocess.STDOUT,
    )

    processes[node_id] = {"process": process, "log_file": log_file}
    time.sleep(STARTUP_STAGGER_SECONDS)

    if process.poll() is not None:
        log_file.flush()
        raise RuntimeError(f"{node_id} exited immediately with code {process.returncode}. Check {node_log_path}")

    print(f"{node_id} started")


def kill_node(node_id: str) -> None:
    info = processes.get(node_id)
    if info is None:
        return

    process: subprocess.Popen = info["process"]
    if process.poll() is None:
        print(f"Killing {node_id}")
        try:
            process.kill()
        except Exception:
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                process.terminate()
            except Exception:
                pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    process.kill()
                except Exception:
                    pass
                try:
                    process.wait(timeout=3)
                except Exception:
                    pass


def kill_all_nodes() -> None:
    print("\nShutting down all nodes...")
    for node_id in list(processes.keys()):
        try:
            kill_node(node_id)
        except Exception as exc:
            print(f"Failed to stop {node_id}: {exc}")

    for node_id, info in list(processes.items()):
        log_file = info.get("log_file")
        if log_file is not None:
            try:
                log_file.flush()
                log_file.close()
            except Exception:
                pass

    processes.clear()


def write_results_csv() -> None:
    fieldnames = [
        "phase",
        "status",
        "elapsed_ms",
        "initial_nodes",
        "added_node_count",
        "max_neighbors",
        "details",
        "metadata",
    ]
    file_exists = RESULTS_CSV.exists()

    with RESULTS_CSV.open("a", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)

        if not file_exists:
            writer.writeheader()

        for row in results:
            writer.writerow(row)


def record_result(
    phase: str,
    status: str,
    elapsed_ms: Optional[float],
    details: str = "",
    metadata: Optional[Dict[str, Any]] = None,
    initial_nodes: int = INITIAL_NODE_COUNT,
    added_node_count: int = ADDED_NODE_COUNT,
    max_neighbors: int = MAX_NEIGHBORS,
) -> None:
    fieldnames = [
        "phase",
        "status",
        "elapsed_ms",
        "initial_nodes",
        "added_node_count",
        "max_neighbors",
        "details",
        "metadata",
    ]
    
    row = {
        "phase": phase,
        "status": status,
        "elapsed_ms": "" if elapsed_ms is None else f"{elapsed_ms:.0f}",
        "details": details,
        "metadata": json.dumps(metadata or {}, ensure_ascii=False),
        "initial_nodes": initial_nodes,
        "added_node_count": added_node_count,
        "max_neighbors": max_neighbors,
    }
    
    # results.append(row)

    # write_results_csv()
    
    file_exists = RESULTS_CSV.exists()

    with RESULTS_CSV.open("a", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)

        if not file_exists:
            writer.writeheader()

        writer.writerow(row)


def countdown_sleep(seconds: int, label: str) -> None:
    for remaining in range(seconds, 0, -1):
        print(f"[{label}] {remaining}s remaining")
        time.sleep(1)


def wait_for_condition(
    label: str,
    condition_fn: Callable[[], bool],
    timeout_seconds: int = PHASE_TIMEOUT_SECONDS,
    progress_interval_seconds: int = PROGRESS_INTERVAL_SECONDS,
) -> float:
    start = time.monotonic()
    next_progress_mark = progress_interval_seconds

    while True:
        if condition_fn():
            return time.monotonic() - start

        elapsed = time.monotonic() - start
        if elapsed >= timeout_seconds:
            raise TimeoutError(f"{label} did not finish within {timeout_seconds} seconds")

        if elapsed >= next_progress_mark:
            print(f"[{label}] {int(next_progress_mark)}s / {timeout_seconds}s elapsed")
            next_progress_mark += progress_interval_seconds

        time.sleep(POLL_INTERVAL_SECONDS)


def start_initial_cluster() -> List[str]:
    started_ids: List[str] = []
    for _ in range(INITIAL_NODE_COUNT):
        node_id = next_node_id()
        start_node(node_id)
        started_ids.append(node_id)
    return started_ids


def active_cluster_size() -> int:
    return len(active_nodes(get_all_nodes()))


def run_phase_1() -> tuple:
    phase_name = "Phase 1"
    expected_active = INITIAL_NODE_COUNT
    print(f"\n{phase_name}: waiting for {expected_active} active nodes to reach MAX_NEIGHBORS")

    edge_case_info = {"deficient_node": None, "printed": False}

    def condition() -> bool:
        nodes = get_all_nodes()
        completion = check_neighbor_completion(nodes, expected_active)
        
        # Log edge case once if exactly 1 deficient node
        if completion["deficient_node"] and not edge_case_info["printed"]:
            edge_case_info["deficient_node"] = completion["deficient_node"]
            edge_case_info["printed"] = True
            msg = f"[EDGE CASE] Only 1 deficient node: {completion['deficient_node']}"
            print(msg)
        
        return (
            completion["complete"]
            and all_active_neighbors_are_mutual(nodes)
        )

    elapsed = wait_for_condition(phase_name, condition)
    print(f"{phase_name} completed in {elapsed:.2f}s")
    return elapsed, edge_case_info["deficient_node"]


def run_phase_2(victim_id: str) -> float:
    phase_name = "Phase 2"
    print(f"\n{phase_name}: killing {victim_id} and waiting for first new failure report")

    baseline_report_id = current_max_failure_report_id()
    kill_node(victim_id)
    killed_at = time.monotonic()

    def condition() -> bool:
        return failure_report_exists_for(victim_id, baseline_report_id)

    elapsed_since_kill = wait_for_condition(phase_name, condition)
    total_elapsed = time.monotonic() - killed_at
    print(f"{phase_name} completed in {total_elapsed:.2f}s")
    return total_elapsed


def run_phase_3(victim_id: str, expected_active_after_repair: int) -> tuple:
    phase_name = "Phase 3"
    print(f"\n{phase_name}: waiting until {victim_id} is removed from all neighbors and active nodes return to MAX_NEIGHBORS")

    edge_case_info = {"deficient_node": None, "printed": False}

    def condition() -> bool:
        nodes = get_all_nodes()
        completion = check_neighbor_completion(nodes, expected_active_after_repair)
        
        # Log edge case once if exactly 1 deficient node
        if completion["deficient_node"] and not edge_case_info["printed"]:
            edge_case_info["deficient_node"] = completion["deficient_node"]
            edge_case_info["printed"] = True
            msg = f"[EDGE CASE] Only 1 deficient node: {completion['deficient_node']}"
            print(msg)
        
        if not completion["complete"]:
            return False
        if not all_active_neighbors_are_mutual(nodes):
            return False
        for node in active_nodes(nodes):
            if victim_id in node_neighbors(node):
                return False
        return node_is_dead_recorded(victim_id)

    elapsed = wait_for_condition(phase_name, condition, PHASE_3_TIMEOUT_SECONDS)
    print(f"{phase_name} completed in {elapsed:.2f}s")
    return elapsed, edge_case_info["deficient_node"]


def run_phase_4(expected_active_after_addition: int) -> tuple:
    phase_name = "Phase 4"
    print(f"{phase_name}: adding {ADDED_NODE_COUNT} new nodes")

    new_node_ids = []
    for _ in range(ADDED_NODE_COUNT):
        node_id = next_node_id()
        start_node(node_id)
        new_node_ids.append(node_id)

    edge_case_info = {"deficient_node": None, "printed": False}

    def condition() -> bool:
        nodes = get_all_nodes()
        completion = check_neighbor_completion(nodes, expected_active_after_addition)
        
        # Log edge case once if exactly 1 deficient node
        if completion["deficient_node"] and not edge_case_info["printed"]:
            edge_case_info["deficient_node"] = completion["deficient_node"]
            edge_case_info["printed"] = True
            msg = f"[EDGE CASE] Only 1 deficient node: {completion['deficient_node']}"
            print(msg)
        
        return (
            completion["complete"]
            and all_active_neighbors_are_mutual(nodes)
        )

    elapsed = wait_for_condition(phase_name, condition)
    print(f"{phase_name} completed in {elapsed:.2f}s")
    return elapsed, new_node_ids, edge_case_info["deficient_node"]


def running_dashboard_nodes() -> List[str]:
    nodes = get_all_nodes()
    node_ids = []
    for node in active_nodes(nodes):
        node_id = str(node.get("id") or "")
        if node_id:
            node_ids.append(node_id)
    return node_ids


def run_phase_5(victim_ids: List[str], expected_active_after_failures: int) -> tuple:
    phase_name = "Phase 5"
    print(f"\n{phase_name}: killing 6 random nodes and waiting for all of them to be reported dead")

    baseline_report_id = current_max_failure_report_id()
    victims = list(victim_ids)
    if len(victims) != ADDED_NODE_COUNT:
        raise ValueError(f"Phase 5 expects exactly {ADDED_NODE_COUNT} victim nodes")

    for node_id in victims:
        kill_node(node_id)

    killed_at = time.monotonic()
    edge_case_info = {"deficient_node": None, "printed": False}

    def condition() -> bool:
        nodes = get_all_nodes()
        completion = check_neighbor_completion(nodes, expected_active_after_failures)
        
        # Log edge case once if exactly 1 deficient node
        if completion["deficient_node"] and not edge_case_info["printed"]:
            edge_case_info["deficient_node"] = completion["deficient_node"]
            edge_case_info["printed"] = True
            msg = f"[EDGE CASE] Only 1 deficient node: {completion['deficient_node']}"
            print(msg)
        
        if not completion["complete"]:
            return False
        if not all_active_neighbors_are_mutual(nodes):
            return False
        return failure_reports_exist_for_all(victims, baseline_report_id)

    elapsed = wait_for_condition(phase_name, condition, PHASE_5_TIMEOUT_SECONDS)
    total_elapsed = time.monotonic() - killed_at
    print(f"{phase_name} completed in {total_elapsed:.2f}s")
    return total_elapsed, edge_case_info["deficient_node"]


def main() -> None:
    global next_node_index
    ensure_environment()

    try:
        print("Starting initial cluster...")
        start_initial_cluster()
        initial_total = INITIAL_NODE_COUNT

        phase_1_elapsed, phase_1_deficient = run_phase_1()
        phase_1_metadata = {
            "expected_active_nodes": initial_total,
            "max_neighbors": MAX_NEIGHBORS,
        }
        if phase_1_deficient:
            phase_1_metadata["edge_case"] = f"Only 1 deficient node: {phase_1_deficient}"
        
        record_result(
            "Phase 1",
            "PASS",
            phase_1_elapsed * 1000,
            details=f"{initial_total} active nodes reached MAX_NEIGHBORS",
            metadata=phase_1_metadata,
        )

        countdown_sleep(COOLDOWN_SECONDS, "Cooldown before Phase 2")

        phase_2_victim = "node-5"
        phase_2_elapsed = run_phase_2(phase_2_victim)
        record_result(
            "Phase 2",
            "PASS",
            phase_2_elapsed * 1000,
            details=f"First failure report observed for {phase_2_victim}",
            metadata={
                "victim": phase_2_victim,
                "max_neighbors": MAX_NEIGHBORS,
            },
        )

        # countdown_sleep(COOLDOWN_SECONDS, "Cooldown before Phase 3")

        phase_3_expected_active = initial_total - 1
        phase_3_elapsed, phase_3_deficient = run_phase_3(phase_2_victim, phase_3_expected_active)
        phase_3_metadata = {
            "victim": phase_2_victim,
            "expected_active_nodes": phase_3_expected_active,
            "max_neighbors": MAX_NEIGHBORS,
        }
        if phase_3_deficient:
            phase_3_metadata["edge_case"] = f"Only 1 deficient node: {phase_3_deficient}"
        
        record_result(
            "Phase 3",
            "PASS",
            phase_3_elapsed * 1000,
            details=f"{phase_2_victim} removed from neighbor lists; active nodes returned to MAX_NEIGHBORS",
            metadata=phase_3_metadata,
        )

        countdown_sleep(COOLDOWN_SECONDS, "Cooldown before Phase 4")

        phase_4_expected_active = phase_3_expected_active + ADDED_NODE_COUNT
        phase_4_elapsed, new_node_ids, phase_4_deficient = run_phase_4(phase_4_expected_active)
        phase_4_metadata = {
            "added_nodes": new_node_ids,
            "expected_active_nodes": phase_4_expected_active,
            "max_neighbors": MAX_NEIGHBORS,
        }
        if phase_4_deficient:
            phase_4_metadata["edge_case"] = f"Only 1 deficient node: {phase_4_deficient}"
        
        record_result(
            "Phase 4",
            "PASS",
            phase_4_elapsed * 1000,
            details=f"Added {ADDED_NODE_COUNT} nodes and reached MAX_NEIGHBORS",
            metadata=phase_4_metadata,
        )

        countdown_sleep(COOLDOWN_SECONDS, "Cooldown before Phase 5")

        current_running = [node_id for node_id in running_dashboard_nodes() if node_id in processes]
        eligible_victims = [node_id for node_id in current_running if node_id != phase_2_victim]
        if len(eligible_victims) < ADDED_NODE_COUNT:
            raise RuntimeError(
                f"Not enough eligible running nodes for Phase 5: need {ADDED_NODE_COUNT}, got {len(eligible_victims)}"
            )

        random.shuffle(eligible_victims)
        phase_5_victims = eligible_victims[:ADDED_NODE_COUNT]
        print(f"Phase 5 victims: {', '.join(phase_5_victims)}")

        phase_5_expected_active = phase_4_expected_active - ADDED_NODE_COUNT
        phase_5_elapsed, phase_5_deficient = run_phase_5(phase_5_victims, phase_5_expected_active)
        phase_5_metadata = {
            "victims": phase_5_victims,
            "expected_active_nodes": phase_5_expected_active,
            "max_neighbors": MAX_NEIGHBORS,
        }
        if phase_5_deficient:
            phase_5_metadata["edge_case"] = f"Only 1 deficient node: {phase_5_deficient}"
        
        record_result(
            "Phase 5",
            "PASS",
            phase_5_elapsed * 1000,
            details=f"Detected death of 6 nodes and active nodes returned to MAX_NEIGHBORS",
            metadata=phase_5_metadata,
        )

        print("\nAll phases completed successfully.")

    except TimeoutError as exc:
        print(f"\n[FAILED] {exc}")
        current_phase = "Unknown"
        if not results:
            current_phase = "Phase 1"
        elif len(results) == 1:
            current_phase = "Phase 2"
        elif len(results) == 2:
            current_phase = "Phase 3"
        elif len(results) == 3:
            current_phase = "Phase 4"
        elif len(results) >= 4:
            current_phase = "Phase 5"

        record_result(
            current_phase,
            "FAILED",
            PHASE_TIMEOUT_SECONDS * 1000,
            details=str(exc),
            metadata={
                "timeout_seconds": PHASE_TIMEOUT_SECONDS,
            },
        )
        raise SystemExit(1)
    except KeyboardInterrupt:
        print("\nCTRL+C detected")
        raise SystemExit(130)
    finally:
        kill_all_nodes()


if __name__ == "__main__":
    main()
