#requires -Version 7.0
# scripts/check-doc-discipline.ps1
# 文档纪律门禁 — 防止 vibe coding 常见的流水账/历史文档堆积
# 控制论角色：传感层自动传感器，把文档纪律从"应当"型劝说升级为机械门禁
# 零外部依赖：纯 PowerShell + Select-String
$ErrorActionPreference = "Stop"
$violations = @()

# 定位仓库根（脚本在 scripts/ 下，向上一层）
$root = Split-Path -Parent $PSScriptRoot

# ----- 断言1 决策目录禁日期编号文件名 -----
# 决策按主题归并（决策/README.md），不得出现 DECISION-YYYYMMDD-* 或 DECISION- 前缀文件
$decisionDir = Join-Path $root "docs/反馈层/决策"
if (Test-Path $decisionDir) {
  Get-ChildItem $decisionDir -Filter *.md | ForEach-Object {
    if ($_.Name -match "^DECISION-\d{8}" -or $_.Name -match "^DECISION-") {
      $script:violations += "[决策命名] $($_.Name): 禁日期编号/DECISION-前缀，改按主题命名（见 决策/README.md）"
    }
  }
}

# ----- 断言2 交接.md 最近完成简表行数上限 -----
# 简表是历史，必须保持"简"，防单调增长。上限 15 行
$handoverFile = Join-Path $root "docs/反馈层/交接.md"
if (Test-Path $handoverFile) {
  $lines = Get-Content $handoverFile
  $dataRows = 0
  foreach ($line in $lines) {
    if ($line -match "^\|.*\|$") {
      # 表格行：排除分隔行（|---|）和表头边界
      if ($line -match "^\|[\s\-:|]+\|$") { continue }
      $dataRows++
    }
  }
  if ($dataRows -gt 15) {
    $script:violations += "[交接简表] docs/反馈层/交接.md 表格数据行 $dataRows 行，超过上限 15，需精简（收敛项移除/简表归并）"
  }
}

# ----- 断言3 错误预防.md 禁时态/序数流水账标题 -----
# 错误预防只留通则，不应出现日期/年份/序数标题
$errorPrevention = Join-Path $root "docs/反馈层/错误预防.md"
if (Test-Path $errorPrevention) {
  Select-String -Path $errorPrevention -Pattern "^#{1,3}\s.*(20\d{2}|第.+次|\d{4}-\d{2}-\d{2})" | ForEach-Object {
    $script:violations += "[错误预防时态] docs/反馈层/错误预防.md:$($_.LineNumber): 标题含日期/序数，违反'只留通则'约定: $($_.Line.Trim())"
  }
}

# ----- 断言4 docs 禁会话流水账文件名 -----
# 不应有"第N次会话""YYYYMMDD进展"这类时态编号文件（决策目录交断言1，ERROR-*.md 由 AGENTS.md 4.3 允许，REVIEW-*.md 由 4.5 允许）
Get-ChildItem (Join-Path $root "docs") -Filter *.md -Recurse | ForEach-Object {
  $rel = $_.FullName.Substring($root.Length + 1) -replace "\\","/"
  # 决策目录交给断言1
  if ($rel -match "docs/反馈层/决策/") { return }
  if ($_.Name -match "^ERROR-\d{8}") { return }
  if ($_.Name -match "^REVIEW-\d{8}") { return }
  if ($_.Name -match "第.+次" -or $_.Name -match "^\d{8}") {
    $script:violations += "[流水账命名] ${rel}: 文件名含会话序数/日期编号，违反文档纪律（见 AGENTS.md 4.1）"
  }
}

# ----- 输出 -----
& (Join-Path $PSScriptRoot "check-agent-environment-ownership.ps1")
if ($LASTEXITCODE -ne 0) {
  $violations += "[环境所有权] check-agent-environment-ownership.ps1 失败，详见上方输出"
}

if ($violations.Count -gt 0) {
  Write-Host "文档纪律门禁失败：" -ForegroundColor Red
  $violations | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
  exit 1
}
Write-Host "文档纪律门禁通过" -ForegroundColor Green
exit 0
