#!/usr/bin/env python3
"""
FINDING NEMO Evaluation CLI.
Main entry-point for benchmarking the FINDING NEMO protocol.

Supports:
  - Setting k (degree) and g (added nodes, g > k)
  - Predefined sequence of tests (Test 1, Test 2, Test 3)
  - Convergence checking and % success metric calculation
  - Simulation mode (fast parameter sweeps) and Live agent mode
  - Publication-ready figure plotting and LaTeX snippet generation
"""

import argparse
import sys
from pathlib import Path
from typing import List

from config import EvaluationConfig
from test_runner import NemoTestRunner


def parse_int_list(arg: str) -> List[int]:
    try:
        return [int(x.strip()) for x in arg.split(",") if x.strip()]
    except ValueError:
        raise argparse.ArgumentTypeError(f"Invalid comma-separated integer list: {arg}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="FINDING NEMO Overlay Evaluation Suite (Springer LNCS)",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )

    parser.add_argument(
        "-k", "--k",
        type=int,
        default=4,
        help="Target node degree constraint (must be a positive even integer: 2, 4, 6, 8...)",
    )
    parser.add_argument(
        "-g", "--g",
        type=int,
        default=8,
        help="Number of nodes added in scaled/burst evaluations (must be > k)",
    )
    parser.add_argument(
        "-t", "--trials",
        type=int,
        default=10,
        help="Number of independent trials per data point to compute %% success",
    )
    parser.add_argument(
        "--base-nodes",
        type=int,
        default=None,
        help="Base network size for scaled/burst tests (default: max(k + 1, 12))",
    )
    parser.add_argument(
        "--test",
        choices=["1", "2", "3", "all"],
        default="all",
        help="Specific test to execute: 1 (Small net vs k), 2 (Scaled net vs g), 3 (Burst churn vs s), or all",
    )
    parser.add_argument(
        "--mode",
        choices=["sim", "live"],
        default="sim",
        help="Execution mode: 'sim' (fast discrete-event protocol simulation) or 'live' (real Java node agents)",
    )
    parser.add_argument(
        "--k-sweep",
        type=parse_int_list,
        default=None,
        help="Custom comma-separated degree values for Test 1 (e.g., '2,4,6,8,10')",
    )
    parser.add_argument(
        "--g-sweep",
        type=parse_int_list,
        default=None,
        help="Custom comma-separated added node counts for Test 2 (e.g., '6,8,10,12,14')",
    )
    parser.add_argument(
        "--s-sweep",
        type=parse_int_list,
        default=None,
        help="Custom comma-separated burst sizes for Test 3 (e.g., '2,4,6,8')",
    )
    parser.add_argument(
        "--results-dir",
        type=Path,
        default=None,
        help="Custom directory path for CSV results",
    )
    parser.add_argument(
        "--plots-dir",
        type=Path,
        default=None,
        help="Custom directory path for plots and LaTeX snippets",
    )

    args = parser.parse_args()

    # Validate k and g
    if args.k <= 0 or args.k % 2 != 0:
        print(f"Error: -k must be a positive even integer, got {args.k}", file=sys.stderr)
        return 1
    if args.g <= args.k:
        print(f"Error: -g ({args.g}) must be strictly greater than -k ({args.k})", file=sys.stderr)
        return 1

    # Initialize configuration
    config = EvaluationConfig(
        k=args.k,
        g=args.g,
        trials=args.trials,
        base_node_count=args.base_nodes,
        k_sweep=args.k_sweep if args.k_sweep is not None else [2, 4, 6, 8, 10],
        g_sweep=args.g_sweep,
        s_sweep=args.s_sweep,
    )
    if args.results_dir is not None:
        config.results_dir = args.results_dir
        config.results_dir.mkdir(parents=True, exist_ok=True)
    if args.plots_dir is not None:
        config.plots_dir = args.plots_dir
        config.plots_dir.mkdir(parents=True, exist_ok=True)

    runner = NemoTestRunner(config=config, mode=args.mode)

    if args.test == "all":
        runner.run_all()
    elif args.test == "1":
        runner.run_test1()
    elif args.test == "2":
        runner.run_test2()
    elif args.test == "3":
        runner.run_test3()

    print("\n[SUCCESS] Evaluation finished successfully!")
    print(f"Results: {config.results_dir}")
    print(f"Plots & LaTeX Snippets: {config.plots_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
