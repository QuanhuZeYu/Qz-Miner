# 决策：planning 空气候选按 context 固定 1024:1 计费

## 控制论定位

反馈层·决策。

## 核心结论

- 只有候选事务本次读取精确得到 `Blocks.air` 才享受折扣；同一 `ChainSearchContext` 每累计提交 1024 个空气消耗 1 个正 work budget，尾余数自然丢弃。
- `PlanningCandidateWorkBudget` 是六个 `BudgetedChainTraverser` 的唯一计费器，只保存 `airRemainder(0..1023)`；余数不放在每片重建的 `ParallelTickControl`/`ParallelWorkBudget`，也不跨 context、玩家、round 或 server/preview 任务共享。
- 候选事务的线性化顺序固定为：检查 cancel/`shouldYield()` → 读取当前方块 → 配额边界先扣正预算 → 返回提交结果 → 调用方推进 cursor/visited/frontier/queue。第 1024 个扣费失败时所有持久状态不变，下一片重读同一坐标，接受世界事实已经变化。

## 事务边界

- 新邻居生成或队首 poll 与首次 candidate 判定合并为一个正常候选事务；非空气事务正常扣 1。空气提交不调用 candidate filter、matcher 或 consumer，也不入 frontier。
- duplicate、半径拒绝、frontier 搬运、空队列推进、shell/slice 切换、GT connected-direction 解析、matcher 与 consumer 不属于空气候选事务，继续独立收费。
- AREA 的空气只跳当前空间坐标并继续扫描；Flood/Logging/GT 的空气节点不入队、不扩展。已排队目标后来变空气时，在 `peek()` 后先完成可恢复计费，成功后才 poll 一次。
- `FloodFillTraverser` 与 `GregTechCableTraverser.seed(...)` 只初始化可恢复状态；世界/candidate 探测在首次带 control 的 `step(...)` 完成。GT origin 参与语义、规划完成门与主线程原子执行不变。

## 身份与兼容边界

- null、液体、基岩、业务 matcher 拒绝和模组自定义 air-like 方块均不享受折扣。
- 不修改 `ParallelTickControl`、`ParallelWorkBudget`、`ParallelTickExecutor`、配置 schema、watchdog、状态机、wire、toolswap 或 Qz-UILib。
- 默认 server/client 单片预算均为 640；1024:1 只改变空气候选事务计费，不承诺整次遍历绝对只消耗 `air/1024`。

## 被否决方案

- **把余数放进 work budget/control**：对象每片重建，会在 slice/tick 边界丢余数。
- **每个 traverser 各自计数**：模式间易漂移，无法证明同一合同。
- **先推进游标再在第 1024 个扣费**：预算不足会丢坐标；回滚又易重复 queue/visited。
- **先 poll 再确认空气**：配额不足时无法保持队列不变。
- **每片入口先扣 shell/slice 再尝试坐标**：work budget=1 会在同一入口永久重复扣费。

## 运行态边界

纯计费、六 traverser、低预算恢复、空气断链与 AREA 后续实体已有自动化合同；client/dedicated 大空区、真实 15ms deadline/window/watchdog 和 GT 线缆替换仍需运行态回流，不由本地 JVM/Gradle 结果替代。

## 演进

- 2026-07-25 首次确立固定 1024:1、context 余数所有权与配额边界事务顺序。
