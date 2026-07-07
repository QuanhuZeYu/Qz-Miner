# scripts/check-doc-fact-drift.ps1
# 文档事实漂移门禁 — 防止文档引用已删除/改名的类（现状锚失真）
# 控制论角色：传感层事实传感器，弥补 check-doc-discipline.ps1 只查格式不查事实的盲区
# 解决问题：代码高频变动、文档低频滞后导致的漂移（如 v2 重写后文档仍引用已删的 ChainExecutor）
# 零外部依赖：纯 PowerShell + Select-String
$ErrorActionPreference = "Stop"
$violations = @()

# 定位仓库根（脚本在 scripts/ 下，向上一层）
$root = Split-Path -Parent $PSScriptRoot

# ----- 预扫描 src 下所有 .java 基名，建索引 -----
# 同时覆盖 src/main 与 src/test：测试类也是项目真实类，决策/文档常引用
$javaBasenames = @{}
foreach ($sub in @("src/main", "src/test")) {
  $subPath = Join-Path $root $sub
  if (Test-Path $subPath) {
    Get-ChildItem -Path $subPath -Filter *.java -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
      $base = [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
      $javaBasenames[$base] = $true
    }
  }
}

# ----- 外部类白名单 -----
# 这些是文档常引用但不在本项目 src 下的外部依赖类（JDK 异常 / Minecraft / Forge / GregTech /
# MixinExtras / LootGames 等），属于合法引用，不算漂移。
# 维护规则：新增外部依赖类引用导致误报时，按"库来源"分组追加。
$externalAllowlist = @(
  # JDK 异常与并发类型
  'LinkageError', 'SecurityException', 'NoClassDefFoundError', 'RejectedExecutionException',
  'RuntimeException', 'IllegalStateException', 'InterruptedException',
  # Minecraft 核心
  'EntityPlayerMP', 'EntityPlayer', 'EntityItem', 'TileEntity', 'World', 'Block', 'ItemStack',
  'GuiScreen', 'BaseScreen', 'IIconRegister', 'IChatComponent', 'NBTTagCompound',
  # Minecraft Forge / Network
  'SimpleNetworkWrapper', 'FMLEventChannel', 'ByteBuf',
  # GregTech / GTNH
  'BaseMetaPipeEntity', 'MetaTileEntity', 'MetaPipeEntity',
  'GTOreAdapter', 'BWOreAdapter', 'GTPPOreAdapter',
  'GregTechAPI', 'TileEntityOres', 'BWTileEntityMetaGeneratedOre', 'BlockBaseOre',
  # Forge Ore Harvest 事件
  'HarvestDropsEvent', 'BlockEvent',
  # Mixin
  'MixinExtras', 'Inject', 'At', 'CallbackInfo', 'CallbackInfoReturnable',
  # 可选模组（被反射探测，不要求编译期存在）
  'LootGames', 'MSBoard'
)
$externalSet = @{}
foreach ($name in $externalAllowlist) { $externalSet[$name] = $true }

# ----- 收集待扫描的 .md 文件 -----
# 范围：docs/**/*.md + 根目录 *.md
# 排除：.opencode/、build/、run/（前者会话临时，后两者构建产物）
$docFiles = @()
$docsDir = Join-Path $root "docs"
if (Test-Path $docsDir) {
  $docFiles += Get-ChildItem -Path $docsDir -Filter *.md -Recurse -File -ErrorAction SilentlyContinue
}
Get-ChildItem -Path $root -Filter *.md -File -ErrorAction SilentlyContinue | ForEach-Object { $docFiles += $_ }

# ----- 豁免规则 -----
# 行级历史叙事关键词（命中即跳过该行所有引用核对）
$historyKeywords = '已删除|已删|删除|废弃|迁移前|曾被|曾经|历史|取代|替换为|改为'

# 判断是否为"历史快照"文件（断言A 与 断言B 整体跳过）
# 原则：errors/ / 决策/ / 诊断层/ 记录事件快照或演进史，常引用当时存在但现已删/改的类与路径，
# 不属于"现状锚"，不参与事实漂移核对（现状锚失真只针对当前真理文档：NORTH_STAR / AGENTS /
# 边界 / 项目结构 / 门禁脚本说明 / 硬约束总目录 等）
function Test-HistorySnapshot([string]$rel) {
  if ($rel -match '^docs/反馈层/errors/') { return $true }
  if ($rel -match '^docs/反馈层/决策/') { return $true }
  if ($rel -match '^docs/诊断层/') { return $true }
  return $false
}

# 判断裸 CamelCase 标识符是否明显不是类名（断言B候选过滤）
function Test-NotAClass([string]$name) {
  # 单字母或空
  if ($name.Length -le 1) { return $true }
  # 含 . / - （路径/配置键/方法签名）
  if ($name -match '[./\-]') { return $true }
  # 含下划线（常量 NORTH_STAR / 不变量编号 I_1）
  if ($name -match '_') { return $true }
  # 全大写（常量 G1 / I1 / 状态码）
  if (-not ($name -cmatch '[a-z]')) { return $true }
  # 全数字开头或纯数字
  if ($name -match '^\d') { return $true }
  # 必须含至少一个大写（已隐含：开头大写）
  if (-not ($name -cmatch '[A-Z]')) { return $true }
  return $false
}

# ----- 主扫描 -----
foreach ($doc in $docFiles) {
  $rel = $doc.FullName.Substring($root.Length + 1) -replace '\\','/'
  # 排除 .opencode / build / run（这些虽不在 docs 下，根 *.md 也可能误入；保险起见再过滤一次）
  if ($rel -match '^\.opencode/') { continue }
  if ($rel -match '^build/') { continue }
  if ($rel -match '^run/') { continue }

  $isHistorySnap = Test-HistorySnapshot $rel

  # 逐行读，保留行号
  $lines = Get-Content -LiteralPath $doc.FullName -ErrorAction SilentlyContinue
  if (-not $lines) { continue }
  $lineNo = 0
  foreach ($line in $lines) {
    $lineNo++
    # 行级关键词豁免：历史叙事行整体跳过
    $lineExempt = $false
    if ($line -match $historyKeywords) { $lineExempt = $true }

    # ----- 断言A：带 .java 后缀的路径/文件名引用核对（历史快照文件跳过）-----
    if ($isHistorySnap) {
      # 历史文档不参与漂移核对（详见 Test-HistorySnapshot 注释）
    } else {
      # 匹配反引号包围、内容含 .java 的引用（路径或纯文件名皆可）
      $matchesA = [regex]::Matches($line, '`([^`]*?\.java)(?::\d+(?:-\d+)?)?`')
      foreach ($m in $matchesA) {
        $raw = $m.Groups[1].Value
        # 取 basename（不含扩展名）作为核对键
        $baseName = [System.IO.Path]::GetFileNameWithoutExtension($raw)
        if (-not $baseName) { continue }
        if ($lineExempt) { continue }
        if ($externalSet.ContainsKey($baseName)) { continue }
        if (-not $javaBasenames.ContainsKey($baseName)) {
          $script:violations += "[事实漂移] ${rel}:${lineNo}: 引用 .java 文件 ``$raw``（基名 $baseName）在 src 下不存在"
        }
      }
    }

    # ----- 断言B：裸 CamelCase 类名核对（历史快照文件跳过）-----
    if (-not $isHistorySnap) {
      # 反引号包围、大驼峰开头、至少两个大写字母（如 ChainExecutor、BoxScanTraverser）
      $matchesB = [regex]::Matches($line, '`([A-Z][a-zA-Z0-9]*[A-Z][a-zA-Z0-9]*)`')
      foreach ($m in $matchesB) {
        $name = $m.Groups[1].Value
        # 明显非类名跳过
        if (Test-NotAClass $name) { continue }
        # 已被断言A路径式引用处理（如 `ChainExecutor.java`）—— B 正则不会捕获到，但保险再排一次
        if ($lineExempt) { continue }
        if ($externalSet.ContainsKey($name)) { continue }
        if (-not $javaBasenames.ContainsKey($name)) {
          $script:violations += "[事实漂移] ${rel}:${lineNo}: 引用类 $name 在 src 下不存在（行未标注历史叙事）"
        }
      }
    }
  }
}

# ----- 输出 -----
if ($violations.Count -gt 0) {
  Write-Host "文档事实漂移门禁失败：" -ForegroundColor Red
  $violations | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
  exit 1
}
Write-Host "文档事实漂移门禁通过" -ForegroundColor Green
exit 0
