$ErrorActionPreference = "Stop"

$baselines = @(
    @{ Manifest = "2.8.4"; GregTech = "5.09.51.482" },
    @{ Manifest = "2.9.0-beta-1"; GregTech = "5.09.52.594" }
)

if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
    throw "GRADLE_USER_HOME must be set"
}
if ($env:GRADLE_USER_HOME -notmatch '^[\x00-\x7F]+$') {
    throw "GRADLE_USER_HOME must contain ASCII characters only"
}

foreach ($baseline in $baselines) {
    $property = "-Pelytra.manifest.version=$($baseline.Manifest)"
    Write-Host "Verifying GTNH $($baseline.Manifest)"

    $insight = & .\gradlew.bat --no-daemon $property dependencyInsight --dependency GT5-Unofficial --configuration compileClasspath
    if ($LASTEXITCODE -ne 0) { throw "dependencyInsight failed for $($baseline.Manifest)" }
    $insight | Write-Host
    if (($insight -join "`n") -notmatch [regex]::Escape($baseline.GregTech)) {
        throw "Expected GT5-Unofficial $($baseline.GregTech) was not resolved"
    }

    & .\gradlew.bat --no-daemon $property clean test compileJava check
    if ($LASTEXITCODE -ne 0) { throw "Gradle verification failed for $($baseline.Manifest)" }

    & .\gradlew.bat --stop
    if ($LASTEXITCODE -ne 0) { throw "Gradle daemon stop failed after $($baseline.Manifest)" }
}
