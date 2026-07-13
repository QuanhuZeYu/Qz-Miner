#requires -Version 7.0
# qz-control-envelope/v1：冻结写集、误差收敛与审查死区门禁。
[CmdletBinding(DefaultParameterSetName = 'Gate')]
param(
  [Parameter(ParameterSetName = 'Gate', Mandatory)][ValidateSet('PreWrite', 'PostWrite', 'Review')][string]$Action,
  [Parameter(ParameterSetName = 'Gate')][string]$EnvelopePath = '.opencode/control-envelope.json',
  [Parameter(ParameterSetName = 'Gate')][string]$FindingsPath,
  [Parameter(ParameterSetName = 'SelfTest', Mandatory)][switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$script:Protocol = 'qz-control-envelope/v1'
$script:Root = Split-Path -Parent $PSScriptRoot

function New-Result([string]$Status, [string]$Reason, [int]$ExitCode) {
  [ordered]@{ protocol = $script:Protocol; status = $Status; reasonCode = $Reason; exitCode = $ExitCode }
}

function Read-JsonFile([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "FILE_MISSING|$Path" }
  try { [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path)) | ConvertFrom-Json -Depth 20 -NoEnumerate }
  catch { throw "JSON_INVALID|$Path" }
}

function Get-RepoIdentity {
  $top = (& git -C $script:Root rev-parse --show-toplevel 2>$null)
  $head = (& git -C $script:Root rev-parse HEAD 2>$null)
  if ($LASTEXITCODE -ne 0 -or -not $top -or -not $head) { throw 'REPOSITORY_UNAVAILABLE' }
  @{ Root = [IO.Path]::GetFullPath($top.Trim()); Head = $head.Trim().ToLowerInvariant() }
}

function Get-PropertyNames($Value) { @($Value.PSObject.Properties.Name) }
function Is-JsonObject($Value) { $null -ne $Value -and $Value.GetType().FullName -ceq 'System.Management.Automation.PSCustomObject' }
function Has-OnlyProperties($Value, [string[]]$Allowed) {
  if (-not (Is-JsonObject $Value)) { return $false }
  @((Get-PropertyNames $Value) | Where-Object { $_ -notin $Allowed }).Count -eq 0
}
function Has-Text($Value, [string]$Name) {
  $Name -in (Get-PropertyNames $Value) -and $Value.$Name -is [string] -and -not [string]::IsNullOrWhiteSpace($Value.$Name)
}
function Is-StringArray($Value, [string]$Name, [bool]$AllowEmpty = $false) {
  if ($Name -notin (Get-PropertyNames $Value)) { return $false }
  if ($Value.$Name -isnot [array]) { return $false }
  $items = $Value.$Name
  if (-not $AllowEmpty -and $items.Count -eq 0) { return $false }
  @($items | Where-Object { $_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_) }).Count -eq 0
}

function Get-RequiredSensorFailures($Envelope, [ValidateSet('Before','After')][string]$Side) {
  $field = if ($Side -ceq 'Before') { 'beforeStatus' } else { 'afterStatus' }
  @($Envelope.sensors | Where-Object { $_.required -eq $true -and $_.$field -cne 'PASS' }).Count
}

function Get-ErrorVector($Envelope, [ValidateSet('Before','After')][string]$Side) {
  $source = if ($Side -ceq 'Before') { $Envelope.errorBefore } else { $Envelope.errorAfter }
  [int[]]@([int]$source.outOfEnvelope, [int]$source.p0, [int]$source.p1, (Get-RequiredSensorFailures $Envelope $Side))
}

function Test-Envelope($Envelope, [string]$Phase, $Repo = $null) {
  $errors = @()
  if (-not (Is-JsonObject $Envelope)) { return @('ENVELOPE_MUST_BE_OBJECT') }
  $topLevelFields = @(
    'contractId','version','ownerRepo','baselineHead','allowedWrites','protectedPaths','acceptanceIds','riskIds',
    'lineageId','actuationAttempt','maxAttempts','mode','errorBefore','errorAfter','sensors'
  )
  if (-not (Has-OnlyProperties $Envelope $topLevelFields)) { $errors += 'ENVELOPE_UNKNOWN_FIELD' }
  foreach ($name in @('contractId','version','ownerRepo','baselineHead','lineageId','mode')) {
    if (-not (Has-Text $Envelope $name)) { $errors += "FIELD_INVALID:$name" }
  }
  foreach ($name in @('allowedWrites','protectedPaths','acceptanceIds','riskIds')) {
    if (-not (Is-StringArray $Envelope $name ($name -in @('protectedPaths','riskIds')))) { $errors += "FIELD_INVALID:$name" }
  }
  if ($Envelope.version -cne $script:Protocol) { $errors += 'VERSION_UNSUPPORTED' }
  if ($Envelope.mode -notin @('implementation-complete','write-milestone','verification-only')) { $errors += 'MODE_INVALID' }
  if ($Envelope.actuationAttempt -isnot [long]) { $errors += 'ATTEMPT_INVALID' }
  elseif ([int]$Envelope.actuationAttempt -lt 1 -or [int]$Envelope.actuationAttempt -gt 5) { $errors += 'ATTEMPT_OUT_OF_RANGE' }
  if ($Envelope.maxAttempts -isnot [long] -or $Envelope.maxAttempts -ne 5) { $errors += 'MAX_ATTEMPTS_MUST_BE_5' }
  if ($Envelope.baselineHead -isnot [string] -or $Envelope.baselineHead -cnotmatch '^[0-9a-fA-F]{40}$') { $errors += 'BASELINE_INVALID' }
  foreach ($side in @('errorBefore','errorAfter')) {
    if ($side -notin (Get-PropertyNames $Envelope)) { $errors += "FIELD_INVALID:$side"; continue }
    if (-not (Is-JsonObject $Envelope.$side) -or -not (Has-OnlyProperties $Envelope.$side @('outOfEnvelope','p0','p1','requiredSensorFailures'))) {
      $errors += "FIELD_INVALID:$side"; continue
    }
    foreach ($name in @('outOfEnvelope','p0','p1','requiredSensorFailures')) {
      $value = $Envelope.$side.$name
      if ($value -isnot [long] -or $value -lt 0) { $errors += "ERROR_VECTOR_INVALID:$side.$name" }
    }
  }
  if ('sensors' -notin (Get-PropertyNames $Envelope) -or $Envelope.sensors -isnot [array] -or $Envelope.sensors.Count -eq 0) { $errors += 'SENSORS_INVALID' }
  else {
    $ids = @()
    foreach ($sensor in $Envelope.sensors) {
      if (-not (Has-OnlyProperties $sensor @('id','required','beforeStatus','afterStatus')) -or
          -not (Has-Text $sensor 'id') -or $sensor.required -isnot [bool] -or
          $sensor.beforeStatus -notin @('PASS','FAIL','INCOMPLETE') -or $sensor.afterStatus -notin @('PASS','FAIL','INCOMPLETE')) { $errors += 'SENSOR_INVALID' }
      $ids += $sensor.id
    }
    if (@($ids | Sort-Object -Unique).Count -ne $ids.Count) { $errors += 'SENSOR_SET_NOT_UNIQUE' }
  }
  if (-not $Repo) { $Repo = Get-RepoIdentity }
  if (([IO.Path]::GetFullPath($Envelope.ownerRepo)) -cne $Repo.Root) { $errors += 'OWNER_REPO_MISMATCH' }
  if ($Phase -ceq 'PreWrite' -and $Envelope.baselineHead.ToLowerInvariant() -cne $Repo.Head) { $errors += 'BASELINE_HEAD_MISMATCH' }
  $errors
}

function Test-PathMatch([string]$Path, [string]$Rule) {
  $normalizedPath = $Path.Replace('\','/').TrimStart('./')
  $normalizedRule = $Rule.Replace('\','/').TrimStart('./').TrimEnd('/')
  if ($normalizedRule.IndexOfAny([char[]]'*?[') -ge 0) {
    return [System.Management.Automation.WildcardPattern]::new(
      $normalizedRule, [System.Management.Automation.WildcardOptions]::CultureInvariant).IsMatch($normalizedPath)
  }
  $normalizedPath -ceq $normalizedRule -or $normalizedPath.StartsWith("$normalizedRule/", [StringComparison]::Ordinal)
}

function Test-WriteSet($Envelope, [string[]]$ChangedPaths) {
  $errors = @()
  foreach ($path in @($ChangedPaths | Sort-Object -Unique)) {
    if (@($Envelope.protectedPaths | Where-Object { Test-PathMatch $path $_ }).Count) { $errors += "PROTECTED_PATH:$path"; continue }
    if (-not @($Envelope.allowedWrites | Where-Object { Test-PathMatch $path $_ }).Count) { $errors += "OUT_OF_ENVELOPE:$path" }
  }
  $errors
}

function Invoke-GitNull([string[]]$Arguments) {
  $info = [Diagnostics.ProcessStartInfo]::new()
  $info.FileName = 'git'; $info.UseShellExecute = $false; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
  $info.ArgumentList.Add('-C'); $info.ArgumentList.Add($script:Root)
  foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
  $process = [Diagnostics.Process]::Start($info)
  $output = $process.StandardOutput.ReadToEnd(); $null = $process.StandardError.ReadToEnd(); $process.WaitForExit()
  if ($process.ExitCode -ne 0) { throw 'GIT_DIFF_UNAVAILABLE' }
  $output
}

function Add-NameStatusPaths([Collections.Generic.List[string]]$Paths, [string]$Output) {
  $records = $Output -split "`0"
  for ($i = 0; $i -lt $records.Count - 1;) {
    $status = $records[$i++]; if ([string]::IsNullOrWhiteSpace($status)) { continue }
    if ($i -ge $records.Count) { throw 'GIT_DIFF_PARSE_INVALID' }
    $Paths.Add($records[$i++])
    if ($status[0] -in @('R','C')) {
      if ($i -ge $records.Count) { throw 'GIT_DIFF_PARSE_INVALID' }
      $Paths.Add($records[$i++])
    }
  }
}

function Get-ChangedPaths($Envelope) {
  $paths = @()
  $changed = [Collections.Generic.List[string]]::new()
  foreach ($arguments in @(
    @('diff','--name-status','-z','--diff-filter=ACDMRTUXB',"$($Envelope.baselineHead)..HEAD"),
    @('diff','--name-status','-z','--diff-filter=ACDMRTUXB'),
    @('diff','--cached','--name-status','-z','--diff-filter=ACDMRTUXB')
  )) {
    Add-NameStatusPaths $changed (Invoke-GitNull $arguments)
  }
  foreach ($path in ((Invoke-GitNull @('ls-files','--others','--exclude-standard','-z')) -split "`0")) { if ($path) { $changed.Add($path) } }
  @($changed | Where-Object { $_ } | ForEach-Object { $_.Replace('\','/') } | Sort-Object -Unique)
}

function Test-Convergence($Envelope) {
  $before = Get-ErrorVector $Envelope Before
  $after = Get-ErrorVector $Envelope After
  $errors = @()
  if ([int]$Envelope.errorBefore.requiredSensorFailures -ne $before[3] -or [int]$Envelope.errorAfter.requiredSensorFailures -ne $after[3]) {
    $errors += 'SENSOR_ERROR_VECTOR_MISMATCH'
  }
  $decreased = $false
  for ($i = 0; $i -lt 4; $i++) {
    if ($after[$i] -gt $before[$i]) { $errors += "ERROR_COMPONENT_INCREASED:$i" }
    if ($after[$i] -lt $before[$i]) { $decreased = $true }
  }
  if (-not $decreased) { $errors += 'ERROR_VECTOR_NOT_DECREASED' }
  $errors
}

function Test-ReviewFindings($Envelope, $Document) {
  if ($Document -isnot [array]) { return @{ Errors = @('FINDINGS_MUST_BE_ARRAY'); ContractUpgrade = $false } }
  $findings = $Document
  $errors = @(); $upgrade = $false
  foreach ($finding in $findings) {
    if (-not (Is-JsonObject $finding) -or -not (Has-Text $finding 'severity') -or -not (Has-Text $finding 'classification') -or
        -not (Has-Text $finding 'concreteFailure') -or -not (Has-Text $finding 'evidence')) { $errors += 'FINDING_STRUCTURE_INVALID'; continue }
    if ($finding.classification -ceq 'contract-upgrade') {
      if (-not (Has-OnlyProperties $finding @('severity','classification','concreteFailure','evidence')) -or $finding.severity -notin @('P0','P1')) { $errors += 'CONTRACT_UPGRADE_INVALID' }
      else { $upgrade = $true }
      continue
    }
    if ($finding.severity -ceq 'P2') {
      if ($finding.classification -cne 'observation' -or -not (Has-OnlyProperties $finding @('severity','classification','concreteFailure','evidence'))) { $errors += 'P2_FINDING_INVALID' }
      continue
    }
    if ($finding.severity -notin @('P0','P1') -or -not (Has-OnlyProperties $finding @('severity','classification','acceptanceId','riskId','concreteFailure','evidence'))) { $errors += 'FINDING_SEVERITY_INVALID'; continue }
    $linked = (Has-Text $finding 'acceptanceId' -and $finding.acceptanceId -in @($Envelope.acceptanceIds)) -or
      (Has-Text $finding 'riskId' -and $finding.riskId -in @($Envelope.riskIds))
    if (-not $linked -or -not (Has-Text $finding 'concreteFailure') -or -not (Has-Text $finding 'evidence') -or
        $finding.classification -cne 'correction') { $errors += "UNSUPPORTED_BLOCKER:$($finding.severity)" }
  }
  @{ Errors = $errors; ContractUpgrade = $upgrade }
}

function Invoke-SelfTest {
  $tempParent = [IO.Path]::GetTempPath()
  if (-not (Test-Path -LiteralPath $tempParent -PathType Container)) { throw 'TEMP_PARENT_UNAVAILABLE' }
  $repo = Join-Path $tempParent ('qz-control-loop-' + [Guid]::NewGuid().ToString('N'))
  [IO.Directory]::CreateDirectory((Join-Path $repo 'scripts')) | Out-Null
  $hostPath = (Get-Process -Id $PID).Path
  $covered = @()
  function Invoke-FixtureGit([Parameter(ValueFromRemainingArguments)][string[]]$Arguments) {
    & git.exe -C $repo @Arguments 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "SELFTEST_GIT_FAILED:$($Arguments -join ',')" }
  }
  function Write-Json([string]$Path, $Value) { [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Value -Depth 20), [Text.Encoding]::UTF8) }
  function Invoke-Public([string]$Action, [string]$Envelope, [string]$Findings = $null) {
    $arguments = @('-NoProfile','-File',(Join-Path $repo 'scripts/check-agent-control-loop.ps1'),'-Action',$Action,'-EnvelopePath',$Envelope)
    if ($Findings) { $arguments += @('-FindingsPath',$Findings) }
    $lines = @(& $hostPath @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($lines.Count -ne 1) { throw "SELFTEST_PUBLIC_OUTPUT_NOT_SINGLE_LINE:$($lines -join '|')" }
    try { $result = [string]$lines[0] | ConvertFrom-Json } catch { throw "SELFTEST_PUBLIC_OUTPUT_INVALID:$($lines[0])" }
    @{ Result = $result; ExitCode = $exitCode; LineCount = $lines.Count }
  }
  function New-Envelope([string]$Head, [int]$Attempt = 1) {
    [ordered]@{
      contractId='C1'; version='qz-control-envelope/v1'; ownerRepo=[IO.Path]::GetFullPath($repo); baselineHead=$Head
      allowedWrites=@('committed.txt','staged.txt','unstaged.txt','untracked.txt','allowed/*'); protectedPaths=@('protected'); acceptanceIds=@('A1'); riskIds=@('R1')
      lineageId='L1'; actuationAttempt=$Attempt; maxAttempts=5; mode='write-milestone'
      errorBefore=[ordered]@{outOfEnvelope=0;p0=1;p1=1;requiredSensorFailures=1}
      errorAfter=[ordered]@{outOfEnvelope=0;p0=0;p1=1;requiredSensorFailures=0}
      sensors=@([ordered]@{id='S1';required=$true;beforeStatus='FAIL';afterStatus='PASS'})
    }
  }
  try {
    [IO.File]::Copy($PSCommandPath, (Join-Path $repo 'scripts/check-agent-control-loop.ps1'))
    Invoke-FixtureGit init; Invoke-FixtureGit config user.email 'selftest@example.invalid'; Invoke-FixtureGit config user.name 'Control Loop SelfTest'
    [IO.Directory]::CreateDirectory((Join-Path $repo 'protected')) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $repo 'allowed')) | Out-Null
    [IO.File]::WriteAllText((Join-Path $repo 'protected/source.txt'), 'baseline')
    [IO.File]::WriteAllText((Join-Path $repo 'baseline.txt'), 'baseline')
    Invoke-FixtureGit add .; Invoke-FixtureGit commit -m baseline
    $baseline = (& git -C $repo rev-parse HEAD).Trim()
    $envelopePath = Join-Path $repo 'envelope.json'; Write-Json $envelopePath (New-Envelope $baseline)
    if ((Invoke-Public PreWrite $envelopePath).Result.status -cne 'PASS') { throw '公开 PreWrite fixture 失败' }
    foreach ($staleCase in @(
      @{ Name='owner-repo-mismatch'; Mutate={ param($e) $e.ownerRepo = [IO.Path]::GetFullPath((Join-Path $repo '..')) }; Reason='PREWRITE_OWNER_REPO_MISMATCH' },
      @{ Name='baseline-head-mismatch'; Mutate={ param($e) $e.baselineHead = ('0' * 40) }; Reason='PREWRITE_BASELINE_HEAD_MISMATCH' }
    )) {
      $fixture = New-Envelope $baseline; & $staleCase.Mutate $fixture
      $path = Join-Path $repo "$($staleCase.Name).json"; Write-Json $path $fixture
      $actual = Invoke-Public PreWrite $path
      if ($actual.Result.status -cne 'INCOMPLETE' -or $actual.Result.reasonCode -cne $staleCase.Reason -or
          $actual.ExitCode -ne 78 -or $actual.LineCount -ne 1 -or -not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "$($staleCase.Name) 未以单行 INCOMPLETE/exit 78 拒绝并保留 envelope"
      }
    }
    $covered += 'public-prewrite-stale-context-incomplete-exit78-preserves-envelope'

    $unknown = New-Envelope $baseline; $unknown['unexpectedTopLevel'] = 'rejected'
    $unknownPath = Join-Path $repo 'unknown-top-level.json'; Write-Json $unknownPath $unknown
    $unknownResult = Invoke-Public PreWrite $unknownPath
    if ($unknownResult.Result.status -cne 'FAIL' -or $unknownResult.Result.reasonCode -notmatch 'ENVELOPE_UNKNOWN_FIELD') {
      throw '顶层未知字段未被公开 CLI fixture 阻断'
    }
    $covered += 'authoritative-top-level-schema-rejects-unknown-fields'
    [IO.File]::WriteAllText((Join-Path $repo 'committed.txt'), 'commit'); Invoke-FixtureGit add committed.txt; Invoke-FixtureGit commit -m after-baseline
    [IO.File]::WriteAllText((Join-Path $repo 'staged.txt'), 'staged'); Invoke-FixtureGit add staged.txt
    [IO.File]::WriteAllText((Join-Path $repo 'baseline.txt'), 'unstaged')
    [IO.File]::WriteAllText((Join-Path $repo 'unstaged.txt'), 'unstaged')
    [IO.File]::WriteAllText((Join-Path $repo 'untracked.txt'), 'untracked')
    Invoke-FixtureGit mv protected/source.txt allowed/renamed.txt
    $post = Invoke-Public PostWrite $envelopePath
    if ($post.Result.status -cne 'FAIL' -or $post.Result.reasonCode -notmatch 'PROTECTED_PATH:protected/source.txt' -or $post.Result.reasonCode -notmatch 'OUT_OF_ENVELOPE:baseline.txt') { throw '真实 Git 多层 diff/rename 双端未阻断' }
    $covered += 'real-git-baseline-staged-unstaged-untracked-rename-both-ends'

    $valid = New-Envelope $baseline
    foreach ($case in @(
      @{ Name='scalar-array'; Mutate={ param($e) $e.allowedWrites='committed.txt' }; Expected='FIELD_INVALID:allowedWrites' },
      @{ Name='single-sensor-object'; Mutate={ param($e) $e.sensors=[ordered]@{id='S1';required=$true;beforeStatus='FAIL';afterStatus='PASS'} }; Expected='SENSORS_INVALID' },
      @{ Name='attempt1-flat'; Mutate={ param($e) $e.errorAfter=[ordered]@{outOfEnvelope=0;p0=1;p1=1;requiredSensorFailures=1}; $e.sensors[0].afterStatus='FAIL' }; Expected='ERROR_VECTOR_NOT_DECREASED' },
      @{ Name='attempt1-growth'; Mutate={ param($e) $e.errorAfter.p1=2 }; Expected='ERROR_COMPONENT_INCREASED:2' }
    )) {
      $action = if ($case.Name -like 'attempt1-*') { 'PostWrite' } else { 'PreWrite' }
      $fixtureHead = if ($action -ceq 'PreWrite') { (& git -C $repo rev-parse HEAD).Trim() } else { $baseline }
      $fixture = New-Envelope $fixtureHead; & $case.Mutate $fixture
      $path = Join-Path $repo "$($case.Name).json"; Write-Json $path $fixture
      $actual = Invoke-Public $action $path
      if ($actual.Result.status -cne 'FAIL' -or $actual.Result.reasonCode -notmatch [regex]::Escape($case.Expected)) { throw "$($case.Name) 假类型/收敛 fixture 未阻断" }
    }
    $covered += 'strict-json-arrays-sensor-and-attempt1-convergence'

    $reviewEnvelope = Join-Path $repo 'review-envelope.json'; Write-Json $reviewEnvelope (New-Envelope ((& git -C $repo rev-parse HEAD).Trim()))
    $findingCases = @(
      @{ Name='findings-object'; Value=[ordered]@{findings=@()}; Expected='FINDINGS_MUST_BE_ARRAY' },
      @{ Name='finding-fake-type'; Value=@('not-an-object','also-not-an-object'); Expected='FINDING_STRUCTURE_INVALID' },
      @{ Name='p1-missing-evidence'; Value=@(
          [ordered]@{severity='P1';classification='correction';acceptanceId='A1';concreteFailure='failure';evidence='' },
          [ordered]@{severity='P1';classification='correction';acceptanceId='A1';concreteFailure='failure';evidence='' }); Expected='FINDING_STRUCTURE_INVALID' }
    )
    foreach ($case in $findingCases) {
      $path = Join-Path $repo "$($case.Name).json"; Write-Json $path $case.Value
      $actual = Invoke-Public Review $reviewEnvelope $path
      if ($actual.Result.status -cne 'FAIL' -or $actual.Result.reasonCode -notmatch $case.Expected) { throw "$($case.Name) finding fixture 未阻断" }
    }
    $p2Path = Join-Path $repo 'p2.json'; Write-Json $p2Path @(
      [ordered]@{severity='P2';classification='observation';concreteFailure='minor issue';evidence='line evidence'},
      [ordered]@{severity='P2';classification='observation';concreteFailure='minor issue 2';evidence='line evidence 2'})
    if ((Invoke-Public Review $reviewEnvelope $p2Path).Result.status -cne 'PASS') { throw '合法 P2 未进入死区' }
    $upgradePath = Join-Path $repo 'upgrade.json'; Write-Json $upgradePath @(
      [ordered]@{severity='P1';classification='contract-upgrade';concreteFailure='missing contract';evidence='review evidence'},
      [ordered]@{severity='P0';classification='contract-upgrade';concreteFailure='missing risk';evidence='other evidence'})
    if ((Invoke-Public Review $reviewEnvelope $upgradePath).Result.reasonCode -cne 'CONTRACT_UPGRADE_REQUIRED') { throw 'contract-upgrade 未返回 INCOMPLETE' }
    $covered += 'strict-findings-and-contract-upgrade'
    New-Result 'SELF_TEST_SUCCEEDED' ($covered -join ',') 0
  } finally {
    if (Test-Path -LiteralPath $repo) { Remove-Item -LiteralPath $repo -Recurse -Force -ErrorAction SilentlyContinue }
  }
}

function Main {
  if ($SelfTest) { return Invoke-SelfTest }
  $resolvedEnvelope = if ([IO.Path]::IsPathRooted($EnvelopePath)) { $EnvelopePath } else { Join-Path $script:Root $EnvelopePath }
  $envelope = Read-JsonFile $resolvedEnvelope
  $phase = if ($Action -ceq 'PreWrite') { 'PreWrite' } else { 'PostWrite' }
  $errors = @(Test-Envelope $envelope $phase)
  if ($Action -ceq 'PreWrite') {
    if ('OWNER_REPO_MISMATCH' -in $errors) { return New-Result 'INCOMPLETE' 'PREWRITE_OWNER_REPO_MISMATCH' 78 }
    if ('BASELINE_HEAD_MISMATCH' -in $errors) { return New-Result 'INCOMPLETE' 'PREWRITE_BASELINE_HEAD_MISMATCH' 78 }
  }
  if ($Action -ceq 'PostWrite') {
    $errors += Test-WriteSet $envelope (Get-ChangedPaths $envelope)
    $errors += Test-Convergence $envelope
  }
  if ($Action -ceq 'Review') {
    if (-not $FindingsPath) { throw 'FINDINGS_PATH_REQUIRED' }
    $resolvedFindings = if ([IO.Path]::IsPathRooted($FindingsPath)) { $FindingsPath } else { Join-Path $script:Root $FindingsPath }
    $review = Test-ReviewFindings $envelope (Read-JsonFile $resolvedFindings)
    $errors += $review.Errors
    if ($review.ContractUpgrade -and -not $errors.Count) { return New-Result 'INCOMPLETE' 'CONTRACT_UPGRADE_REQUIRED' 78 }
  }
  if ($errors.Count) { return New-Result 'FAIL' (($errors | Sort-Object -Unique) -join ',') 1 }
  New-Result 'PASS' "$Action`_PASSED" 0
}

$result = $null
try { $result = Main } catch { $result = New-Result 'INCOMPLETE' $_.Exception.Message 78 }
[Console]::Out.WriteLine(($result | ConvertTo-Json -Compress -Depth 8))
exit $result.exitCode
