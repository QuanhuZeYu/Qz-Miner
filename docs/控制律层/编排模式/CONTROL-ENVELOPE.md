# 冻结控制包协议

> `qz-control-envelope/v1` 是非平凡写盘任务的任务级设定值载体。默认文件 `.opencode/control-envelope.json` 易失且被 Git 忽略；真实任务控制包不得提交。

## 1. 生命周期

主 build 在首次写盘前冻结控制包，fixer 写前执行 `PreWrite`，写后执行 `PostWrite`，reviewer 以同一文件执行 `Review`。合同目标、验收、写集或风险边界改变时，由 oracle 重整定并形成新合同版本；当前轮不得原地扩大。活动 envelope 在任务完成后必须删除；新任务不得把文件存在视为可复用授权，`PreWrite` 发现 `baselineHead` 陈旧或 `ownerRepo` 不匹配时返回 `INCOMPLETE`，禁止静默沿用。

`ownerRepo` 必须是跨仓唯一的规范化绝对仓库根，`baselineHead` 是本轮写盘前 HEAD。`PreWrite` 发现二者任一与当前仓库不匹配时，公开 CLI 只输出一行结果，分别以 `PREWRITE_OWNER_REPO_MISMATCH` 或 `PREWRITE_BASELINE_HEAD_MISMATCH` 明示原因，并以退出码 78 返回 `INCOMPLETE`；活动文件原样保留，等待重新冻结，禁止作为普通校验失败继续作用。`allowedWrites` 是唯一可写集合，`protectedPaths` 优先级更高；两者支持仓根相对路径和 PowerShell wildcard；PowerShell wildcard 的 `*` 可以匹配 `/` 并跨目录。空白工作树仍要通过 `PostWrite`。

## 2. v1 结构

```json
{
  "contractId": "contract-topic-v1",
  "version": "qz-control-envelope/v1",
  "ownerRepo": "D:\\Code\\MC\\Qz-Miner",
  "baselineHead": "40位Git提交哈希",
  "allowedWrites": ["scripts/example.ps1"],
  "protectedPaths": ["src"],
  "acceptanceIds": ["A1"],
  "riskIds": ["R1"],
  "lineageId": "stable-logical-lineage",
  "actuationAttempt": 1,
  "maxAttempts": 5,
  "mode": "write-milestone",
  "errorBefore": { "outOfEnvelope": 0, "p0": 1, "p1": 0, "requiredSensorFailures": 1 },
  "errorAfter": { "outOfEnvelope": 0, "p0": 0, "p1": 0, "requiredSensorFailures": 0 },
  "sensors": [
    { "id": "S1", "required": true, "beforeStatus": "FAIL", "afterStatus": "PASS" }
  ]
}
```

上述示例列出的 15 个顶层字段构成 v1 唯一权威字段集合，字段均为必需，任何未知顶层字段均拒绝；`protectedPaths`、`riskIds` 可为空。`mode` 仅允许 `implementation-complete`、`write-milestone`、`verification-only`。sensor 集合在同一冻结合同内不可增删，状态仅为 `PASS|FAIL|INCOMPLETE`。

## 3. 误差与抗积分饱和

误差向量固定为 `E=[outOfEnvelope,p0,p1,requiredSensorFailures]`。只有同一合同版本下 fixer 执行器的写盘作用计入 `actuationAttempt`；review、explore 和纯传感不计。第 1 至第 5 次每次 `PostWrite` 都要求所有分量不增且至少一项严格下降，并核对必需 sensor 失败数与向量一致。

越界或任一次误差不下降立即停止 fixer，由 oracle 重整定。第 5 次作用后主 build 也不得绕过写集或追加第 6 次；只能冻结新合同，或向用户返回 `INCOMPLETE`。

## 4. 审查死区

reviewer 输出结构化 findings JSON。P0/P1 必须绑定控制包内 `acceptanceId` 或 `riskId`，同时给出 `concreteFailure`、`evidence`、`classification=correction`；否则是无依据 blocker。P2 位于审查死区，只记录观察，不触发 fixer。已关闭问题只有出现新证据并重新满足上述因果链才可重开。

合同遗漏使用 `classification=contract-upgrade`；门禁返回 `INCOMPLETE/CONTRACT_UPGRADE_REQUIRED`，交 oracle/主 build 重整定，不计当前轮 FAIL。

## 5. 门禁命令

```powershell
pwsh -NoProfile -File scripts/check-agent-control-loop.ps1 -Action PreWrite
pwsh -NoProfile -File scripts/check-agent-control-loop.ps1 -Action PostWrite
pwsh -NoProfile -File scripts/check-agent-control-loop.ps1 -Action Review -FindingsPath .opencode/review-findings.json
pwsh -NoProfile -File scripts/check-agent-control-loop.ps1 -SelfTest
```
