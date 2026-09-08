# FINDING NEMO Evaluation Framework

A dedicated, reproducible evaluation and benchmarking framework for the **FINDING NEMO** (*Finding Neighbors and Evicting Members of their Own*) overlay join-and-evict protocol.

This test suite is designed to produce empirical data, publication-ready vector figures, and LaTeX snippets directly for Section 6 (*Simulation Results*) of **`finding-nemo.pdf`** (Springer LNCS template in `document/finding_nemo/Latex-Template-for-Springer/`).

---

## 1. Theoretical Background & Test Suite Design

The evaluation implements a predefined sequence of tests directly validating the mathematical guarantees and concurrency limits formalized in `finding-nemo.pdf`:

| Test | Phase | Experimental Scenario | Evaluation Metric | Theoretical Motivation |
|---|---|---|---|---|
| **Test 1** | Small Network | Sequentially add $k + 1$ nodes into an empty space | % Success vs. $k$ | Validates Case 1 (clique formation where each node achieves degree $k$ without evictions). |
| **Test 2** | Scaled Network | Sequentially add $g$ consecutive nodes joining one after another into an established base network | % Success vs $g$, % Success vs $k$, and a bubble chart k vs g | Validates Case 2 (bounded $k$-regularity via $k/2$ direct targets and $k/2$ evicted hand-offs). |
| **Test 3** | Concurrent Churn | Inject a simultaneous burst of $s$ nodes ($s \le g$) into the system with 0 delay | a bubble chart $k$ vs $s$ | Empirically verifies the Poisson concurrency vulnerability model $P(X \ge 2)$ in Section 5. |

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

## 2. Distributed Team Workflow (Continuous Execution)

To scale up our trials and cover a massive range of parameters, the evaluation is designed to be run **distributed across the team**. Instead of everyone overwriting a single file, the framework accumulates data seamlessly without Git merge conflicts.

### Step 1: Run the Evaluator
Each team member runs the continuous evaluator (`run_indefinitely.py`) using their own name and their assigned parameter sweeps. This creates a *personal* CSV file (e.g., `results_phuc.csv`).

```bash
# Example: Phuc tests specific sweeps for k and g
python3 run_indefinitely.py --user phuc --k-sweep 4 6 --g-sweep 8 10 12 --s-sweep 4 6
```
- `--user`: **REQUIRED** for teamwork. Ensures your data is saved to `results/results_<user>.csv`.
- `--k-sweep`: Custom degrees to test (e.g. `2 4 6`).
- `--g-sweep`: Custom added nodes to test (e.g. `8 10 12`).
- `--s-sweep`: Custom burst sizes to test (e.g. `4 6`).

*Note: You can run this command, stop it (`Ctrl+C`), and run it again tomorrow. The script safely **appends** new trials to your CSV without deleting old ones!*

### Step 2: Commit Your Results
Once you've run enough trials, commit your specific CSV file to GitHub:
```bash
git add finding-nemo-evaluation/results/results_<your_name>.csv
git commit -m "Added more evaluation trials for k=4,6"
git push
```

### Step 3: Automatic Integration (Zero Merge Conflicts)
When you `git pull` and receive your teammates' CSV files (e.g. `results_john.csv` and `results_alice.csv`), you do **not** need to manually merge anything!

The next time *anyone* runs `run_indefinitely.py` (or triggers the plotter), the plotter automatically scans the `results/` folder, reads **all** the CSV files, aggregates everyone's trials together in memory, and generates unified bubble charts.

---

## 3. Integrating Results into `finding-nemo.pdf`

Every time the plotter runs, it writes:
1. **Raw CSV data** into `results/` for reproducible statistical analysis.
2. **High-resolution figures** (`.pdf` and `.png` for bubble charts) into `plots/`.
3. **Springer LNCS LaTeX Snippets** into `plots/figures_for_latex.tex`.

To include the generated figures in your paper, just copy the code from `finding-nemo-evaluation/plots/figures_for_latex.tex` directly into Section 6 (*Simulation Results*) of your `finding-nemo.tex` file.

