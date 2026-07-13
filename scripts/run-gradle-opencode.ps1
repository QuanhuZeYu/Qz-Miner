# qz-gradle-opencode/v1：OpenCode 在 Windows 上有界启动与观察 Gradle 的唯一入口。
[CmdletBinding(DefaultParameterSetName = 'Protocol')]
param(
  [Parameter(ParameterSetName = 'Protocol', Mandatory = $true)][string]$Action,
  [Parameter(ParameterSetName = 'Protocol')][string]$RunId,
  [Parameter(ParameterSetName = 'Protocol')][string[]]$GradleArgs = @(),
  [Parameter(ParameterSetName = 'Protocol')][int]$ExecutionTimeoutSeconds = 900,
  [Parameter(ParameterSetName = 'Protocol')][int]$WaitSeconds = 30,
  [Parameter(ParameterSetName = 'Protocol')][int]$PollIntervalSeconds = 2,
  [Parameter(ParameterSetName = 'SelfTest', Mandatory = $true)][switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$script:Protocol = 'qz-gradle-opencode/v1'
$script:Root = Split-Path -Parent $PSScriptRoot
$script:RuntimeRoot = Join-Path ([IO.Path]::GetTempPath()) 'opencode'
$script:WrapperPath = Join-Path $script:Root 'gradlew.bat'
$script:EnvironmentCheck = $true
$script:AfterStartFault = $null

function Set-RuntimePaths {
  $script:LockPath = Join-Path $script:RuntimeRoot 'qz-gradle-opencode-v1.active.lock'
  $script:GuardPath = Join-Path $script:RuntimeRoot 'qz-gradle-opencode-v1.mutation.guard'
}
Set-RuntimePaths

function Write-Result([hashtable]$Value) {
  $Value.protocol = $script:Protocol
  foreach ($name in @('runId', 'reasonCode', 'gradleExitCode')) {
    if (-not $Value.ContainsKey($name)) { $Value[$name] = $null }
  }
  [Console]::Out.WriteLine(($Value | ConvertTo-Json -Compress -Depth 6))
}
function Fail([string]$Kind, [string]$Reason, [string]$Id = $null) {
  throw [InvalidOperationException]::new("$Kind|$Reason|$Id")
}
function Paths([string]$Id) {
  $prefix = Join-Path $script:RuntimeRoot "qz-gradle-v1-$Id"
  @{
    Metadata = "$prefix.metadata.json"; Launcher = "$prefix.launcher.cmd"
    Stdout = "$prefix.stdout.log"; Stderr = "$prefix.stderr.log"
    Exit = "$prefix.exit.json"; ExitPending = "$prefix.exit.json.pending"
    ExitWriting = "$prefix.exit.json.writing"
  }
}
function Assert-Id([string]$Id) {
  if ([string]::IsNullOrWhiteSpace($Id) -or $Id -cnotmatch '^[a-f0-9]{32}$') { Fail PARAM INVALID_RUN_ID $Id }
}
function Assert-Environment {
  if (-not $script:EnvironmentCheck) { return }
  $item = Get-Item -LiteralPath 'Env:GRADLE_USER_HOME' -ErrorAction SilentlyContinue
  if (-not $item -or [string]::IsNullOrWhiteSpace($item.Value) -or
      -not [IO.Path]::IsPathRooted($item.Value) -or
      $item.Value -cmatch '[^\x00-\x7F]' -or
      -not (Test-Path -LiteralPath $item.Value -PathType Container)) { Fail ENV ENVIRONMENT_UNAVAILABLE }
}
function Assert-Args([string[]]$GradleArguments) {
  $tasks = @('compileJava', 'test', 'check', 'build', 'publishToMavenLocal')
  $noValue = @('--offline', '--no-configuration-cache')
  $testTaskSelected = $false
  $needsFilter = $false
  if (-not $GradleArguments.Count) { Fail PARAM EMPTY_GRADLE_ARGS }
  foreach ($arg in $GradleArguments) {
    if ($null -eq $arg -or $arg -cmatch '[\x00-\x1F\x7F]') { Fail PARAM INVALID_GRADLE_ARGUMENT }
    if ($needsFilter) {
      if ($arg -cnotmatch '^[A-Za-z_$][A-Za-z0-9_.$]*(?:\*|[A-Za-z0-9_$])*?$') { Fail PARAM INVALID_TEST_FILTER }
      $needsFilter = $false
      continue
    }
    if ($arg -ceq '--tests') {
      if (-not $testTaskSelected) { Fail PARAM TEST_FILTER_WITHOUT_TEST_TASK }
      $needsFilter = $true
      continue
    }
    if ($noValue -ccontains $arg) { continue }
    if ($arg -cmatch '^-Pgtnh\.settings\.blowdryerTag=[A-Za-z0-9._-]*$') { continue }
    if ($arg.StartsWith('-', [StringComparison]::Ordinal)) { Fail PARAM OPTION_NOT_ALLOWLISTED }
    $leaf = ($arg -csplit ':')[-1]
    if ($arg -cnotmatch '^(?::[A-Za-z0-9_.-]+)*:[A-Za-z0-9_.-]+$|^[A-Za-z0-9_.-]+$' -or
        -not ($tasks -ccontains $leaf)) { Fail PARAM TASK_NOT_ALLOWLISTED }
    if ($leaf -ceq 'test') { $testTaskSelected = $true }
  }
  if ($needsFilter) { Fail PARAM MISSING_TEST_FILTER }
}
function Guard([scriptblock]$Body) {
  [IO.Directory]::CreateDirectory($script:RuntimeRoot) | Out-Null
  $stream = $null
  for ($i = 0; $i -lt 500; $i++) {
    try {
      $stream = [IO.File]::Open($script:GuardPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
      break
    } catch [IO.IOException] { Start-Sleep -Milliseconds 10 }
  }
  if (-not $stream) { Fail INTERNAL GUARD_UNAVAILABLE }
  try { return & $Body } finally { $stream.Dispose() }
}
function Read-Json([string]$Path) {
  try { [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8) | ConvertFrom-Json } catch { $null }
}
function Has-Identity($Value) {
  $Value -and $Value.protocol -is [string] -and $Value.runId -is [string] -and
    $Value.invocationId -is [string] -and $Value.protocol -ceq $script:Protocol -and
    $Value.runId -cmatch '^[a-f0-9]{32}$' -and $Value.invocationId -cmatch '^[a-f0-9]{32}$'
}
function Token($Value) {
  if (Has-Identity $Value) { "$($Value.protocol)|$($Value.runId)|$($Value.invocationId)" } else { $null }
}
function RepoDigest {
  $sha = [Security.Cryptography.SHA256]::Create()
  try {
    ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(
      ([IO.Path]::GetFullPath($script:Root).ToLowerInvariant()))))).Replace('-', '').ToLowerInvariant()
  } finally { $sha.Dispose() }
}
function Write-FlushedFile([string]$Path, [string]$Text, [Text.Encoding]$Encoding) {
  $bytes = $Encoding.GetBytes($Text)
  $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
  try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
}
function Atomic-Json([string]$Path, $Value) {
  $temp = "$Path.$([Guid]::NewGuid().ToString('N')).tmp"
  $backup = "$Path.$([Guid]::NewGuid().ToString('N')).bak"
  try {
    Write-FlushedFile $temp ($Value | ConvertTo-Json -Compress) ([Text.Encoding]::UTF8)
    if (Test-Path -LiteralPath $Path) {
      [IO.File]::Replace($temp, $Path, $backup)
      if (Test-Path -LiteralPath $backup) { [IO.File]::Delete($backup) }
    } else { [IO.File]::Move($temp, $Path) }
  } finally { if (Test-Path -LiteralPath $temp) { [IO.File]::Delete($temp) } }
}
function Read-Terminal([string]$Path, $Metadata) {
  if (-not (Test-Path -LiteralPath $Path)) { return @{ kind = 'NONE' } }
  $sentinel = Read-Json $Path
  if (-not $sentinel) { return @{ kind = 'CORRUPT' } }
  if (-not (Has-Identity $Metadata) -or -not (Has-Identity $sentinel)) { return @{ kind = 'STALE_IDENTITY' } }
  if ((Token $sentinel) -cne (Token $Metadata)) { return @{ kind = 'STALE_IDENTITY' } }
  if ($sentinel.exitCode -isnot [int] -and $sentinel.exitCode -isnot [long]) { return @{ kind = 'CORRUPT' } }
  $value = [long]$sentinel.exitCode
  if ($value -lt [int]::MinValue -or $value -gt [int]::MaxValue) { return @{ kind = 'CORRUPT' } }
  $code = [int]$value
  @{ kind = 'VALID'; code = $code; sentinel = $sentinel; path = $Path }
}
function Has-ValidMetadataFields($Metadata) {
  if ($Metadata.phase -isnot [string] -or
      ($Metadata.deadlineUtc -isnot [string] -and $Metadata.deadlineUtc -isnot [DateTime])) { return $false }
  $deadline = [DateTime]::MinValue
  if ($Metadata.deadlineUtc -is [DateTime]) { $deadline = $Metadata.deadlineUtc }
  elseif (-not [DateTime]::TryParse($Metadata.deadlineUtc, [ref]$deadline)) { return $false }
  if ($Metadata.phase -ceq 'PREPARED') { return $true }
  if ($Metadata.phase -cne 'RUNNING') { return $false }
  if (($Metadata.pid -isnot [int] -and $Metadata.pid -isnot [long]) -or
      ($Metadata.processStartTicks -isnot [int] -and $Metadata.processStartTicks -isnot [long])) { return $false }
  $processId = [long]$Metadata.pid; $ticks = [long]$Metadata.processStartTicks
  $processId -gt 0 -and $processId -le [int]::MaxValue -and $ticks -gt 0
}
function Release-InGuard($Identity) {
  if (-not (Test-Path -LiteralPath $script:LockPath)) { return }
  $current = Read-Json $script:LockPath
  if ((Token $current) -and (Token $current) -ceq (Token $Identity)) { [IO.File]::Delete($script:LockPath) }
}
function Release($Identity) { $ignored = Guard { Release-InGuard $Identity } }
function Terminal-Result([string]$Id, $Paths, $Terminal) {
  $code = $Terminal.code
  @{ status = $(if ($code -eq 0) { 'SUCCEEDED' } else { 'FAILED' }); runId = $Id
    reasonCode = 'GRADLE_TERMINAL'; gradleExitCode = $code; protocolExitCode = $code
    stdout = $Paths.Stdout; stderr = $Paths.Stderr }
}
function Acquire($Owner, $Paths) {
  $result = Guard {
    if (Test-Path -LiteralPath $script:LockPath) {
      $old = Read-Json $script:LockPath
      if (-not (Has-Identity $old)) { Fail LOCK LOCK_CORRUPT }
      $oldPaths = Paths $old.runId
      $oldMetadata = Read-Json $oldPaths.Metadata
      $terminal = Read-Terminal $oldPaths.Exit $oldMetadata
      if ((Token $old) -ceq (Token $oldMetadata) -and $terminal.kind -ceq 'VALID') {
        [IO.File]::Delete($script:LockPath)
      } else { Fail LOCK LOCK_HELD $old.runId }
    }
    foreach ($path in $Paths.Values) { if (Test-Path -LiteralPath $path) { Fail LOCK RUN_ARTIFACT_EXISTS $Owner.runId } }
    $temp = "$($script:LockPath).$([Guid]::NewGuid().ToString('N')).tmp"
    try {
      Write-FlushedFile $temp ($Owner | ConvertTo-Json -Compress) ([Text.Encoding]::UTF8)
      [IO.File]::Move($temp, $script:LockPath)
    } finally { if (Test-Path -LiteralPath $temp) { [IO.File]::Delete($temp) } }
  }
}
function Test-LauncherAlive($Metadata) {
  if ($Metadata.phase -cne 'RUNNING' -or $null -eq $Metadata.pid -or $null -eq $Metadata.processStartTicks) { return $false }
  $process = Get-Process -Id ([int]$Metadata.pid) -ErrorAction SilentlyContinue
  if (-not $process) { return $false }
  try { $process.StartTime.ToUniversalTime().Ticks -eq [long]$Metadata.processStartTicks } catch { $false }
}
function Incomplete([string]$Id, [string]$Reason, $Active = $null) {
  @{ status = 'INCOMPLETE'; runId = $Id; activeRunId = $Active; reasonCode = $Reason; incomplete = $true; protocolExitCode = 78 }
}
function State([string]$Id) {
  Assert-Id $Id
  $paths = Paths $Id
  $metadata = Read-Json $paths.Metadata
  if (-not $metadata) {
    $active = Guard { if (Test-Path -LiteralPath $script:LockPath) { Read-Json $script:LockPath } }
    return Incomplete $Id 'METADATA_MISSING_OR_CORRUPT' $(if ($active) { $active.runId } else { $null })
  }
  if (-not (Has-Identity $metadata) -or $metadata.runId -cne $Id) { return Incomplete $Id 'METADATA_IDENTITY_MISMATCH' }
  if (-not (Has-ValidMetadataFields $metadata)) { return Incomplete $Id 'METADATA_FIELDS_INVALID' }

  # terminal-first：历史 Run 的有效终态不依赖当前 active lock，因而可安全重复 Poll。
  $terminal = Read-Terminal $paths.Exit $metadata
  if ($terminal.kind -ceq 'VALID') {
    $released = Guard { Release-InGuard $metadata }
    return Terminal-Result $Id $paths $terminal
  }
  if ($terminal.kind -ceq 'CORRUPT') { return Incomplete $Id 'CORRUPT_FINALIZATION' }

  $guarded = Guard {
    $lock = if (Test-Path -LiteralPath $script:LockPath) { Read-Json $script:LockPath } else { $null }
    if (-not (Has-Identity $lock) -or (Token $lock) -cne (Token $metadata)) {
      return Incomplete $Id 'ACTIVE_LOCK_IDENTITY_MISMATCH' $(if ($lock) { $lock.runId } else { $null })
    }
    $canonical = Read-Terminal $paths.Exit $metadata
    if ($canonical.kind -ceq 'VALID') { Release-InGuard $metadata; return Terminal-Result $Id $paths $canonical }
    if ($canonical.kind -ceq 'CORRUPT') { return Incomplete $Id 'CORRUPT_FINALIZATION' $lock.runId }
    if ($metadata.phase -ceq 'PREPARED') {
      return @{ status = 'STARTED_UNCONFIRMED'; runId = $Id; activeRunId = $lock.runId
        reasonCode = 'PREPARED_WITHOUT_FINALIZATION'; incomplete = $true; protocolExitCode = 78 }
    }
    if ($metadata.phase -cne 'RUNNING') { return Incomplete $Id 'METADATA_PHASE_INVALID' $lock.runId }
    if (Test-LauncherAlive $metadata) {
      if ([DateTime]::UtcNow -gt [DateTime]::Parse($metadata.deadlineUtc)) {
        return @{ status = 'TIMED_OUT_ACTIVE'; runId = $Id; reasonCode = 'EXECUTION_TIMEOUT'; incomplete = $true; protocolExitCode = 124 }
      }
      return @{ status = 'RUNNING'; runId = $Id; reasonCode = 'PROCESS_ACTIVE'; protocolExitCode = 3 }
    }

    # launcher 已确认退出后才读取 pending；.writing 永远不是读入口。
    $pending = Read-Terminal $paths.ExitPending $metadata
    if ($pending.kind -ceq 'VALID') {
      try { [IO.File]::Move($paths.ExitPending, $paths.Exit) } catch { }
      $promoted = Read-Terminal $paths.Exit $metadata
      if ($promoted.kind -ceq 'VALID') { Release-InGuard $metadata; return Terminal-Result $Id $paths $promoted }
    }
    return Incomplete $Id 'FINALIZATION_INCOMPLETE' $lock.runId
  }
  return $guarded
}
function Get-TaskSummary([string[]]$GradleArguments) {
  $tasks = @(); $booleans = @(); $argumentCount = $GradleArguments.Count; $skip = $false
  foreach ($arg in $GradleArguments) {
    if ($skip) { $skip = $false; continue }
    if ($arg -ceq '--tests') { $skip = $true; continue }
    if ($arg -ceq '--offline' -or $arg -ceq '--no-configuration-cache') { $booleans += $arg; continue }
    if (-not $arg.StartsWith('-', [StringComparison]::Ordinal)) { $tasks += ($arg -csplit ':')[-1] }
  }
  [ordered]@{ tasks = $tasks; argumentCount = $argumentCount; booleanOptions = $booleans }
}
function Start-Run {
  Assert-Environment
  Assert-Args $GradleArgs
  if ($RunId) { Fail PARAM START_RUN_ID_FORBIDDEN $RunId }
  $id = [Guid]::NewGuid().ToString('N'); $invocation = [Guid]::NewGuid().ToString('N')
  $paths = Paths $id; $now = [DateTime]::UtcNow
  $owner = [ordered]@{ protocol = $script:Protocol; runId = $id; invocationId = $invocation; repoRootDigest = RepoDigest }
  Acquire $owner $paths
  $launchAttempted = $false
  try {
    if (-not (Test-Path -LiteralPath $script:WrapperPath -PathType Leaf)) { Fail INTERNAL WRAPPER_MISSING $id }
    $metadata = [ordered]@{ protocol = $script:Protocol; runId = $id; invocationId = $invocation; phase = 'PREPARED'
      startedAtUtc = $now.ToString('o'); deadlineUtc = $now.AddSeconds($ExecutionTimeoutSeconds).ToString('o')
      taskSummary = Get-TaskSummary $GradleArgs }
    Atomic-Json $paths.Metadata $metadata
    $encoded = @($GradleArgs | ForEach-Object { '"{0}"' -f $_ })
    $command = (@('call', ('"{0}"' -f $script:WrapperPath)) + $encoded +
      @('--console=plain', ('1>"{0}"' -f $paths.Stdout), ('2>"{0}"' -f $paths.Stderr))) -join ' '
    $json = ('{"protocol":"' + $script:Protocol + '","runId":"' + $id + '","invocationId":"' + $invocation + '","exitCode":%1}').Replace('"', '^"')
    [IO.File]::WriteAllLines($paths.Launcher, @('@echo off', $command, 'call :done %ERRORLEVEL%', 'exit /b', ':done',
      ('>"{0}" echo {1}' -f $paths.ExitWriting, $json), ('move /y "{0}" "{1}" >nul' -f $paths.ExitWriting, $paths.ExitPending),
      ('move /y "{0}" "{1}" >nul' -f $paths.ExitPending, $paths.Exit), ('del /q "{0}"' -f $paths.Launcher), 'exit /b %1'), [Text.Encoding]::ASCII)
    $launchAttempted = $true
    $process = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d', '/q', '/v:off', '/c', ('"{0}"' -f $paths.Launcher)) -WorkingDirectory $script:Root -WindowStyle Hidden -PassThru
    if ($script:AfterStartFault) { & $script:AfterStartFault }
    $metadata.phase = 'RUNNING'; $metadata.pid = $process.Id; $metadata.processStartTicks = $process.StartTime.ToUniversalTime().Ticks
    Atomic-Json $paths.Metadata $metadata
    @{ status = 'STARTING'; runId = $id; reasonCode = 'PROCESS_STARTED'; protocolExitCode = 0 }
  } catch {
    if ($launchAttempted) { Fail POSTSTART STARTED_UNCONFIRMED $id }
    Release $owner
    foreach ($path in @($paths.Launcher, $paths.Metadata)) { if (Test-Path -LiteralPath $path) { [IO.File]::Delete($path) } }
    throw
  }
}
function Wait-Run([string]$Id, [int]$Seconds, [int]$Interval) {
  $until = [DateTime]::UtcNow.AddSeconds($Seconds)
  do {
    $state = State $Id
    if ($state.status -notin @('STARTING', 'RUNNING', 'FINALIZING')) { return $state }
    Start-Sleep -Milliseconds ([Math]::Min($Interval * 1000, 200))
  } while ([DateTime]::UtcNow -lt $until)
  @{ status = 'WAIT_WINDOW_EXPIRED'; runId = $Id; reasonCode = 'WAIT_WINDOW'; incomplete = $true; protocolExitCode = 124 }
}

function Invoke-SelfTest {
  $old = @($script:RuntimeRoot, $script:WrapperPath, $script:EnvironmentCheck, $script:AfterStartFault, $script:ExecutionTimeoutSeconds, $script:Root)
  $root = Join-Path ([IO.Path]::GetTempPath()) ('qz-protocol-' + [Guid]::NewGuid().ToString('N'))
  [IO.Directory]::CreateDirectory($root) | Out-Null
  $script:RuntimeRoot = Join-Path $root 'runtime'; $script:Root = $root; $script:WrapperPath = Join-Path $root 'fake.cmd'
  $script:EnvironmentCheck = $false; $script:ExecutionTimeoutSeconds = 30; Set-RuntimePaths
  $covered = @()
  try {
    $source = [IO.File]::ReadAllText($PSCommandPath)
    $forbiddenNames = @(
      ('QZ_GRADLE_SELFTEST_' + 'TICKET'), ('QZ_GRADLE_SELFTEST_' + 'FIXTURE'),
      ('SkipEnvironment' + 'Check'), ('Wrapper' + 'Override'))
    foreach ($forbidden in $forbiddenNames) {
      if ($source.IndexOf($forbidden, [StringComparison]::Ordinal) -ge 0) { throw "存在环境后门: $forbidden" }
    }
    $covered += 'no-environment-backdoor'
    $bad = @('-Dfoo=x', '-Pfoo=x', '-p', '--project-dir', '-I', '--init-script', '--settings-file', '--include-build', '--dry-run', '-m', '-x', '--exclude-task', 'runClient21', ':x:runServer25', 'CompileJava', 'help', '--tests', 'a/b', '--offline=x', '@args')
    foreach ($arg in $bad) { $rejected = $false; try { Assert-Args @($arg) } catch { $rejected = $true }; if (-not $rejected) { throw "allowlist 未拒绝 $arg" } }
    Assert-Args @('compileJava', 'test', '--offline', '--no-configuration-cache', '--tests', 'a.b.C*', '-Pgtnh.settings.blowdryerTag=beta-1')
    Assert-Args @('compileJava', '-Pgtnh.settings.blowdryerTag=')
    $covered += 'strict-allowlist-and-test-state'
    $script:GradleArgs = @('-Psecret=CANARY'); try { Start-Run | Out-Null } catch { }
    if (Test-Path $script:RuntimeRoot) { if ([IO.Directory]::GetFiles($script:RuntimeRoot).Count) { throw '拒绝参数产生协议产物' } }
    $covered += 'rejected-canary-no-artifacts'

    $cwdMarker = Join-Path $root 'wrapper-cwd.txt'
    [IO.File]::WriteAllLines($script:WrapperPath, @('@echo off', ('cd >"{0}"' -f $cwdMarker), 'ping -n 2 127.0.0.1 >nul', 'exit /b 7'), [Text.Encoding]::ASCII)
    $script:GradleArgs = @('compileJava'); $start = Start-Run
    $final = Wait-Run $start.runId 10 1
    if ($final.gradleExitCode -ne 7) { throw "exit7 未传播: $($final | ConvertTo-Json -Compress)" }
    if ([IO.Path]::GetFullPath(([IO.File]::ReadAllText($cwdMarker).Trim())) -cne [IO.Path]::GetFullPath($root)) { throw 'wrapper 工作目录未绑定仓根' }
    $covered += 'production-exit7-and-repository-cwd'

    function New-Fixture([string]$Phase = 'RUNNING') {
      $id = [Guid]::NewGuid().ToString('N'); $inv = [Guid]::NewGuid().ToString('N'); $paths = Paths $id
      $meta = [ordered]@{ protocol = $script:Protocol; runId = $id; invocationId = $inv; phase = $Phase
        deadlineUtc = [DateTime]::UtcNow.AddMinutes(1).ToString('o'); pid = 999999; processStartTicks = 1 }
      Atomic-Json $paths.Metadata $meta
      @{ id = $id; inv = $inv; paths = $paths; meta = $meta }
    }
    $fixture = New-Fixture; if ((State $fixture.id).status -eq 'RUNNING') { throw '缺锁误报 RUNNING' }
    Atomic-Json $script:LockPath @{ protocol = $script:Protocol; runId = $fixture.id; invocationId = ([Guid]::NewGuid().ToString('N')) }
    if ((State $fixture.id).status -eq 'RUNNING') { throw '错锁误报 RUNNING' }; [IO.File]::Delete($script:LockPath)
    $covered += 'lock-required-and-matched'

    $stale = New-Fixture; Atomic-Json $script:LockPath $stale.meta
    Atomic-Json $stale.paths.Exit @{ protocol = $script:Protocol; runId = $stale.id; invocationId = ([Guid]::NewGuid().ToString('N')); exitCode = 0 }
    if ((State $stale.id).status -eq 'SUCCEEDED' -or -not (Test-Path $script:LockPath)) { throw '旧 exit 身份假绿或释放锁' }
    [IO.File]::Delete($script:LockPath); $covered += 'stale-exit-not-green'

    $oldRun = New-Fixture; Atomic-Json $oldRun.paths.Exit @{ protocol = $script:Protocol; runId = $oldRun.id; invocationId = $oldRun.inv; exitCode = 0 }
    $newOwner = @{ protocol = $script:Protocol; runId = ([Guid]::NewGuid().ToString('N')); invocationId = ([Guid]::NewGuid().ToString('N')) }
    Atomic-Json $script:LockPath $newOwner
    if ((State $oldRun.id).status -ne 'SUCCEEDED' -or (State $oldRun.id).status -ne 'SUCCEEDED') { throw '旧终态不可重复 Poll' }
    if ((Token (Read-Json $script:LockPath)) -cne (Token $newOwner)) { throw '旧终态删除新锁' }; [IO.File]::Delete($script:LockPath)
    $covered += 'terminal-first-repeat-poll-aba-safe'

    foreach ($exitValue in @('bad', '{"protocol":"qz-gradle-opencode/v1","runId":"PLACE","invocationId":"INV","exitCode":"illegal"}')) {
      $late = New-Fixture; Atomic-Json $script:LockPath $late.meta
      if ($exitValue -ceq 'bad') { [IO.File]::WriteAllText($late.paths.Exit, $exitValue) }
      else { Atomic-Json $late.paths.Exit @{ protocol = $script:Protocol; runId = $late.id; invocationId = $late.inv; exitCode = 'illegal' } }
      if ((State $late.id).reasonCode -ne 'CORRUPT_FINALIZATION' -or -not (Test-Path $script:LockPath)) { throw '损坏/非法 exit 未保锁' }
      [IO.File]::Delete($script:LockPath)
    }
    $covered += 'late-corrupt-and-illegal-exit-retain-lock'

    foreach ($exitValue in @('"0"', '0.0', 'true', 'null', '2147483648')) {
      $typed = New-Fixture; Atomic-Json $script:LockPath $typed.meta
      [IO.File]::WriteAllText($typed.paths.Exit, ('{{"protocol":"{0}","runId":"{1}","invocationId":"{2}","exitCode":{3}}}' -f $script:Protocol, $typed.id, $typed.inv, $exitValue))
      $typedState = State $typed.id
      if ($typedState.reasonCode -cne 'CORRUPT_FINALIZATION' -or -not (Test-Path $script:LockPath)) { throw "非法 exitCode $exitValue 未保锁" }
      [IO.File]::Delete($script:LockPath)
    }
    $covered += 'strict-native-integer-exit-code'

    foreach ($field in @('pid', 'processStartTicks', 'deadlineUtc')) {
      $badMeta = New-Fixture; $badMeta.meta[$field] = $(if ($field -ceq 'deadlineUtc') { 123 } else { 'bad' })
      Atomic-Json $badMeta.paths.Metadata $badMeta.meta; Atomic-Json $script:LockPath $badMeta.meta
      $badState = State $badMeta.id
      if ($badState.status -cne 'INCOMPLETE' -or $badState.reasonCode -cne 'METADATA_FIELDS_INVALID' -or -not (Test-Path $script:LockPath)) { throw "metadata $field 坏类型未 INCOMPLETE 保锁" }
      [IO.File]::Delete($script:LockPath)
    }
    $covered += 'invalid-metadata-fields-incomplete'

    $script:AfterStartFault = { throw 'injected-after-launch' }
    $faultId = $null
    try { Start-Run | Out-Null; throw 'launchAttempted 故障未抛出' } catch {
      $faultParts = $_.Exception.Message -split '\|', 3
      if ($faultParts.Count -ne 3 -or $faultParts[0] -cne 'POSTSTART' -or $faultParts[1] -cne 'STARTED_UNCONFIRMED') { throw }
      $faultId = $faultParts[2]
    }
    if (-not $faultId -or -not (Test-Path $script:LockPath)) { throw 'launchAttempted 故障未保锁或缺 runId' }
    $faultPaths = Paths $faultId
    if (-not (Test-Path $faultPaths.Metadata) -or -not (Test-Path $faultPaths.Launcher)) { throw 'launchAttempted 故障删除协议产物' }
    $script:AfterStartFault = $null
    [IO.File]::Delete($script:LockPath)
    $covered += 'launch-attempted-fault-retains-artifacts'

    $pending = New-Fixture; Atomic-Json $script:LockPath $pending.meta
    Atomic-Json $pending.paths.ExitWriting @{ protocol = $script:Protocol; runId = $pending.id; invocationId = $pending.inv; exitCode = 0 }
    if ((State $pending.id).reasonCode -ne 'FINALIZATION_INCOMPLETE') { throw '.writing 被读取' }
    Atomic-Json $pending.paths.ExitPending @{ protocol = $script:Protocol; runId = $pending.id; invocationId = $pending.inv; exitCode = 7 }
    if ((State $pending.id).gradleExitCode -ne 7 -or (Test-Path $script:LockPath)) { throw 'valid pending 未收口' }
    $covered += 'writing-ignored-valid-pending-promoted'

    $prepared = New-Fixture 'PREPARED'; Atomic-Json $script:LockPath $prepared.meta
    if ((State $prepared.id).reasonCode -ne 'PREPARED_WITHOUT_FINALIZATION') { throw 'PREPARED 被推断退出' }; [IO.File]::Delete($script:LockPath)
    $covered += 'prepared-retains-lock'
    $failedState = @{ status = 'FAILED'; runId = $prepared.id; gradleExitCode = 3; protocolExitCode = 3 }
    $originalState = ${function:State}; try { ${function:State} = { param($Id) $failedState }; if ((Wait-Run $prepared.id 2 1).status -ne 'FAILED') { throw 'exit3 Wait 被当作运行中' } } finally { ${function:State} = $originalState }
    $covered += 'wait-exit3-terminal'

    $helper = Join-Path $root 'guard-helper.ps1'; $marker = Join-Path $root 'guard-held'
    [IO.File]::WriteAllText($helper, 'param($Guard,$Marker);$s=[IO.File]::Open($Guard,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);[IO.File]::WriteAllText($Marker,"held");Start-Sleep -Milliseconds 700;$s.Dispose()')
    $child = Start-Process -FilePath 'pwsh' -ArgumentList @('-NoProfile', '-File', $helper, $script:GuardPath, $marker) -PassThru -WindowStyle Hidden
    for ($i = 0; $i -lt 100 -and -not (Test-Path $marker); $i++) { Start-Sleep -Milliseconds 10 }
    $watch = [Diagnostics.Stopwatch]::StartNew(); $guardValue = Guard { 'guard-result' }; $watch.Stop()
    if (-not $child.WaitForExit(30000)) { throw 'guard helper 30 秒内未退出' }
    if ($guardValue -cne 'guard-result' -or $watch.ElapsedMilliseconds -lt 400) { throw '跨进程 guard 未排斥或未返回 body 结果' }
    $covered += 'cross-process-guard-exclusion-and-result'
    @{ status = 'SELF_TEST_SUCCEEDED'; runId = $null; reasonCode = 'ALL_FIXTURES_PASSED'; covered = $covered; protocolExitCode = 0 }
  } finally {
    $script:AfterStartFault = $null
    if (Test-Path $root) { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
    $script:RuntimeRoot = $old[0]; $script:WrapperPath = $old[1]; $script:EnvironmentCheck = $old[2]
    $script:AfterStartFault = $old[3]; $script:ExecutionTimeoutSeconds = $old[4]; $script:Root = $old[5]; Set-RuntimePaths
  }
}
function Main {
  if ($SelfTest) { return Invoke-SelfTest }
  if ($Action -notin @('Start', 'Poll', 'Wait')) { Fail PARAM INVALID_ACTION }
  if ($ExecutionTimeoutSeconds -lt 1 -or $ExecutionTimeoutSeconds -gt 86400 -or
      $WaitSeconds -lt 1 -or $WaitSeconds -gt 240 -or $PollIntervalSeconds -lt 1 -or $PollIntervalSeconds -gt 30) { Fail PARAM INVALID_TIME }
  if ($Action -ceq 'Start') { return Start-Run }
  if (-not $RunId) { Fail PARAM RUN_ID_REQUIRED }
  if ($GradleArgs.Count) { Fail PARAM GRADLE_ARGS_ONLY_FOR_START $RunId }
  if ($Action -ceq 'Poll') { return State $RunId }
  Wait-Run $RunId $WaitSeconds $PollIntervalSeconds
}

$result = $null; $exit = 0
try { $result = Main; $exit = [int]$result.protocolExitCode } catch {
  $parts = $_.Exception.Message -split '\|', 3; $kind = $parts[0]
  $reason = if ($parts.Count -gt 1) { $parts[1] } elseif ($SelfTest) { $_.Exception.Message } else { 'INTERNAL_ERROR' }
  $id = if ($parts.Count -gt 2) { $parts[2] } else { $null }
  $exit = switch ($kind) { PARAM { 64 } LOCK { 75 } ENV { 78 } POSTSTART { 74 } default { 74 } }
  $result = @{ status = $(if ($kind -in @('ENV', 'POSTSTART')) { 'INCOMPLETE' } else { 'ERROR' }); runId = $id
    reasonCode = $reason; gradleExitCode = $null; activeRunId = $(if ($kind -eq 'LOCK') { $id } else { $null }); protocolExitCode = $exit }
}
Write-Result $result
exit $exit
