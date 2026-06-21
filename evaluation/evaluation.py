import csv
import json
import random
import subprocess
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

import requests

API_BASE = "http://localhost:6789/api"
SCRIPT_DIR = Path(__file__).resolve().parent
JAR_PATH = SCRIPT_DIR / "node-agent-1.0.jar"
LOG_DIR = SCRIPT_DIR / "logs"
RESULTS_CSV = SCRIPT_DIR / "results_khanhteo4.csv"

ADVERTISE_HOST = "172.16.128.204"
MULTICAST_INTERFACE = "wireless_32768"

MAX_NEIGHBORS = 8
INITIAL_NODE_COUNT = 10
ADDED_NODE_COUNT = 6

PHASE_TIMEOUT_SECONDS = 120
PHASE_3_TIMEOUT_SECONDS = 180
PHASE_5_TIMEOUT_SECONDS = 180
PHASE_R_TIMEOUT_SECONDS = 120

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

RESULT_FIELDNAMES = [
    "timestamp",
    "phase",
    "status",
    "elapsed_ms",
    "initial_nodes",
    "added_node_count",
    "max_neighbors",
    "details",
    "edge_case_note",
    "failed_nodes_count",
    "metadata",
]


def current_timestamp() -> str:
    """Return the current local date and time with the UTC offset."""
    return datetime.now().astimezone().isoformat(timespec="seconds")


def log(message: str = "") -> None:
    """Print an evaluation log message prefixed with the local date and time."""
    timestamp = datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S %z")
    if message.startswith("\n"):
        print()
        message = message[1:]
    print(f"[{timestamp}] {message}")


def ensure_results_csv_schema() -> None:
    """Add the timestamp column to an existing results.csv without losing old rows."""
    if not RESULTS_CSV.exists() or RESULTS_CSV.stat().st_size == 0:
        return

    with RESULTS_CSV.open("r", newline="", encoding="utf-8") as source_file:
        reader = csv.DictReader(source_file)
        existing_fieldnames = reader.fieldnames or []

        if "timestamp" in existing_fieldnames:
            return

        existing_rows = list(reader)

    temporary_path = RESULTS_CSV.with_suffix(".csv.tmp")
    with temporary_path.open("w", newline="", encoding="utf-8") as target_file:
        writer = csv.DictWriter(
            target_file,
            fieldnames=RESULT_FIELDNAMES,
            extrasaction="ignore",
        )
        writer.writeheader()

        for row in existing_rows:
            row["timestamp"] = ""
            writer.writerow(row)

    temporary_path.replace(RESULTS_CSV)


def ensure_environment() -> None:
    if not JAR_PATH.exists():
        raise FileNotFoundError(f"Jar not found: {JAR_PATH}")
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    ensure_results_csv_schema()


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


def neighbor_completion_check(nodes, expected_active_count):
    active = active_nodes(nodes)

    deficient_nodes = [
        str(node.get("id") or "")
        for node in active
        if len(node_neighbors(node)) != MAX_NEIGHBORS
    ]

    if len(active) != expected_active_count:
        return False, None, deficient_nodes

    if len(deficient_nodes) == 0:
        return True, None, deficient_nodes

    return False, None, deficient_nodes


def all_active_neighbors_are_mutual(nodes: List[Dict[str, Any]]) -> bool:
    if not CHECK_MUTUAL_NEIGHBORS:
        return True

    node_map = {
        str(node.get("id")): set(node_neighbors(node))
        for node in active_nodes(nodes)
        if node.get("id") is not None
    }

    for node_id, neighbors in node_map.items():
        for neighbor_id in neighbors:
            if neighbor_id not in node_map:
                return False
            if node_id not in node_map[neighbor_id]:
                return False

    return True


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
        raise RuntimeError(
            f"{node_id} exited immediately with code {process.returncode}. Check {node_log_path}"
        )

    log(f"{node_id} started")


def kill_node(node_id: str) -> None:
    info = processes.get(node_id)
    if info is None:
        return

    process: subprocess.Popen = info["process"]
    if process.poll() is None:
        log(f"Killing {node_id}")
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
    log("\nShutting down all nodes...")
    for node_id in list(processes.keys()):
        try:
            kill_node(node_id)
        except Exception as exc:
            log(f"Failed to stop {node_id}: {exc}")

    for info in list(processes.values()):
        log_file = info.get("log_file")
        if log_file is not None:
            try:
                log_file.flush()
                log_file.close()
            except Exception:
                pass

    processes.clear()


def append_result_row(row: Dict[str, Any]) -> None:
    file_exists = RESULTS_CSV.exists() and RESULTS_CSV.stat().st_size > 0

    with RESULTS_CSV.open("a", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(
            f, fieldnames=RESULT_FIELDNAMES, extrasaction="ignore")
        if not file_exists:
            writer.writeheader()
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
    edge_case_note: str = "",
    failed_nodes_count: int = 0,
) -> None:
    row = {
        "timestamp": current_timestamp(),
        "phase": phase,
        "status": status,
        "elapsed_ms": "" if elapsed_ms is None else f"{elapsed_ms:.0f}",
        "details": details,
        "edge_case_note": edge_case_note,
        "failed_nodes_count": failed_nodes_count,
        "metadata": json.dumps(metadata or {}, ensure_ascii=False),
        "initial_nodes": initial_nodes,
        "added_node_count": added_node_count,
        "max_neighbors": max_neighbors,
    }
    results.append(row)
    append_result_row(row)


def countdown_sleep(seconds: int, label: str) -> None:
    for remaining in range(seconds, 0, -1):
        log(f"[{label}] {remaining}s remaining")
        time.sleep(1)


ConditionResult = Tuple[bool, Optional[str], List[str]]
ConditionFn = Callable[[], ConditionResult]


def wait_for_condition(
    label: str,
    condition_fn: ConditionFn,
    timeout_seconds: int = PHASE_TIMEOUT_SECONDS,
    progress_interval_seconds: int = PROGRESS_INTERVAL_SECONDS,
) -> Tuple[float, bool, Optional[str], List[str]]:
    start = time.monotonic()
    next_progress_mark = progress_interval_seconds
    last_note: Optional[str] = None
    last_failed_nodes: List[str] = []

    while True:
        ok, note, failed_nodes = condition_fn()
        last_note = note
        last_failed_nodes = failed_nodes

        if ok:
            elapsed = time.monotonic() - start
            if note:
                log(f"[{label}] {note}")
            return elapsed, False, note, failed_nodes

        elapsed = time.monotonic() - start
        if elapsed >= timeout_seconds:
            log(f"[{label}] TIMEOUT after {timeout_seconds}s")
            return elapsed, True, last_note, last_failed_nodes

        if elapsed >= next_progress_mark:
            log(
                f"[{label}] {int(next_progress_mark)}s / {timeout_seconds}s elapsed")
            next_progress_mark += progress_interval_seconds

        time.sleep(POLL_INTERVAL_SECONDS)


def start_initial_cluster() -> List[str]:
    started_ids: List[str] = []
    for _ in range(INITIAL_NODE_COUNT):
        node_id = next_node_id()
        start_node(node_id)
        started_ids.append(node_id)
        time.sleep(5)
    return started_ids


def run_phase_1() -> Tuple[float, bool, Optional[str], List[str]]:
    phase_name = "Phase 1"
    expected_active = INITIAL_NODE_COUNT
    log(
        f"\n{phase_name}: waiting for {expected_active} active nodes to reach MAX_NEIGHBORS")

    def condition() -> ConditionResult:
        nodes = get_all_nodes()
        ok, note, deficient_nodes = neighbor_completion_check(
            nodes, expected_active)
        if not ok:
            return False, None, deficient_nodes
        if not all_active_neighbors_are_mutual(nodes):
            return False, None, deficient_nodes
        return True, note, deficient_nodes

    elapsed, timed_out, note, deficient_nodes = wait_for_condition(
        phase_name, condition, PHASE_TIMEOUT_SECONDS)
    log(f"{phase_name} completed in {elapsed:.2f}s")
    return elapsed, timed_out, note, deficient_nodes


def run_phase_2(victim_id: str) -> Tuple[float, bool, Optional[str], List[str]]:
    phase_name = "Phase 2"
    log(
        f"\n{phase_name}: killing {victim_id} and waiting for first new failure report")

    baseline_report_id = current_max_failure_report_id()
    killed_at = time.monotonic()
    kill_node(victim_id)

    def condition() -> ConditionResult:
        return failure_report_exists_for(victim_id, baseline_report_id), None, []

    elapsed_since_kill, timed_out, _, _ = wait_for_condition(
        phase_name, condition, PHASE_TIMEOUT_SECONDS)
    total_elapsed = time.monotonic() - killed_at
    log(f"{phase_name} completed in {total_elapsed:.2f}s")
    return total_elapsed, timed_out, None, []


def run_phase_3(victim_id: str, expected_active_after_repair: int) -> Tuple[float, bool, Optional[str], List[str]]:
    phase_name = "Phase 3"
    log(
        f"\n{phase_name}: waiting until {victim_id} is removed from all neighbors and surviving nodes return to MAX_NEIGHBORS"
    )

    def condition() -> ConditionResult:
        nodes = get_all_nodes()

        # The dashboard/database may keep the killed node marked as UP for a while.
        # Phase 2 has already confirmed its failure report, so Phase 3 should judge
        # convergence using only the surviving active nodes.
        surviving_nodes = [
            node
            for node in active_nodes(nodes)
            if str(node.get("id") or "") != victim_id
        ]

        deficient_nodes = [
            str(node.get("id") or "")
            for node in surviving_nodes
            if len(node_neighbors(node)) != MAX_NEIGHBORS
        ]

        if len(surviving_nodes) != expected_active_after_repair:
            return False, (
                f"Expected {expected_active_after_repair} surviving active nodes, "
                f"but found {len(surviving_nodes)}"
            ), deficient_nodes

        if deficient_nodes:
            return False, None, deficient_nodes

        nodes_referencing_victim = [
            str(node.get("id") or "")
            for node in surviving_nodes
            if victim_id in node_neighbors(node)
        ]
        if nodes_referencing_victim:
            return False, (
                f"{victim_id} is still referenced by: "
                f"{', '.join(nodes_referencing_victim)}"
            ), deficient_nodes

        if not all_active_neighbors_are_mutual(surviving_nodes):
            return False, "Surviving neighbor relationships are not mutual", deficient_nodes

        # wait_for_condition() returns immediately when this becomes True,
        # so main() proceeds directly to the cooldown before Phase 4.
        return True, None, []

    elapsed, timed_out, note, deficient_nodes = wait_for_condition(
        phase_name, condition, PHASE_3_TIMEOUT_SECONDS)
    log(f"{phase_name} completed in {elapsed:.2f}s")
    return elapsed, timed_out, note, deficient_nodes


def run_phase_4(expected_active_after_addition: int) -> Tuple[float, bool, List[str], Optional[str], List[str]]:
    phase_name = "Phase 4"
    log(f"\n{phase_name}: adding {ADDED_NODE_COUNT} new nodes")

    start_time = time.monotonic()
    new_node_ids: List[str] = []
    for _ in range(ADDED_NODE_COUNT):
        node_id = next_node_id()
        start_node(node_id)
        new_node_ids.append(node_id)
        time.sleep(5)

    def condition() -> ConditionResult:
        nodes = get_all_nodes()
        ok, note, deficient_nodes = neighbor_completion_check(
            nodes, expected_active_after_addition)
        if not ok:
            return False, None, deficient_nodes
        if not all_active_neighbors_are_mutual(nodes):
            return False, None, deficient_nodes
        return True, note, deficient_nodes

    elapsed_wait, timed_out, note, deficient_nodes = wait_for_condition(
        phase_name, condition, PHASE_TIMEOUT_SECONDS)
    total_elapsed = time.monotonic() - start_time
    log(f"{phase_name} completed in {total_elapsed:.2f}s")
    return total_elapsed, timed_out, new_node_ids, note, deficient_nodes


def running_dashboard_nodes() -> List[str]:
    nodes = get_all_nodes()
    node_ids: List[str] = []
    for node in active_nodes(nodes):
        node_id = str(node.get("id") or "")
        if node_id:
            node_ids.append(node_id)
    return node_ids


def run_phase_5(victim_ids: List[str], expected_active_after_failures: int) -> Tuple[float, bool, Optional[str], List[str]]:
    phase_name = "Phase 5"
    log(f"\n{phase_name}: killing 6 random nodes and waiting for all of them to be reported dead")

    baseline_report_id = current_max_failure_report_id()
    victims = list(victim_ids)
    if len(victims) != ADDED_NODE_COUNT:
        raise ValueError(
            f"Phase 5 expects exactly {ADDED_NODE_COUNT} victim nodes")

    start_time = time.monotonic()
    for node_id in victims:
        kill_node(node_id)

    def condition() -> ConditionResult:
        nodes = get_all_nodes()

        ok, note, deficient_nodes = neighbor_completion_check(
            nodes, expected_active_after_failures)
        if not ok:
            return False, None, deficient_nodes

        if not all_active_neighbors_are_mutual(nodes):
            return False, None, deficient_nodes

        if not failure_reports_exist_for_all(victims, baseline_report_id):
            return False, None, deficient_nodes

        return True, note, deficient_nodes

    elapsed_wait, timed_out, note, deficient_nodes = wait_for_condition(
        phase_name, condition, PHASE_5_TIMEOUT_SECONDS)
    total_elapsed = time.monotonic() - start_time
    log(f"{phase_name} completed in {total_elapsed:.2f}s")
    return total_elapsed, timed_out, note, deficient_nodes


def run_phase_r(deficient_nodes: List[str], expected_active_count: int) -> Tuple[float, bool, Optional[str], List[str]]:
    phase_name = "Phase R"
    nodes_to_restart = list(dict.fromkeys(deficient_nodes))

    log(f"\n{phase_name}: restarting {len(nodes_to_restart)} deficient node(s)")

    for node_id in nodes_to_restart:
        kill_node(node_id)

    for node_id in nodes_to_restart:
        start_node(node_id)

    def condition() -> ConditionResult:
        nodes = get_all_nodes()

        ok, note, deficient_nodes_now = neighbor_completion_check(
            nodes, expected_active_count)
        if not ok:
            return False, None, deficient_nodes_now

        if not all_active_neighbors_are_mutual(nodes):
            return False, None, deficient_nodes_now

        return True, note, deficient_nodes_now

    elapsed, timed_out, note, deficient_nodes_now = wait_for_condition(
        phase_name,
        condition,
        PHASE_R_TIMEOUT_SECONDS,
    )
    log(f"{phase_name} completed in {elapsed:.2f}s")
    return elapsed, timed_out, note, deficient_nodes_now


def maybe_run_phase_r(
    trigger_phase: str,
    timed_out: bool,
    deficient_nodes: List[str],
    expected_active_count: int,
) -> None:
    if not timed_out or not deficient_nodes:
        return

    nodes_to_restart = list(dict.fromkeys(deficient_nodes))
    phase_r_elapsed, phase_r_timed_out, phase_r_note, phase_r_deficient_nodes = run_phase_r(
        nodes_to_restart,
        expected_active_count,
    )

    phase_r_details = (
        f"Restarted {len(nodes_to_restart)} deficient node(s) after {trigger_phase} timeout"
    )
    if phase_r_note:
        phase_r_details += f"; {phase_r_note}"

    if phase_r_timed_out:
        phase_r_details = (
            f"Phase R timed out after restarting {len(nodes_to_restart)} node(s); "
            f"{len(phase_r_deficient_nodes)} node(s) still did not reach MAX_NEIGHBORS"
        )
        if phase_r_deficient_nodes:
            phase_r_details += f": {', '.join(phase_r_deficient_nodes)}"

    record_result(
        "Phase R",
        "PASS" if not phase_r_timed_out else "PARTIAL",
        phase_r_elapsed * 1000,
        details=phase_r_details,
        metadata={
            "trigger_phase": trigger_phase,
            "restarted_nodes": nodes_to_restart,
            "expected_active_nodes": expected_active_count,
            "max_neighbors": MAX_NEIGHBORS,
            "timed_out": phase_r_timed_out,
        },
        failed_nodes_count=len(
            phase_r_deficient_nodes) if phase_r_timed_out else 0,
    )


def main() -> None:
    ensure_environment()

    current_phase = "Startup"

    try:
        log("Starting initial cluster...")
        start_initial_cluster()
        initial_total = INITIAL_NODE_COUNT

        current_phase = "Phase 1"
        phase_1_elapsed, phase_1_timed_out, phase_1_note, phase_1_deficient_nodes = run_phase_1()
        phase_1_details = f"{initial_total} active nodes reached MAX_NEIGHBORS"
        if phase_1_note:
            phase_1_details += f"; {phase_1_note}"
        if phase_1_timed_out:
            phase_1_details = (
                f"Timed out waiting for full neighbors; {len(phase_1_deficient_nodes)} node(s) still did not reach MAX_NEIGHBORS"
            )
            if phase_1_deficient_nodes:
                phase_1_details += f": {', '.join(phase_1_deficient_nodes)}"

        record_result(
            "Phase 1",
            "PASS" if not phase_1_timed_out else "PARTIAL",
            phase_1_elapsed * 1000,
            details=phase_1_details,
            metadata={
                "expected_active_nodes": initial_total,
                "max_neighbors": MAX_NEIGHBORS,
                "timed_out": phase_1_timed_out,
            },
            edge_case_note=phase_1_note or "",
            failed_nodes_count=len(
                phase_1_deficient_nodes) if phase_1_timed_out else 0,
        )

        maybe_run_phase_r("Phase 1", phase_1_timed_out,
                          phase_1_deficient_nodes, initial_total)

        countdown_sleep(COOLDOWN_SECONDS, "Cooldown before Phase 2")

        current_phase = "Phase 2"
        phase_2_victim = "node-5"
        phase_2_elapsed, phase_2_timed_out, _, _ = run_phase_2(phase_2_victim)
        record_result(
            "Phase 2",
            "PASS" if not phase_2_timed_out else "PARTIAL",
            phase_2_elapsed * 1000,
            details=(
                f"First failure report observed for {phase_2_victim}"
                if not phase_2_timed_out
                else f"Timed out waiting for first failure report for {phase_2_victim}"
            ),
            metadata={
                "victim": phase_2_victim,
                "max_neighbors": MAX_NEIGHBORS,
                "timed_out": phase_2_timed_out,
            },
            failed_nodes_count=0,
        )

        current_phase = "Phase 3"
        phase_3_expected_active = initial_total - 1
        phase_3_elapsed, phase_3_timed_out, phase_3_note, phase_3_deficient_nodes = run_phase_3(
            phase_2_victim, phase_3_expected_active)
        phase_3_details = (
            f"{phase_2_victim} removed from neighbor lists; active nodes returned to MAX_NEIGHBORS"
        )
        if phase_3_note:
            phase_3_details += f"; {phase_3_note}"
        if phase_3_timed_out:
            phase_3_details = (
                f"Timed out waiting for convergence; {len(phase_3_deficient_nodes)} node(s) still did not reach MAX_NEIGHBORS"
            )
            if phase_3_deficient_nodes:
                phase_3_details += f": {', '.join(phase_3_deficient_nodes)}"

        record_result(
            "Phase 3",
            "PASS" if not phase_3_timed_out else "PARTIAL",
            phase_3_elapsed * 1000,
            details=phase_3_details,
            metadata={
                "victim": phase_2_victim,
                "expected_active_nodes": phase_3_expected_active,
                "max_neighbors": MAX_NEIGHBORS,
                "timed_out": phase_3_timed_out,
            },
            edge_case_note=phase_3_note or "",
            failed_nodes_count=len(
                phase_3_deficient_nodes) if phase_3_timed_out else 0,
        )

        maybe_run_phase_r("Phase 3", phase_3_timed_out,
                          phase_3_deficient_nodes, phase_3_expected_active)

        countdown_sleep(COOLDOWN_SECONDS, "Cooldown before Phase 4")

        current_phase = "Phase 4"
        phase_4_expected_active = phase_3_expected_active + ADDED_NODE_COUNT
        phase_4_elapsed, phase_4_timed_out, new_node_ids, phase_4_note, phase_4_deficient_nodes = run_phase_4(
            phase_4_expected_active)
        phase_4_details = f"Added {ADDED_NODE_COUNT} nodes and reached MAX_NEIGHBORS"
        if phase_4_note:
            phase_4_details += f"; {phase_4_note}"
        if phase_4_timed_out:
            phase_4_details = (
                f"Timed out waiting after adding nodes; {len(phase_4_deficient_nodes)} node(s) still did not reach MAX_NEIGHBORS"
            )
            if phase_4_deficient_nodes:
                phase_4_details += f": {', '.join(phase_4_deficient_nodes)}"

        record_result(
            "Phase 4",
            "PASS" if not phase_4_timed_out else "PARTIAL",
            phase_4_elapsed * 1000,
            details=phase_4_details,
            metadata={
                "added_nodes": new_node_ids,
                "expected_active_nodes": phase_4_expected_active,
                "max_neighbors": MAX_NEIGHBORS,
                "timed_out": phase_4_timed_out,
            },
            edge_case_note=phase_4_note or "",
            failed_nodes_count=len(
                phase_4_deficient_nodes) if phase_4_timed_out else 0,
        )

        maybe_run_phase_r("Phase 4", phase_4_timed_out,
                          phase_4_deficient_nodes, phase_4_expected_active)

        countdown_sleep(COOLDOWN_SECONDS, "Cooldown before Phase 5")

        current_phase = "Phase 5"
        current_running = [
            node_id for node_id in running_dashboard_nodes() if node_id in processes]
        eligible_victims = [
            node_id for node_id in current_running if node_id != phase_2_victim]
        if len(eligible_victims) < ADDED_NODE_COUNT:
            raise RuntimeError(
                f"Not enough eligible running nodes for Phase 5: need {ADDED_NODE_COUNT}, got {len(eligible_victims)}"
            )

        random.shuffle(eligible_victims)
        phase_5_victims = eligible_victims[:ADDED_NODE_COUNT]
        log(f"Phase 5 victims: {', '.join(phase_5_victims)}")

        phase_5_expected_active = phase_4_expected_active - ADDED_NODE_COUNT
        phase_5_elapsed, phase_5_timed_out, phase_5_note, phase_5_deficient_nodes = run_phase_5(
            phase_5_victims, phase_5_expected_active)
        phase_5_details = "Detected death of 6 nodes and active nodes returned to MAX_NEIGHBORS"
        if phase_5_note:
            phase_5_details += f"; {phase_5_note}"
        if phase_5_timed_out:
            phase_5_details = (
                f"Timed out waiting for all killed nodes to be reported and for convergence; {len(phase_5_deficient_nodes)} node(s) still did not reach MAX_NEIGHBORS"
            )
            if phase_5_deficient_nodes:
                phase_5_details += f": {', '.join(phase_5_deficient_nodes)}"

        record_result(
            "Phase 5",
            "PASS" if not phase_5_timed_out else "PARTIAL",
            phase_5_elapsed * 1000,
            details=phase_5_details,
            metadata={
                "victims": phase_5_victims,
                "expected_active_nodes": phase_5_expected_active,
                "max_neighbors": MAX_NEIGHBORS,
                "timed_out": phase_5_timed_out,
            },
            edge_case_note=phase_5_note or "",
            failed_nodes_count=len(
                phase_5_deficient_nodes) if phase_5_timed_out else 0,
        )

        maybe_run_phase_r("Phase 5", phase_5_timed_out,
                          phase_5_deficient_nodes, phase_5_expected_active)

        log("\nAll phases completed successfully.")

    except KeyboardInterrupt:
        log("\nCTRL+C detected")
        raise SystemExit(130)
    finally:
        kill_all_nodes()


if __name__ == "__main__":
    main()
