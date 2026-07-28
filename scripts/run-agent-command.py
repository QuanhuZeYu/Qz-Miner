from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path


TIMEOUT_SECONDS = 300
REPO_SCRIPTS = {
    "check-agent-environment-ownership": "check-agent-environment-ownership.ps1",
    "check-doc-discipline": "check-doc-discipline.ps1",
    "check-doc-fact-drift": "check-doc-fact-drift.ps1",
}
SELF_TEST_SCRIPTS = {
    "check-agent-environment-ownership",
    "check-doc-fact-drift",
}


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


def run_repo_script(argv: list[str], root: Path) -> int:
    if not argv or argv[0] not in REPO_SCRIPTS:
        print("unknown repository script", file=sys.stderr)
        return 64
    if "--self-test" in argv[1:] and argv[0] not in SELF_TEST_SCRIPTS:
        print("this repository script does not expose --self-test", file=sys.stderr)
        return 64
    if any(arg != "--self-test" for arg in argv[1:]) or argv[1:].count("--self-test") > 1:
        print("repository scripts only accept the optional --self-test flag", file=sys.stderr)
        return 64

    pwsh = shutil.which("pwsh")
    if not pwsh:
        print("PowerShell 7 executable not found for audited repository script", file=sys.stderr)
        return 127
    script = root / "scripts" / REPO_SCRIPTS[argv[0]]
    command = [pwsh, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(script)]
    if argv[1:]:
        command.append("-SelfTest")
    return run(command, root)


def main() -> int:
    argv = sys.argv[1:]
    root = Path(__file__).resolve().parent.parent
    if argv[:1] == ["repo-script"]:
        return run_repo_script(argv[1:], root)
    if argv[:1] == ["--"]:
        argv = argv[1:]
    if not argv:
        print(
            "usage: python scripts/run-agent-command.py -- <executable> [args...]\n"
            "       python scripts/run-agent-command.py repo-script <name> [--self-test]",
            file=sys.stderr,
        )
        return 64

    return run(argv, root)


if __name__ == "__main__":
    raise SystemExit(main())
