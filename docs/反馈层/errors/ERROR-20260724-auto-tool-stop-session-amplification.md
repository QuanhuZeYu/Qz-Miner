# ERROR：自动工具目标校验失败放大为整轮取消

## 错误现象

普通 `CHAIN/AREA` 在已经成功消费若干目标后，只要后续某个目标的实时采掘权威或接替候选校验失败，执行桥就把统一的 `STOP` 提升为 planner 协作取消，整条连锁提前结束。2026-07-24 服务端日志中 311 次 `PlanStarted` 有 120 个唯一会话以 `auto-tool-takeover-stopped` 收口，且包含大量不应进入接替事务的 round 0。

同一体验还被两处前置放大：planner 曾按 PlanStarted 冻结的工具集合删掉爆破、隧道、区段清理和普通连锁目标，使规划开始时空手、工具损坏或背包稍后补工具都无法恢复拓扑候选；`INTERACT` 与非 GT `SPECIAL` 又按成功数预算，连续失败可能单 tick 无界消费且不发布零成功推进。

## 触发场景

- round 0 当前目标 `canHarvest=false` 或权威调用异常。
- 活动 round 的 APPLIED 工具实时复验失败，或稳定空手租约命中的单个目标权威失败。
- TAKEOVER 候选在服务端写前发现 fingerprint 已变化或剩余耐久不足。
- 非空 anchor 的客户端对当前目标精确回复无候选 DECLINE。
- 普通执行器连续 `canExecute=false`、`execute=false` 或目标跳过时，循环只按成功数限额，可能在单 tick 无界 drain；零成功消费又不发布 `ExecutionAdvanced`。
- PlanStarted 时空手、低耐久、未知工具或背包尚无工具，冻结能力返回 NONE，合法爆破/隧道/同块/区段目标在进入主线程前即被永久删除。
- 未声明 Forge harvestTool 的未知模组工具可由稳定 `Item.canHarvestBlock` 判定，但旧候选只允许 TiC 类名适配且要求 `getDigSpeed>1`，产生白名单与低效率假阴性。
- 玩家确认爆破、爆破隧道和连锁伐木都应采用“失败方块不破坏、后续目标继续”的体验；其中 `CHAIN_LOGGING` 若中间原木已通过 candidate filter、却被采掘 matcher 软拒绝，旧遍历会在该点停止扩展并截断后续整棵树。

## 根本原因

`AutoToolSwapTakeoverCoordinator` 只有 `PROCEED/WAIT/STOP` 三态，把“当前目标不能安全执行”与“会话身份或未结算事务不能继续”折叠为同一 STOP。`ChainExecutionEventBridge` 随后对任一 STOP 调用 `stopForTakeover`，局部拒绝被放大为规划取消。

同时，普通执行循环用成功执行数控制 `maxBreakPerTick`，没有把已经 poll 但被执行器拒绝或失败的目标计入预算；推进事件也只在成功数大于零时发布，无法表达“有消费、零成功”的合法进度。

`LoggingFloodFillTraverser` 又把 matcher 同时当成“是否入执行队列”和“是否属于连通图”的判定。后者本应由原木/对象组 candidate filter 独占，两个语义折叠后，一个目标的采掘软失败会被放大为拓扑断路。

此外，规划 admission 错把“此刻拥有可用工具”当成结构身份事实；候选资格又把模组类名与效率猜测当硬门。两者都把应由服务端主线程逐目标读取的瞬时资源提前固化到了 worker 规划阶段。

## 修复方案

- round service 增加内部 `SKIP_TARGET` 终态，只授予已由当前 intent 安全结算并推进 sequence 的 candidate fingerprint、低耐久与非空 anchor 精确无候选 DECLINE；后者使用固定 `no-candidate` 诊断。
- coordinator 增加 `GateResult.SKIP_TARGET`。round 0 实时权威、无 pending 的失效 block/meta、APPLIED 复验、空手租约复验及上述已结算 gate 只拒绝当前目标；deadline、发送失败、等待目标漂移、endpoint/round/generation、库存、ledger、sync/orphan 等保持 STOP。
- 执行桥四态处理：WAIT 不 poll，STOP 复用协作取消，SKIP_TARGET 只 poll 精确队首并记录 consumed/skipped、绝不调用执行器，PROCEED 正常执行。
- 普通执行以实际 poll 数限制每 tick 处理量；任一消费都发布一次 `ExecutionAdvanced`，只有成功执行设置 50ms 节流。规划未完成的瞬时空队列不结束，全跳过在规划完成后沿正常完成与生命周期清理收口。
- `ChainPlanDiag stage=ExecutionFinished` 汇总 confirmed/consumed/skipped/succeeded，不增加逐目标日志。
- `CHAIN_LOGGING` 在 candidate=true/matcher=false 时不调用 consumer、不增加 confirmed，但从该已确认连通节点继续预算化生成邻居；candidate=false 仍立即截断。被拒原木自身与后续每个目标都不绕过采掘权威。
- 生产 server/preview planner 不再捕获或绑定 `PlanningToolCapabilitySnapshot`；采掘 matcher 只保留空气、液体、基岩、脚底与模式结构身份，工具、库存、效率、耐久和 `Block.canHarvestBlock` 全部推迟到服务端主线程逐目标终裁。
- null harvestTool 且材质需要工具时直接调用稳定 `Item.canHarvestBlock`，不按 TiC/模组类名白名单；资格硬门只保留 canHarvest 与耐久储备，效率仅作候选事实。
- `INTERACT` 与非 GT `SPECIAL` 复用同一实际 poll 预算和零成功推进；`BlockHarvestActionExecutor` 透传 `tryHarvestBlock` boolean。GT 线缆等待 planner、预校验与单 tick 原子例外保持不变。

## 预防措施

- 新增 poll 前异步资源门时必须先证明终态是否已退休 sequence；没有退休的 timeout、send failure、等待目标漂移不得降为局部跳过。
- 回归矩阵必须同时覆盖可跳过与必须 STOP 两类，并断言被跳过目标零 `canExecute/execute`、迟到 intent 零库存副作用。
- `maxBreakPerTick` 一律按所有实际 poll 计数，不能按成功数；`executedThisTick=0` 的消费推进必须能喂 watchdog。
- 流式执行测试必须覆盖规划未完成时队列瞬空，以及 planning complete 后全跳过的 `ExecutionFinished → LifecycleCleanup` 正常收口。
- 连通模式测试必须分别证明 matcher 软拒绝仍可桥接、candidate 拒绝不可桥接、拒绝节点不增加 confirmed，并以低预算跨分片结果对照守住 I3。
- planner 测试必须证明生产装配不再引用冻结工具能力，worker 代码段不读库存、不调用玩家/未知工具收获回调；结构 matcher 仍分别守同块、矿石、原木与 TileEntity 身份。
- 所有非 GT 普通执行模式都要用“全失败批次”验证实际 poll 上限与 `ExecutionAdvanced(0, ...)`；同 tick 消费后 STOP 的事件顺序必须先推进、后收口，且不得借机改写 STOP 来源。

## 验证边界

纯 JVM 测试可证明事务终态、库存零副作用、poll 预算、推进事件、生命周期事件顺序及伐木连通图的纯逻辑；不能替代真实 client/dedicated 的网络时序、主线程世界事实、掉落窗口、树形模组方块与连续大批次运行态。未取得实机证据前继续标记 **INCOMPLETE**。
