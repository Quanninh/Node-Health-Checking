"""
Live Evaluator module for FINDING NEMO.
Controls real Java node-agent processes, provides an embedded dashboard HTTP API
if ServerApplication is not active, captures node logs, and evaluates real-world convergence.
"""

import json
import random
import subprocess
import sys
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any, Deque, Dict, List, Optional, Set, Tuple
from urllib import request

from config import EvaluationConfig
from topology_analyzer import TopologyAnalysis, TopologyAnalyzer


# -----------------------------------------------------------------------------
# Embedded Mock Dashboard Server (port 6789)
# -----------------------------------------------------------------------------

class MockDashboardState:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.nodes: Dict[str, Dict[str, Any]] = {}
        self.failure_reports: List[Dict[str, Any]] = []

    def handle_heartbeat(self, payload: Dict[str, Any]) -> None:
        node_id = str(payload.get("id") or "")
        if not node_id:
            return
        with self.lock:
            self.nodes[node_id] = {
                "id": node_id,
                "status": str(payload.get("status") or "UP").upper(),
                "neighbors": payload.get("neighbors") or [],
                "last_seen": time.time(),
            }

    def get_nodes(self) -> List[Dict[str, Any]]:
        with self.lock:
            return list(self.nodes.values())

    def reset(self) -> None:
        with self.lock:
            self.nodes.clear()
            self.failure_reports.clear()


SHARED_DASHBOARD_STATE = MockDashboardState()


class DashboardHTTPRequestHandler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length > 0 else b"{}"
        try:
            payload = json.loads(body.decode("utf-8"))
        except Exception:
            payload = {}

        if self.path.startswith("/api/heartbeat"):
            SHARED_DASHBOARD_STATE.handle_heartbeat(payload)
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"Heartbeat OK")
        elif self.path.startswith("/api/failure-report"):
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"Report OK")
        else:
            self.send_response(404)
            self.end_headers()

    def do_GET(self) -> None:
        if self.path.startswith("/api/nodes"):
            data = SHARED_DASHBOARD_STATE.get_nodes()
            encoded = json.dumps(data).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(encoded)
        elif self.path.startswith("/api/failure-reports"):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b"[]")
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format: str, *args: Any) -> None:
        # Suppress noisy HTTP request logging in terminal
        pass


class EmbeddedDashboardServer:
    def __init__(self, port: int = 6789) -> None:
        self.port = port
        self.server: Optional[HTTPServer] = None
        self.thread: Optional[threading.Thread] = None

    def start(self) -> bool:
        try:
            self.server = HTTPServer(("0.0.0.0", self.port), DashboardHTTPRequestHandler)
            self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
            self.thread.start()
            return True
        except OSError:
            # Port already in use, external dashboard (Spring Boot) is likely already active
            return False

    def stop(self) -> None:
        if self.server:
            self.server.shutdown()
            self.server.server_close()


# -----------------------------------------------------------------------------
# Managed Process for Node Agents
# -----------------------------------------------------------------------------

@dataclass
class ManagedNodeProcess:
    node_id: str
    process: subprocess.Popen
    output_tail: Deque[str] = field(default_factory=lambda: deque(maxlen=100))
    reader_thread: Optional[threading.Thread] = None


class LiveNodeManager:
    def __init__(self, config: EvaluationConfig) -> None:
        self.config = config
        self.processes: Dict[str, ManagedNodeProcess] = {}

    def start_node(self, node_id: str, target_k: int) -> None:
        if node_id in self.processes and self.is_running(node_id):
            return

        cmd = [
            "java",
            "-jar",
            str(self.config.jar_path),
            "--bind-host",
            "0.0.0.0",
            "--advertise-host",
            self.config.advertise_host,
            "--max-neighbors",
            str(target_k),
            "--multicast-interface",
            self.config.multicast_interface,
            "--node-id",
            node_id,
        ]

        proc = subprocess.Popen(
            cmd,
            cwd=str(self.config.jar_path.parent),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )

        managed = ManagedNodeProcess(node_id=node_id, process=proc)
        thread = threading.Thread(target=self._capture_output, args=(managed,), daemon=True)
        managed.reader_thread = thread
        self.processes[node_id] = managed
        thread.start()

    def _capture_output(self, managed: ManagedNodeProcess) -> None:
        stream = managed.process.stdout
        if not stream:
            return
        try:
            for raw_line in stream:
                line = raw_line.strip()
                if line:
                    managed.output_tail.append(line)
        except Exception:
            pass

    def is_running(self, node_id: str) -> bool:
        managed = self.processes.get(node_id)
        return managed is not None and managed.process.poll() is None

    def stop_node(self, node_id: str) -> None:
        managed = self.processes.pop(node_id, None)
        if not managed:
            return
        proc = managed.process
        if proc.poll() is None:
            try:
                proc.terminate()
                proc.wait(timeout=3)
            except Exception:
                try:
                    proc.kill()
                    proc.wait(timeout=2)
                except Exception:
                    pass

    def stop_all(self) -> None:
        for node_id in list(self.processes.keys()):
            self.stop_node(node_id)


# -----------------------------------------------------------------------------
# Live Evaluator
# -----------------------------------------------------------------------------

class LiveEvaluator:
    def __init__(self, config: EvaluationConfig) -> None:
        self.config = config
        self.manager = LiveNodeManager(config)
        self.dashboard_server = EmbeddedDashboardServer(config.dashboard_port)
        self.expected_active_ids: Set[str] = set()

    def setup(self) -> None:
        if self.config.auto_start_mock_dashboard:
            started = self.dashboard_server.start()
            if started:
                print(f"[LiveEvaluator] Started embedded mock dashboard on port {self.config.dashboard_port}")
            else:
                print(f"[LiveEvaluator] Using existing dashboard on port {self.config.dashboard_port}")

    def teardown(self) -> None:
        self.manager.stop_all()
        self.dashboard_server.stop()
        SHARED_DASHBOARD_STATE.reset()

    def fetch_snapshot(self) -> Tuple[Dict[str, List[str]], Set[str]]:
        try:
            req = request.Request(f"{self.config.dashboard_url}/nodes")
            with request.urlopen(req, timeout=3.0) as resp:
                data = json.loads(resp.read().decode("utf-8"))
        except Exception:
            # Fallback to shared memory if local mock
            data = SHARED_DASHBOARD_STATE.get_nodes()

        neighbor_map: Dict[str, List[str]] = {}
        up_ids: Set[str] = set()
        for item in data:
            nid = str(item.get("id") or "")
            if not nid:
                continue
            status = str(item.get("status") or "").upper()
            if status == "UP":
                up_ids.add(nid)
            neighbor_map[nid] = list(item.get("neighbors") or [])
        return neighbor_map, up_ids

    def wait_for_convergence(self, target_k: int, timeout_seconds: float) -> Tuple[bool, TopologyAnalysis]:
        start = time.monotonic()
        streak = 0
        last_analysis: Optional[TopologyAnalysis] = None

        while time.monotonic() - start < timeout_seconds:
            neighbor_map, up_ids = self.fetch_snapshot()
            analysis = TopologyAnalyzer.analyze(
                neighbor_map=neighbor_map,
                expected_active_ids=self.expected_active_ids,
                target_degree=target_k,
                up_node_ids=up_ids,
            )
            last_analysis = analysis

            if analysis.is_converged:
                streak += 1
                if streak >= self.config.convergence_confirmation_polls:
                    return True, analysis
            else:
                streak = 0

            time.sleep(self.config.poll_interval_seconds)

        assert last_analysis is not None
        return False, last_analysis

    def run_test1_trial(self, k: int) -> Tuple[bool, TopologyAnalysis]:
        self.manager.stop_all()
        SHARED_DASHBOARD_STATE.reset()
        self.expected_active_ids.clear()
        time.sleep(1.0)

        for i in range(k + 1):
            node_id = f"live1_k{k}_{i:02d}"
            self.expected_active_ids.add(node_id)
            self.manager.start_node(node_id, target_k=k)
            time.sleep(self.config.node_add_interval_seconds)

        return self.wait_for_convergence(target_k=k, timeout_seconds=self.config.timeout_seconds)

    def run_test2_trial(self, k: int, g: int) -> Tuple[bool, TopologyAnalysis]:
        self.manager.stop_all()
        SHARED_DASHBOARD_STATE.reset()
        self.expected_active_ids.clear()
        time.sleep(1.0)

        # Base network
        for i in range(k + 1):
            node_id = f"live2_base_{i:02d}"
            self.expected_active_ids.add(node_id)
            self.manager.start_node(node_id, target_k=k)
            time.sleep(self.config.node_add_interval_seconds)

        self.wait_for_convergence(target_k=k, timeout_seconds=self.config.timeout_seconds)

        # Consecutive joins
        for j in range(g):
            node_id = f"live2_join_{j:02d}"
            self.expected_active_ids.add(node_id)
            self.manager.start_node(node_id, target_k=k)
            time.sleep(self.config.node_add_interval_seconds)

        return self.wait_for_convergence(target_k=k, timeout_seconds=self.config.timeout_seconds)

    def run_test3_trial(self, k: int, burst_size_s: int) -> Tuple[bool, TopologyAnalysis]:
        self.manager.stop_all()
        SHARED_DASHBOARD_STATE.reset()
        self.expected_active_ids.clear()
        time.sleep(1.0)

        # Base network
        for i in range(k + 1):
            node_id = f"live3_base_{i:02d}"
            self.expected_active_ids.add(node_id)
            self.manager.start_node(node_id, target_k=k)
            time.sleep(self.config.node_add_interval_seconds)

        self.wait_for_convergence(target_k=k, timeout_seconds=self.config.timeout_seconds)

        # Simultaneous burst: start all s nodes with 0 delay
        for j in range(burst_size_s):
            node_id = f"live3_burst_{j:02d}"
            self.expected_active_ids.add(node_id)
            self.manager.start_node(node_id, target_k=k)

        return self.wait_for_convergence(target_k=k, timeout_seconds=self.config.timeout_seconds)

