# Qz-Miner 设计导向标（North Star）

> 这是本项目的**中心思想宪章**。任何架构决策、线程模型调整、兼容层设计、掉落与生命周期改动，都必须先与本文件对照。
> 当代码与本文件冲突时，**先改文件再改代码**——要么说服自己遵守，要么显式记录一次"偏离"并说明理由（见文末《修订纪律》）。
> 它的存在不是为了好看，而是为了在需求摇摆、上游迁移、长时任务跨会话时，**守住那条不能弯的脊柱**。

> **来源声明**：本宪章由 `docs/设定值层/边界.md`（现状锚）、`docs/反馈层/errors/`（踩坑）、`docs/诊断层/reviews/REVIEW-20260528-代码框架设计风险排查.md`（系统性风险审查）向上提炼而成。核心信条与不变量随项目认知深化持续校准。

---

## 0. 如何使用本文件

- **写新功能前**：读《核心信条》和《决策检查清单》，确认方案没踩《反模式》。
- **动线程模型前**：确认没破坏《关键不变量》I1-I3（主线程主权 / 协作式取消 / 预算化遍历）。
- **动网络/生命周期/掉落前**：确认没破坏 I4-I7。
- **做可选模组兼容前**：确认守 I6 反射安全边界。
- **跟随 GTNH 上游迁移前**：确认 I8 注入点仍有效。
- **评审代码时**：用《关键不变量》当 checklist，任何一条被破坏都应阻断合并。
- **架构争论时**：回到《一句话中心思想》，多数争论本质是忘了"我们当初为什么这么定"。

---

## 1. 一句话中心思想

> **并行线程只负责"快速且可安全中断地算出要挖哪些方块"；主线程独占"真实破坏方块、生成掉落、修改世界"。二者经预算化协议与主线程屏障协作，全程掉落不丢、生命周期干净。**

整个系统的所有设计，都是这句话的展开。读不懂某个模块为何如此设计时，回到这句话。

---

## 2. 第一性原理

1. **Minecraft 1.7.10 的世界不是线程安全的**。任何世界写入、方块破坏、掉落实体生成，都必须在服务端主线程完成。并行线程只能读世界、产规划结果，绝不能直接动手。
2. **强杀比卡顿更危险**。并行 worker 一旦在安全 tick 空间外被强杀，可能留下半破坏状态、泄漏的世界锁、未释放的 GPU 资源。协作式（让 worker 自己停到安全点）是唯一安全的中止方式。
3. **大范围连锁必须切片**。一次连锁可能涉及成千上万个方块，不可能在单 tick 内算完。预算化、可跨分片恢复是稳定接口，一次性构造是大范围崩溃的根源。
4. **掉落是玩家的资产，不是过程的中间态**。已收集但未释放的掉落，绝不能因会话清理、线程异常、生命周期切换而丢失。
5. **可选模组永远可能不存在或签名不兼容**。兼容层必须以"能力不可用则降级"为常态，以"触发对端类初始化"为事故。

---

## 3. 核心信条（Tenets）

### 信条一：世界写入主权在主线程
- **是什么**：方块破坏、掉落实体生成、世界状态修改只在服务端主线程。并行规划线程只读世界、只产规划结果、只标记规划完成。
- **为什么**：MC 1.7.10 世界非线程安全；并行线程直接写世界是 native crash 与状态损坏的直接来源。
- **代价**：主线程是吞吐瓶颈，需用节流执行（`ChainExecutor`）控速。

### 信条二：协作式优于强制式
- **是什么**：并行 Tick 任务的暂停/恢复/终止，由任务在内部预算化安全点自行判断（`ParallelTickControl`）；禁超时强杀、禁 `Future.cancel(true)` 常规取消、禁主线程绕过 `endStage` 屏障。
- **为什么**：强杀可能让 worker 停在安全 tick 空间外，继续触碰世界状态，风险高于卡顿本身。
- **代价**：取消不是即时的，必须等 worker 停到安全边界后窗口才能关闭。

### 信条三：预算化遍历是唯一稳定接口
- **是什么**：所有 traverser 实现 `BudgetedChainTraverser`，工作计入 `ParallelTickControl` 预算，状态可跨分片恢复。不再有 `step(context, int maxNodes, ...)` 兼容路径。
- **为什么**：大范围连锁必须能按预算切片让出主线程，否则单 tick 卡死或阻塞世界。
- **代价**：traverser 必须显式管理可恢复状态机，实现成本高于一次性遍历。

### 信条四：掉落不丢失
- **是什么**：玩家级掉落缓冲（`ChainPlayerDropBuffer`）与会话生命周期解耦；实体生成失败或抛异常时回填缓冲；释放兜底链：当前位置 → 重生点 → 出生点 → 告警丢弃。
- **为什么**：掉落是玩家资产。会话提前清理、线程异常、生命周期切换都不应吞物品。
- **代价**：缓冲需独立管理生命周期，跨会话兜底依赖 MC 1.7.10 重生点/出生点 API。

### 信条五：网络与生命周期统一收口
- **是什么**：所有 `SimpleNetworkWrapper` 服务端入包与客户端状态同步，经 `ServerMainThreadDispatcher`/`ClientMainThreadDispatcher` 收口后再触碰主线程语义状态；玩家退出/重生/切维度/克隆/单人退主菜单统一经 `ChainStateService.cleanupPlayerState`；客户端断线/世界卸载经 `ClientMainThreadDispatcher` 停并行预览并释放 GPU 资源。
- **为什么**：Netty IO 线程、客户端线程直接触碰主线程状态是竞态与 native crash 的主要入口。
- **代价**：每个网络处理器需显式 dispatch，不能图省事直接处理。

### 信条六：可选模组反射安全边界
- **是什么**：类探测不触发静态初始化，成员解析吞掉 `LinkageError`/`SecurityException`，不扫描对端客户端签名方法；能力不可用以降级处理。
- **为什么**：服务端反射扫描客户端专属签名类会触发 `IIconRegister` 等客户端类加载，导致 dedicated server 初始化崩溃（见 `errors/ERROR-20260601-lootgames-server-client-signature.md`）。
- **代价**：兼容能力必须显式降级路径，不能用"反正有就行"的乐观反射。

### 信条七：上游适配跟随
- **是什么**：矿石时运上限修复拦截上游 `fortune > 3` 表达式（随 GTNH adapter 迁移注入点），不靠重算 `Random.nextInt` 参数；GT 线缆刷新跟随上游 API（`issueTileUpdate`）。
- **为什么**：只重算 `nextInt` 不拦上游截断，时运上限突破无效；注入点随上游版本漂移，必须复核。
- **代价**：每次 GTNH 大版本升级都要复核注入点是否仍稳定。

---

## 4. 核心数据流与职责边界

一次连锁请求从触发到清理，自上而下。`│` 上标注的是**阶段间如何交接**：

```
①  触发层        按键 / 破坏方块 / 右键交互 → ChainSession 请求
        │  请求入队
②  规划层(并行)  ParallelTickExecutor 上跑 BudgetedChainTraverser
                 预算化遍历 + 匹配器筛选 → 产出待破坏候选
        │  只产规划结果、只标记规划完成（不写世界、不切执行态）  ← I1
③  执行层(主线程) ChainExecutor 节流消费候选 → BlockHarvestActionExecutor
                 真实破坏方块（主线程独占）                      ← I1
        │  HarvestDropsEvent 聚合
④  掉落层(主线程) ChainDropCollector → ChainPlayerDropBuffer(玩家级)
                 → 主线程释放掉落实体（失败回填缓冲）             ← I4
        │  会话结束
⑤  清理层        ChainStateService.cleanupPlayerState 统一收口    ← I5
                 客户端断线/卸载 → ClientMainThreadDispatcher 释放 GPU  ← I5
```

- **并行段 = ②**：职责是"快速且可安全中断地算出要挖哪些方块"。只读世界。
- **主线程段 = ③④**：职责是"真实破坏、生成掉落、修改世界"。
- **交接契约 = 预算化候选队列 + 规划完成标记**：并行段写候选、标记完成；执行段消费候选、判断结束。最终结束统一由主线程 `ChainExecutor` 判断，并行段不得在空队列时切 `IDLE` 或清 session（见 `errors/ERROR-20260605-chain-drop-final-target-race.md`）。

主模式 `CHAIN / AREA / INTERACT / SPECIAL` 共用此数据流，差异在规划层的遍历器与匹配器策略。

---

## 5. 关键不变量（Invariants）— 评审时逐条核对

这些是**任何提交都不得破坏**的硬约束。破坏其一即应阻断合并。

- **I1**　世界权威写入（方块破坏、掉落实体生成、世界状态修改）只在服务端主线程。并行规划线程只读世界、只产规划结果，不直接写世界、不切执行状态。
- **I2**　并行 Tick 任务只协作式取消（任务在内部预算化安全点判定），禁超时强杀、禁 `Future.cancel(true)` 常规取消、禁主线程绕过 `endStage` 屏障。`Future.cancel(true)` 仅保留在执行器最终 `shutdown()` 兜底路径。
- **I3**　所有 traverser 实现 `BudgetedChainTraverser`，工作计入 `ParallelTickControl` 预算、状态可跨分片恢复；禁 `step(context, int maxNodes, ...)` 兼容路径与一次性构造。
- **I4**　所有 `SimpleNetworkWrapper` 服务端入包与客户端状态同步，经 `ServerMainThreadDispatcher`/`ClientMainThreadDispatcher` 收口后再触碰主线程语义状态。
- **I5**　掉落缓冲玩家级（`ChainPlayerDropBuffer`）与会话生命周期解耦；实体生成失败或抛异常时回填缓冲；禁在会话清理/重启/生命周期切换时丢弃已收集掉落。
- **I6**　可选模组反射：类探测不触发静态初始化、成员解析吞掉 `LinkageError`/`SecurityException`、不扫描对端客户端签名方法；能力不可用以降级处理，统一经 `ClassNameCompatSupport`/`ReflectiveMemberSupport`。
- **I7**　玩家生命周期事件（退出/重生/切维度/克隆/单人退主菜单）统一经 `ChainStateService.cleanupPlayerState`；客户端断线/世界卸载经 `ClientMainThreadDispatcher` 停并行预览任务并释放 `ChainPreviewMeshCache` 持有的 GPU 资源。
- **I8**　矿石时运上限修复拦截上游 `fortune > 3` 表达式（随 GTNH `GTOreAdapter`/`BWOreAdapter`/`GTPPOreAdapter` 迁移注入点），禁只重算 `Random.nextInt` 参数。
- **I9**　`ParallelTickExecutor.endStage(...)` 是主线程屏障，必须等待所有 active worker 停到安全边界；进入新分片前确认窗口仍打开、阶段匹配、tick 未过期。
- **I10**　连锁框架状态变更经唯一的 `ChainStateMachine` 合法转移表驱动（5 态 IDLE/ARMED/PLANNING/RUNNING/FINISHING），状态按玩家 UUID 分槽独立维护（per-player `Map<UUID, PlayerPhaseSlot>`）。状态机是各槽 `phase`/`generation` 的唯一写权威，外部入口只能 `bus.publish` 事件、不能直接 `transition`。越界（非法源→目标组合）即丢弃事件并诊断日志，不得静默改态或抛异常中断 drain。worker 线程只发事件不切态是 I10 对 I1 的延伸保证。T4 ARMED→PLANNING 推进源为 `BlockBreakObserved` 或 `RightClickObserved` 或 `LeftClickObserved`（破坏观测/右键观测/左键观测三事件入口，GT 线缆替换走左键入口），三者均 `++generation` 后转移。

---

## 6. 反模式（Anti-patterns）— 见到就应警觉

这些是会**悄悄侵蚀中心思想**的常见诱惑：

- **并行线程动手**：规划线程直接破坏方块、生成掉落实体、修改世界状态。→ 破坏 I1。
- **强杀 worker**：用超时关闭窗口、`Future.cancel(true)` 常规取消、主线程绕过 `endStage` 等待。→ 破坏 I2，可能让 worker 停在安全空间外。
- **一次性遍历**：traverser 不走预算化、在构造器里一次性构造完整 offset 列表、单片内无预算连续推进。→ 破坏 I3，大范围连锁卡死。
- **网络处理器直碰状态**：`SimpleNetworkWrapper` 处理器在 Netty IO 线程或客户端线程直接触碰 `ChainStateService` 等主线程语义状态。→ 破坏 I4（见 `errors/ERROR-...-issue234` 同类排查）。
- **掉落绑会话**：把掉落缓冲挂在 `ChainSession → ChainRuntimeState`，会话清理时连掉落一起清。→ 破坏 I5（见 `errors/ERROR-20260528-session-drop-buffer-coupling.md`）。
- **规划线程切空闲**：规划线程观察到空队列就直接把执行状态切 `IDLE` 或清 session。→ 破坏 I1 执行态主权，导致最后一次掉落脱离聚合链路（见 `errors/ERROR-20260605-chain-drop-final-target-race.md`）。
- **乐观反射**：反射可选模组触发对端静态初始化、不吞 `LinkageError`、`Class#getMethod` 扫描对端客户端签名方法。→ 破坏 I6，dedicated server 崩溃。
- **只改 nextInt**：矿石时运上限突破只重算 `Random.nextInt` 参数，不拦上游 `fortune > 3` 截断。→ 破坏 I8，突破无效。

> 经验法则：当你想"就这一次，直接在并行线程/Netty 线程里顺手改一下"时，**那一次就是 native crash 或吞物品的起点**。要么按宪章走收口，要么走《修订纪律》显式登记偏离。

---

## 7. 决策检查清单（动手前过一遍）

新增/修改功能前，自问：

1. 这次世界写入/方块破坏/掉落生成在服务端主线程吗？（I1）
2. 涉及并行任务取消时，走的是协作式安全点吗？没有超时强杀或 `Future.cancel` 常规取消？（I2）
3. 新增/修改的 traverser 实现了 `BudgetedChainTraverser`？工作计预算？状态可跨分片恢复？（I3）
4. 网络入包/客户端状态同步经主线程 dispatcher 收口？（I4）
5. 掉落路径有失败回填？缓冲没绑在会话上？（I5）
6. 可选模组反射守了"不触发静态初始化/吞 LinkageError/不扫客户端签名"三件套？（I6）
7. 生命周期清理走 `ChainStateService.cleanupPlayerState` 或 `ClientMainThreadDispatcher` 统一收口？（I7）
8. 矿石时运相关改动拦的是 `fortune > 3` 表达式，不是 `nextInt`？（I8）
9. 动了 `ParallelTickExecutor` 时，`endStage` 屏障仍等所有 worker 到安全边界？（I9）
10. 连锁状态变更经 `ChainStateMachine` 合法转移？越界丢弃+诊断？worker 只 publish 不切态？（I10；T4 由 `BlockBreakObserved`、`RightClickObserved` 或 `LeftClickObserved` 触发，三者均 `++generation`；状态按玩家 UUID 分槽，applyTransition 仍是唯一写点）

**十条全过，才动手。**

---

## 8. 修订纪律

- 本文件是**活的宪章**，可以改，但改的成本应当被有意抬高，以免随意妥协。
- **允许偏离**，但偏离必须显式：在下方《偏离登记》追加一条，写明"违反了哪条信条/不变量、为什么、影响范围、何时回填"。隐性偏离（不登记就绕过）是唯一不可接受的行为。
- 信条（第 3 节）和不变量（第 5 节）的改动，应被视为重大架构变更，需要比改代码更慎重的讨论，并经用户确认。
- 每次大版本，回看《偏离登记》：要么把偏离转正（改宪章），要么把债还掉（改代码）。
- 已还清的偏离即从《偏离登记》移除，不再保留；登记只承载尚未回填的活跃偏离。

### 偏离登记（Deviation Log）

<deviation-log>

<!-- 偏离模板
<deviation id="YYYY-MM-DD-简述">
  <what>违反了哪条信条/不变量，做了什么</what>
  <why>为什么这次必须偏离，代价是什么</why>
  <scope>影响范围（哪些路径/场景触发）</scope>
  <status>待回填：回填方案与优先级</status>
</deviation>
-->

</deviation-log>

---

> **最后一句**：这套系统的价值不在任何单项技术，而在"并行算得快但不停在不安全的地方、主线程写得准但不丢玩家的掉落"这条贯穿始终的纪律。
