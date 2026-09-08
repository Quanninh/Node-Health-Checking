"""
Test Runner module for FINDING NEMO evaluation suite.
Executes the predefined sequence of tests:
  - Test 1: Small network, sequentially add k + 1 nodes into an empty space -> plot % success vs k, log trials?
  - Test 2: Scaled network with g consecutive nodes joining one after another -> plot % success vs g, % success vs k, and a bubble chart k vs g
  - Test 3: A burst of g nodes into the system -> plot bubble chart k vs s
Records CSVs, aggregates metrics, and generates plots.
"""

import csv
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from config import EvaluationConfig
from nemo_simulator import NemoSimulator
from plotter import NemoPlotter


@dataclass
class TrialRecord:
    timestamp: str
    test_name: str
    param_name: str
    param_value: int
    trial: int
    status: str  # PASS / FAIL
    elapsed_ms: float
    problem_count: int
    details: str


class NemoTestRunner:
    def __init__(self, config: EvaluationConfig, mode: str = "sim") -> None:
        self.config = config
        self.mode = mode.lower()
        self.simulator = NemoSimulator(config)
        self.plotter = NemoPlotter(config.plots_dir)

    def run_all(self) -> Dict[str, Any]:
        print(f"\n{'='*70}")
        print(f"Starting FINDING NEMO Evaluation Suite (Mode: {self.mode.upper()})")
        print(f"Parameters: k={self.config.k_sweep}, g={self.config.g_sweep}, s={self.config.s_sweep}")
        print(f"Trials per batch: {self.config.trials_per_batch}")
        print(f"{'='*70}\n")

        try:
            self.run_batch()

            # The plotter will read the cumulative CSVs and generate all plots
            print(f"\n[OK] Generating plots from cumulative data...")
            self.plotter.generate_all_plots()
            return {}
        finally:
            pass

    def run_batch(self) -> None:
        csv_path = self.config.results_csv_path
        file_exists = csv_path.exists()
        
        # Open in append mode
        with csv_path.open("a", newline="", encoding="utf-8") as f:
            fieldnames = ["timestamp", "test_name", "k", "g", "s", "success_rate", "trials"]
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            if not file_exists:
                writer.writeheader()

            for k in self.config.k_sweep:
                for g in (self.config.g_sweep or []):
                    for s in (self.config.s_sweep or []):
                        if g <= k or s > g:
                            continue  # invalid bounds
                        
                        print(f"Sequence (k={k}, g={g}, s={s}) for {self.config.trials_per_batch} trials:")
                        
                        t1_passes = 0
                        t2_passes = 0
                        t3_passes = 0

                        for trial in range(self.config.trials_per_batch):
                            # Test 1
                            t1_conv, _ = self.simulator.run_test1_trial(k=k)
                            t2_conv, _ = self.simulator.run_test2_trial(k=k, g=g)
                            t3_conv, _ = self.simulator.run_test3_trial(k=k, burst_size_s=s)
                            
                            if t1_conv: t1_passes += 1
                            if t2_conv: t2_passes += 1
                            if t3_conv: t3_passes += 1
                            print(".", end="", flush=True)

                        print(" Done.")
                        
                        ts = datetime.now().isoformat()
                        writer.writerow({
                            "timestamp": ts, "test_name": "Test 1", 
                            "k": k, "g": g, "s": s, 
                            "success_rate": (t1_passes / self.config.trials_per_batch) * 100.0,
                            "trials": self.config.trials_per_batch
                        })
                        writer.writerow({
                            "timestamp": ts, "test_name": "Test 2", 
                            "k": k, "g": g, "s": s, 
                            "success_rate": (t2_passes / self.config.trials_per_batch) * 100.0,
                            "trials": self.config.trials_per_batch
                        })
                        writer.writerow({
                            "timestamp": ts, "test_name": "Test 3", 
                            "k": k, "g": g, "s": s, 
                            "success_rate": (t3_passes / self.config.trials_per_batch) * 100.0,
                            "trials": self.config.trials_per_batch
                        })
            print(f"  [OK] Batch appended to {csv_path}")

