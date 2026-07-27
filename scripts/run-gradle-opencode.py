from __future__ import annotations

import base64
import json
import shutil
import subprocess
import sys
from pathlib import Path


PROTOCOL_ERROR = 64
ENVIRONMENT_ERROR = 78

POWERSHELL_BRIDGE = r"""
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'PowerShell 7 or newer is required by qz-gradle-opencode/v1'
}
$payloadJson = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__PAYLOAD__'))
$payload = $payloadJson | ConvertFrom-Json
$parameters = @{}
if ($payload.command -eq 'self-test') {
    $parameters.SelfTest = $true
} else {
    $parameters.Action = [string]$payload.action
    if ($null -ne $payload.gradleArgs) {
        $parameters.GradleArgs = [string[]]@($payload.gradleArgs)
    }
    if ($null -ne $payload.runId) {
        $parameters.RunId = [string]$payload.runId
    }
    if ($null -ne $payload.waitSeconds) {
        $parameters.WaitSeconds = [int]$payload.waitSeconds
    }
}
& ([string]$payload.scriptPath) @parameters
if ($null -eq $LASTEXITCODE) { exit 0 }
exit [int]$LASTEXITCODE
"""


def usage() -> int:
    print(
        "usage:\n"
        "  python scripts/run-gradle-opencode.py start <gradle-args...>\n"
        "  python scripts/run-gradle-opencode.py poll <run-id>\n"
        "  python scripts/run-gradle-opencode.py wait <run-id> [--seconds N]\n"
        "  python scripts/run-gradle-opencode.py self-test",
        file=sys.stderr,
    )
    return PROTOCOL_ERROR


def parse(argv: list[str], script_path: Path) -> tuple[dict[str, object], int] | None:
    if not argv:
        return None
    command = argv[0].lower()
    payload: dict[str, object] = {"command": command, "scriptPath": str(script_path)}
    timeout = 300

    if command == "start" and len(argv) > 1:
        payload.update(action="Start", gradleArgs=argv[1:])
    elif command == "poll" and len(argv) == 2:
        payload.update(action="Poll", runId=argv[1])
    elif command == "wait" and len(argv) in {2, 4}:
        seconds = 30
        if len(argv) == 4:
            if argv[2] != "--seconds":
                return None
            try:
                seconds = int(argv[3])
            except ValueError:
                return None
            if seconds < 1 or seconds > 240:
                return None
        payload.update(action="Wait", runId=argv[1], waitSeconds=seconds)
        timeout = seconds + 60
    elif command == "self-test" and len(argv) == 1:
        pass
    else:
        return None
    return payload, timeout


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    script_path = root / "scripts" / "run-gradle-opencode.ps1"
    parsed = parse(sys.argv[1:], script_path)
    if parsed is None:
        return usage()
    payload, timeout = parsed

    pwsh = shutil.which("pwsh")
    if not pwsh:
        print("PowerShell 7 executable not found for qz-gradle-opencode/v1", file=sys.stderr)
        return ENVIRONMENT_ERROR

    payload_text = json.dumps(payload, ensure_ascii=True, separators=(",", ":"))
    payload_base64 = base64.b64encode(payload_text.encode("utf-8")).decode("ascii")
    bridge = POWERSHELL_BRIDGE.replace("__PAYLOAD__", payload_base64)
    encoded = base64.b64encode(bridge.encode("utf-16-le")).decode("ascii")
    command = [pwsh, "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded]
    try:
        process = subprocess.Popen(command, cwd=root, shell=False)
    except FileNotFoundError as error:
        print(f"Python adapter failed: {error}", file=sys.stderr)
        return ENVIRONMENT_ERROR
    try:
        return process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        # Do not kill the protocol bridge; it may still be closing its lock safely.
        print(f"Python adapter timed out after {timeout} seconds; process was not killed", file=sys.stderr)
        return ENVIRONMENT_ERROR


if __name__ == "__main__":
    raise SystemExit(main())
