# 环境所有权门禁：守卫现行 agent 指令、文档和脚本，CI workflow 与历史 errors 不在扫描范围。
[CmdletBinding()]
param([switch]$SelfTest)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Test-EnvironmentOwnershipText {
  param([string]$Text, [string]$Source)
  $hits = @()
  $name = '[A-Za-z_][A-Za-z0-9_]*'
  $rules = @(
    @{ Name = "PowerShell环境写入"; Pattern = "(?im)\`$env:$name\s*(?:\+|-)?=" },
    @{ Name = "PowerShell环境删除"; Pattern = '(?im)\b(?:Remove-Item|Set-Item)\s+(?:-[A-Za-z]+\s+)*(?:["'']?)Env:\\?[A-Za-z_][A-Za-z0-9_]*' },
    @{ Name = "CMD环境写入"; Pattern = ('(?im)(?:^|[&|]\s*|cmd(?:\.exe)?\s+(?:/\w+\s+)*["'']?)\s*set\s+(?:["'']?){0}\s*=' -f $name) },
    @{ Name = "CMD持久环境写入"; Pattern = '(?im)(?:^|[&|]\s*|cmd(?:\.exe)?\s+(?:/\w+\s+)*["'']?)\s*setx(?:\.exe)?\b(?:\s+/i)?\s+["'']?[A-Za-z_][A-Za-z0-9_]*' },
    @{ Name = "POSIX环境写入"; Pattern = ('(?im)(?:^|[;&|]\s*)\s*(?:export\s+|env\s+)(?:["'']?){0}(?:["'']?)\s*=' -f $name) },
    @{ Name = "环境API写入"; Pattern = '(?i)\[\s*(?:System\.)?Environment\s*\]::SetEnvironmentVariable\s*\(' },
    @{ Name = "注册表环境写入"; Pattern = '(?im)\b(?:reg(?:\.exe)?\s+(?:add|delete)|Set-ItemProperty|New-ItemProperty|Remove-ItemProperty)\b[^\r\n]*(?:\\Environment\b|Environment\\)' },
    @{ Name = "环境持久化文件写入"; Pattern = '(?im)(?:Set-Content|Add-Content|Out-File|>>?|tee)\b[^\r\n]*(?:\.env\b|gradle\.properties\b|(?:Microsoft\.)?PowerShell_profile\b|\$PROFILE\b|/(?:etc/)?profile\b|[\\/]\.profile\b)' },
    @{ Name = "Gradle/JDK环境绕过"; Pattern = '(?i)(?:^|\s)(?:-g(?:\s+|=)|--gradle-user-home(?:\s+|=)|-Dgradle\.user\.home(?:\s+|=)|-Dorg\.gradle\.java\.home(?:\s+|=))' },
    @{ Name = "敏感变量回显"; Pattern = '(?im)\b(?:echo|Write-(?:Host|Output))\b[^\r\n]*(?:SECRET|TOKEN|API_KEY)' },
    @{ Name = "环境变量全量枚举"; Pattern = '(?im)(?:Get-ChildItem|dir|ls|gci)\s+(?:-[A-Za-z]+\s+)*Env:\s*(?:$|[|;])|\b(?:env|printenv|set)\s*$' }
  )
  foreach ($rule in $rules) {
    if ($Text -match $rule.Pattern) { $hits += "[$($rule.Name)] $Source" }
  }
  return $hits
}

if ($SelfTest) {
  $valid = @(
    'Get-Item Env:JAVA_HOME',
    'echo $env:PUBLIC_PATH',
    'Test-Path Env:JAVA_HOME',
    'java -version',
    './gradlew.bat -Pfeature=true test',
    'PowerShell 使用 $env:<变量> 写入属于违规示例。',
    'Gradle 的 <短选项-g> <路径> 形式不得用于绕过。'
  )
  foreach ($fixture in $valid) { if ((Test-EnvironmentOwnershipText $fixture "valid").Count) { throw "合法 fixture 被误报: $fixture" } }
  $invalid = @(
    '$env:SECRET = "x"', '$Env:TOKEN+="x"', '$ENV:API_KEY -= "x"', '$env:EMPTY=' ,
    'Remove-Item Env:SECRET', 'Set-Item Env:TOKEN "x"',
    'set SECRET=', 'SET "TOKEN=x"', 'cmd /c "set API_KEY=x"', 'cmd.exe /i /c setx "SECRET" "x"', 'setx /i TOKEN x',
    'export SECRET=x', 'EXPORT "TOKEN"=x', 'env API_KEY=value command', 'env "SECRET"="x" command',
    '[Environment]::SetEnvironmentVariable("A","x","Process")', '[System.Environment]::SetEnvironmentVariable("A","x","User")', '[Environment]::SetEnvironmentVariable("A","x","Machine")',
    'reg add HKCU\Environment /v SECRET /d x', 'Set-ItemProperty HKCU:\Environment SECRET x',
    'Set-Content $PROFILE ''$env:A="x"''', 'Add-Content .env ''A=x''', '''A=x'' | Out-File gradle.properties',
    'gradlew -g C:\cache', 'gradlew -g=C:\cache', 'gradlew --gradle-user-home C:\cache', 'gradlew --gradle-user-home=C:\cache', 'gradlew -Dgradle.user.home=x', 'gradlew -Dorg.gradle.java.home=x',
    'echo $env:SECRET', 'Write-Host $env:TOKEN', 'Get-ChildItem Env:', 'printenv'
  )
  foreach ($fixture in $invalid) { if ((Test-EnvironmentOwnershipText $fixture "fixture").Count -eq 0) { throw "违规 fixture 未阻断: $fixture" } }
  Write-Host "环境所有权门禁自测通过（零例外）" -ForegroundColor Green
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
Write-Host "环境所有权门禁通过（零例外）" -ForegroundColor Green
