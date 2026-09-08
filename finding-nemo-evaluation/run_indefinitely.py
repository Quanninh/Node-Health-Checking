import time
from config import EvaluationConfig
from test_runner import NemoTestRunner

def main():
    print("Starting continuous evaluation loop. Press Ctrl+C to stop.")
    config = EvaluationConfig()
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
        except KeyboardInterrupt:
            print("\nContinuous evaluation stopped by user.")
            break
        except Exception as e:
            print(f"Error during iteration {iteration}: {e}")
            time.sleep(5)

if __name__ == "__main__":
    main()

