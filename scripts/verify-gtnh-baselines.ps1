#requires -Version 7.0
# 维护者可选的本地诊断入口；发布权威是 branch CI matrix 与 tag exact-SHA 门。
# OpenCode agent 不获授权执行本脚本，也不得绕过 qz-gradle-opencode/v1 协议调用 wrapper。
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $repoRoot "gradle/gtnh-baselines.json"
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json

function Assert-ExactProperties {
    param(
        [Parameter(Mandatory)] [object] $Value,
        [Parameter(Mandatory)] [string[]] $Expected,
        [Parameter(Mandatory)] [string] $Context
    )

    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $wanted = @($Expected | Sort-Object)
    if (($actual -join "`n") -cne ($wanted -join "`n")) {
        throw "$Context has unexpected or missing properties"
    }
}

Assert-ExactProperties $manifest @("schemaVersion", "baselines") "Baseline manifest"
if ($manifest.schemaVersion -ne 1) {
    throw "Unsupported baseline manifest schemaVersion"
}
$baselines = @($manifest.baselines)
if ($baselines.Count -ne 2) {
    throw "Baseline manifest must contain exactly two baselines"
}
$seenManifests = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($baseline in $baselines) {
    Assert-ExactProperties $baseline @("manifest", "gregTechVersion") "Baseline"
    if ($baseline.manifest -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' -or
        $baseline.gregTechVersion -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$') {
        throw "Baseline manifest has an unsafe value"
    }
    if (-not $seenManifests.Add([string] $baseline.manifest)) {
        throw "Baseline manifests must be unique"
    }
}

if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
    throw "GRADLE_USER_HOME must be set"
}
if ($env:GRADLE_USER_HOME -notmatch '^[\x00-\x7F]+$') {
    throw "GRADLE_USER_HOME must contain ASCII characters only"
}

foreach ($baseline in $baselines) {
    $manifestProperty = "-Pelytra.manifest.version=$($baseline.manifest)"
    $expectedProperty = "-Pqz.gtnh.expectedGregTechVersion=$($baseline.gregTechVersion)"
    Write-Host "Verifying GTNH $($baseline.manifest)"

    & (Join-Path $repoRoot "gradlew.bat") --no-daemon --rerun-tasks $manifestProperty $expectedProperty verifyGtnhBaseline test compileJava check
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle verification failed for $($baseline.manifest)"
    }
}
