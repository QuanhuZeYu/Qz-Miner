# 并行 Tick 协作式调度重构计划

本文面向接手实现的 Agent，描述 Qz-Miner 并行 Tick 任务从“时间窗口 + boolean 分片”升级为“预算化协作式调度框架”的工程化重构步骤。

## 不变量

- 禁止通过超时强行结束并行窗口。
- 禁止在 `shutdownNow()`、`Future.cancel(...)` 后让主线程继续 tick，除非是最终进程/服务端停止的兜底清理路径。
- `ParallelTickExecutor.endStage(...)` 必须等待所有 worker 停在安全边界后才能结束并行空间。
- 所有任务分片必须自身绝对有界，且在任务内部安全点主动让出或终止。
- 服务端世界写入、真实方块破坏、掉落实体生成仍保持在主线程执行。
- 客户端预览取消、断线、世界卸载后，旧任务不得再污染新 generation 的预览状态。

## 目标架构

### 并行框架层

新增或调整以下概念：

- `ParallelTaskResult`：任务单片结果枚举。
- `ParallelTickControl`：任务可查询的控制与预算对象。
- `ParallelWorkBudget`：封装单片工作量预算。
- `ParallelTaskState`：注册任务生命周期状态。
- `ParallelTickTask`：从 boolean 返回升级为结构化结果。

建议枚举：

```java
public enum ParallelTaskResult {
    CONTINUE,
    YIELDED,
    COMPLETED,
    TERMINATED
}
```

建议控制接口：

```java
public interface ParallelTickControl {
    long getTickId();

    ParallelTickStage getStage();

    boolean isWindowOpen();

    boolean isCancelRequested();

    boolean shouldYield();

    boolean tryConsumeWork(int units);

    long getElapsedNanoTime();
}
```

建议任务接口：

```java
public interface ParallelTickTask {
    ParallelTaskResult run(ParallelTickControl control) throws Exception;

    default void onCancelRequested(String reason) {}

    default void cleanupAfterTermination() {}
}
```

### 连锁遍历层

新增预算化遍历接口，逐步替代 `ChainTraverser.step(...)` 的 `maxNodes` 语义：

```java
public interface BudgetedChainTraverser {
    TraversalStepResult step(
        ChainSearchContext context,
        ParallelTickControl control,
        ChainTargetMatcher matcher,
        ChainTargetConsumer consumer);
}
```

建议结果类型：

```java
public enum TraversalStepResult {
    CONTINUE,
    YIELDED,
    COMPLETED,
    TERMINATED
}
```

## 生命周期设计

任务状态建议至少覆盖：

- `REGISTERED`：已注册，等待窗口。
- `RUNNING`：worker 正在执行安全分片。
- `YIELDED`：任务主动让出，下个窗口继续。
- `CANCEL_REQUESTED`：外部请求取消，尚未在任务内部安全点收口。
- `TERMINATING`：任务正在执行安全清理。
- `TERMINATED`：任务响应取消并安全退出。
- `COMPLETED`：任务自然完成。
- `FAILED`：任务异常失败并被注销。

`unregister()` 后不应立即假定任务已停，只能设置取消请求并唤醒 worker。任务从注册列表移除的可靠时机是 worker 返回 `COMPLETED`、`TERMINATED` 或 `FAILED` 后。

## 预算规则

统一原则：只要推进了可能消耗时间的动作，就必须消耗预算。

必须计入预算的动作包括：

- 从 frontier 中 poll 一个候选节点。
- 调用 `canTraverse(...)` 或 `matcher.matches(...)` 前后的候选检查。
- 生成一个邻居候选。
- 扫描一个盒扫坐标。
- 推进一个空 shell、空 slice 或空 queue。
- `nextFrontier -> currentFrontier` 搬运一个元素。
- 向 consumer 提交一个目标前后的状态检查。

预算耗尽时返回 `YIELDED`，不能继续循环“顺手做完”。终止请求优先级高于预算让出：如果 `control.isCancelRequested()` 为 true，任务应尽快在安全点返回 `TERMINATED`。

## 分阶段实施计划

### 阶段 1：并行框架协议

目标：建立结构化任务结果与取消请求语义，不先改遍历器算法。

实施点：

- 新增 `ParallelTaskResult`。
- 新增 `ParallelTickControl` 或把控制方法扩展到 `ParallelTickContext`。
- 新增 `ParallelWorkBudget`，每片工作预算由配置控制：服务端默认 `parallelTickServerWorkBudgetUnits = 64`，客户端默认 `parallelTickClientWorkBudgetUnits = 640`。
- 将 `RegisteredTask` 增加 `cancelRequested`、`state`、`cancelReason`。
- `unregister()` 改为设置取消请求并唤醒窗口，避免把 `Future.cancel(true)` 作为常规取消路径。
- `runSlicesInCurrentTick(...)` 根据 `ParallelTaskResult` 处理继续、让出、完成、终止。
- `awaitNextWindow()` 只等待本任务对应阶段，不要因其他阶段窗口返回 `null` 结束任务。
- `enterWorker()` 前在锁内确认窗口仍打开、阶段仍匹配、tick 未过期。

验收：

- 现有任务可通过临时适配返回 `CONTINUE/COMPLETED`。
- `./gradlew.bat compileJava` 通过。
- 旧行为不应因接口替换而改变主要功能。

当前状态：已完成并通过 `./gradlew.bat compileJava`。

后续补充：单片工作预算已改为可配置项，服务端读取 `general.parallelTickServerWorkBudgetUnits`，客户端读取 `client.parallelTickClientWorkBudgetUnits`，执行器创建每片预算时会按当前配置值生效。

### 阶段 2：任务适配层

目标：让服务端规划与客户端预览先使用新协议，但 traverser 仍可暂时通过适配运行。

实施点：

- 改造 `AbstractFloodFillPlanningStrategy` 的 lambda 为显式任务类或小型内部对象，便于持有取消状态与清理逻辑。
- 改造 `BlockBoxScanPlanningStrategy` 同上。
- 改造 `ChainPreviewController` 的预览任务，取消时只返回 `TERMINATED`，不再写入旧 generation。
- `ChainRuntimeState.clear(...)` 与 `stopExecutionPreservingDrops(...)` 调用 `plannerSubscription.unregister()` 后，语义上视为“请求取消”，不要假设 worker 已同步停止。
- 如果需要同步等待某任务安全终止，应通过执行器在 `endStage(...)` 的 worker 屏障完成，而不是在状态清理路径中阻塞等待。

验收：

- 松开连锁键、切模式、清理 session、客户端停止预览都只请求取消。
- 旧任务晚返回时不能污染新 session 或新 preview generation。
- `./gradlew.bat compileJava` 通过。

当前状态：已完成并通过 `./gradlew.bat compileJava`；阶段 6 已清理旧遍历接口，当前服务端规划与客户端预览通过预算化协议运行。

### 阶段 3：洪泛与 GT 线缆预算化

目标：消除 `nextFrontier -> currentFrontier` 整体搬运不计入预算的问题。

实施点：

- 将 `ChainSearchAlgorithm.step(...)` 拆成可预算状态机。
- 为 frontier 轮转增加显式状态，例如 `PROCESS_CURRENT_FRONTIER`、`ROTATE_FRONTIER`。
- 每搬运一个 `nextFrontier` 元素消耗预算。
- `GregTechCableTraverser.step(...)` 采用同样的轮转逻辑。
- 邻居生成每生成一个候选消耗预算；如果生成被中断，需要保存当前节点和邻居游标。

验收：

- 大范围矿脉或线缆网络不会在单个 `step(...)` 内搬空大 frontier。
- 预算耗尽时返回 `YIELDED`，下片继续同一层轮转或同一节点邻居生成。
- `./gradlew.bat compileJava` 通过。

当前状态：已完成并通过 `./gradlew.bat compileJava`；`FloodFillTraverser` 和 `GregTechCableTraverser` 已接入预算化协议，旧兼容路径已在阶段 6 清理。

### 阶段 4：伐木大邻域预算化

目标：消除 `LoggingFloodFillTraverser` 在单个匹配节点内遍历任意大 offset 列表的风险。

实施点：

- 为伐木遍历器增加当前中心节点、offsetIndex、邻居生成中状态。
- `createNeighborOffsets(...)` 当前会一次性构造完整列表；如果 `chainLoggingShellLayers` 仍允许极大值，需考虑改为三重坐标游标，不一次性分配完整 offset 列表。
- 每推进一个 offset 消耗预算。
- 预算耗尽时保存 offset 游标并返回 `YIELDED`。

验收：

- 极大 `chainLoggingShellLayers` 不会导致 seed 或单片 step 大量分配/遍历。
- 伐木预览与服务端规划都能跨片恢复。
- `./gradlew.bat compileJava` 通过。

当前状态：已完成并通过 `./gradlew.bat compileJava`；`LoggingFloodFillTraverser` 已改为三维 offset 游标，不再预构造完整邻域列表。

### 阶段 5：盒扫、隧道、区段清理统一预算化

目标：让所有 traverser 使用同一预算协议，避免固定上界实现成为例外。

实施点：

- `BoxScanTraverser` 已有游标，改为使用 `control.tryConsumeWork(...)` 替代内部固定 `MAX_SCAN_COORDINATES_PER_BATCH` 或与其合并。
- `TunnelBoxScanTraverser` 的空切片推进必须消耗预算；大量空切片不能在单片中无限推进。
- `SectionClearTraverser` 的壳层扫描改为坐标游标，虽然区段固定 4096 坐标，也应与协议一致。
- 所有 `step(...)` 返回结构化 `TraversalStepResult`。

验收：

- 所有 traverser 的空队列/空切片/空壳层推进均计入预算。
- `./gradlew.bat compileJava` 通过。

当前状态：已完成并通过 `./gradlew.bat compileJava`；`BoxScanTraverser`、`TunnelBoxScanTraverser`、`SectionClearTraverser` 已逐个接入预算化协议，且每个 traverser 改完后均单独编译通过。

### 阶段 6：清理旧接口与文档

目标：移除临时适配层，固定新边界。

实施点：

- 删除或废弃 `ChainTraverser.step(context, int maxNodes, ...)` 的旧接口。
- 更新类注释，明确每个遍历器都是可预算、可恢复、可取消的协作任务。
- 更新 `docs/设定值层/边界.md`，记录并行 Tick 新稳定边界。
- 如发现踩坑，按 `docs/反馈层/错误预防.md` 通则沉淀，详情落 `docs/反馈层/errors/`。

验收：

- `grep` 确认旧 `maxNodes` 遍历接口不再被生产路径调用。
- `./gradlew.bat compileJava` 通过。
- 工作区 diff 可审阅，提交信息按仓库规范编写。

当前状态：已完成并通过 `./gradlew.bat compileJava`；旧 `ChainTraverser.java` 已删除，`BudgetedChainTraverser` 已成为独立接口，源码 grep 已确认不再存在旧 `step(context, int maxNodes, ...)` 方法、`MAX_SCAN_PER_SLICE` 常量或 `maxNodes` 兼容路径。

## 关键文件

- `src/main/java/club/heiqi/qz_miner/parallel/ParallelTickContext.java`
- `src/main/java/club/heiqi/qz_miner/parallel/ParallelTickTask.java`
- `src/main/java/club/heiqi/qz_miner/parallel/ParallelTickExecutor.java`
- `src/main/java/club/heiqi/qz_miner/parallel/ParallelTickSubscription.java`
- `src/main/java/club/heiqi/qz_miner/chain/planner/BudgetedChainTraverser.java`
- `src/main/java/club/heiqi/qz_miner/chain/planner/ChainTraversalSupport.java`
- `src/main/java/club/heiqi/qz_miner/chain/planner/ChainSearchAlgorithm.java`
- `src/main/java/club/heiqi/qz_miner/chain/planner/FloodFillTraverser.java`
- `src/main/java/club/heiqi/qz_miner/chain/planner/LoggingFloodFillTraverser.java`
- `src/main/java/club/heiqi/qz_miner/chain/planner/GregTechCableTraverser.java`
- `src/main/java/club/heiqi/qz_miner/chain/planner/BoxScanTraverser.java`
- `src/main/java/club/heiqi/qz_miner/chain/planner/TunnelBoxScanTraverser.java`
- `src/main/java/club/heiqi/qz_miner/chain/planner/SectionClearTraverser.java`
- `src/main/java/club/heiqi/qz_miner/chain/planner/AbstractFloodFillPlanningStrategy.java`
- `src/main/java/club/heiqi/qz_miner/chain/planner/BlockBoxScanPlanningStrategy.java`
- `src/main/java/club/heiqi/qz_miner/chain/client/ChainPreviewController.java`
- `src/main/java/club/heiqi/qz_miner/chain/state/ChainRuntimeState.java`

## 实机回归建议

- 服务端：按住连锁键启动大范围 `CHAIN` 矿脉规划，中途松开按键，确认安全停止且 tick 不绕过 worker 收口。
- 服务端：大范围 `AREA` 空区域扫描，确认空壳层不会造成单 tick 长卡顿。
- 服务端：GT 线缆替换规划，确认连通关系和二阶段重连状态仍正确。
- 客户端：按住连锁键预览后快速切换目标，确认旧 generation 不污染新预览。
- 客户端：预览中退出世界或断线，确认无 GL 上下文错误，无预览任务继续写状态。
- 配置：调大 `chainRadius`、`chainMaxBlocks`、`chainLoggingShellLayers`、`parallelTickServerWorkBudgetUnits`、`parallelTickClientWorkBudgetUnits` 做压力回归。

## 接手建议

阶段 1、阶段 2、阶段 3、阶段 4、阶段 5 和阶段 6 已完成。下一个 Agent 推荐优先执行实机回归：大范围 `CHAIN`、大范围 `AREA` 空区、GT 线缆替换、客户端预览快速切换、预览中断线/退出世界。若后续新增 traverser，必须直接实现 `BudgetedChainTraverser` 并把所有推进动作纳入 `ParallelTickControl` 工作预算。
