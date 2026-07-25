# 自动工具空手优先级、规划取消与预览 seed 漂移

## 错误现象

- 普通 CHAIN/AREA 在空手时把队首直接视为可执行，石头或矿石可能绕过工具接替请求；反过来，pre-edge 已进入规划时又只看当刻主手，背包存在合格工具也可能规划不到目标。
- 工具接替 STOP 与 worker 完成并发时，可能出现迟到 `PlanCompleted`、PLANNING 期伪造 `ExecutionFinished` 或规划订阅未协作停止。
- 工具接替等待门已进入 DECLINED/STOP/APPLIED 后，松键 CLOSE 仍被“pending 非空即拒绝”挡住，round 可永久停在 CLOSING；同时 `planningComplete` 若早于 `PlanCompleted` 入队可见，执行桥会先发布 `ExecutionFinished`。
- SWAP/TAKEOVER/RESTORE 的库存布局首次可见后重启预览时重新读取 origin；origin 已被破坏后会读到 air，导致预览丢失原 block/meta/tile seed。

## 触发场景

- 空手按住连锁键破坏需要工具的方块，或在首个破坏事件先于客户端 SWAP 完成的 pre-edge 时序中启动规划。
- worker 完成声明与主线程 TAKEOVER STOP、watchdog 或生命周期清理处于同一时间窗口。
- 首块已破坏后，客户端收到任一已应用库存动作并在原版库存同步首次可见时刷新锁定预览。

## 根本原因

- 规划、客户端候选与执行门没有共享一个候选能力模型；空手被编码为特殊直通分支，而不是排在真实工具之后的通用 Forge 无工具候选。
- 规划订阅安装、外部取消和 worker 完成分散在不同对象，没有单一单调终局决定谁可以发布终态事件。
- round 动作分派把 pending gate 的存在性检查放在 action sequence 和收口动作终裁之前；规划完成路径也把本地 COMPLETED/volatile 标志写在跨线程 publication 之前。
- 预览只租赁 origin 坐标，没有租赁首次捕获的完整 `BlockSeedSnapshot` 与 world identity；工具刷新错误地把“重新计算”解释为“重新采样 seed”。

## 修复方案

- 由 `ToolHarvestEligibility` 统一真实工具的收获与耐久硬门；效率只保留为可选候选事实。`PlanningToolCapabilitySnapshot` 仅为顶层 CHAIN 在 PlanStarted 主线程冻结当前手持、selector 排序的背包全部工具与空手候选，worker 只以冻结集合决定 CHAIN admission 和断链。AREA 爆破不绑定该快照，按空间与子模式结构宽进。执行仍由主线程 `ChainHarvestRules.canHarvest` 实时复验。
- `AutoToolSwapRoundService` 仅把合法、精确且原 anchor 为空的 `DECLINE_TAKEOVER` 结算为内部 `DECLINED`；Coordinator 对 APPLIED 和 DECLINED 都在 poll 前复验。超时、拒绝、身份/库存漂移和权威异常保持 STOP。
- `ChainExecutionContext` 线性化订阅安装、协作取消和完成发布；取消胜出时 worker 静默终止，完成胜出时先观察合法 `PlanCompleted` 再收口。
- `AutoToolSwapRoundService` 在身份与 sequence 校验后允许 CLOSE/RESTORE/ABANDON 停止并退休等待门，再按既有 ledger 语义收口；`ChainExecutionContext` 以 PlanCompleted 成功 publication 为先行线性化点并最后暴露 `planningComplete`，失败时由规划桥单次发布固定原因 PlanCancelled。
- `ChainPreviewController` 首次捕获完整 seed 并绑定 world 租约；三种库存动作的 verified-layout effect 只重算同一 seed，生命周期、新目标和 world 变化清租约与去重身份。

## 预防措施

- 资源候选必须显式定义顺序与最终权威：当前可用手持优先，真实候选其次，空手最后；不得用方块名单或空值分支代替能力模型。
- admission 必须先按顶层 mode 定义所有权：CHAIN 冻结能力用于拓扑断链，AREA 不按工具删空间目标；不得把任一侧规则外推到全部模式。
- CHAIN 快照不得冒充执行权威；worker 禁读实时库存，主线程在每次队列消费前保留完整玩家/事件/耐久复验。执行中工具损坏不回写已规划拓扑。
- `getDigSpeed` 的低值或异常都不是候选资格硬门；只有 canHarvest 与统一耐久储备决定工具是否可用。
- 异步终局必须由单一线性化对象决定发布权；协作取消只请求 worker 到安全点，不强杀或绕过 `endStage`。
- 重算派生结果时先区分“不可变输入租约”和“可变派生状态”；已破坏 origin 的 seed 不得从世界回读。
