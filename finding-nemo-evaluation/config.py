"""
Configuration module for the FINDING NEMO evaluation suite.
Provides default parameters, environment overrides, and path resolutions.
"""

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional

BASE_DIR = Path(__file__).resolve().parent
REPO_ROOT = BASE_DIR.parent
DEFAULT_JAR_PATH = REPO_ROOT / "evaluation" / "node-agent-1.0.jar"
RESULTS_DIR = BASE_DIR / "results"
PLOTS_DIR = BASE_DIR / "plots"


@dataclass
class EvaluationConfig:
    # Target degree that each node must maintain (k must be an even integer)
    k: int = 4
    # Number of nodes added in scaled/burst tests (g must be strictly greater than k)
    g: int = 8
    # Number of independent trials per batch (used in continuous runner)
    trials_per_batch: int = 10

    # Test 1 degree sweep list (must all be even integers >= 2)
    k_sweep: List[int] = field(default_factory=lambda: [2, 4, 6, 8, 10])

    # Test 2 added node count sweep list (all values should be > k)
    g_sweep: Optional[List[int]] = None

    # Test 3 burst size sweep list (all values in range [2, g])
    s_sweep: Optional[List[int]] = None

    # Timeouts & convergence stability
    timeout_seconds: float = 60.0
    stabilization_seconds: float = 5.0
    poll_interval_seconds: float = 0.2
    convergence_confirmation_polls: int = 3

    # Live agent specific settings
    #! consider out of date since we focus on a simulation FINDING NEMO written in python
    jar_path: Path = DEFAULT_JAR_PATH
    dashboard_url: str = "http://localhost:6789/api"
    dashboard_port: int = 6789
    advertise_host: str = "127.0.0.1"
    multicast_interface: str = "en0" if os.uname().sysname == "Darwin" else "eth0"
    node_add_interval_seconds: float = 2.0
    auto_start_mock_dashboard: bool = True

    # Directories
    results_dir: Path = RESULTS_DIR
    plots_dir: Path = PLOTS_DIR
    results_csv_path: Path = RESULTS_DIR / "cumulative_results.csv"

    def __post_init__(self) -> None:
        self.validate()

        if self.g_sweep is None:
            # Generate sensible g sweep: [k+1, k+2, k+4, k+6, k+8...] up to max(g, k+8)
            max_g = max(self.g, self.k + 8)
            candidates = sorted(list(set([self.k + 1] + list(range(self.k + 2, max_g + 1, 2)))))
            self.g_sweep = candidates

        if self.s_sweep is None:
            # Generate burst sizes s from 2 up to g
            step = max(1, self.g // 6)
            s_vals = sorted(list(set([2] + list(range(2, self.g + 1, step)) + [self.g])))
            self.s_sweep = [s for s in s_vals if 2 <= s <= self.g]

        self.results_dir.mkdir(parents=True, exist_ok=True)
        self.plots_dir.mkdir(parents=True, exist_ok=True)

    def validate(self) -> None:
        if self.k <= 0 or self.k % 2 != 0:
            raise ValueError(f"k must be a positive even integer, got {self.k}")
        if self.g <= self.k:
            raise ValueError(f"g ({self.g}) must be strictly greater than k ({self.k})")
        if self.trials_per_batch <= 0:
            raise ValueError(f"trials_per_batch must be positive, got {self.trials_per_batch}")
        for val in self.k_sweep:
            if val <= 0 or val % 2 != 0:
                raise ValueError(f"k_sweep elements must be positive even integers, got {val}")
