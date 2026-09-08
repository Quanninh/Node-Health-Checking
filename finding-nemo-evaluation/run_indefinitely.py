import argparse
import sys
import time
from pathlib import Path
from config import EvaluationConfig
from test_runner import NemoTestRunner

def parse_args():
    parser = argparse.ArgumentParser(description="Run FINDING NEMO evaluation indefinitely.")
    parser.add_argument("--user", type=str, default="", help="Your name/identifier for the output CSV (e.g., phuc). Avoids git merge conflicts.")
    parser.add_argument("--k-sweep", type=int, nargs="+", help="Custom degrees to test (e.g., 2 4 6 8)")
    parser.add_argument("--g-sweep", type=int, nargs="+", help="Custom added nodes to test (e.g., 6 8 10)")
    parser.add_argument("--s-sweep", type=int, nargs="+", help="Custom burst sizes to test (e.g., 4 6 8)")
    parser.add_argument("--test", action="store_true", help="Run in test mode (only 2 iterations)")
    return parser.parse_args()

def main():
    args = parse_args()
    
    # Configure custom parameters
    config = EvaluationConfig()
    
    if args.user:
        config.results_csv_path = config.results_dir / f"results_{args.user}.csv"
    if args.k_sweep:
        config.k_sweep = args.k_sweep
    if args.g_sweep:
        config.g_sweep = args.g_sweep
    if args.s_sweep:
        config.s_sweep = args.s_sweep
        
    print("Starting continuous evaluation loop. Press Ctrl+C to stop.")
    config = EvaluationConfig()
    print(f"Outputting results to: {config.results_csv_path.name}")
    runner = NemoTestRunner(config, mode="sim")
    
    iteration = 1
    while True:
        print(f"\n--- Batch Iteration {iteration} ---")
        try:
            runner.run_batch()
            runner.plotter.generate_all_plots()
            iteration += 1
            # Run just 2 iterations in automated test mode to make sure it doesn't run forever here
            import sys
            if len(sys.argv) > 1 and sys.argv[1] == "--test":
                if iteration > 2:
                    break
            if args.test and iteration > 2:
                break
        except KeyboardInterrupt:
            print("\nContinuous evaluation stopped by user.")
            break
        except Exception as e:
            print(f"Error during iteration {iteration}: {e}")
            time.sleep(5)

if __name__ == "__main__":
    main()

