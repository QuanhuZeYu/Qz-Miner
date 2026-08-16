# 空手接替逐目标往返导致执行吞吐退化

> **历史错误记录。** 5.2 已删除逐目标 takeover 热路，5.3 又以 shared soft deadline 替代 `maxBreakPerTick` 与 50ms 节流；下文描述的是当时症状、修复与证据边界。

## 错误现象

真实空手连锁中，约 1793 个目标的规划只需约 2 秒，但服务端随后每个目标都建立一次 `TAKEOVER` 等待门并等待客户端 `DECLINE_TAKEOVER`。执行桥因此约每 tick 只消费一个目标，50 tick 仅处理约 51 个目标后触发 watchdog；`maxBreakPerTick=64` 未成为实际吞吐上限。

## 触发场景

- 服务端当前主手为空，客户端对目标扫描后没有真实候选并合法 DECLINE。
- 同一 round 后续目标具有相同 block id + 完整 metadata，库存 0..35、锚点槽位和生命周期身份均未变化。
- 服务端仍逐目标请求客户端再次证明“没有更高优先级真实候选”。

## 根本原因

DECLINE 后的空手资格只用于当前队首目标：Coordinator 实时复验空手与采掘权威后立即丢弃该资格。稳定批次没有 round-scoped 资格租约，导致本应由本地纯值身份证明的稳定事实退化为每目标网络往返；执行桥在 WAIT 时提前结束当 tick，最终把吞吐压低到约一个目标每 tick。

## 修复方案

- 在 `AutoToolSwapRoundService` 的单个 `RoundRecord` 中保存至多一个空手回退租约，不建立随目标增长的 map。
- 租约绑定 endpoint、round、generation、不含坐标的 block id + 完整 metadata、空锚点槽位，以及 inventory 0..35 每槽 `AutoToolSwapStackState.sameContent` 的完整纯值身份。
- 仅在合法 DECLINE 已结算、pending 已消费、空手与库存安全门复验、实时 `ChainHarvestRules.canHarvest` 为 true 后安装。
- 命中租约时仍对每个目标实时执行采掘权威；目标能力、任一槽完整内容、锚点、GUI/cursor、round 或生命周期变化会清除旧租约并重新走真实候选优先协商。A→B→A 因单项租约必须再次协商 A。
- 诊断只记录 create/invalidated，并在 round close 汇总 create/hit/invalidated 计数；命中不逐目标输出日志。

## 预防措施

- 对异步资源等待门区分“每目标瞬时权威”与“round 内稳定资格”：瞬时权威不得缓存，稳定资格必须用完整纯值身份限界后才可租赁。
- 回归同时断言稳定同 key 的 N 个目标只产生一次请求、每目标权威仍被调用，以及 block/meta/36 槽/锚点/GUI/cursor/generation/round/lifecycle 变化后不命中旧租约。
- wire、客户端候选、50ms 节流、`maxBreakPerTick`、状态机与 GT 线缆执行路径均不因性能修复放宽。

## 验证边界

目标测试与全量自动化只能证明纯值合同和副作用边界；真实 client/dedicated 下的大批量空手吞吐、watchdog 与日志计数仍为 **INCOMPLETE**，不能由构建成功替代。
