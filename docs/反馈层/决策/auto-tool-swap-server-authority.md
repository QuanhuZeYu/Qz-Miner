# 决策：自动工具换位服务端权威事务

## 结论

- 自动工具换位的唯一库存写权属于服务端主线程。客户端只读取库存事实、按本地优先级选择候选并提交 intent，不直接修改库存，也不通过原版容器点击完成事务。
- Qz-Miner 协议负责 round 建立、动作意图、动作结算和专用阶段关联；真实库存视图仍由服务端应用 mutation 后通过原版完整 window 0 publication 下发。
- 自动工具协议 v4 作为客户端与服务端共同升级的原子边界，不允许 v3/v4 混合协商；两端必须使用同一 Qz-Miner 版本，旧端在 RoundStart/整包校验处 fail-closed。v4 保留六包、动作码、字段顺序与固定帧，只将 TAKEOVER/DECLINE 的既有 long 关联字段解释为独立 `takeoverRequestId`。

## 实现锚

- `NetworkMain.register()`：注册自动工具两个 C2S 与四个 S2C；新增固定 60 字节接替目标请求。
- `ServerAutoToolSwapRequestDispatch`：将 C2S 原始请求投递到服务端主线程，并连接 round 服务、库存端口和 S2C 回执发送。
- `AutoToolSwapRoundService`：维护服务端 round、普通动作序列、独立接替请求水位、可逆账本和单项 exact publication pending，执行请求幂等与动作结算。
- `AutoToolSwapRoundService` 的 `RoundRecord`：额外维护至多一个空手回退租约及 create/hit/invalidated 汇总计数；不形成按目标增长的缓存。
- `MinecraftAutoToolSwapInventoryPort`：在服务端玩家个人库存中只执行一次槽位 mutation；每次 publication 尝试重新标脏并通过 `sendContainerToPlayer(...)` 发送完整个人库存。
- `AutoToolSwapTakeoverCoordinator`：普通 CHAIN/AREA 在队首 `peek()` 后建立 `PROCEED/WAIT/SKIP_TARGET/STOP` 门；请求局部失败只退休精确请求和队首，committed publication pending 则保留 issued ownership 并继续 WAIT。
- `ClientProxy`：按 `ctx.netHandler` 捕获连接 token，经客户端主线程 connection/world gate 将四个 S2C 发布给 adapter。
- `AutoToolSwapClientReducer`：客户端 cycle、nonce/round/普通 sequence/request ID/phase 归因、库存双门、关闭原因、固定 cadence 与 exact retry 的唯一可变业务权威，以 Event 输入并输出不可变 Effect。
- `AutoToolSwapClientAdapter`：承接 gate 后的 S2C，只采样 Minecraft 事实、执行 reducer effect 和网络 I/O；后续 C2S 在 `ClientTick` 发送。
- `AutoToolSwapClientProtocolValidator`：无字段，只负责 raw、wire enum、范围与单包结构校验，不判断历史关联。
- `ToolHarvestEligibility`：显式 harvestTool 交 Forge 等级语义终裁；无显式 harvestTool 且材质需要工具时直接调用稳定 `Item.canHarvestBlock`，不维护模组类白名单。

## 原因

- 客户端库存点击与原版库存包监听会把写权、确认时序和恢复责任分散到两端，无法稳定判断迟到包、换栏、GUI、断线和新旧 round 的归属。
- 服务端持有玩家、个人库存窗口、cursor、创造模式和真实槽位内容等安全事实，适合在一个主线程事务中完成校验、交换、记账与同步。
- 原版完整容器 publication 已经是库存视图的权威发布路径；Qz-Miner 只需承载换位命令、回执与 round 关联，不应复制一套库存同步协议。完整重发让连续失败恢复不依赖服务端容器差异缓存是否已经被前一次失败尝试消费。

## 协议与库存边界

- `AutoToolSwapRoundService` 按玩家 UUID、在线 endpoint 与 `serverRoundId` 维护单个 round。SWAP/RESTORE/FREEZE/ABANDON/CLOSE 使用严格递增的普通 `actionSequence`；TAKEOVER/DECLINE 使用独立单调 `takeoverRequestId`，请求建立时即烧号且永不回滚。两个命名空间分别只缓存最近一个已确认 exact 结果；发行 `r+1` 后旧 `r` exact 与同号改 payload 均在库存和诊断边界前拒绝。
- `FROZEN` 是工具已固定但 round 仍可继续接收专用活跃 phase 的稳定活跃态；只有 `CLOSING` 取得单调关闭语义并禁止新 `SWAP/FREEZE`。`PLANNING/RUNNING/FINISHING` 只要求“确保已冻结”：服务端已 FROZEN、同一 FREEZE in-flight 或已成功结算时，客户端只维持本地 FROZEN 投影，不发送第二个 intent。
- 候选由客户端按 `client.autoToolPrioritySelectors` 排序；`ToolHarvestEligibility` 以目标收获结论与“剩余耐久至少 2 点”作为资格硬门，不可损耗工具使用 `Integer.MAX_VALUE`。目标实际效率仍被采样为候选事实，但不再单独否决低效率、可收获的未知工具。服务端仍不相信客户端候选结论，只校验个人库存 window 0、空 cursor、非创造模式、槽位范围、当前热栏、剩余耐久、内容 fingerprint 与可逆 ledger。
- `MinecraftAutoToolSwapInventoryPort` 直接交换 `InventoryPlayer.mainInventory[0..35]`；交换或三槽轮转正常返回就是唯一 mutation commit。后续每次 publication 都调用 `markDirty()` 与 `sendContainerToPlayer(player.inventoryContainer)` 重发完整 window 0，不使用 `detectAndSendChanges()` 的差异缓存作为恢复依据。
- 库存比较分两层：每个 SWAP/RESTORE intent 携带捕获当刻的双槽完整 fingerprint，服务端与当前双槽 exact 比较以阻断陈旧请求；ledger 跨 round 只租赁稳定 role（registry id + stable subtype），允许 count、damage、energy 与 NBT 合法变化。空槽只兼容空槽，活动工具允许同 role 或破损后的空槽。
- RESTORE 交换的是校验通过后的两个当前真实栈，不使用 ledger 旧内容回写；因此不会回滚动态变化，也不会复制或吞掉栈。sameRole 只证明角色所有权，不承诺对象 instance identity。原 anchor 非空时 candidate 仍须保持同 role；原 anchor 为空时允许 candidate 被任意当前真实栈占用，RESTORE 将占位栈直接交换到主手并把借用工具送回原槽。该窄例外不放宽 intent 双槽 exact 新鲜度、活动工具 role/empty 或库存安全上下文。
- `ABANDON(5)` 是无法安全 RESTORE 时的显式收口动作：请求使用 ledger 真实双槽与两个 canonical control fingerprint。服务端只接受当前 endpoint/round/sequence、`SWAPPED/FROZEN/CLOSING`、匹配 ledger 槽位；成功时不读取、不交换、不同步库存，只清 ledger/keyDown 并进入 FINISHED。重复相同 intent 复用动作缓存，旧身份或拒绝不得清当前账本。
- `TAKEOVER(6)`/`DECLINE_TAKEOVER(7)` 是 FROZEN 中途的独立同 round 事务。服务端为队首目标建立唯一 pending，并在建立时消耗新的 `takeoverRequestId`；客户端下一 ClientTick 使用请求 block id/meta 采样。无 ledger 双槽交换；有 ledger 单次轮转 `A<-D,C<-A,D<-C`，ledger 滚动到新候选且保留最初 anchor。原 anchor 为空、deadline 前精确匹配 pending 的 DECLINE 结算为内部 `DECLINED`，Coordinator 随后重验空手、库存安全与实时采掘权威；非空 anchor 的 canonical DECLINE 表示无候选，零库存访问结算为 `SKIP_TARGET` 并记录固定 `no-candidate`。其它写前校验失败同样只形成当前请求的 `SKIP_TARGET` 结算，不推进普通 sequence。
- takeover target 与首块前准星身份共用完整 signed `int` 值域：存在目标的 block ID 为 `1..Integer.MAX_VALUE`，metadata 为 `0..Integer.MAX_VALUE`，block ID `0` 保留为空气/缺席 sentinel。协议 v4 继续使用两个 `int` 字段、既有字段顺序、60-byte 固定帧和六包注册；负值、存在目标 ID 0 与协议 v3 均 fail-closed。
- pending takeover 只约束继续执行动作，不得阻断 round 终裁。服务端先完成 endpoint/round/幂等与普通 sequence 校验，再允许 `CLOSE`、`RESTORE`、`ABANDON` 退休尚未 committed 的等待门并沿既有 ledger 合同结算。若当前 takeover 已提交 mutation 或 result publication，deadline、目标/执行身份漂移、release 与 IDLE 都不得抢占；Coordinator 必须保留 issued ownership 并返回 WAIT，直到 exact ActionResult 发送成功并确认 gate 终态。旧 request ID、同号改 payload 或旧身份的迟到 TAKEOVER 在任何库存读取、写入、同步和诊断快照前拒绝。
- 合法空 anchor DECLINE 被执行桥消费后，只有同一主线程时刻的库存安全门、空手、完整 0..35 `AutoToolSwapStackState.sameContent` identity 与实时采掘权威都成立，才安装单项 round-scoped 空手回退租约。租约绑定 endpoint/round/generation、当前热栏锚点与不含坐标的 block id + 完整 metadata；不与 ledger/pending 共存，并随关闭、终态或生命周期清理。
- 同一租约后续命中只省略 TAKEOVER/DECLINE 网络往返，不省略每目标 `ChainHarvestRules.canHarvest`。block/meta、任一槽数量/耐久/NBT/内容、选中槽、GUI/cursor、endpoint/round/generation 变化均清旧租约并重新执行真实候选优先决策；A→B→A 必须重新协商 A。诊断不逐目标记录 hit，只在 create/invalidated 与 round close 汇总计数。
- TAKEOVER 写前重新校验 endpoint/round/generation/request ID、pending 目标身份、热栏锚点、exact fingerprint、候选剩余至少 2 点、受保护槽角色及槽位互异。库存上下文、锚点、ledger role、槽冲突、读取失败、candidate fingerprint 与低耐久均在零写入边界形成当前请求的 `SKIP_TARGET` 结算；只有 ActionResult sender 正常返回后才确认门终态。mutation 后同步失败返回 `SYNC_FAILED` 并保留 committed pending；exact retry 只重发完整库存，mutation 恒为一次，恢复后发布原结算。APPLIED 被执行桥消费后仍须按新主手实时 `ChainHarvestRules.canHarvest` 复验，失败只跳过当前目标。
- 每个 round 至多保留一个 exact publication pending。不同 intent 在 pending 期间直接拒绝；普通动作只在 ActionResult sender 成功后的 `confirmIntentResultPublication` 推进 sequence，takeover 也只在该点把 WAITING 提交为 APPLIED/DECLINED/SKIP_TARGET。客户端收到 `SYNC_FAILED` 后不进入布局观察，而是在后续 tick 重发同一 intent；无回包按 20 tick cadence 重发，BEGIN/SWAP/RESTORE/CLOSE/ABANDON 以 120 tick 硬 deadline 收口，活跃 FREEZE/TAKEOVER 仍由真实 watchdog 或 lifecycle 控制。
- 客户端不调用 `windowClick`，不监听 C0E/S32/S2F/S30 作为自动工具事务确认；动作成功后只观察服务端同步回来的受保护槽位是否达到 ledger 目标布局。
- 首块成功前的普通匹配使用客户端 light 快照中的 `ABSENT` 或 `blockId + metadata` 目标身份；坐标、TileEntity/NBT 不参与。同身份维持 10 tick 扫描水位，block/meta 变化立即触发 latest-target-wins，连续两个 END tick ABSENT 才确认丢失。已有 ledger 时先完成旧 RESTORE，再为最终有效目标 FULL；已发送 SWAP/RESTORE 不取消，也不在旧 ledger 上发送第二个普通 SWAP。
- `serverRoundId` 在服务端激活 PENDING round 时分配，随后作为不可变身份随 `ChainEvent` 传播。工具阶段由 `PacketAutoToolSwapRoundPhase` 单独关联，客户端只接受当前 round 且严格递增的 `phaseSequence`；通用 `PacketChainPhaseSnapshot` 不承担工具关联。
- 采掘资格先检查输入；目标声明 harvestTool 时只采用 Forge 等级语义，禁止 fallback 绕过。仅 null harvestTool 且材质仍要求工具时，直接调用 Minecraft owner 上的稳定 `Item.canHarvestBlock(Block, ItemStack)`；不探测 TiC 或其它模组类型，不解析成员、不扫描类名，调用异常 fail-closed。

## 生命周期边界

- GUI 打开或玩家切换热栏锚点时，若 role 租约仍兼容则对已有换位执行 RESTORE，round 保持 OPEN；若租约不兼容则显式 ABANDON 并按既有安全合同进入 IDLE/WAIT_RELEASE，不把不安全布局误判为已恢复。
- 松开连锁键、配置从启用改为禁用，或专用 round 收到 IDLE 时，按 RESTORE 后 CLOSE 的顺序收口。
- 专用 round 自然进入 IDLE 时，即使物理连锁键仍持续按住，也必须先让旧 round 完整执行 RESTORE→CLOSE。只有 CLOSE 精确结算为 FINISHED、客户端协议已复位到 IDLE，且期间没有松键/快速重按、配置关闭、生命周期复位、拒绝或 orphan，才在下一次 `ClientTick` 创建新 nonce 并先提交 RoundStart；提交成功后由 `KeyListener` 补发 fresh `PacketKeyState(KEY_CHAIN, true)` 激活新服务端 round。旧 round 不复活，也不增加状态机捷径。
- 连接断开、世界替换、硬事务 deadline、包失配、ABANDON 拒绝、服务停止或真实 watchdog 时不伪造成功也不盲目发送恢复。普通同步/ActionResult 发送失败先在同 lifecycle 内 exact retry；断线、重生、切维度和服务停止等硬生命周期统一复位 reducer，并由服务端清理直接销毁 round、账本与 publication pending，绝不把旧事务带入新 lifecycle。
- 四个自动工具 S2C 先按连接 identity 捕获 token，再经客户端主线程的当前连接与当前世界 gate 发布到 adapter。接替请求 publication 不扫描世界/库存、不发送 C2S；TAKEOVER/DECLINE 延迟到下一次 `ClientTick`。

## 规划宽进与执行实时权威边界

- planner admission 按顶层模式分流。`CHAIN_BASE/ORE/LOGGING` 在 PlanStarted 主线程冻结当前主手、selector 排序后的背包全部工具与空手能力并集，server 与 preview 的正式 matcher 及对象组扩展共用该 evaluator；匹配方块没有任何冻结可用能力时不入队、不生成邻居，形成明确断链。worker 只读冻结 ItemStack 副本，不读实时库存。
- `AREA_HARVESTABLE_ALL/TUNNEL/SAME_BLOCK/ORE/SECTION_CLEAR` 不捕获或绑定 `PlanningToolCapabilitySnapshot`，继续按预算化空间范围、同块/矿石结构身份以及空气、液体、基岩和脚底安全宽进。爆破确认数表示应由主线程逐个尝试的目标，不是工具能力承诺；INTERACT 与 GT SPECIAL 保持既有专用路径。
- `PlanningToolCapabilitySnapshot` 是 CHAIN 规划的不可变能力输入，不是执行权威。主线程 `ChainHarvestRules.canHarvest` 始终按当前玩家、工具、世界、脚底与耐久逐目标终裁；执行中工具损坏不改写已规划拓扑，单目标无候选或实时拒绝只跳当前目标，之后补入库存仍可影响尚未消费目标。
- 主线程普通 CHAIN/AREA 在执行器检查前以 `peek → takeover gate → poll` 排序消费。非空主手只有实时权威与耐久储备都成立才直通，否则可请求真实候选；空主手先 WAIT 请求候选，只有合法 DECLINED 后可空手兜底。WAIT 不 poll；SKIP_TARGET poll 后不调用执行器；STOP 只保留真实 round/lifecycle/fatal 收口。请求发送失败、deadline、目标漂移和已确认写前拒绝都按精确 request ID 局部跳过；committed publication pending 继续 WAIT，既不消费队首也不伪造 `ExecutionAdvanced`。GT 线缆 SPECIAL 与 INTERACT 不接入。
- 空手兜底的稳定候选资格可在上述严格身份内按 round 租赁，使同 key 批次首次最多一次 WAIT；租约命中仍位于 `peek()` 与 `poll()` 之间并逐目标实时复验权威，因此脚底、世界状态和事件语义变化继续 fail-closed。
- 非 GT 普通 `CHAIN/AREA/INTERACT/SPECIAL` 的 `maxBreakPerTick` 都是 poll/processed 预算，不是成功数预算；成功、执行器拒绝/失败与目标跳过共同计数。任一消费均发布 `ExecutionAdvanced`（可为零成功），只有成功执行设置 50ms 节流；同 tick 已消费后出现既有 STOP 时先发布推进再按旧 STOP 收口。规划完成后全跳过也沿正常 `ExecutionFinished → LifecycleCleanup` 收口，掉落窗口不因单目标跳过关闭。GT 线缆的等待、预校验、单 tick 原子执行和旧取消例外不变。
- `CHAIN_LOGGING` 不把能力拒绝解释为可桥接软失败：candidate=false 或最终 matcher=false 都不入队、不增加 confirmed，也不生成邻居；只有 candidate/matcher 均接受的原木或对象组节点才继续预算化扩展。

## 客户端预览刷新边界

- reducer 只在 SWAP/TAKEOVER/RESTORE 的目标库存布局经原版同步首次可见时输出 verified-layout effect；APPLIED 回包、重复观察和非库存动作不刷新。
- `ChainPreviewController` 为当前 origin 租赁首次捕获的完整 `BlockSeedSnapshot` 与 world identity。verified-layout 刷新绕过 phase lock 重算派生预览，但复用同一 block/meta/tile seed，不回读已破坏 origin。
- 新目标、松键、禁用预览、断线或 world 生命周期失效会清 seed、租约内动作去重身份并协作取消旧 worker；generation 继续隔离迟到结果。

## 不变量影响

- **I4**：C2S 在服务端主线程处理，S2C 在客户端主线程且通过连接/世界 token gate 后发布；Netty 线程只捕获原始字段和连接 identity。
- **I7**：断线、世界卸载、连接接管和世界接管统一复位客户端自动工具状态；异常清理不跨生命周期恢复旧 round。
- **I10**：工具 round 不取得连锁五态写权。`serverRoundId` 作为事件身份随状态机事件不可变传播，专用投影只观察已完成的 `ChainPhaseChanged`，不通过工具协议直接切换 `ChainStateMachine`。

## 兼容边界

- 新协议包构成一次客户端/服务端原子版本升级；不承诺旧客户端连接新服务端或新客户端连接旧服务端时继续工作。
- 外部接入方不应依赖包 ID、字段布局、round 状态、普通 sequence 或 request ID 细节作为稳定扩展 API。稳定事实只有服务端库存写权、mutation 单次提交与原版完整库存 publication。

## 未完成实机验收

协议 v4 的本地自动化与构建证据以活动任务结果为准；无论静态/JVM 门禁结论如何，用户实机 client/dedicated 仍未完成。仍需覆盖：

- 同版本客户端与 dedicated server 建连，以及版本不匹配的拒绝/失配收口。
- 首轮立即匹配、每 10 tick 重匹配、当前主手有效时短路、首块成功后冻结。
- 松键与自然 IDLE（含持续按键跨多个 round）、GUI/re-anchor、快速开始后立即收口、工具破损后的 RESTORE。
- 断线、重生、切维度、创造模式与服务端生命周期清理。

在上述矩阵完成前，不把自动工具运行态标记为实机已通过。

## 演进

- 2026-07-25：协议原子升级为 v4，保留六包、动作码、字段顺序和固定帧；TAKEOVER/DECLINE 改用独立烧号 request ID。服务端将 mutation、完整库存 publication 与 ActionResult confirm 分层，连续 sync/sender failure 只 exact retry 且 mutation 一次；请求发送、deadline、漂移和写前失败收窄为 target-local SKIP，committed publication pending 保持 WAIT/ownership，硬 lifecycle 直接销毁。客户端补固定 cadence、较长准备/关闭 deadline、SYNC_FAILED 次 tick exact retry 与 A→B→C 乱序隔离；运行态继续 INCOMPLETE。
- 2026-07-25：去除自动工具目标身份残留的 24-bit/16-bit 人为上限，DTO、packet 校验、空手租约 capability key 与客户端目标身份统一接受完整正/非负 int 域。保留 block ID 0 sentinel、协议 v3、两个 int 字段、60-byte framing、库存事务与状态机语义；运行态继续标记 INCOMPLETE。
- 2026-07-25：按最终语义把 planner 分为 CHAIN 冻结能力断链与 AREA 爆破宽进：CHAIN server/preview 绑定主手、背包全部工具与空手并集，含对象组和伐木 matcher=false 都阻断邻居；AREA 只守空间与子模式结构并把目标逐个交主线程尝试。保留真实 `tryHarvestBlock` 结果、实际 poll 预算、零成功推进与通用 Item API；效率采样异常只降级为 false。toolswap v3 wire、配置 schema、五态转移表、既有 STOP 来源与 GT 线缆原子例外不变，运行态仍为 INCOMPLETE。
- 2026-07-24：将普通 CHAIN/AREA 的接替校验拆为目标级 `SKIP_TARGET` 与会话级 STOP。仅实时目标权威、失效 block/meta 及已推进 sequence 的 candidate fingerprint/低耐久/精确无候选结算局部跳过；未结算事务、身份、库存、ledger、sync/orphan 故障不放宽。执行预算改按 poll 计数，零成功消费继续发布推进并自然完成。当时把 `CHAIN_LOGGING` matcher 拒绝视为可桥接软失败的中间方案已由 2026-07-25 最终断链语义取代。
- 2026-07-21：曾为 TiC `HarvestTool` 族增加 null-harvestTool 类名适配；该白名单方案现已被所有未知工具共用的稳定 `Item.canHarvestBlock` 虚调用取代，显式 harvestTool 仍由 Forge 终裁。
- 2026-07-18：新增服务端 round-scoped 单项空手回退租约，以完整 36 槽纯值 identity 消除稳定同 key 批次的逐目标 TAKEOVER/DECLINE；真实候选仍优先、每目标权威不缓存，wire、客户端候选、执行节流、状态机与 GT 线缆路径不变。自动化不替代真实吞吐与 watchdog 复验，运行态仍为 INCOMPLETE。
- 2026-07-18：修复接替 pending 反向阻断松键 CLOSE 的 round 终裁；闭环动作在合法 sequence 后安全退休等待门，迟到 TAKEOVER 保持零库存副作用。同期将 PlanCompleted 成功入队设为规划完成 publication 线性化点，失败固定发布一次 `plan-completion-publication-failed` 取消；wire、版本、配置 schema 与五态转移表不变，hotfix 运行态仍为 INCOMPLETE。
- 2026-07-18：统一规划、客户端候选与执行期采掘能力边界；新增冻结能力集合、空手最低优先级、内部 DECLINED、APPLIED 实时复验和 round=0 直判。同期将 planning STOP/complete 线性化，并以完整 seed 租约刷新三种库存布局对应的预览；wire、协议版本、配置 schema 与五态转移表不变，运行态仍待用户实机。
- 2026-07-16：补齐首块前目标身份与 latest-target-wins。普通 FULL 改为只消费 light 固化的 block/meta；目标变化按唯一 ledger 先 RESTORE 后重匹配，空气采用连续 2 个 END tick 防抖。协议 v3、服务端写权、TAKEOVER 与连锁五态不变。
- 2026-07-16：客户端原子迁移为单一 `AutoToolSwapClientReducer`；删除并行的 controller、transaction enum 与有状态 protocol 子模型。当时的 wire、服务端 round/ledger、库存事务、dispatcher、lifecycle gate 与产品时序不变。
- 2026-07-16：纠正客户端将 `FROZEN` 折叠为 `CLOSING` 的派生错误，并将活跃 phase 的 FREEZE 请求收敛为按 round 幂等；真实 `CLOSING`、自然 IDLE 与 release 的恢复关闭合同不变。
- 2026-07-16：前序协议将请求 exact 新鲜度与跨 round stable-role 租约分层，并新增显式 `ABANDON(5)`，使无法安全恢复的 ledger 可在零库存访问下收口到 FINISHED。
- 2026-07-16：统一剩余耐久至少 2 点的技术门，并把 worker 规划能力判定与主线程执行耐久判定拆分；原 anchor 为空且候选槽被掉落物占用时，RESTORE 改为交换当前真实双槽。协议版本、动作码与 framing 不变，中途接替留待协议 v3。
- 2026-07-16：协议原子升级为 v3，新增同 round TAKEOVER/DECLINE、服务端目标请求、poll 前等待门、连续接替三槽轮转和 `client.autoToolTakeoverEnabled`；运行态仍待用户实机。
