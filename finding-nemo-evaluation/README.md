# FINDING NEMO Evaluation Framework

A dedicated, reproducible evaluation and benchmarking framework for the **FINDING NEMO** (*Finding Neighbors and Evicting Members of their Own*) overlay join-and-evict protocol.

This test suite is designed to produce empirical data, publication-ready vector figures, and LaTeX snippets directly for Section 6 (*Simulation Results*) of **`finding-nemo.pdf`** (Springer LNCS template in `document/finding_nemo/Latex-Template-for-Springer/`).

---

## 1. Theoretical Background & Test Suite Design

The evaluation implements a predefined sequence of tests directly validating the mathematical guarantees and concurrency limits formalized in `finding-nemo.pdf`:

| Test | Phase | Experimental Scenario | Evaluation Metric | Theoretical Motivation |
|---|---|---|---|---|
| **Test 1** | Small Network | Sequentially add $k + 1$ nodes into an empty space | % Success vs. $k$ | Validates Case 1 (clique formation where each node achieves degree $k$ without evictions). |
| **Test 2** | Scaled Network | Sequentially add $g$ consecutive nodes joining one after another into an established base network | % Success vs. $g$ | Validates Case 2 (bounded $k$-regularity via $k/2$ direct targets and $k/2$ evicted hand-offs). |
| **Test 3** | Concurrent Churn | Inject a simultaneous burst of $s$ nodes ($s \le g$) into the system with 0 delay | % Success vs. burst size $s$ | Empirically verifies the Poisson concurrency vulnerability model $P(X \ge 2)$ in Section 5. |

### Definition of Convergence & Success

Derived strictly from `evaluation/evaluation.py` and Chapter 6 of `document/main.pdf`:
- An active node is **problematic** if it violates any of the following:
  1. **Degree constraint**: Degree $\ne k$ (or for small networks $N \le k+1$, degree $\ne N-1$). Nodes with fewer edges are classified as **deficient**.
  2. **Duplicate edges**: Contains repeated entries in its neighbor list.
  3. **Self-edges**: Contains itself as a neighbor.
  4. **Stale edges**: Contains references to dead, inactive, or non-existent nodes.
  5. **Non-mutual edges**: Relationship is asymmetric ($v \in N_u$ but $u \notin N_v$).
- **Network Convergence**: All expected active nodes are reported UP, zero problematic nodes exist, and the network forms a single connected component.
- **Success Rate (% Success)**: Across $T$ independent trials,
  $$\% \text{ Success} = \frac{\text{Number of Converged Trials}}{T} \times 100\%$$

---

## 2. Directory Structure

```
finding-nemo-evaluation/
├── config.py                 # Configuration dataclass (k, g, timeouts, sweeps, paths)
├── topology_analyzer.py      # Formal topology validator and convergence checker
├── nemo_simulator.py         # Discrete-event simulation matching JoinPlanner.java
├── live_evaluator.py         # Live Java node-agent subprocess runner + embedded dashboard
├── test_runner.py            # Orchestrator for Test 1, Test 2, and Test 3
├── plotter.py                # Publication-ready vector SVG & Matplotlib plotter + LaTeX exporter
├── run_evaluation.py         # Unified CLI entry-point
├── test_evaluation_unit.py   # Unit test suite verifying convergence logic & simulations
├── results/                  # Generated CSV results
│   ├── test1_small_network_k.csv
│   ├── test2_scaled_network_g.csv
│   └── test3_burst_network_s.csv
└── plots/                    # Output figures and LaTeX snippet
    ├── test1_success_vs_k.svg
    ├── test2_success_vs_g.svg
    ├── test3_success_vs_s.svg
    └── figures_for_latex.tex
```

---

## 3. Quick Start & CLI Usage

### Running the Full Suite (Simulation Mode — Recommended for fast sweeps)
```bash
python3 run_evaluation.py --k 4 --g 8 --trials 20
```

### Parameter Options:
- `-k`, `--k`: Target node degree constraint (must be positive even integer: 2, 4, 6, 8...). Default: `4`.
- `-g`, `--g`: Number of nodes added in scaled and burst tests (must be $> k$). Default: `8`.
- `-t`, `--trials`: Number of trials per data point. Default: `10`.
- `--base-nodes`: Base network size for scaled/burst tests. Default: `max(k + 1, 12)`.
- `--test`: Run a specific test (`1`, `2`, `3`, or `all`). Default: `all`.
- `--mode`: Execution mode (`sim` for high-speed simulation, or `live` for real Java `node-agent-1.0.jar` processes). Default: `sim`.
- `--k-sweep`: Custom comma-separated degrees for Test 1 (e.g. `--k-sweep 2,4,6,8,10`).
- `--g-sweep`: Custom comma-separated added node counts for Test 2 (e.g. `--g-sweep 6,8,10,12,14`).
- `--s-sweep`: Custom comma-separated burst sizes for Test 3 (e.g. `--s-sweep 2,4,6,8`).

### Examples:
```bash
# Run only Test 1 across degrees 2, 4, 6, 8, 10 with 30 trials per degree
python3 run_evaluation.py --test 1 --k-sweep 2,4,6,8,10 --trials 30

# Run Test 2 with target degree k=6 and added nodes up to g=16
python3 run_evaluation.py --test 2 -k 6 -g 16 --trials 15

# Run Test 3 (burst concurrency) with 20 base nodes to evaluate vulnerability decay
python3 run_evaluation.py --test 3 -k 4 -g 8 --base-nodes 20 --trials 25
```

---

## 4. Running Against Real Java Agents (`--mode live`)

The framework can run against the compiled `node-agent-1.0.jar` binary:
```bash
python3 run_evaluation.py --mode live --k 4 --g 6 --trials 3
```
- The evaluator automatically starts an embedded mock dashboard HTTP server on port `6789` to ingest `/api/heartbeat` requests from agents and expose `/api/nodes`.
- If the official Spring Boot `ServerApplication` is already running on port `6789`, the evaluator seamlessly queries that server.
- All spawned Java processes are gracefully stopped and killed on exit or `Ctrl+C`.

---

## 5. Integrating Results into `finding-nemo.pdf`

Every evaluation run writes:
1. **Raw CSV data** into `results/` for reproducible statistical analysis.
2. **High-resolution figures** (`.svg`, and `.pdf`/`.png` if matplotlib is installed) into `plots/`.
3. **Springer LNCS LaTeX Snippets** into `plots/figures_for_latex.tex`.

To include the generated figures in your paper:
Copy the code from `finding-nemo-evaluation/plots/figures_for_latex.tex` directly into Section 6 (*Simulation Results*) of `document/finding_nemo/Latex-Template-for-Springer/finding-nemo.tex`.

