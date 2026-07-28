# 决策：自动工具换位服务端权威事务

## 结论

- 普通 `CHAIN/AREA` 的执行期工具选择、库存 mutation 与恢复全部属于服务端主线程；`AutoToolSwapServerBatchService` 是仓内唯一 physical ledger owner。客户端不写库存，也不决定新服务端热路的逐目标候选。
- 每个目标都从服务端实时读取 block/meta、当前手和个人库存 `0..35`，按服务器已提交配置选择工具，并在同一 tick 继续消费既有 poll budget。正常结果只有 `PROCEED/SKIP_TARGET/STOP`，不发送 `AutoToolSwapTakeoverRequest`，也不等待客户端 tick、ACK 或网络 `WAIT`。
- `AutoToolSwapRoundService` 只保留 v4 wire projection/control：round、普通 sequence、exact result cache、phase 与 closure。它不创建库存端口、不执行 mutation，也不持有 physical ledger。
- 5.2 协议保留 v4 五包及动作/状态/结果 code；takeover packet、独立 request ID、客户端 fallback 与 capability negotiation 已删除。

## 实现锚

- `AutoToolSwapServerBatchService`：按玩家持有唯一 local session、physical ledger、owner/batch epoch、冻结 policy 与 publication gate；实现二槽交换、三槽轮转、segment/final restore、异常 image 分类和幂等 lifecycle finalizer。
- `MinecraftAutoToolSwapCandidateSource`：在服务端主线程逐目标重读 live 世界、选中槽、当前手及 `0..35` 库存，复用 `ToolHarvestEligibility`、`ToolCandidateOrder` 与 `ChainHarvestRules`。
- `ChainExecutionEventBridge`：普通 `CHAIN/AREA` 使用 `begin → prepare/poll/execute → end`；`INTERACT` 与 GT 线缆 `SPECIAL` 保持既有分支。terminal 路径在 `ExecutionFinished/LifecycleCleanup` 前先通过 local finalizer。
- `MinecraftAutoToolSwapInventoryPort`：只交换/轮转 `InventoryPlayer.mainInventory` 中的真实引用；publication 每次重新 `markDirty()` 并用 `sendContainerToPlayer(player.inventoryContainer)` 发布完整 window 0。
- `ServerAutoToolSwapRequestDispatch`：仍在服务端主线程结算 v4 control intent，但不创建库存端口；sender 失败只 exact 重发同一 projection 结果。
- `AutoToolSwapClientReducer` / `AutoToolSwapClientAdapter`：round 激活后直接发送零库存 mutation 的 `FREEZE`，不扫描库存、不解析目标，也不响应旧服接替请求。

## 原因

- 逐目标 client RTT 会让普通批次在每个队首停在网络 `WAIT`，吞吐受客户端 tick、延迟与 ACK 约束；服务端已经持有目标、库存和采掘权威，无需把候选决策往返远端。
- 服务端本地单 owner 能把 mutation、ledger 和 restore-before-cleanup 放在同一主线程顺序内，同时避免 RoundService、客户端期望与 coordinator 各持一份可写账本。
- 原版完整 window 0 仍是客户端库存视图的权威发布；批内允许显示滞后比复制一套逐 mutation 网络协议更简单，也符合本轮已接受的吞吐优先取舍。

## 本地 session 与库存边界

- session 身份绑定玩家 UUID、endpoint/world identity、dimension、generation、`serverRoundId`（`0` 也是合法值）、anchor slot、单调 owner epoch 与创建时 policy epoch。活动 owner 不接受不同 round/generation/world/anchor 的新 batch；stale token 在候选扫描和库存 factory 前停止。
- 服务器 Authority 来自当前 `CommittedSnapshot` 的 `autoToolSwapEnabled` 与 `autoToolPrioritySelectors`。字段路径暂保留 `client.*` 旧名，但远程客户端不会上传或覆盖服务器值；活动 session 冻结创建时策略，reload 只影响下一 session。
- 候选以 `canHarvest + 剩余耐久至少 2` 为硬门，selector 首次命中优先，同优先级及未命中候选按槽 `0..35` 排序。效率只作为事实，不否决可收获工具；未声明 harvestTool 的未知工具继续使用稳定 `Item.canHarvestBlock`。
- 当前手可用时零 mutation；首次借用执行二槽交换。已有 ledger 且需新候选时一次轮转 `A<-D,C<-A,D<-C`，original anchor 角色始终由唯一 carrier 持有；无候选或选中当前 carrier 时先做 segment restore，再以恢复后的真实主手终裁当前目标。
- 每次 mutation 前重验 target、选中槽、库存安全上下文和 exact 候选内容；mutation 后仍调用 `ChainHarvestRules.canHarvest`。无候选、目标/候选漂移、低耐久、工具破损或实时采掘拒绝只跳当前目标；库存上下文、owner 身份或受保护槽第三布局不可信时停止 batch。
- mutation 正常返回是唯一 commit 点。mutation 抛错后只比较已知 pre/post image：pre-image 可安全跳过，post-image 提交 ledger 后进入 recovery-only，未知 image 记录 conflict 并停止；任何分支都不盲目重放 mutation。
- restore 只交换校验后的当前真实栈，不把旧 ItemStack 内容写回，因此保留合法的耐久、数量、能量与 NBT 演化。受保护槽出现既非 borrowed layout 也非 restored layout 的第三布局时 fail closed，不搬运、合并或覆盖外部物品。

## 批量 publication

- 一次 ordinary batch 是单玩家、单 server tick 的执行范围；一个 batch 内可有多次 mutation，但不逐次同步。
- 每次 committed mutation 只把玩家 publication gate 标脏。每玩家每 server tick 最多尝试一次完整 window 0 publication；普通 `CONTINUE` 由 server tick END 统一 flush，terminal batch 则先 final restore，再尝试发布最终布局，然后才释放 `ExecutionFinished/LifecycleCleanup/IDLE`。
- 同 tick ordinary 收口、松键和 lifecycle 共用同一 `lastPublicationAttemptTick`，不会各发一次。同步失败不回滚、不重放 mutation，只留下无 mutation 权限的 visibility tombstone，下一 tick 重试 publication；连续失败不会无限阻塞连锁 cleanup。

## 生命周期边界

- natural finish、key release、PlanCancelled、ordinary STOP、WatchdogTimeout、logout、respawn、dimension change、clone 与 server stop 都先进入幂等 local finalizer，再清 projection、execution registry、状态或 endpoint 映射。
- 非 forced 的取消、watchdog 与 lifecycle 事件必须匹配当前 local owner 的 round/generation；迟到旧事件不得恢复或清除新 owner。forced lifecycle 按玩家 UUID 收口当前 owner。
- clone 同时尝试 old/new endpoint，并只在一侧匹配已知 borrowed/restored layout 时恢复；endpoint 不可用、mutation 未应用或未知第三布局都返回显式分类，不伪报成功。
- `PacketKeyState(false)` 是松键 physical restore 的可靠入口，先于 `AutoToolSwapRoundService.onKeyReleased` 和 `LifecycleCleanup`；local finalizer 后服务端直接把 wire projection 终结为 `FINISHED`，迟到 `CLOSE` 幂等接受。客户端 `CLOSE` 不能取得或清除 local ledger。
- finalizer 完成安全恢复或分类冲突后会移除 mutation owner；publication 失败只保留 visibility tombstone。重复 finalizer 不执行第二次 mutation。

## 客户端观察边界

- 5.2 client 在 RoundStart 激活后直接发送 `FREEZE`；ordinary 候选与 mutation 全在服务端本地完成，客户端只观察 phase 与原版库存 publication。
- 客户端库存显示与预览可以在批内滞后，不新增中途 mutation 专用同步。客户端不以 `windowClick`、库存扫描或容器包监听取得写权。
- `serverRoundId`、phase 与 packet surface 只用于 wire 归因，不授予 physical ownership。5.1 及更旧端由严格 minor-family 握手拒绝，不提供 mixed-minor fallback。

## 规划宽进与执行实时权威边界

- planner admission 按顶层模式分流。`CHAIN_BASE/ORE/LOGGING` 在 PlanStarted 主线程冻结当前主手、selector 排序后的背包全部工具与空手能力并集，server 与 preview 的正式 matcher 及对象组扩展共用该 evaluator；匹配方块没有任何冻结可用能力时不入队、不生成邻居，形成明确断链。worker 只读冻结 ItemStack 副本，不读实时库存。
- `AREA_HARVESTABLE_ALL/TUNNEL/SAME_BLOCK/ORE/SECTION_CLEAR` 不捕获或绑定 `PlanningToolCapabilitySnapshot`，继续按预算化空间范围、同块/矿石结构身份以及空气、液体、基岩和脚底安全宽进。爆破确认数表示应由主线程逐个尝试的目标，不是工具能力承诺；INTERACT 与 GT SPECIAL 保持既有专用路径。
- `PlanningToolCapabilitySnapshot` 是 CHAIN 规划的不可变能力输入，不是执行权威。主线程 `ChainHarvestRules.canHarvest` 始终按当前玩家、工具、世界、脚底与耐久逐目标终裁；执行中工具损坏不改写已规划拓扑，单目标无候选或实时拒绝只跳当前目标，之后补入库存仍可影响尚未消费目标。
- 主线程普通 `CHAIN/AREA` 以 `peek → local prepare → poll` 排序消费。`PROCEED/SKIP_TARGET` 才 poll，`SKIP_TARGET` 不调用执行器，`STOP` 保留当前队首并进入 restore 屏障；正常 local prepare 不返回网络 `WAIT`。
- 非 GT 普通 `CHAIN/AREA/INTERACT/SPECIAL` 的 `maxBreakPerTick` 都是 poll/processed 预算，不是成功数预算；成功、执行器拒绝/失败与目标跳过共同计数。任一消费均发布 `ExecutionAdvanced`（可为零成功），只有成功执行设置 50ms 节流；同 tick 已消费后出现 STOP 时先发布推进再收口。GT 线缆的等待、预校验、单 tick 原子执行和旧取消例外不变。

## 客户端预览刷新边界

- 新 client/new server 没有逐 mutation ActionResult 或 verified-layout effect；预览是 observer，可在一个 local batch 内滞后。ARMED 未触发时可继续采样准星；本地成功破坏当前 frozen origin 后由 controller 本地租约先锁定，直到服务端 phase/generation 证明终态，不能在 active round 用下一次准星采样替换 origin。本轮不为预览增加执行 gate 或专用同步。
- `ChainPreviewController` 的 frozen seed、world identity、generation 隔离及生命周期清理保持不变。预览滞后不能反向阻塞服务端 ordinary poll budget。

## 不变量影响

- **I1/I4**：候选读取、mutation、restore 与 C2S 结算都在服务端主线程；Netty 只捕获 raw 字段并投递。客户端 S2C 仍经连接/世界 token gate。
- **I7**：服务端 hard lifecycle 在 endpoint/state 清理前 final restore 或分类冲突；客户端 lifecycle 只复位 observer/projection，不跨连接恢复旧 round。
- **I10**：local service 不直接写连锁五态。`serverRoundId` 与 generation 只用于事件/session 身份，terminal restore barrier 完成后仍由既有事件驱动状态机合法收口。

## 兼容边界

- 5.2 family 内的客户端/服务端允许 patch、stable/prerelease/dev 混连，但共同受五包 v4 wire 冻结约束；`5.1.x` 或其他 minor 不兼容，也不提供 capability negotiation。
- packet ID、字段布局、round 状态与普通 sequence 不是第三方扩展 API；它们作为 5.2 内部网络兼容基线不得在该 family 内变化。需要不兼容演进时必须升级新 minor。
- 长期稳定事实是服务端库存写权、local mutation 单次提交/异常不重放、restore-before-cleanup 与原版完整库存 publication。

## 未完成实机验收

服务端本地批量路径的 JVM/Gradle 证据以活动任务结果为准；无论静态门禁结论如何，用户实机 client/dedicated 仍未完成。仍需覆盖：

- 新 client/new server 连续 `CHAIN/AREA` 大批次、同 tick 多次二槽/三槽接替、segment/final restore、真实 poll budget 与批内 observer 滞后。
- 5.2 stable/pre/dev mixed-patch、5.1/畸形拒绝和 missing-mod 实际 channel 行为。
- 松键、自然完成、STOP、watchdog、logout、respawn、切维度、clone、server stop 的真实 restore-before-cleanup 与每玩家/tick publication 0/1。
- 第三方同时改写受保护槽、endpoint 不可用或完整 window 0 连续发送失败时的 conflict/tombstone 诊断与玩家可见结果。
- HUD/预览在 local batch 中允许滞后后的最终收敛。

在上述矩阵完成前，不把自动工具运行态标记为实机已通过。

## 演进

- 2026-07-28：升级严格 5.2 minor family，删除 takeover packet/coordinator、客户端库存扫描/target rematch、旧服 fallback 与对应配置字段；服务端本地 physical ledger 和五包 round/phase control 继续保留。
- 2026-07-27：校正 preview observer 边界：本地成功破坏当前 frozen origin 是 phase 异步投影窗口的租约线性化点；客户端不伪造 phase/generation、不增加网络或执行 gate，verified-layout 仍只派生刷新同一 seed。
- 2026-07-27：普通 `CHAIN/AREA` 从逐目标 `TakeoverRequest/WAIT` 迁移为服务端本地批量接替。新增唯一 local physical ledger owner、服务端实时候选、二槽/三槽/segment/final restore、mutation image 分类、每玩家/tick publication gate 与 restore-before-cleanup；RoundService 降为零库存 projection，客户端改为 direct `FREEZE` 并保留旧服 fallback。v4 wire、5.1 family、配置 schema、五态转移表与 GT/INTERACT 分支不变，运行态继续 INCOMPLETE。
- 2026-07-25：协议原子升级为 v4，保留六包、动作码、字段顺序和固定 framing；TAKEOVER/DECLINE 使用独立烧号 request ID。当时的逐目标请求、empty-hand lease 与 committed publication WAIT 只作为旧服兼容历史，不再是新 ordinary production 热路。
- 2026-07-16 至 2026-07-24：形成 v3 接替、首块前重匹配、server mutation-once、完整 int target identity、CHAIN 冻结能力与 AREA 宽进等前置合同；其中 wire、规划边界和通用 Item API 继续有效，逐目标 client authority 已由本决策替代。
