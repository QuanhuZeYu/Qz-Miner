from __future__ import annotations

import subprocess
import sys
from pathlib import Path


TIMEOUT_SECONDS = 300
def run(argv: list[str], root: Path) -> int:
    try:
        subprocess.run(argv, cwd=root, check=True, shell=False, timeout=TIMEOUT_SECONDS)
    except FileNotFoundError:
        print(f"executable not found: {argv[0]}", file=sys.stderr)
        return 127
    except subprocess.TimeoutExpired:
        print(f"command timed out after {TIMEOUT_SECONDS} seconds", file=sys.stderr)
        return 124
    except subprocess.CalledProcessError as error:
        return error.returncode
    return 0


def main() -> int:
    argv = sys.argv[1:]
    root = Path(__file__).resolve().parent.parent
    if argv[:1] == ["--"]:
        argv = argv[1:]
    if not argv:
        print("usage: python scripts/run-agent-command.py -- <executable> [args...]", file=sys.stderr)
        return 64

    return run(argv, root)


if __name__ == "__main__":
    raise SystemExit(main())
