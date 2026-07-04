# 决策：并行 Tick 协作式暂停、恢复与终止

## 背景

当前并行 Tick 执行器通过 `MixinTickEvent` 在 Forge Tick 事件前后打开并关闭并行窗口，`ParallelTickExecutor.endStage(...)` 会关闭窗口并等待 `activeWorkers` 归零。这个方向必须保持：主线程不能在 worker 仍可能触碰世界状态时继续推进后续 tick。

本轮研究确认，卡顿风险不能通过超时、`shutdownNow()`、`Future.cancel(...)` 后让主线程继续推进来解决。正确边界应下沉到任务内部：每个 `ParallelTickTask.run(...)` 分片必须绝对有界，并在安全中断点主动返回，下一次窗口从已保存状态继续。

## 现状结论

- `ParallelTickContext` 目前只携带 tick 编号、开始时间、deadline 和阶段，没有取消、暂停、终止或工作量预算协议。
- `ParallelTickTask` 只返回 boolean，无法区分“自然完成”“预算让出”“取消终止”。
- `ParallelTickExecutor` 会按时间窗口循环调用 `task.run(context)`，但无法约束单次 `run(...)` 内部耗时；只要单片无界，`endStage(...)` 就会被拖住。
- `unregister()` 当前会移除任务并调用 `Future.cancel(true)`；如果任务已经进入 `run(...)`，线程中断只能等任务内部自然返回才生效，不是可靠的安全终止协议。
- `shutdown()` 当前使用 `shutdownNow()`，只适合最终进程/服务端停止时的兜底，不应作为正常 tick 生命周期或预览取消的语义基础。
- `awaitNextWindow()` 当前遇到非本任务阶段的窗口可能返回 `null` 并结束 worker，后续设计需要避免跨阶段窗口误杀任务。
- `runSlicesInCurrentTick(...)` 关闭窗口与 worker 启动下一片之间存在竞态；即使主线程会等待，也应在进入新分片前再次检查窗口与阶段。

## 遍历器分片审查

- `BoxScanTraverser` 已具备增量壳扫游标，装填阶段单批最多扫描 256 个坐标，当前是最接近目标模型的实现。
- `ChainSearchAlgorithm.step(...)`、`LoggingFloodFillTraverser.step(...)`、`GregTechCableTraverser.step(...)` 的节点处理受 `maxNodes` 限制，但层切换时会把整个 `nextFrontier` 一次性搬回 `currentFrontier`，该搬运不计入预算，前沿很大时单片仍可能无界。
- `LoggingFloodFillTraverser.enqueueNeighbors(...)` 会按 `chainLoggingShellLayers` 生成邻域偏移；该配置允许无限大，单个匹配节点就可能产生很大的邻居遍历成本，需要把邻居生成也改为预算化或限制为可暂停状态。
- `TunnelBoxScanTraverser` 每次装填 3x3 切片本身很小，但当大量空切片被 `canTraverse(...)` 过滤掉时，`processed` 不增长，可能在一次 `step(...)` 内连续推进很多空切片；空切片推进必须计入预算。
- `SectionClearTraverser` 单次壳层装填受 16x16x16 区段限制，是固定上界，但仍未纳入 `maxNodes` 预算；为了协议一致，也应把壳层坐标扫描预算化。
- `ChainPreviewController` 的本地预览任务复用相同 `ChainTraverser.step(...)`，因此继承上述所有风险；`previewState.incrementScannedCount()` 当前统计的是分片次数，不是实际扫描节点数，不能作为安全预算依据。
- LootGames 远程预览不在客户端本地 `ChainTraverser` 任务内，但客户端应用返回结果时仍应保持请求代际校验，避免取消后写入旧预览状态。

## 最终选择

采用协作式协议，不采用强制熔断：

1. `ParallelTickExecutor.endStage(...)` 继续作为主线程屏障，只能在 worker 全部返回安全边界后结束并行空间。
2. `ParallelTickTask.run(...)` 内部必须接受可查询的控制信号和工作量预算，主动在安全点返回。
3. 遍历器必须把所有实际推进动作计入预算，包括节点 poll、坐标扫描、空队列推进、空切片推进、邻居生成、frontier 搬运。
4. 任务让出时不丢弃运行态，依靠 `ChainSearchContext`、遍历器字段或新增游标字段保存当前位置，下次窗口继续。
5. 任务终止必须是“请求终止 -> 任务在安全点观察到 -> 清理自身状态 -> 返回完成/终止结果”，而不是外部中断线程后让主线程继续。

## 协议设计

后续实现建议引入一个轻量控制层，可以扩展 `ParallelTickContext`，也可以新增只读控制对象：

- `isPauseRequested()`：窗口关闭、预算耗尽或外部暂停时为 true，任务应尽快在安全点返回并保留状态。
- `isTerminationRequested()`：订阅注销、会话清理、生命周期停止时为 true，任务应在安全点停止并执行必要清理。
- `tryConsumeWork(int units)`：所有任务内部循环每推进一个可度量动作前后都消耗预算；预算不足时返回让出。
- `shouldYield()`：统一判断预算耗尽、窗口关闭、阶段不匹配、暂停请求或终止请求。

`ParallelTickTask` 的返回值建议从 boolean 升级为结果枚举，至少区分：

- `CONTINUE`：本片已安全让出，后续 tick 继续。
- `COMPLETED`：任务自然完成，执行器注销。
- `TERMINATED`：任务响应终止请求并完成清理，执行器注销。
- `FAILED`：任务异常失败，由执行器记录并注销。

如果短期内保持 boolean，也必须约定：返回 `true` 表示“未完成或预算让出”，返回 `false` 表示“自然完成或已安全终止”，终止原因只能通过任务自身日志或状态记录区分。

## 遍历器改造原则

- `step(...)` 不应只以 `maxNodes` 作为“确认节点”预算，而应使用统一工作预算。
- 空队列推进必须计入预算；例如空 shell、空 slice、空 frontier rotation 都消耗工作单位。
- `nextFrontier -> currentFrontier` 搬运必须分批，并保存“正在轮转 frontier”的状态；轮转未完成前不要开始处理下一层节点，避免层级混杂。
- 大邻域生成必须改为可恢复游标；例如伐木模式不能在一个匹配节点内一次遍历任意大的 offset 列表。
- `consumer.accept(...)` 前后要检查终止信号，避免取消后继续向预览状态或待破坏队列写入旧结果。
- 每个遍历器的 `seed(...)` 也应保持小而有界；如果未来 seed 需要大范围装填，应改为首次 `step(...)` 的预算化阶段。

## 执行器改造原则

- `unregister()` 改为请求终止并唤醒 worker，不应依赖 `Future.cancel(true)` 作为正常取消路径。
- 任务从列表移除最好发生在 worker 确认安全退出后，或通过状态标记避免再次调度，防止主线程误以为任务已经完全停住。
- `awaitNextWindow()` 应等待本任务对应阶段的下一个窗口；遇到其他阶段窗口不应返回 `null` 结束任务。
- `enterWorker()` 前应在同一把锁下确认窗口仍打开、阶段仍匹配、tick 未过期，避免窗口关闭后再启动新分片。
- `endStage(...)` 被中断时不能静默放行并让 tick 继续；如果必须响应中断，也应保持“worker 未收口则不结束并行空间”的不变量。

## 影响范围

- 服务端 `CHAIN`、`AREA`、`INTERACT` 规划任务。
- `SPECIAL_GT_CABLE_REPLACE` 的 GT 线缆遍历任务。
- 客户端本地预览任务。
- `ChainRuntimeState`、`ChainSession` 的规划订阅生命周期。
- `ParallelTickExecutor` 的注册、注销、窗口等待与 worker 调度协议。

## 后续注意事项

- 先改协议与遍历器有界性，再考虑任何监控或告警；不要实现超时强杀。
- 优先处理无界风险最大的 frontier 整体搬运、伐木大邻域生成、空切片推进。
- 客户端预览取消要保证旧任务即使晚返回，也不能再污染新 generation 的预览状态。
- 服务端规划取消要保留掉落缓冲现有语义，不能因为终止规划而吞掉已收集掉落。
- 改造后需要至少运行 `./gradlew.bat compileJava`，并做客户端预览切换、松开连锁键、退出世界、服务端规划取消的实机回归。
