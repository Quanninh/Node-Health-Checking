import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Optional

SCRIPT_DIR = Path(__file__).resolve().parent
EVALUATION_SCRIPT = SCRIPT_DIR / "evaluation.py"

DELAY_BETWEEN_RUNS_SECONDS = 5
CHILD_SHUTDOWN_TIMEOUT_SECONDS = 20


def timestamp() -> str:
    return datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S %z")


def stop_child(process: Optional[subprocess.Popen]) -> None:
    if process is None or process.poll() is not None:
        return

    # Ctrl+C is normally delivered to both this runner and evaluation.py.
    # Give evaluation.py time to execute its cleanup/finally block first.
    try:
        process.wait(timeout=CHILD_SHUTDOWN_TIMEOUT_SECONDS)
        return
    except subprocess.TimeoutExpired:
        pass

    print(
        f"[{timestamp()}] evaluation.py did not stop in time; terminating it...",
        flush=True,
    )
    try:
        process.terminate()
        process.wait(timeout=5)
        return
    except (OSError, subprocess.TimeoutExpired):
        pass

    print(f"[{timestamp()}] Force-killing evaluation.py...", flush=True)
    try:
        process.kill()
        process.wait(timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        pass


def main() -> int:
    if not EVALUATION_SCRIPT.exists():
        print(f"Could not find: {EVALUATION_SCRIPT}", file=sys.stderr)
        return 1

    run_number = 0
    current_process: Optional[subprocess.Popen] = None

    print(
        f"[{timestamp()}] Repeated evaluation runner started.\n"
        "Press Ctrl+C at any time to stop.",
        flush=True,
    )

    try:
        while True:
            run_number += 1

            print(
                "\n"
                + "=" * 70
                + f"\n[{timestamp()}] STARTING EVALUATION RUN #{run_number}"
                + "\n"
                + "=" * 70,
                flush=True,
            )

            started_at = time.monotonic()

            # sys.executable uses the same Python installation that started
            # this runner. Running `py run_evaluations.py` therefore has the
            # same effect as repeatedly running `py evaluation.py`.
            current_process = subprocess.Popen(
                [sys.executable, str(EVALUATION_SCRIPT)],
                cwd=SCRIPT_DIR,
            )

            exit_code = current_process.wait()
            current_process = None

            elapsed_seconds = time.monotonic() - started_at

            print(
                "\n"
                + "-" * 70
                + (
                    f"\n[{timestamp()}] EVALUATION RUN #{run_number} FINISHED "
                    f"with exit code {exit_code} after {elapsed_seconds:.2f}s"
                )
                + "\n"
                + "-" * 70,
                flush=True,
            )

            # evaluation.py uses exit code 130 when it receives Ctrl+C.
            if exit_code == 130:
                print(
                    f"[{timestamp()}] The evaluation was interrupted; stopping the runner.",
                    flush=True,
                )
                break

            print(
                f"[{timestamp()}] Waiting {DELAY_BETWEEN_RUNS_SECONDS}s "
                "before the next run...",
                flush=True,
            )
            time.sleep(DELAY_BETWEEN_RUNS_SECONDS)

    except KeyboardInterrupt:
        print(
            f"\n[{timestamp()}] Ctrl+C detected; stopping after {run_number} run(s)...",
            flush=True,
        )
        stop_child(current_process)

    finally:
        stop_child(current_process)

    print(f"[{timestamp()}] Repeated evaluation runner stopped.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
