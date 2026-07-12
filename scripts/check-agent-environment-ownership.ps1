# 环境所有权门禁：守卫现行 agent 指令、文档和脚本，CI workflow 与历史 errors 不在扫描范围。
[CmdletBinding()]
param([switch]$SelfTest)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Test-EnvironmentOwnershipText {
  param([string]$Text, [string]$Source)
  $hits = @()
  # 根规则必须能原样命名禁令；仅从检测输入中剔除明确的禁止性叙述。
  $Text = (($Text -split "`r?`n") | Where-Object { $_ -notmatch '禁止|不得|永不允许|绝不' }) -join "`n"
  $rules = @(
    @{ Name = "PowerShell环境赋值"; Pattern = '(?im)\$env:(GRADLE_USER_HOME|JAVA_HOME)\s*=' },
    @{ Name = "CMD环境赋值"; Pattern = '(?im)^\s*(setx?|set)\s+(GRADLE_USER_HOME|JAVA_HOME)(\s|=)' },
    @{ Name = "POSIX环境注入"; Pattern = '(?im)^\s*(export\s+|env\s+)(GRADLE_USER_HOME|JAVA_HOME)\s*=' },
    @{ Name = "环境API写入"; Pattern = '(?i)SetEnvironmentVariable\s*\(' },
    @{ Name = "注册表环境写入"; Pattern = '(?im)\b(reg\s+(add|delete)|Set-ItemProperty|New-ItemProperty)\b.*\bEnvironment\b' },
    @{ Name = "Gradle环境绕过"; Pattern = '(?i)(-Dgradle\.user\.home|--gradle-user-home|(?:^|\s)-g\s+|-Dorg\.gradle\.java\.home)' },
    @{ Name = "固定盘符环境赋值"; Pattern = '(?im)(GRADLE_USER_HOME|JAVA_HOME)\s*=\s*["'']?[A-Za-z]:[\\/]' }
  )
  foreach ($rule in $rules) {
    if ($Text -match $rule.Pattern) { $hits += "[$($rule.Name)] $Source" }
  }
  return $hits
}

if ($SelfTest) {
  $valid = '只读核验 GRADLE_USER_HOME；缺失时返回 INCOMPLETE。'
  if ((Test-EnvironmentOwnershipText $valid "valid").Count -ne 0) { throw "只读 fixture 被误报" }
  $invalid = @('$env:GRADLE_USER_HOME="X"','setx JAVA_HOME X','export JAVA_HOME=/x','env JAVA_HOME=/x tool','[Environment]::SetEnvironmentVariable("JAVA_HOME","x","Process")','reg add HKCU\Environment','gradlew -Dgradle.user.home=x','gradlew --gradle-user-home x','gradlew -g x','gradlew -Dorg.gradle.java.home=x')
  foreach ($fixture in $invalid) {
    if ((Test-EnvironmentOwnershipText $fixture "fixture").Count -eq 0) { throw "违规 fixture 未阻断: $fixture" }
  }
  # 当前白名单为空；过期或未登记的例外因此与普通写入一样失败，User/Machine 永不允许。
  Write-Host "环境所有权门禁自测通过" -ForegroundColor Green
  exit 0
}

$files = @("AGENTS.md", "CLAUDE.md", "README.md", "README.zh-CN.md")
$files += @(Get-ChildItem (Join-Path $root ".opencode/agents") -Filter *.md -File -ErrorAction SilentlyContinue | ForEach-Object FullName)
$files += @(Get-ChildItem (Join-Path $root "docs/控制律层") -Filter *.md -File -Recurse -ErrorAction SilentlyContinue | ForEach-Object FullName)
$files += @(Get-ChildItem (Join-Path $root "scripts") -Filter *.ps1 -File | Where-Object Name -ne "check-agent-environment-ownership.ps1" | ForEach-Object FullName)
$violations = @()
foreach ($file in $files) {
  $path = if ([IO.Path]::IsPathRooted($file)) { $file } else { Join-Path $root $file }
  if (Test-Path $path) { $violations += Test-EnvironmentOwnershipText (Get-Content $path -Raw) ($path.Substring($root.Length + 1) -replace '\\','/') }
}
foreach ($required in @("AGENTS.md", ".opencode/agents/build.md", ".opencode/agents/fixer.md", ".opencode/agents/reviewer.md")) {
  $text = Get-Content (Join-Path $root $required) -Raw
  if ($text -notmatch '环境所有权' -or $text -notmatch '只读') { $violations += "[缺少正向锚] $required" }
}
if ($violations.Count) { Write-Host "环境所有权门禁失败：" -ForegroundColor Red; $violations | Sort-Object -Unique | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }; exit 1 }
Write-Host "环境所有权门禁通过" -ForegroundColor Green
