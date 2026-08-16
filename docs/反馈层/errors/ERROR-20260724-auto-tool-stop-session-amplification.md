# ERROR：自动工具目标校验失败放大为整轮取消

> **历史错误记录。** CHAIN 能力断链、AREA 宽进、局部 `SKIP_TARGET` 与零成功推进仍有效；逐目标网络 gate 已由 5.2 服务端本地批量路径取代，poll/count budget 与 50ms 节流又在 5.3 改为 shared soft deadline。下文按发生时版本保留。

## 错误现象

普通 `CHAIN/AREA` 在已经成功消费若干目标后，只要后续某个目标的实时采掘权威或接替候选校验失败，执行桥就曾把统一的 `STOP` 提升为 planner 协作取消，整条任务提前结束。2026-07-24 服务端日志中 311 次 `PlanStarted` 有 120 个唯一会话以 `auto-tool-takeover-stopped` 收口，且包含大量不应进入接替事务的 round 0。

后续纠偏又一度把所有采掘模式都改成无工具 planning admission，并让 `CHAIN_LOGGING` 的 matcher=false 节点继续桥接。这恢复了爆破宽进，却错误放宽了普通连锁拓扑：没有任何冻结能力的匹配方块仍可跨越，和最终用户语义不符。

## 触发场景

- round 0 当前目标 `canHarvest=false` 或权威调用异常。
- 活动 round 的 APPLIED 工具实时复验失败，或稳定空手租约命中的单个目标权威失败。
- TAKEOVER 候选在服务端写前发现 fingerprint 已变化或剩余耐久不足。
- 非空 anchor 的客户端对当前目标精确回复无候选 DECLINE。
- 普通执行器连续 `canExecute=false`、`execute=false` 或目标跳过时，循环只按成功数限额，可能在单 tick 无界 drain；零成功消费又不发布 `ExecutionAdvanced`。
- AREA 在 PlanStarted 时空手、低耐久、未知工具或背包尚无工具，若错误绑定冻结能力，会把合法爆破/隧道/同块/区段目标在进入主线程前永久删除。
- CHAIN 若完全取消冻结能力门，或让 `CHAIN_LOGGING` 的 matcher=false 节点继续生成邻居，则没有任何可用能力的匹配方块会错误桥接后续拓扑。
- 未声明 Forge harvestTool 的未知模组工具可由稳定 `Item.canHarvestBlock` 判定，但旧候选只允许 TiC 类名适配且要求 `getDigSpeed>1`，产生白名单与低效率假阴性；效率回调抛错还会污染完整库存快照。

## 根本原因

`AutoToolSwapTakeoverCoordinator` 只有 `PROCEED/WAIT/STOP` 三态，把“当前目标不能安全执行”与“会话身份或未结算事务不能继续”折叠为同一 STOP。`ChainExecutionEventBridge` 随后对任一 STOP 调用 `stopForTakeover`，局部拒绝被放大为规划取消。

同时，普通执行循环用成功执行数控制 `maxBreakPerTick`，没有把已经 poll 但被执行器拒绝或失败的目标计入预算；推进事件也只在成功数大于零时发布，无法表达“有消费、零成功”的合法进度。

问题本质是把三种边界混为一谈：执行期单目标失败、CHAIN 的能力拓扑边界、AREA 的空间目标范围。执行期失败应局部跳过；CHAIN 需要 PlanStarted 冻结能力决定是否连通；AREA 则必须把空间与子模式结构目标宽进后逐个交主线程尝试。

候选层还把模组类名和效率猜测当成资格硬门。前者漏掉未知但合法的 Item 实现，后者把不可靠的可选性能事实升级成库存事务可信度。

## 当时修复方案（v3）

- round service 增加内部 `SKIP_TARGET` 终态，只授予已由当前 intent 安全结算并推进 sequence 的 candidate fingerprint、低耐久与非空 anchor 精确无候选 DECLINE；后者使用固定 `no-candidate` 诊断。
- coordinator 增加 `GateResult.SKIP_TARGET`。round 0 实时权威、无 pending 的失效 block/meta、APPLIED 复验、空手租约复验及上述已结算 gate 只拒绝当前目标；deadline、发送失败、等待目标漂移、endpoint/round/generation、库存、ledger、sync/orphan 等保持 STOP。
- 执行桥四态处理：WAIT 不 poll，STOP 复用协作取消，SKIP_TARGET 只 poll 精确队首并记录 consumed/skipped、绝不调用执行器，PROCEED 正常执行。
- 普通执行以实际 poll 数限制每 tick 处理量；任一消费都发布一次 `ExecutionAdvanced`，只有成功执行设置 50ms 节流。规划未完成的瞬时空队列不结束，全跳过在规划完成后沿正常完成与生命周期清理收口。
- `ChainPlanDiag stage=ExecutionFinished` 汇总 confirmed/consumed/skipped/succeeded，不增加逐目标日志。
- 顶层 `CHAIN` 在 PlanStarted 主线程冻结主手、selector 排序后的背包全部工具与空手能力并集；server/preview 的基础、矿石、伐木及对象组扩展 matcher 共用该 evaluator。candidate=false 或 matcher=false 都不入队、不增加 confirmed、不生成邻居。
- 顶层 `AREA` 不捕获或绑定工具能力；可收获全部、隧道、同块、矿石和区段清理只保留各自空间/结构与世界安全门，目标逐个交主线程执行。INTERACT 与 GT SPECIAL 保持专用路径。
- null harvestTool 且材质需要工具时直接调用稳定 `Item.canHarvestBlock`，不按 TiC/模组类名白名单；资格硬门只保留 canHarvest 与耐久储备。`getDigSpeed` 仅为可选事实，其 `RuntimeException/LinkageError` 只令 effective=false，不使槽位或库存 untrusted。
- `INTERACT` 与非 GT `SPECIAL` 复用同一实际 poll 预算和零成功推进；`BlockHarvestActionExecutor` 透传 `tryHarvestBlock` boolean。GT 线缆等待 planner、预校验与单 tick 原子例外保持不变。

## 当时预防措施（v3）

- 新增 poll 前异步资源门时必须先证明终态是否已退休 sequence；没有退休的 timeout、send failure、等待目标漂移不得降为局部跳过。
- 回归矩阵必须同时覆盖可跳过与必须 STOP 两类，并断言被跳过目标零 `canExecute/execute`、迟到 intent 零库存副作用。
- `maxBreakPerTick` 一律按所有实际 poll 计数，不能按成功数；`executedThisTick=0` 的消费推进必须能喂 watchdog。
- 流式执行测试必须覆盖规划未完成时队列瞬空，以及 planning complete 后全跳过的 `ExecutionFinished → LifecycleCleanup` 正常收口。
- 连通模式测试必须分别证明 candidate=false、matcher=false 都不可桥接，matcher=true 才可入队并扩展；低预算跨分片结果须与充足预算一致以守 I3。
- planner 测试必须按顶层 mode 断言：CHAIN 绑定冻结能力且 worker 不读实时库存，AREA 不绑定工具能力并保留各子模式空间/结构 matcher。不得用“所有模式都冻结”或“所有模式都宽进”的单一策略代替分流。
- 工具事实测试必须直接覆盖通用 Item 虚调用、低效率与 `getDigSpeed` 两类异常；不能靠生产注释中出现旧 adapter 名称制造假阳性。
- 所有非 GT 普通执行模式都要用“全失败批次”验证实际 poll 上限与 `ExecutionAdvanced(0, ...)`；同 tick 消费后 STOP 的事件顺序必须先推进、后收口，且不得借机改写 STOP 来源。

## v4 后续收窄

- TAKEOVER/DECLINE 不再占用普通 `actionSequence`，改用每 round 独立单调且发布即烧号的 `takeoverRequestId`。发送失败、deadline、目标或执行身份漂移都只退休精确旧请求并 `SKIP_TARGET`；下一目标重新读取库存并使用新请求号，迟到旧请求在任何库存或诊断副作用前拒绝。
- 服务端写前的库存上下文、选中槽、锚点、ledger role、槽冲突、读取、候选 fingerprint/耐久等失败都结算为当前请求的 `SKIP_TARGET`，不再升级为普通会话 STOP；普通 sequence 不受 takeover 结果推进。
- mutation 正常返回后进入单项 committed publication pending。库存同步失败或 ActionResult sender 未正常返回都保留 exact intent，重试只发送完整 window 0 与同一结果，mutation 恒为一次。该 pending 在确认前不可被 deadline、漂移、release 或 IDLE 抢占，Coordinator 必须保留 issued ownership 并返回 WAIT；WAIT 未消费目标时不得发布伪 `ExecutionAdvanced` 喂 watchdog。
- STOP 仅保留真实 key/round/lifecycle/fatal 收口；断线、重生、切维度、服务停止和真实 watchdog 直接清理 round/pending，不跨 lifecycle 恢复。客户端对无回包使用固定 20 tick immutable retry，对 `SYNC_FAILED` 使用后续 tick exact retry；新请求只替换未提交的旧 takeover，A→B→C 排队仅保留最新 C，迟到 A/B result 不污染当前 ordinary in-flight 或新 ownership。
- v4 回归必须把 request cache 的 r/r+1、连续 sync/result sender failure、双槽/三槽 mutation 一次、committed pending × 漂移/deadline/release/IDLE/watchdog/lifecycle，以及客户端 cadence/exact retry/乱序作为同一矩阵；详细根因与线性化点见 `ERROR-20260725-auto-tool-request-sync-recovery.md`。

## 验证边界

纯 JVM 测试可证明事务终态、库存零副作用、poll 预算、推进事件、生命周期事件顺序及伐木连通图的纯逻辑；不能替代真实 client/dedicated 的网络时序、主线程世界事实、掉落窗口、树形模组方块与连续大批次运行态。未取得实机证据前继续标记 **INCOMPLETE**。
