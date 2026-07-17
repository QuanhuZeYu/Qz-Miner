# 自动工具 FROZEN 被折叠为 CLOSING

## 错误现象

客户端收到首块 `FREEZE ACCEPTED/FROZEN` 后，在专用 `PLANNING` phase 到达时记录
`reason=protocol-orphan`，随后提前发送 `RESTORE→CLOSE`。工具因此在连锁执行完成前被还原；实机 round
曾在 `workerConfirmed=202`、`executionSucceeded=50` 时被截断，其他更早还原的 round 成功数为 0。

## 触发场景

精确顺序为 `SWAP APPLIED` → 本地首块请求 `FREEZE` → `FREEZE ACCEPTED/FROZEN` → 专用
`PLANNING/RUNNING/FINISHING` phase。phase 要求“确保已冻结”，但客户端把它误当成需要发送另一个
`FREEZE`；错误的关闭标记又使该动作不可用，最终走 protocol-orphan 收口。

## 根本原因

`AutoToolSwapClientReducer.onActionResult` 将服务端 `FROZEN` 与 `CLOSING` 一并派生为
`round.closing=true`，混淆了稳定活跃态和单调关闭态。同时 `requestFreeze` 没有识别服务端已
`FROZEN`、同一 `FREEZE` 正在等待回执或已经成功结算，导致活跃 phase 重复表达同义意图。

## 修复方案

- 只有真实 `CLOSING` 派生关闭语义；`FROZEN` 保持 round 活跃。
- `requestFreeze` 以当前服务端状态、in-flight intent 和成功 settlement 为幂等门；已冻结时只维持本地
  `FROZEN` 投影，不分配新的 `actionSequence`。
- 保留真实 `CLOSING` 的 SWAP/FREEZE 安全门，并继续由自然 `IDLE`、release、配置、GUI/re-anchor
  和 lifecycle 既有路径执行恢复或收口。
- 回归同时覆盖 phase 先于 FREEZE 回执、FREEZE 回执先于 phase，以及重复
  `PLANNING/RUNNING/FINISHING`。

## 预防措施

- 协议状态映射必须逐态建模；稳定活跃态不得用“非 OPEN”之类否定条件折叠成关闭态。
- “确保某状态”的 phase/event 应优先实现为幂等投影，分别覆盖请求未发、in-flight、settled 和真实
  closing 四个阶段。
- 回归必须断言 effect 的缺席：无第二 `FREEZE`、无 `RESTORE/CLOSE`、无 `protocol-orphan`，而不只
  断言最终枚举值。
