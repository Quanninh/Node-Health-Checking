import csv
import json
import random
import shutil
import signal
import subprocess
import sys
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Deque, Dict, Iterable, List, Optional, Set, Tuple

import requests

# =============================================================================
# User settings
# =============================================================================

API_BASE = "http://localhost:6789/api"
SCRIPT_DIR = Path(__file__).resolve().parent
JAR_PATH = SCRIPT_DIR / "node-agent-1.0.jar"

# =============================================================================
# CHANGE THIS TO results_yourname.csv
# =============================================================================
RESULTS_CSV = SCRIPT_DIR / "results_yourname.csv"

# =============================================================================
# CHANGE THIS TO your.ip.add.ress
# =============================================================================
ADVERTISE_HOST = "172.16.132.30"
# =============================================================================
# CHANGE THIS TO wireless_32768 ON WINDOWS AND en0 ON MACOS
# =============================================================================
MULTICAST_INTERFACE = "wireless_32768"

# =============================================================================
# CONFIGURE THIS
# =============================================================================

MAX_NEIGHBORS = 4
INITIAL_NODE_COUNT = 10
ADDED_NODE_COUNT = 5
REMOVED_NODE_COUNT = 5

# =============================================================================
# =============================================================================

PHASE_1_TIMEOUT_SECONDS = 120
PHASE_2_1_TIMEOUT_SECONDS = 120
PHASE_2_2_TIMEOUT_SECONDS = 120
PHASE_3_TIMEOUT_SECONDS = 180
PHASE_4_TIMEOUT_SECONDS = 120
PHASE_5_TIMEOUT_SECONDS = 180
PHASE_R_TIMEOUT_SECONDS = 120

STABILIZATION_SECONDS = 10
COOLDOWN_SECONDS = 10
POLL_INTERVAL_SECONDS = 0.5
PROGRESS_INTERVAL_SECONDS = 5
CONVERGENCE_CONFIRMATION_POLLS = 3
API_TIMEOUT_SECONDS = 5.0

NODE_ADD_INTERVAL_SECONDS = 5
NODE_DELETE_INTERVAL_SECONDS = 0
NODE_RESTART_INTERVAL_SECONDS = 0
MAX_PHASE_R_REPLACEMENTS = 4

KEEP_NODE_LOGS = False
PROCESS_OUTPUT_TAIL_LINES = 200
PROCESS_DIAGNOSTIC_PRINT_LINES = 40
CLEAN_GENERATED_LOGS = True
GENERATED_LOG_PATHS = [
    SCRIPT_DIR / "logs",
    SCRIPT_DIR / "packet_log",
    SCRIPT_DIR / "log.txt",
]

# =============================================================================
# CSV schema
# =============================================================================

RESULT_FIELDNAMES = [
    "timestamp",
    "phase",
    "status",
    "elapsed_ms",
    "max_neighbors",
    "initial_nodes",
    "added_node_count",
    "removed_node_count",
    "expected_up",
    "actual_up",
    "problem_count",
    "details",
    "topology",
]


# =============================================================================
# Data classes
# =============================================================================

@dataclass
class ManagedProcess:
    node_id: str
    process: subprocess.Popen
    log_file: Optional[Any] = None
    output_tail: Deque[str] = field(default_factory=lambda: deque(maxlen=PROCESS_OUTPUT_TAIL_LINES))
    reader_thread: Optional[threading.Thread] = None


@dataclass
class DashboardNode:
    node_id: str
    status: str
    neighbors: List[str]


@dataclass
class FailureReport:
    report_id: int
    reporter_node_id: str
    failed_node_id: str
    status: str = ""
    timestamp: str = ""


@dataclass
class Snapshot:
    api_ok: bool
    nodes: Dict[str, DashboardNode] = field(default_factory=dict)
    error: str = ""

    def up_ids(self) -> Set[str]:
        return {node_id for node_id, node in self.nodes.items() if node.status == "UP"}


@dataclass
class TopologyAnalysis:
    ok: bool
    details: str
    problem_owners: List[str]
    deficient_nodes: List[str]
    topology_json: str
    expected_up: int
    actual_up: int
    api_ok: bool


@dataclass
class PhaseResult:
    phase: str
    status: str
    elapsed_seconds: Optional[float]
    details: str
    snapshot: Optional[Snapshot]
    problem_owners: List[str] = field(default_factory=list)
    deficient_nodes: List[str] = field(default_factory=list)


# =============================================================================
# Utilities
# =============================================================================

def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def log(message: str = "") -> None:
    stamp = datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S %z")
    if message.startswith("\n"):
        print()
        message = message[1:]
    print(f"[{stamp}] {message}", flush=True)


def sleep_interval(seconds: float, shutdown: "ShutdownState") -> None:
    end = time.monotonic() + max(seconds, 0)
    while time.monotonic() < end:
        if shutdown.requested:
            raise KeyboardInterrupt
        time.sleep(min(0.2, end - time.monotonic()))


def sleep_between_items(seconds: float, index: int, total: int, shutdown: "ShutdownState") -> None:
    if index < total - 1 and seconds > 0:
        sleep_interval(seconds, shutdown)


def compact_list(values: Iterable[str]) -> str:
    return "[" + ",".join(sorted(values)) + "]"


# =============================================================================
# Shutdown handling
# =============================================================================

class ShutdownState:
    def __init__(self) -> None:
        self.requested = False

    def request(self, signum: int, frame: Any) -> None:
        self.requested = True
        log("\nShutdown requested")


# =============================================================================
# Result writer
# =============================================================================

class ResultWriter:
    def __init__(self, csv_path: Path) -> None:
        self.csv_path = csv_path

    def write(self, result: PhaseResult, analysis: Optional[TopologyAnalysis] = None) -> None:
        topology = analysis.topology_json if analysis is not None else ""
        expected_up = analysis.expected_up if analysis is not None else ""
        actual_up = analysis.actual_up if analysis is not None else ""
        problem_count = len(result.problem_owners)

        row = {
            "timestamp": now_iso(),
            "phase": result.phase,
            "status": result.status,
            "elapsed_ms": "" if result.elapsed_seconds is None else f"{result.elapsed_seconds * 1000:.0f}",
            "max_neighbors": MAX_NEIGHBORS,
            "initial_nodes": INITIAL_NODE_COUNT,
            "added_node_count": ADDED_NODE_COUNT,
            "removed_node_count": REMOVED_NODE_COUNT,
            "expected_up": expected_up,
            "actual_up": actual_up,
            "problem_count": problem_count,
            "details": result.details,
            "topology": topology,
        }

        file_exists = self.csv_path.exists() and self.csv_path.stat().st_size > 0
        with self.csv_path.open("a", newline="", encoding="utf-8") as file:
            writer = csv.DictWriter(file, fieldnames=RESULT_FIELDNAMES, extrasaction="ignore")
            if not file_exists:
                writer.writeheader()
            writer.writerow(row)


# =============================================================================
# Node identities
# =============================================================================

class NodeIdentityGenerator:
    def __init__(self) -> None:
        self.used_codes: Set[int] = set()

    def next_id(self, phase_code: str) -> str:
        if len(self.used_codes) >= 100:
            raise RuntimeError("No unused two-digit node codes remain")

        while True:
            code = random.randint(0, 99)
            if code not in self.used_codes:
                self.used_codes.add(code)
                break

        letter = chr(ord("a") + random.randint(0, 25))
        return f"{letter}.{phase_code}.{code:02d}"


# =============================================================================
# Dashboard API
# =============================================================================

class DashboardClient:
    def __init__(self) -> None:
        self.session = requests.Session()

    def _get(self, path: str) -> Any:
        response = self.session.get(f"{API_BASE}{path}", timeout=API_TIMEOUT_SECONDS)
        response.raise_for_status()
        return response.json()

    def check_available(self) -> None:
        self._get("/nodes")

    def snapshot(self) -> Snapshot:
        try:
            data = self._get("/nodes")
        except Exception as exc:
            return Snapshot(api_ok=False, error=str(exc))

        if not isinstance(data, list):
            return Snapshot(api_ok=False, error="/nodes did not return a list")

        nodes: Dict[str, DashboardNode] = {}
        for item in data:
            if not isinstance(item, dict):
                continue

            node_id = str(item.get("id") or "")
            if not node_id:
                continue

            raw_neighbors = item.get("neighbors") or []
            neighbors = [str(neighbor) for neighbor in raw_neighbors] if isinstance(raw_neighbors, list) else []
            nodes[node_id] = DashboardNode(node_id=node_id, status=str(item.get("status") or "").upper(), neighbors=neighbors)

        return Snapshot(api_ok=True, nodes=nodes)

    def failure_reports(self) -> List[FailureReport]:
        data = self._get("/failure-reports")
        if not isinstance(data, list):
            return []

        reports: List[FailureReport] = []
        for item in data:
            if not isinstance(item, dict):
                continue

            try:
                report_id = int(item.get("id") or 0)
            except (TypeError, ValueError):
                continue

            reports.append(FailureReport(
                report_id=report_id,
                reporter_node_id=str(item.get("reporterNodeId") or ""),
                failed_node_id=str(item.get("failedNodeId") or ""),
                status=str(item.get("status") or ""),
                timestamp=str(item.get("timestamp") or ""),
            ))

        return reports

    def max_failure_report_id(self) -> int:
        max_id = 0
        for report in self.failure_reports():
            max_id = max(max_id, report.report_id)
        return max_id

    def new_reports_for(self, failed_node_ids: Iterable[str], min_report_id: int) -> List[FailureReport]:
        failed_set = set(failed_node_ids)
        return [report for report in self.failure_reports() if report.report_id > min_report_id and report.failed_node_id in failed_set]


# =============================================================================
# Process manager
# =============================================================================

class NodeManager:
    def __init__(self, shutdown: ShutdownState) -> None:
        self.shutdown = shutdown
        self.processes: Dict[str, ManagedProcess] = {}
        self.run_log_dir = SCRIPT_DIR / "logs" / datetime.now().strftime("%Y%m%d-%H%M%S") if KEEP_NODE_LOGS else None

    def start(self, node_id: str) -> None:
        if node_id in self.processes and self.is_running(node_id):
            raise RuntimeError(f"{node_id} is already running")

        log_file = None
        if KEEP_NODE_LOGS:
            assert self.run_log_dir is not None
            self.run_log_dir.mkdir(parents=True, exist_ok=True)
            log_path = self.run_log_dir / f"{node_id}.log"
            log_file = log_path.open("a", encoding="utf-8")

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
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
            )
        except Exception:
            if log_file is not None:
                log_file.close()
            raise

        managed = ManagedProcess(node_id=node_id, process=process, log_file=log_file)
        reader_thread = threading.Thread(target=self._capture_output, args=(managed,), name=f"output-{node_id}", daemon=True)
        managed.reader_thread = reader_thread
        self.processes[node_id] = managed
        reader_thread.start()
        log(f"started {node_id}")

    def _capture_output(self, managed: ManagedProcess) -> None:
        stream = managed.process.stdout
        if stream is None:
            return

        try:
            for raw_line in stream:
                line = raw_line.rstrip("\r\n")
                managed.output_tail.append(line)
                if managed.log_file is not None:
                    managed.log_file.write(raw_line)
                    managed.log_file.flush()
        except Exception as exc:
            managed.output_tail.append(f"[evaluator output reader error: {exc}]")
        finally:
            try:
                stream.close()
            except Exception:
                pass

    def diagnostic_tail(self, node_id: str, max_lines: int = PROCESS_DIAGNOSTIC_PRINT_LINES) -> List[str]:
        managed = self.processes.get(node_id)
        if managed is None:
            return []
        return list(managed.output_tail)[-max_lines:]

    def print_diagnostic(self, node_id: str, reason: str) -> None:
        managed = self.processes.get(node_id)
        if managed is None:
            log(f"[diagnostic] {node_id}: {reason}; process is no longer tracked")
            return

        return_code = managed.process.poll()
        state = "RUNNING" if return_code is None else f"EXITED({return_code})"
        log(f"[diagnostic] {node_id}: {reason}; process={state}; last_output_lines={PROCESS_DIAGNOSTIC_PRINT_LINES}")
        tail = self.diagnostic_tail(node_id)
        if not tail:
            log(f"[diagnostic] {node_id}: no captured output")
            return

        for line in tail:
            print(f"    {node_id} | {line}", flush=True)

    def stop(self, node_id: str) -> bool:
        managed = self.processes.get(node_id)
        if managed is None:
            return True

        process = managed.process
        if process.poll() is None:
            log(f"stopping {node_id}")
            try:
                process.terminate()
            except Exception:
                pass

            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.force_kill(process)

        stopped = process.poll() is not None
        if stopped:
            self.close_log(managed)
            self.processes.pop(node_id, None)
        return stopped

    def force_kill(self, process: subprocess.Popen) -> None:
        try:
            process.kill()
        except Exception:
            pass

        try:
            process.wait(timeout=5)
            return
        except subprocess.TimeoutExpired:
            pass

        if sys.platform.startswith("win"):
            try:
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
            except Exception:
                pass

        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            pass

    def close_log(self, managed: ManagedProcess) -> None:
        if managed.reader_thread is not None and managed.reader_thread.is_alive():
            managed.reader_thread.join(timeout=1)

        if managed.log_file is not None:
            try:
                managed.log_file.flush()
                managed.log_file.close()
            except Exception:
                pass

    def is_running(self, node_id: str) -> bool:
        managed = self.processes.get(node_id)
        return managed is not None and managed.process.poll() is None

    def running_ids(self) -> Set[str]:
        return {node_id for node_id in self.processes if self.is_running(node_id)}

    def stop_all(self) -> Tuple[int, int, int]:
        stopped = 0
        already_exited = 0
        failed = 0

        for node_id, managed in list(self.processes.items()):
            if managed.process.poll() is not None:
                already_exited += 1
                self.close_log(managed)
                self.processes.pop(node_id, None)
                continue

            if self.stop(node_id):
                stopped += 1
            else:
                failed += 1

        for node_id, managed in list(self.processes.items()):
            if managed.process.poll() is not None:
                self.close_log(managed)
                self.processes.pop(node_id, None)

        return stopped, already_exited, failed


# =============================================================================
# Topology analysis
# =============================================================================

class TopologyAnalyzer:
    def __init__(self, manager: NodeManager) -> None:
        self.manager = manager

    def analyze(self, snapshot: Snapshot, expected_active_ids: Set[str], all_created_ids: Set[str]) -> TopologyAnalysis:
        if not snapshot.api_ok:
            return TopologyAnalysis(
                ok=False,
                details="api_unavailable",
                problem_owners=[],
                deficient_nodes=[],
                topology_json=json.dumps({"api_error": snapshot.error}, separators=(",", ":")),
                expected_up=len(expected_active_ids),
                actual_up=0,
                api_ok=False,
            )

        up_ids = snapshot.up_ids()
        running_ids = self.manager.running_ids()
        problem_owners: Set[str] = set()
        deficient_nodes: Set[str] = set()
        detail_parts: List[str] = []

        missing_up = expected_active_ids - up_ids
        unexpected_up = up_ids - expected_active_ids
        not_running = expected_active_ids - running_ids
        running_not_up = missing_up & running_ids

        if missing_up:
            detail_parts.append(f"missing_up={compact_list(missing_up)}")
            problem_owners.update(missing_up)
        if running_not_up:
            detail_parts.append(f"running_not_up={compact_list(running_not_up)}")
        if unexpected_up:
            detail_parts.append(f"unexpected_up={compact_list(unexpected_up)}")
            problem_owners.update(unexpected_up & all_created_ids)
        if not_running:
            detail_parts.append(f"not_running={compact_list(not_running)}")
            problem_owners.update(not_running)

        duplicate_owners: Set[str] = set()
        self_edge_owners: Set[str] = set()
        stale_edge_owners: Set[str] = set()
        overfull_nodes: Set[str] = set()

        for node_id in sorted(expected_active_ids):
            node = snapshot.nodes.get(node_id)
            neighbors = node.neighbors if node is not None else []

            if len(neighbors) < MAX_NEIGHBORS:
                deficient_nodes.add(node_id)
                problem_owners.add(node_id)
            elif len(neighbors) > MAX_NEIGHBORS:
                overfull_nodes.add(node_id)
                problem_owners.add(node_id)

            if len(neighbors) != len(set(neighbors)):
                duplicate_owners.add(node_id)
                problem_owners.add(node_id)

            if node_id in neighbors:
                self_edge_owners.add(node_id)
                problem_owners.add(node_id)

            invalid_neighbors = [neighbor for neighbor in neighbors if neighbor not in expected_active_ids]
            if invalid_neighbors:
                stale_edge_owners.add(node_id)
                problem_owners.add(node_id)

        non_mutual_participants: Set[str] = set()
        for node_id in sorted(expected_active_ids):
            node = snapshot.nodes.get(node_id)
            if node is None:
                continue

            for neighbor_id in node.neighbors:
                if neighbor_id not in expected_active_ids:
                    continue
                neighbor = snapshot.nodes.get(neighbor_id)
                if neighbor is None or node_id not in neighbor.neighbors:
                    non_mutual_participants.add(node_id)
                    non_mutual_participants.add(neighbor_id)

        if non_mutual_participants:
            problem_owners.update(non_mutual_participants)

        if deficient_nodes:
            detail_parts.append(f"deficient={compact_list(deficient_nodes)}")
        if overfull_nodes:
            detail_parts.append(f"overfull={compact_list(overfull_nodes)}")
        if duplicate_owners:
            detail_parts.append(f"duplicate={compact_list(duplicate_owners)}")
        if self_edge_owners:
            detail_parts.append(f"self_edge={compact_list(self_edge_owners)}")
        if stale_edge_owners:
            detail_parts.append(f"stale_edge={compact_list(stale_edge_owners)}")
        if non_mutual_participants:
            detail_parts.append(f"non_mutual={compact_list(non_mutual_participants)}")

        topology_json = self.topology_json(snapshot, all_created_ids)
        ok = not detail_parts
        return TopologyAnalysis(
            ok=ok,
            details="stable" if ok else " ".join(detail_parts),
            problem_owners=sorted(problem_owners),
            deficient_nodes=sorted(deficient_nodes),
            topology_json=topology_json,
            expected_up=len(expected_active_ids),
            actual_up=len(up_ids),
            api_ok=True,
        )

    def topology_json(self, snapshot: Snapshot, all_created_ids: Set[str]) -> str:
        payload: Dict[str, Any] = {}
        for node_id in sorted(all_created_ids):
            node = snapshot.nodes.get(node_id)
            payload[node_id] = {
                "api": node.status if node is not None else "MISSING",
                "process": "RUNNING" if self.manager.is_running(node_id) else "STOPPED",
                "neighbors": sorted(node.neighbors) if node is not None else [],
            }
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


# =============================================================================
# Phase monitoring
# =============================================================================

class Monitor:
    def __init__(self, client: DashboardClient, analyzer: TopologyAnalyzer, shutdown: ShutdownState) -> None:
        self.client = client
        self.analyzer = analyzer
        self.shutdown = shutdown

    def wait_for_convergence(
        self,
        phase: str,
        expected_active_ids: Set[str],
        all_created_ids: Set[str],
        timeout_seconds: int,
    ) -> Tuple[PhaseResult, TopologyAnalysis]:
        start = time.monotonic()
        deadline = start + timeout_seconds
        next_progress = start + PROGRESS_INTERVAL_SECONDS
        success_streak = 0
        first_success_elapsed: Optional[float] = None
        first_success_snapshot: Optional[Snapshot] = None
        last_analysis: Optional[TopologyAnalysis] = None
        last_snapshot: Optional[Snapshot] = None

        while True:
            if self.shutdown.requested:
                raise KeyboardInterrupt

            snapshot = self.client.snapshot()
            analysis = self.analyzer.analyze(snapshot, expected_active_ids, all_created_ids)
            last_analysis = analysis
            last_snapshot = snapshot

            if analysis.ok:
                if success_streak == 0:
                    first_success_elapsed = time.monotonic() - start
                    first_success_snapshot = snapshot
                success_streak += 1
                if success_streak >= CONVERGENCE_CONFIRMATION_POLLS:
                    result = PhaseResult(phase, "PASS", first_success_elapsed, "stable", first_success_snapshot, [], [])
                    return result, analysis
            else:
                success_streak = 0
                first_success_elapsed = None
                first_success_snapshot = None

            now = time.monotonic()
            if now >= deadline:
                assert last_analysis is not None
                status = "ERROR" if not last_analysis.api_ok else "TIMEOUT"
                result = PhaseResult(
                    phase=phase,
                    status=status,
                    elapsed_seconds=now - start,
                    details=last_analysis.details,
                    snapshot=last_snapshot,
                    problem_owners=last_analysis.problem_owners,
                    deficient_nodes=last_analysis.deficient_nodes,
                )
                return result, last_analysis

            if now >= next_progress:
                log(f"[{phase}] {int(now - start)}s/{timeout_seconds}s {analysis.details}")
                next_progress += PROGRESS_INTERVAL_SECONDS

            time.sleep(POLL_INTERVAL_SECONDS)

    def monitor_stabilization(
        self,
        phase: str,
        expected_active_ids: Set[str],
        all_created_ids: Set[str],
    ) -> Tuple[PhaseResult, TopologyAnalysis]:
        start = time.monotonic()
        deadline = start + STABILIZATION_SECONDS
        last_analysis: Optional[TopologyAnalysis] = None
        last_snapshot: Optional[Snapshot] = None

        while True:
            if self.shutdown.requested:
                raise KeyboardInterrupt

            snapshot = self.client.snapshot()
            analysis = self.analyzer.analyze(snapshot, expected_active_ids, all_created_ids)
            last_analysis = analysis
            last_snapshot = snapshot

            if not analysis.ok:
                status = "ERROR" if not analysis.api_ok else "FAIL"
                result = PhaseResult(
                    phase=phase,
                    status=status,
                    elapsed_seconds=time.monotonic() - start,
                    details=analysis.details,
                    snapshot=snapshot,
                    problem_owners=analysis.problem_owners,
                    deficient_nodes=analysis.deficient_nodes,
                )
                return result, analysis

            now = time.monotonic()
            if now >= deadline:
                result = PhaseResult(phase, "PASS", STABILIZATION_SECONDS, "stable", last_snapshot, [], [])
                assert last_analysis is not None
                return result, last_analysis

            time.sleep(POLL_INTERVAL_SECONDS)


# =============================================================================
# Evaluation runner
# =============================================================================

class EvaluationRunner:
    def __init__(self) -> None:
        self.shutdown = ShutdownState()
        self.client = DashboardClient()
        self.manager = NodeManager(self.shutdown)
        self.identity = NodeIdentityGenerator()
        self.writer = ResultWriter(RESULTS_CSV)
        self.analyzer = TopologyAnalyzer(self.manager)
        self.monitor = Monitor(self.client, self.analyzer, self.shutdown)
        self.expected_active_ids: Set[str] = set()
        self.all_created_ids: Set[str] = set()
        self.current_phase = "startup"
        self.abort_run = False

    def validate(self) -> None:
        if not JAR_PATH.exists():
            raise FileNotFoundError(f"Jar not found: {JAR_PATH}")
        if INITIAL_NODE_COUNT <= 0:
            raise ValueError("INITIAL_NODE_COUNT must be positive")
        if ADDED_NODE_COUNT < 0:
            raise ValueError("ADDED_NODE_COUNT cannot be negative")
        if REMOVED_NODE_COUNT <= 0:
            raise ValueError("REMOVED_NODE_COUNT must be positive")
        if MAX_NEIGHBORS < 0:
            raise ValueError("MAX_NEIGHBORS cannot be negative")
        if CONVERGENCE_CONFIRMATION_POLLS <= 0:
            raise ValueError("CONVERGENCE_CONFIRMATION_POLLS must be positive")
        if INITIAL_NODE_COUNT + ADDED_NODE_COUNT + MAX_PHASE_R_REPLACEMENTS * 4 > 100:
            raise ValueError("Configuration may require more than 100 unique node codes")

        counts = {
            "Phase 1": INITIAL_NODE_COUNT,
            "Phase 3": INITIAL_NODE_COUNT - 1,
            "Phase 4": INITIAL_NODE_COUNT - 1 + ADDED_NODE_COUNT,
            "Phase 5": INITIAL_NODE_COUNT - 1 + ADDED_NODE_COUNT - REMOVED_NODE_COUNT,
        }

        for phase, count in counts.items():
            if count <= 0:
                raise ValueError(f"{phase} would have non-positive active nodes")
            if MAX_NEIGHBORS >= count:
                raise ValueError(f"{phase} has {count} active nodes, impossible with MAX_NEIGHBORS={MAX_NEIGHBORS}")
            if (count * MAX_NEIGHBORS) % 2 != 0:
                raise ValueError(f"{phase} cannot form a mutual {MAX_NEIGHBORS}-regular topology with {count} nodes")

        self.client.check_available()

    def run(self) -> None:
        self.validate()
        signal.signal(signal.SIGINT, self.shutdown.request)

        try:
            self.start_initial_nodes()
            self.run_convergence_phase("1", "Phase 1", PHASE_1_TIMEOUT_SECONDS, allow_repair=True)
            if self.abort_run:
                return

            self.cooldown("before Phase 2")
            self.run_single_failure_phases()
            if self.abort_run:
                return

            self.cooldown("before Phase 4")
            self.add_nodes("4", ADDED_NODE_COUNT)
            self.run_convergence_phase("4", "Phase 4", PHASE_4_TIMEOUT_SECONDS, allow_repair=True)
            if self.abort_run:
                return

            self.cooldown("before Phase 5")
            self.run_phase_5()
            if self.abort_run:
                return

            log("\nAll configured phases finished")
        finally:
            self.cleanup()

    def start_initial_nodes(self) -> None:
        self.current_phase = "start initial nodes"
        log("Starting initial nodes")
        self.add_nodes("1", INITIAL_NODE_COUNT)

    def add_nodes(self, phase_code: str, count: int) -> List[str]:
        created: List[str] = []
        for index in range(count):
            node_id = self.identity.next_id(phase_code)
            self.manager.start(node_id)
            self.expected_active_ids.add(node_id)
            self.all_created_ids.add(node_id)
            created.append(node_id)
            sleep_between_items(NODE_ADD_INTERVAL_SECONDS, index, count, self.shutdown)

        if count > 0 and NODE_ADD_INTERVAL_SECONDS > 0:
            log(f"waiting {NODE_ADD_INTERVAL_SECONDS}s after final node launch")
            sleep_interval(NODE_ADD_INTERVAL_SECONDS, self.shutdown)

        return created

    def remove_nodes(self, node_ids: List[str]) -> None:
        for index, node_id in enumerate(node_ids):
            self.manager.stop(node_id)
            self.expected_active_ids.discard(node_id)
            sleep_between_items(NODE_DELETE_INTERVAL_SECONDS, index, len(node_ids), self.shutdown)

    def cooldown(self, label: str) -> None:
        if COOLDOWN_SECONDS <= 0:
            return
        log(f"Cooldown {label}: {COOLDOWN_SECONDS}s")
        sleep_interval(COOLDOWN_SECONDS, self.shutdown)

    def run_convergence_phase(self, phase_code: str, phase_name: str, timeout_seconds: int, allow_repair: bool) -> None:
        self.current_phase = phase_name
        log(f"\n{phase_name}: convergence")
        result, analysis = self.monitor.wait_for_convergence(phase_name, set(self.expected_active_ids), set(self.all_created_ids), timeout_seconds)
        self.writer.write(result, analysis)
        log(f"{phase_name}: {result.status} {result.details}")

        if result.status == "PASS":
            self.run_stabilization_phase(f"S{phase_code}", allow_repair=allow_repair, repair_code=phase_code)
            return

        if allow_repair:
            self.maybe_run_repair(f"R{phase_code}", result.problem_owners, trigger_details=result.details)

    def run_stabilization_phase(self, stabilization_phase: str, allow_repair: bool, repair_code: str) -> None:
        self.current_phase = stabilization_phase
        log(f"{stabilization_phase}: stabilization for {STABILIZATION_SECONDS}s")
        result, analysis = self.monitor.monitor_stabilization(stabilization_phase, set(self.expected_active_ids), set(self.all_created_ids))
        self.writer.write(result, analysis)
        log(f"{stabilization_phase}: {result.status} {result.details}")

        if result.status == "PASS":
            return

        if allow_repair:
            self.maybe_run_repair(f"R{repair_code}", result.problem_owners, trigger_details=f"after_{stabilization_phase}:{result.details}")

    def maybe_run_repair(self, repair_phase: str, problem_owners: List[str], trigger_details: str) -> None:
        owners = [node_id for node_id in dict.fromkeys(problem_owners) if node_id in self.expected_active_ids]

        if not owners:
            snapshot = self.client.snapshot()
            analysis = self.analyzer.analyze(snapshot, set(self.expected_active_ids), set(self.all_created_ids))
            result = PhaseResult(repair_phase, "FAIL", 0, f"no_replaceable_nodes trigger={trigger_details}", snapshot, [], [])
            self.writer.write(result, analysis)
            self.abort_run = True
            return

        if len(owners) > MAX_PHASE_R_REPLACEMENTS:
            snapshot = self.client.snapshot()
            analysis = self.analyzer.analyze(snapshot, set(self.expected_active_ids), set(self.all_created_ids))
            result = PhaseResult(repair_phase, "FAIL", 0, f"too_many_wrong_nodes={len(owners)} nodes={compact_list(owners)}", snapshot, owners, owners)
            self.writer.write(result, analysis)
            self.abort_run = True
            return

        self.run_repair_phase(repair_phase, owners, trigger_details)

    def run_repair_phase(self, repair_phase: str, old_node_ids: List[str], trigger_details: str) -> None:
        self.current_phase = repair_phase
        log(f"\n{repair_phase}: replacing {len(old_node_ids)} wrong node(s)")
        start = time.monotonic()

        for index, node_id in enumerate(old_node_ids):
            self.manager.stop(node_id)
            self.expected_active_ids.discard(node_id)
            sleep_between_items(NODE_DELETE_INTERVAL_SECONDS, index, len(old_node_ids), self.shutdown)

        if NODE_RESTART_INTERVAL_SECONDS > 0:
            sleep_interval(NODE_RESTART_INTERVAL_SECONDS, self.shutdown)

        new_node_ids = self.add_nodes("R", len(old_node_ids))
        mapping = dict(zip(old_node_ids, new_node_ids))
        mapping_text = ",".join(f"{old}->{new}" for old, new in mapping.items())

        result, analysis = self.monitor.wait_for_convergence(repair_phase, set(self.expected_active_ids), set(self.all_created_ids), PHASE_R_TIMEOUT_SECONDS)
        result.elapsed_seconds = time.monotonic() - start if result.elapsed_seconds is not None else None
        result.details = "stable " + mapping_text if result.status == "PASS" else f"{result.details} replaced={mapping_text} trigger={trigger_details}"
        self.writer.write(result, analysis)
        log(f"{repair_phase}: {result.status} {result.details}")

        if result.status == "PASS":
            self.run_stabilization_phase("SR", allow_repair=False, repair_code="R")
            return

        if len(result.problem_owners) > MAX_PHASE_R_REPLACEMENTS:
            self.abort_run = True

    def run_single_failure_phases(self) -> None:
        self.current_phase = "Phase 2/3"
        if not self.expected_active_ids:
            raise RuntimeError("No active nodes available for Phase 2")

        before_snapshot = self.client.snapshot()
        before_analysis = self.analyzer.analyze(before_snapshot, set(self.expected_active_ids), set(self.all_created_ids))
        eligible = sorted(self.expected_active_ids)
        victim_id = random.choice(eligible)
        victim_neighbors = before_snapshot.nodes.get(victim_id).neighbors if before_snapshot.api_ok and victim_id in before_snapshot.nodes else []
        required_reporters = sorted(neighbor for neighbor in victim_neighbors if neighbor in self.expected_active_ids)

        baseline_report_id = self.client.max_failure_report_id()
        log(f"\nPhase 2/3: killing {victim_id}; original_neighbors={compact_list(required_reporters)}")
        killed_at = time.monotonic()
        self.manager.stop(victim_id)
        self.expected_active_ids.discard(victim_id)

        phase_2_1_done = False
        phase_2_2_done = False
        phase_3_done = False
        phase_2_1_written = False
        phase_2_2_written = False
        phase_3_written = False
        phase_3_success_streak = 0
        phase_3_first_success_elapsed: Optional[float] = None
        phase_3_first_success_snapshot: Optional[Snapshot] = None
        reporters_seen: Set[str] = set()
        last_phase_3_analysis = before_analysis
        last_phase_3_snapshot = before_snapshot

        while not (phase_2_1_written and phase_2_2_written and phase_3_written):
            if self.shutdown.requested:
                raise KeyboardInterrupt

            now = time.monotonic()
            elapsed = now - killed_at
            reports = self.client.new_reports_for([victim_id], baseline_report_id)

            if not phase_2_1_done and reports:
                first = min(reports, key=lambda report: report.report_id)
                result = PhaseResult("Phase 2.1", "PASS", elapsed, f"victim={victim_id} reporter={first.reporter_node_id}", before_snapshot)
                self.writer.write(result, before_analysis)
                log(f"Phase 2.1: PASS victim={victim_id} reporter={first.reporter_node_id}")
                phase_2_1_done = True
                phase_2_1_written = True

            reporters_seen.update(report.reporter_node_id for report in reports if report.reporter_node_id)
            missing_reporters = sorted(set(required_reporters) - reporters_seen)

            if not phase_2_2_done and not missing_reporters:
                result = PhaseResult("Phase 2.2", "PASS", elapsed, f"victim={victim_id} reporters={compact_list(required_reporters)}", before_snapshot)
                self.writer.write(result, before_analysis)
                log(f"Phase 2.2: PASS victim={victim_id}")
                phase_2_2_done = True
                phase_2_2_written = True

            if not phase_3_done:
                snapshot = self.client.snapshot()
                analysis = self.analyzer.analyze(snapshot, set(self.expected_active_ids), set(self.all_created_ids))
                last_phase_3_analysis = analysis
                last_phase_3_snapshot = snapshot

                if analysis.ok:
                    if phase_3_success_streak == 0:
                        phase_3_first_success_elapsed = elapsed
                        phase_3_first_success_snapshot = snapshot
                    phase_3_success_streak += 1
                    if phase_3_success_streak >= CONVERGENCE_CONFIRMATION_POLLS:
                        result = PhaseResult("Phase 3", "PASS", phase_3_first_success_elapsed, f"victim={victim_id} stable", phase_3_first_success_snapshot)
                        self.writer.write(result, analysis)
                        log(f"Phase 3: PASS victim={victim_id}")
                        phase_3_done = True
                        phase_3_written = True
                else:
                    phase_3_success_streak = 0
                    phase_3_first_success_elapsed = None
                    phase_3_first_success_snapshot = None

            if not phase_2_1_written and elapsed >= PHASE_2_1_TIMEOUT_SECONDS:
                result = PhaseResult("Phase 2.1", "TIMEOUT", elapsed, f"victim={victim_id} report=missing", before_snapshot)
                self.writer.write(result, before_analysis)
                log(f"Phase 2.1: TIMEOUT victim={victim_id}")
                phase_2_1_written = True

            if not phase_2_2_written and elapsed >= PHASE_2_2_TIMEOUT_SECONDS:
                result = PhaseResult("Phase 2.2", "TIMEOUT", elapsed, f"victim={victim_id} missing_reporters={compact_list(missing_reporters)}", before_snapshot)
                self.writer.write(result, before_analysis)
                log(f"Phase 2.2: TIMEOUT victim={victim_id} missing={compact_list(missing_reporters)}")
                phase_2_2_written = True

            if not phase_3_written and elapsed >= PHASE_3_TIMEOUT_SECONDS:
                status = "ERROR" if not last_phase_3_analysis.api_ok else "TIMEOUT"
                result = PhaseResult(
                    "Phase 3",
                    status,
                    elapsed,
                    f"victim={victim_id} {last_phase_3_analysis.details}",
                    last_phase_3_snapshot,
                    last_phase_3_analysis.problem_owners,
                    last_phase_3_analysis.deficient_nodes,
                )
                self.writer.write(result, last_phase_3_analysis)
                log(f"Phase 3: {status} victim={victim_id} {last_phase_3_analysis.details}")
                phase_3_written = True

            time.sleep(POLL_INTERVAL_SECONDS)

        if phase_3_done:
            self.run_stabilization_phase("S3", allow_repair=True, repair_code="3")
        elif last_phase_3_analysis.problem_owners:
            self.maybe_run_repair("R3", last_phase_3_analysis.problem_owners, trigger_details=last_phase_3_analysis.details)

    def run_phase_5(self) -> None:
        self.current_phase = "Phase 5"
        if len(self.expected_active_ids) < REMOVED_NODE_COUNT:
            raise RuntimeError(f"Not enough active nodes for Phase 5: need {REMOVED_NODE_COUNT}, got {len(self.expected_active_ids)}")

        victims = random.sample(sorted(self.expected_active_ids), REMOVED_NODE_COUNT)
        baseline_report_id = self.client.max_failure_report_id()
        log(f"\nPhase 5: killing victims={compact_list(victims)}")
        self.remove_nodes(victims)
        start = time.monotonic()

        deadline = start + PHASE_5_TIMEOUT_SECONDS
        next_progress = start + PROGRESS_INTERVAL_SECONDS
        diagnosed_nodes: Set[str] = set()
        success_streak = 0
        first_success_elapsed: Optional[float] = None
        first_success_snapshot: Optional[Snapshot] = None
        last_analysis: Optional[TopologyAnalysis] = None
        last_snapshot: Optional[Snapshot] = None
        missing_reports = victims[:]

        while True:
            if self.shutdown.requested:
                raise KeyboardInterrupt

            reports = self.client.new_reports_for(victims, baseline_report_id)
            reported_victims = {report.failed_node_id for report in reports}
            missing_reports = sorted(set(victims) - reported_victims)

            snapshot = self.client.snapshot()
            analysis = self.analyzer.analyze(snapshot, set(self.expected_active_ids), set(self.all_created_ids))
            last_analysis = analysis
            last_snapshot = snapshot
            ok = analysis.ok and not missing_reports

            if ok:
                if success_streak == 0:
                    first_success_elapsed = time.monotonic() - start
                    first_success_snapshot = snapshot
                success_streak += 1
                if success_streak >= CONVERGENCE_CONFIRMATION_POLLS:
                    result = PhaseResult("Phase 5", "PASS", first_success_elapsed, f"victims={compact_list(victims)} stable", first_success_snapshot)
                    self.writer.write(result, analysis)
                    log("Phase 5: PASS")
                    self.run_stabilization_phase("S5", allow_repair=True, repair_code="5")
                    return
            else:
                success_streak = 0
                first_success_elapsed = None
                first_success_snapshot = None

            now = time.monotonic()

            running_ids = self.manager.running_ids()
            running_not_up = (set(self.expected_active_ids) - snapshot.up_ids()) & running_ids
            exited_expected = set(self.expected_active_ids) - running_ids

            for node_id in sorted((running_not_up | exited_expected) - diagnosed_nodes):
                reason = "process still running but dashboard status is not UP" if node_id in running_not_up else "process exited unexpectedly"
                self.manager.print_diagnostic(node_id, reason)
                diagnosed_nodes.add(node_id)

            if now >= deadline:
                assert last_analysis is not None
                status = "ERROR" if not last_analysis.api_ok else "TIMEOUT"
                detail = f"victims={compact_list(victims)} missing_reports={compact_list(missing_reports)} {last_analysis.details}"
                result = PhaseResult("Phase 5", status, now - start, detail, last_snapshot, last_analysis.problem_owners, last_analysis.deficient_nodes)
                self.writer.write(result, last_analysis)
                log(f"Phase 5: {status} {detail}")
                self.maybe_run_repair("R5", last_analysis.problem_owners, trigger_details=detail)
                return

            if now >= next_progress:
                reported_count = len(victims) - len(missing_reports)
                log(
                    f"[Phase 5] {int(now - start)}s/{PHASE_5_TIMEOUT_SECONDS}s "
                    f"reports={reported_count}/{len(victims)} up={analysis.actual_up}/{analysis.expected_up} "
                    f"{analysis.details}"
                )
                while next_progress <= now:
                    next_progress += PROGRESS_INTERVAL_SECONDS

            time.sleep(POLL_INTERVAL_SECONDS)

    def cleanup(self) -> None:
        log("\nCleaning up tracked node processes")
        stopped, already_exited, failed = self.manager.stop_all()
        log(f"Shutdown complete: stopped={stopped}, already_exited={already_exited}, failed={failed}")

        if self.manager.processes:
            log(f"Still tracked: {compact_list(self.manager.processes.keys())}")
        else:
            log("All tracked node processes are stopped")

        if CLEAN_GENERATED_LOGS:
            self.clean_generated_logs()

    def clean_generated_logs(self) -> None:
        for path in GENERATED_LOG_PATHS:
            if KEEP_NODE_LOGS and path == SCRIPT_DIR / "logs":
                continue
            try:
                if path.is_dir():
                    shutil.rmtree(path, ignore_errors=True)
                    log(f"deleted {path}")
                elif path.exists():
                    path.unlink()
                    log(f"deleted {path}")
            except Exception as exc:
                log(f"could not delete {path}: {exc}")


def main() -> None:
    runner = EvaluationRunner()
    try:
        runner.run()
    except KeyboardInterrupt:
        log("\nInterrupted")
        raise SystemExit(130)
    except Exception as exc:
        log(f"\nEvaluation failed during {runner.current_phase}: {exc}")
        raise


if __name__ == "__main__":
    main()
