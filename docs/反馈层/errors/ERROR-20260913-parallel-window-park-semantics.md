# ERROR-20260913：并行窗口 park 语义与「注册列表非空」错配，CLIENT_PRE 空耗 tick 预算

- 日期：2026-09-13
- 范围：`parallel/ParallelTickExecutor.java`（窗口预算与主线程等待）、`mixins/early/MixinTickEvent.java`（阶段划分，本轮未改）
- 现象：预览规划期客户端主线程在每个客户端 tick 的 `CLIENT_PRE` `endStage` 被 park 到 deadline（默认 `general.tickBudgetMs` = 15ms），帧率被并行预算绑架。

## 根因

1. `waitForAvailableWindow` 只按「该 stage 注册任务非空」判断是否继续等待；`YIELDED` 任务仍留在注册列表里，因此 worker 已经全部回到安全边界后，主线程继续空等到 deadline。
2. 任务侧 `shouldYield()` 只认窗口 deadline，`ChainSearchAlgorithm.step` 的单个分片可以吃满整个窗口，`CONTINUE` 循环会把 tick 预算用尽。
3. 归纳：**并行让出预算与 tick 预算同源**，窗口语义缺少「本 tick 是否还有可推进工作」这一概念。

## 修复（B5.1）

- 新增 `ParallelBudgetMode`（`deadline` 默认 = 基线 / `slice`）与纯函数 `ParallelBudgetPolicy`（零 MC/GL/Config 依赖）。
- `slice` 档只压缩客户端 stage 的窗口预算（`general.parallelSliceBudgetMs`，默认 4ms）；主线程在「没有活跃分片，且每个注册任务都已获得本 tick 的调度机会」时立即返回，不再为已经让出的任务空耗墙钟。
- `endStage` 的「先关窗、再等 `activeWorkers` 归零」屏障与协作式停止契约完全不变；不新增取消与强杀路径。

## 验收

- headless：`ParallelBudgetPolicyTest`（档位解析/stage 适用范围/预算钳制/等待边界）、`ParallelTickExecutorBudgetTest`（基线窗口逐值等价、slice 只压客户端、让出后提前返回、每 tick 一次推进、屏障不变量）。
- 真机墙钟对比（预览激活时 `CLIENT_PRE` 每 tick park 分布）**待用户验证**，未在本轮实测。

## 教训

- 窗口等待条件必须表达「是否还有可推进的工作」，不能用「注册列表是否非空」代替。
- 跨 owner 的配置键位先报 Lead 裁决再接线，避免在共享工作区留下不可编译的半成品。
