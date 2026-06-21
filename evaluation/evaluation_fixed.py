import csv
import json
import random
import subprocess
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional, Set, Tuple

import requests

API_BASE = "http://localhost:6789/api"
SCRIPT_DIR = Path(__file__).resolve().parent
JAR_PATH = SCRIPT_DIR / "node-agent-1.0.jar"
LOG_DIR = SCRIPT_DIR / "logs"
RESULTS_CSV = SCRIPT_DIR / "results_phuc.csv"

ADVERTISE_HOST = "192.168.1.6"
MULTICAST_INTERFACE = "wireless_32768"

MAX_NEIGHBORS = 10
INITIAL_NODE_COUNT = 20
ADDED_NODE_COUNT = 5
PHASE_5_VICTIM_COUNT = 5

PHASE_TIMEOUT_SECONDS = 120
PHASE_3_TIMEOUT_SECONDS = 180
PHASE_5_TIMEOUT_SECONDS = 180
PHASE_R_TIMEOUT_SECONDS = 120

COOLDOWN_SECONDS = 10
POLL_INTERVAL_SECONDS = 0.5
PROGRESS_INTERVAL_SECONDS = 5
STARTUP_STAGGER_SECONDS = 0.5
INTER_NODE_START_DELAY_SECONDS = 5
REQUIRED_CONSECUTIVE_SUCCESS_POLLS = 3

CHECK_MUTUAL_NEIGHBORS = True

processes: Dict[str, Dict[str, Any]] = {}
results: List[Dict[str, Any]] = []
next_node_index = 0
session = requests.Session()

ConditionResult = Tuple[bool, Optional[str], List[str]]
ConditionFn = Callable[[], ConditionResult]

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


def validate_configuration() -> None:
    if INITIAL_NODE_COUNT <= 0:
        raise ValueError("INITIAL_NODE_COUNT must be positive")
    if ADDED_NODE_COUNT < 0:
        raise ValueError("ADDED_NODE_COUNT cannot be negative")
    if PHASE_5_VICTIM_COUNT <= 0:
        raise ValueError("PHASE_5_VICTIM_COUNT must be positive")
    if MAX_NEIGHBORS < 0:
        raise ValueError("MAX_NEIGHBORS cannot be negative")
    if INITIAL_NODE_COUNT <= 5:
        raise ValueError("INITIAL_NODE_COUNT must include node-5 for Phase 2")
    if REQUIRED_CONSECUTIVE_SUCCESS_POLLS <= 0:
        raise ValueError("REQUIRED_CONSECUTIVE_SUCCESS_POLLS must be positive")

    expected_counts = {
        "Phase 1": INITIAL_NODE_COUNT,
        "Phase 3": INITIAL_NODE_COUNT - 1,
        "Phase 4": INITIAL_NODE_COUNT - 1 + ADDED_NODE_COUNT,
        "Phase 5": (
            INITIAL_NODE_COUNT
            - 1
            + ADDED_NODE_COUNT
            - PHASE_5_VICTIM_COUNT
        ),
    }

    for phase_name, active_count in expected_counts.items():
        if active_count <= 0:
            raise ValueError(
                f"{phase_name} would have a non-positive active-node count"
            )
        if MAX_NEIGHBORS >= active_count:
            raise ValueError(
                f"{phase_name} cannot give each of {active_count} nodes "
                f"{MAX_NEIGHBORS} distinct non-self neighbors"
            )
        if CHECK_MUTUAL_NEIGHBORS and (active_count * MAX_NEIGHBORS) % 2 != 0:
            raise ValueError(
                f"{phase_name} cannot form a mutual {MAX_NEIGHBORS}-regular "
                f"topology with {active_count} nodes"
            )

    phase_4_count = expected_counts["Phase 4"]
    if PHASE_5_VICTIM_COUNT >= phase_4_count:
        raise ValueError(
            "PHASE_5_VICTIM_COUNT must leave at least one surviving node"
        )


def ensure_environment() -> None:
    validate_configuration()
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
    if not isinstance(neighbors, list):
        return []
    return [str(neighbor_id) for neighbor_id in neighbors]


def node_id_of(node: Dict[str, Any]) -> str:
    return str(node.get("id") or "")


def topology_convergence_check(
    nodes: List[Dict[str, Any]],
    expected_active_count: int,
    excluded_node_ids: Optional[Iterable[str]] = None,
    require_excluded_inactive: bool = False,
) -> ConditionResult:
    """Check that the active topology is complete and contains no stale edges.

    Excluded nodes are omitted from the topology being checked. This is useful
    immediately after killing nodes, while the dashboard may still temporarily
    report them as UP. When require_excluded_inactive is True, the check also
    waits until every excluded node is no longer marked UP.
    """
    excluded: Set[str] = set(excluded_node_ids or [])
    all_active = active_nodes(nodes)
    all_active_ids = {node_id_of(node) for node in all_active if node_id_of(node)}

    excluded_still_active = sorted(excluded.intersection(all_active_ids))
    if require_excluded_inactive and excluded_still_active:
        return (
            False,
            "Killed nodes are still marked UP: "
            + ", ".join(excluded_still_active),
            [],
        )

    checked_nodes = [
        node
        for node in all_active
        if node_id_of(node) and node_id_of(node) not in excluded
    ]
    checked_node_map = {node_id_of(node): node for node in checked_nodes}
    checked_node_ids = set(checked_node_map)

    notes: List[str] = []
    problem_nodes: Set[str] = set()

    if len(checked_nodes) != expected_active_count:
        notes.append(
            f"expected {expected_active_count} active nodes, "
            f"but found {len(checked_nodes)}"
        )

    wrong_neighbor_count: List[str] = []
    duplicate_neighbor_nodes: List[str] = []
    self_neighbor_nodes: List[str] = []
    stale_neighbor_nodes: List[str] = []

    for current_node_id, node in checked_node_map.items():
        neighbors = node_neighbors(node)

        if len(neighbors) != MAX_NEIGHBORS:
            wrong_neighbor_count.append(current_node_id)
            problem_nodes.add(current_node_id)

        if len(neighbors) != len(set(neighbors)):
            duplicate_neighbor_nodes.append(current_node_id)
            problem_nodes.add(current_node_id)

        if current_node_id in neighbors:
            self_neighbor_nodes.append(current_node_id)
            problem_nodes.add(current_node_id)

        if any(neighbor_id not in checked_node_ids for neighbor_id in neighbors):
            stale_neighbor_nodes.append(current_node_id)
            problem_nodes.add(current_node_id)

    if wrong_neighbor_count:
        notes.append(
            f"{len(wrong_neighbor_count)} node(s) do not have exactly "
            f"{MAX_NEIGHBORS} neighbors"
        )

    if duplicate_neighbor_nodes:
        notes.append(
            f"duplicate neighbor entries found on: "
            + ", ".join(sorted(duplicate_neighbor_nodes))
        )

    if self_neighbor_nodes:
        notes.append(
            "self-neighbor references found on: "
            + ", ".join(sorted(self_neighbor_nodes))
        )

    if stale_neighbor_nodes:
        notes.append(
            "inactive, killed, or unknown neighbors are still referenced by: "
            + ", ".join(sorted(stale_neighbor_nodes))
        )

    if CHECK_MUTUAL_NEIGHBORS:
        non_mutual_nodes: Set[str] = set()
        for current_node_id, node in checked_node_map.items():
            for neighbor_id in node_neighbors(node):
                neighbor = checked_node_map.get(neighbor_id)
                if neighbor is None:
                    continue
                if current_node_id not in node_neighbors(neighbor):
                    non_mutual_nodes.add(current_node_id)
                    non_mutual_nodes.add(neighbor_id)

        if non_mutual_nodes:
            problem_nodes.update(non_mutual_nodes)
            notes.append(
                "non-mutual neighbor relationships involve: "
                + ", ".join(sorted(non_mutual_nodes))
            )

    if notes:
        return False, "; ".join(notes), sorted(problem_nodes)

    return True, None, []


def failure_report_exists_for(node_id: str, min_report_id: int) -> bool:
    for report in get_failure_reports():
        try:
            report_id = int(report.get("id") or 0)
        except (TypeError, ValueError):
            continue

        if report_id > min_report_id and str(report.get("failedNodeId")) == node_id:
            return True

    return False


def missing_failure_reports(node_ids: List[str], min_report_id: int) -> List[str]:
    missing = set(node_ids)

    for report in get_failure_reports():
        try:
            report_id = int(report.get("id") or 0)
        except (TypeError, ValueError):
            continue

        if report_id <= min_report_id:
            continue

        failed_node_id = str(report.get("failedNodeId") or "")
        missing.discard(failed_node_id)

        if not missing:
            break

    return sorted(missing)


def current_max_failure_report_id() -> int:
    data = api_get("/failure-reports")
    if not isinstance(data, list):
        raise RuntimeError(
            "Could not read the failure-report baseline from the dashboard API"
        )

    max_id = 0
    for report in data:
        try:
            report_id = int(report.get("id") or 0)
        except (AttributeError, TypeError, ValueError):
            continue
        max_id = max(max_id, report_id)
    return max_id


def next_node_id() -> str:
    global next_node_index
    node_id = f"node-{next_node_index}"
    next_node_index += 1
    return node_id


def start_node(node_id: str) -> None:
    existing = processes.get(node_id)
    if existing is not None:
        existing_process: subprocess.Popen = existing["process"]
        if existing_process.poll() is None:
            raise RuntimeError(f"{node_id} is already running")
        kill_node(node_id)

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

    try:
        process = subprocess.Popen(
            cmd,
            cwd=SCRIPT_DIR,
            stdout=log_file,
            stderr=subprocess.STDOUT,
        )
    except Exception:
        log_file.close()
        raise

    processes[node_id] = {"process": process, "log_file": log_file}
    time.sleep(STARTUP_STAGGER_SECONDS)

    if process.poll() is not None:
        return_code = process.returncode
        kill_node(node_id)
        raise RuntimeError(
            f"{node_id} exited immediately with code {return_code}. "
            f"Check {node_log_path}"
        )

    log(f"{node_id} started")


def kill_node(node_id: str) -> None:
    info = processes.get(node_id)
    if info is None:
        return

    process: subprocess.Popen = info["process"]
    log_file = info.get("log_file")
    stopped = process.poll() is not None

    try:
        if not stopped:
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
                    except subprocess.TimeoutExpired as exc:
                        raise RuntimeError(
                            f"Could not stop {node_id}"
                        ) from exc

        stopped = process.poll() is not None
    finally:
        if stopped:
            processes.pop(node_id, None)
            if log_file is not None:
                try:
                    log_file.flush()
                    log_file.close()
                except Exception:
                    pass


def process_is_running(node_id: str) -> bool:
    info = processes.get(node_id)
    if info is None:
        return False
    process: subprocess.Popen = info["process"]
    return process.poll() is None


def kill_all_nodes() -> None:
    log("\nShutting down all nodes...")
    for node_id in list(processes.keys()):
        try:
            kill_node(node_id)
        except Exception as exc:
            log(f"Failed to stop {node_id}: {exc}")


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


def wait_for_condition(
    label: str,
    condition_fn: ConditionFn,
    timeout_seconds: int = PHASE_TIMEOUT_SECONDS,
    progress_interval_seconds: int = PROGRESS_INTERVAL_SECONDS,
) -> Tuple[float, bool, Optional[str], List[str]]:
    start = time.monotonic()
    next_progress_mark = progress_interval_seconds
    success_streak = 0
    last_failure_note: Optional[str] = None
    last_problem_nodes: List[str] = []

    while True:
        ok, note, problem_nodes = condition_fn()

        if ok:
            success_streak += 1
            if success_streak >= REQUIRED_CONSECUTIVE_SUCCESS_POLLS:
                elapsed = time.monotonic() - start
                return elapsed, False, note, problem_nodes
        else:
            success_streak = 0
            last_failure_note = note
            last_problem_nodes = problem_nodes

        elapsed = time.monotonic() - start
        if elapsed >= timeout_seconds:
            log(f"[{label}] TIMEOUT after {timeout_seconds}s")
            return elapsed, True, last_failure_note, last_problem_nodes

        if elapsed >= next_progress_mark:
            log(
                f"[{label}] {int(next_progress_mark)}s / "
                f"{timeout_seconds}s elapsed"
            )
            next_progress_mark += progress_interval_seconds

        time.sleep(POLL_INTERVAL_SECONDS)


def start_initial_cluster() -> List[str]:
    started_ids: List[str] = []
    for _ in range(INITIAL_NODE_COUNT):
        node_id = next_node_id()
        start_node(node_id)
        started_ids.append(node_id)
        time.sleep(INTER_NODE_START_DELAY_SECONDS)
    return started_ids


def run_phase_1() -> Tuple[float, bool, Optional[str], List[str]]:
    phase_name = "Phase 1"
    expected_active = INITIAL_NODE_COUNT
    log(
        f"\n{phase_name}: waiting for {expected_active} active nodes "
        f"to form a fully valid topology"
    )

    def condition() -> ConditionResult:
        return topology_convergence_check(
            get_all_nodes(),
            expected_active,
        )

    elapsed, timed_out, note, problem_nodes = wait_for_condition(
        phase_name, condition, PHASE_TIMEOUT_SECONDS
    )
    log(f"{phase_name} completed in {elapsed:.2f}s")
    return elapsed, timed_out, note, problem_nodes


def run_phase_2(victim_id: str) -> Tuple[float, bool, Optional[str], List[str]]:
    phase_name = "Phase 2"
    log(
        f"\n{phase_name}: killing {victim_id} and waiting for its first new failure report"
    )

    baseline_report_id = current_max_failure_report_id()
    killed_at = time.monotonic()
    kill_node(victim_id)

    def condition() -> ConditionResult:
        if failure_report_exists_for(victim_id, baseline_report_id):
            return True, None, []
        return False, f"waiting for a new failure report for {victim_id}", []

    _, timed_out, note, _ = wait_for_condition(
        phase_name, condition, PHASE_TIMEOUT_SECONDS
    )
    total_elapsed = time.monotonic() - killed_at
    log(f"{phase_name} completed in {total_elapsed:.2f}s")
    return total_elapsed, timed_out, note, []


def run_phase_3(
    victim_id: str,
    expected_active_after_repair: int,
) -> Tuple[float, bool, Optional[str], List[str]]:
    phase_name = "Phase 3"
    log(
        f"\n{phase_name}: waiting until {victim_id} is removed from all "
        f"neighbor lists and the surviving topology converges"
    )

    def condition() -> ConditionResult:
        # Phase 2 already confirmed a failure report. The dashboard may still
        # temporarily mark the victim UP, so exclude it from the survivor count.
        # It is also excluded from the valid-neighbor set, which means any
        # survivor still referencing it prevents Phase 3 from ending.
        return topology_convergence_check(
            get_all_nodes(),
            expected_active_after_repair,
            excluded_node_ids={victim_id},
            require_excluded_inactive=False,
        )

    elapsed, timed_out, note, problem_nodes = wait_for_condition(
        phase_name, condition, PHASE_3_TIMEOUT_SECONDS
    )
    log(f"{phase_name} completed in {elapsed:.2f}s")
    return elapsed, timed_out, note, problem_nodes


def run_phase_4(
    expected_active_after_addition: int,
) -> Tuple[float, bool, List[str], Optional[str], List[str]]:
    phase_name = "Phase 4"
    log(f"\n{phase_name}: adding {ADDED_NODE_COUNT} new nodes")

    start_time = time.monotonic()
    new_node_ids: List[str] = []
    for _ in range(ADDED_NODE_COUNT):
        node_id = next_node_id()
        start_node(node_id)
        new_node_ids.append(node_id)
        time.sleep(INTER_NODE_START_DELAY_SECONDS)

    def condition() -> ConditionResult:
        return topology_convergence_check(
            get_all_nodes(),
            expected_active_after_addition,
        )

    _, timed_out, note, problem_nodes = wait_for_condition(
        phase_name, condition, PHASE_TIMEOUT_SECONDS
    )
    total_elapsed = time.monotonic() - start_time
    log(f"{phase_name} completed in {total_elapsed:.2f}s")
    return total_elapsed, timed_out, new_node_ids, note, problem_nodes


def running_dashboard_nodes() -> List[str]:
    return [
        node_id_of(node)
        for node in active_nodes(get_all_nodes())
        if node_id_of(node)
    ]


def run_phase_5(
    victim_ids: List[str],
    expected_active_after_failures: int,
) -> Tuple[float, bool, Optional[str], List[str]]:
    phase_name = "Phase 5"
    victims = list(victim_ids)

    if len(victims) != PHASE_5_VICTIM_COUNT:
        raise ValueError(
            f"Phase 5 expects exactly {PHASE_5_VICTIM_COUNT} victim nodes, "
            f"but received {len(victims)}"
        )
    if len(set(victims)) != len(victims):
        raise ValueError("Phase 5 victim IDs must be unique")

    log(
        f"\n{phase_name}: killing {len(victims)} random nodes and waiting "
        f"for both failure detection and topology convergence"
    )

    baseline_report_id = current_max_failure_report_id()
    start_time = time.monotonic()

    for node_id in victims:
        kill_node(node_id)

    def condition() -> ConditionResult:
        # Phase 5 must not pass merely because every survivor still has a list
        # of MAX_NEIGHBORS stale entries. Killed nodes are excluded from the
        # valid-neighbor set, and every remaining edge must point to a currently
        # active survivor. The killed nodes must also no longer be marked UP.
        topology_ok, topology_note, problem_nodes = topology_convergence_check(
            get_all_nodes(),
            expected_active_after_failures,
            excluded_node_ids=victims,
            require_excluded_inactive=True,
        )
        if not topology_ok:
            return False, topology_note, problem_nodes

        missing_reports = missing_failure_reports(victims, baseline_report_id)
        if missing_reports:
            return (
                False,
                "waiting for new failure reports for: "
                + ", ".join(missing_reports),
                [],
            )

        return True, None, []

    _, timed_out, note, problem_nodes = wait_for_condition(
        phase_name, condition, PHASE_5_TIMEOUT_SECONDS
    )
    total_elapsed = time.monotonic() - start_time
    log(f"{phase_name} completed in {total_elapsed:.2f}s")
    return total_elapsed, timed_out, note, problem_nodes


def run_phase_r(
    problem_nodes: List[str],
    expected_active_count: int,
    excluded_node_ids: Optional[Iterable[str]] = None,
    require_excluded_inactive: bool = False,
) -> Tuple[float, bool, Optional[str], List[str]]:
    phase_name = "Phase R"
    nodes_to_restart = list(dict.fromkeys(problem_nodes))

    log(
        f"\n{phase_name}: restarting {len(nodes_to_restart)} "
        f"unconverged node(s)"
    )

    for node_id in nodes_to_restart:
        kill_node(node_id)

    for node_id in nodes_to_restart:
        start_node(node_id)

    def condition() -> ConditionResult:
        return topology_convergence_check(
            get_all_nodes(),
            expected_active_count,
            excluded_node_ids=excluded_node_ids,
            require_excluded_inactive=require_excluded_inactive,
        )

    elapsed, timed_out, note, remaining_problem_nodes = wait_for_condition(
        phase_name,
        condition,
        PHASE_R_TIMEOUT_SECONDS,
    )
    log(f"{phase_name} completed in {elapsed:.2f}s")
    return elapsed, timed_out, note, remaining_problem_nodes


def build_timeout_details(
    prefix: str,
    note: Optional[str],
    problem_nodes: List[str],
) -> str:
    details = prefix
    if note:
        details += f"; {note}"
    if problem_nodes:
        details += f"; affected nodes: {', '.join(problem_nodes)}"
    return details


def maybe_run_phase_r(
    trigger_phase: str,
    timed_out: bool,
    problem_nodes: List[str],
    expected_active_count: int,
    excluded_node_ids: Optional[Iterable[str]] = None,
    require_excluded_inactive: bool = False,
) -> None:
    # Phase R only restarts concrete survivor nodes that remain unconverged.
    # A timeout caused solely by delayed dashboard status or missing failure
    # reports does not restart killed nodes.
    if not timed_out or not problem_nodes:
        return

    nodes_to_restart = list(dict.fromkeys(problem_nodes))
    phase_r_elapsed, phase_r_timed_out, phase_r_note, remaining_problem_nodes = run_phase_r(
        nodes_to_restart,
        expected_active_count,
        excluded_node_ids=excluded_node_ids,
        require_excluded_inactive=require_excluded_inactive,
    )

    if phase_r_timed_out:
        phase_r_details = build_timeout_details(
            f"Phase R timed out after restarting {len(nodes_to_restart)} node(s)",
            phase_r_note,
            remaining_problem_nodes,
        )
    else:
        phase_r_details = (
            f"Restarted {len(nodes_to_restart)} unconverged node(s) "
            f"after {trigger_phase} timeout and restored convergence"
        )

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
            "check_mutual_neighbors": CHECK_MUTUAL_NEIGHBORS,
            "excluded_node_ids": sorted(set(excluded_node_ids or [])),
            "require_excluded_inactive": require_excluded_inactive,
            "timed_out": phase_r_timed_out,
        },
        edge_case_note=phase_r_note or "",
        failed_nodes_count=(
            len(remaining_problem_nodes) if phase_r_timed_out else 0
        ),
    )


def main() -> None:
    ensure_environment()

    current_phase = "Startup"

    try:
        log("Starting initial cluster...")
        start_initial_cluster()
        initial_total = INITIAL_NODE_COUNT

        current_phase = "Phase 1"
        (
            phase_1_elapsed,
            phase_1_timed_out,
            phase_1_note,
            phase_1_problem_nodes,
        ) = run_phase_1()

        phase_1_details = (
            f"{initial_total} active nodes formed a valid topology with "
            f"{MAX_NEIGHBORS} neighbors each"
        )
        if phase_1_timed_out:
            phase_1_details = build_timeout_details(
                "Timed out waiting for initial topology convergence",
                phase_1_note,
                phase_1_problem_nodes,
            )

        record_result(
            "Phase 1",
            "PASS" if not phase_1_timed_out else "PARTIAL",
            phase_1_elapsed * 1000,
            details=phase_1_details,
            metadata={
                "expected_active_nodes": initial_total,
                "max_neighbors": MAX_NEIGHBORS,
                "check_mutual_neighbors": CHECK_MUTUAL_NEIGHBORS,
                "timed_out": phase_1_timed_out,
            },
            edge_case_note=phase_1_note or "",
            failed_nodes_count=(
                len(phase_1_problem_nodes) if phase_1_timed_out else 0
            ),
        )

        maybe_run_phase_r(
            "Phase 1",
            phase_1_timed_out,
            phase_1_problem_nodes,
            initial_total,
        )

        countdown_sleep(COOLDOWN_SECONDS, "Cooldown before Phase 2")

        current_phase = "Phase 2"
        phase_2_victim = "node-5"
        (
            phase_2_elapsed,
            phase_2_timed_out,
            phase_2_note,
            _,
        ) = run_phase_2(phase_2_victim)

        phase_2_details = (
            f"First new failure report observed for {phase_2_victim}"
            if not phase_2_timed_out
            else build_timeout_details(
                f"Timed out waiting for a new failure report for {phase_2_victim}",
                phase_2_note,
                [],
            )
        )

        record_result(
            "Phase 2",
            "PASS" if not phase_2_timed_out else "PARTIAL",
            phase_2_elapsed * 1000,
            details=phase_2_details,
            metadata={
                "victim": phase_2_victim,
                "max_neighbors": MAX_NEIGHBORS,
                "timed_out": phase_2_timed_out,
            },
            edge_case_note=phase_2_note or "",
            failed_nodes_count=0,
        )

        current_phase = "Phase 3"
        phase_3_expected_active = initial_total - 1
        (
            phase_3_elapsed,
            phase_3_timed_out,
            phase_3_note,
            phase_3_problem_nodes,
        ) = run_phase_3(phase_2_victim, phase_3_expected_active)

        phase_3_details = (
            f"{phase_2_victim} was removed from all surviving neighbor lists; "
            f"{phase_3_expected_active} survivors formed a valid topology"
        )
        if phase_3_timed_out:
            phase_3_details = build_timeout_details(
                "Timed out waiting for post-failure topology convergence",
                phase_3_note,
                phase_3_problem_nodes,
            )

        record_result(
            "Phase 3",
            "PASS" if not phase_3_timed_out else "PARTIAL",
            phase_3_elapsed * 1000,
            details=phase_3_details,
            metadata={
                "victim": phase_2_victim,
                "expected_active_nodes": phase_3_expected_active,
                "max_neighbors": MAX_NEIGHBORS,
                "check_mutual_neighbors": CHECK_MUTUAL_NEIGHBORS,
                "timed_out": phase_3_timed_out,
            },
            edge_case_note=phase_3_note or "",
            failed_nodes_count=(
                len(phase_3_problem_nodes) if phase_3_timed_out else 0
            ),
        )

        maybe_run_phase_r(
            "Phase 3",
            phase_3_timed_out,
            phase_3_problem_nodes,
            phase_3_expected_active,
            excluded_node_ids={phase_2_victim},
            require_excluded_inactive=False,
        )

        countdown_sleep(COOLDOWN_SECONDS, "Cooldown before Phase 4")

        current_phase = "Phase 4"
        phase_4_expected_active = phase_3_expected_active + ADDED_NODE_COUNT
        (
            phase_4_elapsed,
            phase_4_timed_out,
            new_node_ids,
            phase_4_note,
            phase_4_problem_nodes,
        ) = run_phase_4(phase_4_expected_active)

        phase_4_details = (
            f"Added {ADDED_NODE_COUNT} nodes; "
            f"{phase_4_expected_active} active nodes formed a valid topology"
        )
        if phase_4_timed_out:
            phase_4_details = build_timeout_details(
                "Timed out waiting for topology convergence after adding nodes",
                phase_4_note,
                phase_4_problem_nodes,
            )

        record_result(
            "Phase 4",
            "PASS" if not phase_4_timed_out else "PARTIAL",
            phase_4_elapsed * 1000,
            details=phase_4_details,
            metadata={
                "added_nodes": new_node_ids,
                "expected_active_nodes": phase_4_expected_active,
                "max_neighbors": MAX_NEIGHBORS,
                "check_mutual_neighbors": CHECK_MUTUAL_NEIGHBORS,
                "timed_out": phase_4_timed_out,
            },
            edge_case_note=phase_4_note or "",
            failed_nodes_count=(
                len(phase_4_problem_nodes) if phase_4_timed_out else 0
            ),
        )

        maybe_run_phase_r(
            "Phase 4",
            phase_4_timed_out,
            phase_4_problem_nodes,
            phase_4_expected_active,
        )

        countdown_sleep(COOLDOWN_SECONDS, "Cooldown before Phase 5")

        current_phase = "Phase 5"
        current_running = [
            node_id
            for node_id in running_dashboard_nodes()
            if process_is_running(node_id)
        ]
        eligible_victims = [
            node_id
            for node_id in current_running
            if node_id != phase_2_victim
        ]

        if len(eligible_victims) < PHASE_5_VICTIM_COUNT:
            raise RuntimeError(
                "Not enough eligible running nodes for Phase 5: "
                f"need {PHASE_5_VICTIM_COUNT}, got {len(eligible_victims)}"
            )

        random.shuffle(eligible_victims)
        phase_5_victims = eligible_victims[:PHASE_5_VICTIM_COUNT]
        log(f"Phase 5 victims: {', '.join(phase_5_victims)}")

        phase_5_expected_active = phase_4_expected_active - PHASE_5_VICTIM_COUNT
        (
            phase_5_elapsed,
            phase_5_timed_out,
            phase_5_note,
            phase_5_problem_nodes,
        ) = run_phase_5(phase_5_victims, phase_5_expected_active)

        phase_5_details = (
            f"Detected all {len(phase_5_victims)} failures; "
            f"{phase_5_expected_active} survivors formed a valid topology "
            f"without stale references"
        )
        if phase_5_timed_out:
            phase_5_details = build_timeout_details(
                "Timed out waiting for all failure reports and topology convergence",
                phase_5_note,
                phase_5_problem_nodes,
            )

        record_result(
            "Phase 5",
            "PASS" if not phase_5_timed_out else "PARTIAL",
            phase_5_elapsed * 1000,
            details=phase_5_details,
            metadata={
                "victims": phase_5_victims,
                "expected_active_nodes": phase_5_expected_active,
                "max_neighbors": MAX_NEIGHBORS,
                "check_mutual_neighbors": CHECK_MUTUAL_NEIGHBORS,
                "timed_out": phase_5_timed_out,
            },
            edge_case_note=phase_5_note or "",
            failed_nodes_count=(
                len(phase_5_problem_nodes) if phase_5_timed_out else 0
            ),
        )

        maybe_run_phase_r(
            "Phase 5",
            phase_5_timed_out,
            phase_5_problem_nodes,
            phase_5_expected_active,
            excluded_node_ids=phase_5_victims,
            require_excluded_inactive=True,
        )

        log("\nAll configured phases finished.")

    except KeyboardInterrupt:
        log("\nCTRL+C detected")
        raise SystemExit(130)
    except Exception as exc:
        log(f"\nEvaluation failed during {current_phase}: {exc}")
        raise
    finally:
        kill_all_nodes()


if __name__ == "__main__":
    main()
