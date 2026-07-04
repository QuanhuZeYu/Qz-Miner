# 连锁末尾目标掉落收集竞态

## 错误现象

- 连锁或范围模式在特定情况下会少释放一个掉落物。
- 常见表现为：连续不松连锁键切换或挖掘另一类方块后，实际破坏数量与最终聚合掉落数量差 1。

## 触发场景

- `CHAIN` 或 `AREA` 使用 `BlockHarvestActionExecutor` 通过 `tryHarvestBlock(...)` 执行真实方块破坏。
- 执行器刚从待破坏队列取出最后一个目标，队列短暂为空。
- 并行规划线程正好完成并观察到空队列。

## 根本原因

- 执行器会先 `poll()` 取出目标，再同步调用 `tryHarvestBlock(...)`。
- 目标已从队列移除但对应 `HarvestDropsEvent` 尚未触发时，规划线程可能把状态切到 `IDLE` 并清理 session。
- `ChainDropCollector` 只在玩家状态仍为执行中时收集并清空 `HarvestDropsEvent` 掉落，状态提前变 `IDLE` 会让最后一次掉落脱离聚合链路。

## 修复方案

- 规划线程完成且队列为空时，只标记规划完成并同步状态，不再直接把会话切到 `IDLE` 或清理 session。
- 执行结束统一由服务端 tick 主线程的 `ChainExecutor` 在 `queue.isEmpty() && session.isPlannerCompleted()` 后调用 `stopPlayerExecution(...)`。
- 掉落释放时如果 `spawnEntityInWorld(...)` 返回失败或抛出运行时异常，将尚未生成的掉落回填到玩家级缓冲，避免 `drain()` 后永久丢失。

## 预防措施

- 并行规划线程不得直接结束可能正在由主线程执行器消费的会话。
- 队列为空不等价于当前没有目标正在执行；涉及真实世界写入和掉落事件时，执行状态收口必须放在主线程执行器。
- 先清空再释放的资源流程必须具备失败回滚能力。
