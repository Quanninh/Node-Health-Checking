"""
Plotting and LaTeX figure export module for FINDING NEMO evaluation.
Generates publication-quality charts designed directly for inclusion in finding-nemo.tex.
"""

import csv
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Tuple

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    HAS_MATPLOTLIB = True
except ImportError:
    HAS_MATPLOTLIB = False


class NemoPlotter:
    def __init__(self, plots_dir: Path) -> None:
        self.plots_dir = plots_dir
        self.plots_dir.mkdir(parents=True, exist_ok=True)
        self.results_csv = self.plots_dir.parent / "results" / "cumulative_results.csv"
        self.results_dir = self.plots_dir.parent / "results"

    def _load_and_aggregate(self) -> Tuple[Dict, Dict, Dict]:
        t1_agg = defaultdict(lambda: {"trials": 0, "success_sum": 0.0})
        t2_agg = defaultdict(lambda: {"trials": 0, "success_sum": 0.0})
        t3_agg = defaultdict(lambda: {"trials": 0, "success_sum": 0.0})

        if not self.results_dir.exists():
            return {}, {}, {}

        for csv_file in self.results_dir.glob("*.csv"):
            with csv_file.open("r", encoding="utf-8") as f:
                reader = csv.DictReader(f)
                for row in reader:
                    try:
                        k = int(row["k"])
                        g = int(row["g"])
                        s = int(row["s"])
                        trials = int(row["trials"])
                        success_rate = float(row["success_rate"])
                        test_name = row["test_name"]
                    except (KeyError, ValueError):
                        continue

                    if "Test 1" in test_name:
                        t1_agg[k]["trials"] += trials
                        t1_agg[k]["success_sum"] += success_rate * trials
                    elif "Test 2" in test_name:
                        t2_agg[(k, g)]["trials"] += trials
                        t2_agg[(k, g)]["success_sum"] += success_rate * trials
                    elif "Test 3" in test_name:
                        t3_agg[(k, s)]["trials"] += trials
                        t3_agg[(k, s)]["success_sum"] += success_rate * trials

        return dict(t1_agg), dict(t2_agg), dict(t3_agg)

    def generate_all_plots(self) -> None:
        t1_agg, t2_agg, t3_agg = self._load_and_aggregate()
        
        if not HAS_MATPLOTLIB:
            print("matplotlib is not installed, skipping plot generation.")
            return

        # ---------------------------------------------------------------------
        # Test 1: % success vs k (Line chart)
        # ---------------------------------------------------------------------
        if t1_agg:
            k_vals = sorted(list(t1_agg.keys()))
            success_vals = [t1_agg[k]["success_sum"] / t1_agg[k]["trials"] for k in k_vals]

            fig, ax = plt.subplots(figsize=(6, 4), dpi=300)
            ax.plot(k_vals, success_vals, marker="o", color="#1d4ed8", linewidth=2.2)
            ax.set_title("Test 1: Overlay Convergence vs Target Degree $k$", fontsize=12, fontweight="bold")
            ax.set_xlabel("Target Degree ($k$)", fontsize=11)
            ax.set_ylabel("% Success (Convergence)", fontsize=11)
            ax.set_ylim(-5, 105)
            ax.grid(True, linestyle="--", alpha=0.6)
            plt.tight_layout()
            plt.savefig(self.plots_dir / "test1_success_vs_k.pdf")
            plt.savefig(self.plots_dir / "test1_success_vs_k.png")
            plt.close()

        # ---------------------------------------------------------------------
        # Test 2: % success vs k (Line chart, averaged over all g for each k)
        # ---------------------------------------------------------------------
        if t2_agg:
            t2_k_agg = defaultdict(lambda: {"trials": 0, "success_sum": 0.0})
            for (k, g), data in t2_agg.items():
                t2_k_agg[k]["trials"] += data["trials"]
                t2_k_agg[k]["success_sum"] += data["success_sum"]

            k_vals_t2 = sorted(list(t2_k_agg.keys()))
            success_vals_t2 = [t2_k_agg[k]["success_sum"] / t2_k_agg[k]["trials"] for k in k_vals_t2]

            fig, ax = plt.subplots(figsize=(6, 4), dpi=300)
            ax.plot(k_vals_t2, success_vals_t2, marker="s", color="#059669", linewidth=2.2)
            ax.set_title("Test 2: Scaled Overlay Convergence vs Target Degree $k$", fontsize=12, fontweight="bold")
            ax.set_xlabel("Target Degree ($k$)", fontsize=11)
            ax.set_ylabel("% Success (Convergence)", fontsize=11)
            ax.set_ylim(-5, 105)
            ax.grid(True, linestyle="--", alpha=0.6)
            plt.tight_layout()
            plt.savefig(self.plots_dir / "test2_success_vs_k.pdf")
            plt.savefig(self.plots_dir / "test2_success_vs_k.png")
            plt.close()

            # Test 2: k vs g (Bubble chart)
            fig, ax = plt.subplots(figsize=(7, 5), dpi=300)
            for (k, g), data in t2_agg.items():
                trials = data["trials"]
                succ = data["success_sum"] / trials
                # Bubble size proportional to trials
                scatter = ax.scatter(k, g, s=trials * 10, alpha=0.6, edgecolors="w", c="#059669")
                # Text inside bubble
                ax.text(k, g, f"{succ:.0f}%", ha='center', va='center', fontsize=8, color='black')
                
            ax.set_title("Test 2: Target Degree ($k$) vs Added Nodes ($g$)", fontsize=12, fontweight="bold")
            ax.set_xlabel("Target Degree ($k$)", fontsize=11)
            ax.set_ylabel("Number of Added Nodes ($g$)", fontsize=11)
            ax.grid(True, linestyle="--", alpha=0.6)
            
            # Create a dummy legend for bubble size
            from matplotlib.lines import Line2D
            legend_elements = [Line2D([0], [0], marker='o', color='w', label='Size = Trials', markerfacecolor='#059669', markersize=10)]
            ax.legend(handles=legend_elements, loc='upper left')
            
            plt.tight_layout()
            plt.savefig(self.plots_dir / "test2_k_vs_g_bubble.pdf")
            plt.savefig(self.plots_dir / "test2_k_vs_g_bubble.png")
            plt.close()

        # ---------------------------------------------------------------------
        # Test 3: k vs s (Bubble chart)
        # ---------------------------------------------------------------------
        if t3_agg:
            fig, ax = plt.subplots(figsize=(7, 5), dpi=300)
            for (k, s), data in t3_agg.items():
                trials = data["trials"]
                succ = data["success_sum"] / trials
                # Bubble size proportional to trials
                scatter = ax.scatter(k, s, s=trials * 10, alpha=0.6, edgecolors="w", c="#dc2626")
                # Text inside bubble
                ax.text(k, s, f"{succ:.0f}%", ha='center', va='center', fontsize=8, color='black')
                
            ax.set_title("Test 3: Target Degree ($k$) vs Burst Size ($s$)", fontsize=12, fontweight="bold")
            ax.set_xlabel("Target Degree ($k$)", fontsize=11)
            ax.set_ylabel("Burst Size ($s$)", fontsize=11)
            ax.grid(True, linestyle="--", alpha=0.6)
            
            from matplotlib.lines import Line2D
            legend_elements = [Line2D([0], [0], marker='o', color='w', label='Size = Trials', markerfacecolor='#dc2626', markersize=10)]
            ax.legend(handles=legend_elements, loc='upper left')
            
            plt.tight_layout()
            plt.savefig(self.plots_dir / "test3_k_vs_s_bubble.pdf")
            plt.savefig(self.plots_dir / "test3_k_vs_s_bubble.png")
            plt.close()

        self.generate_latex_snippets()

    def generate_latex_snippets(self) -> Path:
        snippet_path = self.plots_dir / "figures_for_latex.tex"
        content = [
            "% ==========================================================================",
            "% FINDING NEMO Evaluation Figures - LaTeX Snippets for finding-nemo.tex",
            "% ==========================================================================",
            "",
            "\\begin{figure}[htbp]",
            "  \\centering",
            "  \\includegraphics[width=0.75\\textwidth]{figures/test1_success_vs_k.pdf}",
            "  \\caption{Test 1: Small network convergence.}",
            "\\end{figure}",
            "",
            "\\begin{figure}[htbp]",
            "  \\centering",
            "  \\includegraphics[width=0.75\\textwidth]{figures/test2_success_vs_k.pdf}",
            "  \\caption{Test 2: Scaled network convergence (Line).}",
            "\\end{figure}",
            "",
            "\\begin{figure}[htbp]",
            "  \\centering",
            "  \\includegraphics[width=0.75\\textwidth]{figures/test2_k_vs_g_bubble.pdf}",
            "  \\caption{Test 2: Scaled network convergence (Bubble).}",
            "\\end{figure}",
            "",
            "\\begin{figure}[htbp]",
            "  \\centering",
            "  \\includegraphics[width=0.75\\textwidth]{figures/test3_k_vs_s_bubble.pdf}",
            "  \\caption{Test 3: Concurrent burst vulnerability (Bubble).}",
            "\\end{figure}",
            "",
        ]

        snippet_path.write_text("\n".join(content), encoding="utf-8")
        return snippet_path
