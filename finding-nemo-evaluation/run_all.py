import argparse
import os
import shutil
import time
from evaluate import (
    run_bootstrap_test,
    run_sequential_churn_test,
    run_concurrent_burst_test,
    append_to_csv
)
from plotter import plot_test1, plot_test2, plot_test3

def run_evaluation_suite(member_name="results", test1_trials=50, test2_trials=20, test3_trials=30, clean=True):
    start_time = time.time()
    print("=" * 70)
    print(f"STARTING FINDING NEMO AUTOMATED EVALUATION SUITE")
    print(f"Member / Prefix: {member_name}")
    print("=" * 70)
    
    file_t1 = f"{member_name}_test1.csv"
    file_t2 = f"{member_name}_test2.csv"
    file_t3 = f"{member_name}_test3.csv"
    
    if clean:
        for f in [file_t1, file_t2, file_t3]:
            if os.path.exists(f):
                os.remove(f)
                print(f"Removed previous dataset: {f}")
                
    # -------------------------------------------------------------
    # Test 1: Bootstrap Convergence vs. Target Degree k
    # -------------------------------------------------------------
    print("\n" + "-" * 70)
    print(f"[TEST 1] Running Bootstrap Tests (trials={test1_trials} per k)...")
    print("-" * 70)
    k_values_t1 = [2, 4, 6, 8, 10, 12, 14, 16, 18, 20]
    for k in k_values_t1:
        t0 = time.time()
        success_pct = run_bootstrap_test(k, test1_trials)
        dt = time.time() - t0
        append_to_csv(file_t1, {
            'k': k,
            'trials': test1_trials,
            'success_rate': success_pct,
            'timestamp': time.time()
        })
        print(f"  k={k:2d} | Nodes={k+1:2d} | Trials={test1_trials:3d} | Success={success_pct:5.1f}% | Time={dt:.2f}s")
        
    # -------------------------------------------------------------
    # Test 2: Scalability under Sequential Churn vs. g and k
    # -------------------------------------------------------------
    print("\n" + "-" * 70)
    print(f"[TEST 2] Running Sequential Churn Tests (trials={test2_trials} per config)...")
    print("-" * 70)
    k_values_t2 = [4, 6, 8, 10]
    g_values = [5, 10, 20, 30, 40, 50, 75, 100]
    for k in k_values_t2:
        for g in g_values:
            t0 = time.time()
            success_pct = run_sequential_churn_test(k, g, test2_trials)
            dt = time.time() - t0
            append_to_csv(file_t2, {
                'k': k,
                'g': g,
                'trials': test2_trials,
                'success_rate': success_pct,
                'timestamp': time.time()
            })
            print(f"  k={k:2d} | Sequential Joins (g)={g:3d} | Success={success_pct:5.1f}% | Time={dt:.2f}s")
            
    # -------------------------------------------------------------
    # Test 3: Concurrency and Vulnerability under Burst Joins (s)
    # -------------------------------------------------------------
    print("\n" + "-" * 70)
    print(f"[TEST 3] Running Concurrent Burst Tests (trials={test3_trials} per config)...")
    print("-" * 70)
    k_values_t3 = [4, 6, 8, 10]
    s_values = [1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30]
    for k in k_values_t3:
        for s in s_values:
            t0 = time.time()
            success_pct = run_concurrent_burst_test(k, s, test3_trials)
            dt = time.time() - t0
            append_to_csv(file_t3, {
                'k': k,
                's': s,
                'trials': test3_trials,
                'success_rate': success_pct,
                'timestamp': time.time()
            })
            print(f"  k={k:2d} | Concurrent Burst (s)={s:2d} | Success={success_pct:5.1f}% | Time={dt:.2f}s")
            
    # -------------------------------------------------------------
    # Plot Figures
    # -------------------------------------------------------------
    print("\n" + "-" * 70)
    print("[PLOTTING] Generating LLNCS publication figures...")
    print("-" * 70)
    plot_test1(member_name)
    plot_test2(member_name)
    plot_test3(member_name)
    
    # -------------------------------------------------------------
    # Sync Figures to LaTeX Directory
    # -------------------------------------------------------------
    latex_dir = os.path.abspath(os.path.join(
        os.path.dirname(__file__),
        "../document/finding_nemo/Latex-Template-for-Springer"
    ))
    
    generated_figures = [
        "test1_bootstrap.pdf",
        "test2_success_vs_g.pdf",
        "test2_success_vs_k.pdf",
        "test2_bubble_k_vs_g.pdf",
        "test3_bubble_k_vs_s.pdf",
        "test3_success_vs_s.pdf"
    ]
    
    if os.path.exists(latex_dir):
        print("\n" + "-" * 70)
        print(f"[SYNC] Copying figures to LaTeX directory: {latex_dir}")
        print("-" * 70)
        for fig in generated_figures:
            if os.path.exists(fig):
                dst = os.path.join(latex_dir, fig)
                shutil.copy2(fig, dst)
                print(f"  Copied {fig} -> {dst}")
    else:
        print(f"\nLaTeX directory not found at {latex_dir}; figures remain in current directory.")
        
    total_elapsed = time.time() - start_time
    print("\n" + "=" * 70)
    print(f"EVALUATION SUITE COMPLETE! Total elapsed time: {total_elapsed:.2f}s")
    print("=" * 70)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Automated Evaluation Suite for FINDING NEMO")
    parser.add_argument('--member-name', type=str, default="results", help="Prefix for output CSV files")
    parser.add_argument('--test1-trials', type=int, default=50, help="Trials per k in Test 1")
    parser.add_argument('--test2-trials', type=int, default=20, help="Trials per (k, g) in Test 2")
    parser.add_argument('--test3-trials', type=int, default=30, help="Trials per (k, s) in Test 3")
    parser.add_argument('--keep-previous', action='store_true', help="Do not delete existing CSVs before run")
    
    args = parser.parse_args()
    run_evaluation_suite(
        member_name=args.member_name,
        test1_trials=args.test1_trials,
        test2_trials=args.test2_trials,
        test3_trials=args.test3_trials,
        clean=not args.keep_previous
    )

