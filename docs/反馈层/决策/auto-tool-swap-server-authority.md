# 决策：自动工具换位服务端权威事务

## 结论

- 自动工具换位的唯一库存写权属于服务端主线程。客户端只读取库存事实、按本地优先级选择候选并提交 intent，不直接修改库存，也不通过原版容器点击完成事务。
- Qz-Miner 协议负责 round 建立、动作意图、动作结算和专用阶段关联；真实库存视图仍由服务端应用交换后通过原版容器差异同步下发。
- 自动工具协议 v3 作为客户端与服务端共同升级的原子边界，不允许 v2/v3 混合协商；两端必须使用同一 Qz-Miner 版本，旧端在 RoundStart/整包校验处 fail-closed。

## 实现锚

- `NetworkMain.register()`：注册自动工具两个 C2S 与四个 S2C；新增固定 60 字节接替目标请求。
- `ServerAutoToolSwapRequestDispatch`：将 C2S 原始请求投递到服务端主线程，并连接 round 服务、库存端口和 S2C 回执发送。
- `AutoToolSwapRoundService`：维护服务端 round、动作序列和可逆账本，执行请求幂等与动作结算。
- `AutoToolSwapRoundService` 的 `RoundRecord`：额外维护至多一个空手回退租约及 create/hit/invalidated 汇总计数；不形成按目标增长的缓存。
- `MinecraftAutoToolSwapInventoryPort`：在服务端玩家个人库存中执行槽位交换，并交由原版容器发布库存差异。
- `AutoToolSwapTakeoverCoordinator`：普通 CHAIN/AREA 在队首 `peek()` 后建立 `PROCEED/WAIT/SKIP_TARGET/STOP` 门；目标局部拒绝只消费精确队首，会话/事务故障仍协作取消。
- `ClientProxy`：按 `ctx.netHandler` 捕获连接 token，经客户端主线程 connection/world gate 将四个 S2C 发布给 adapter。
- `AutoToolSwapClientReducer`：客户端 cycle、nonce/round/action/phase 归因、库存双门、关闭原因与重传的唯一可变业务权威，以 Event 输入并输出不可变 Effect。
- `AutoToolSwapClientAdapter`：承接 gate 后的 S2C，只采样 Minecraft 事实、执行 reducer effect 和网络 I/O；后续 C2S 在 `ClientTick` 发送。
- `AutoToolSwapClientProtocolValidator`：无字段，只负责 raw、wire enum、范围与单包结构校验，不判断历史关联。
- `ToolHarvestEligibility`：显式 harvestTool 交 Forge 等级语义终裁；无显式 harvestTool 且材质需要工具时直接调用稳定 `Item.canHarvestBlock`，不维护模组类白名单。

## 原因

- 客户端库存点击与原版库存包监听会把写权、确认时序和恢复责任分散到两端，无法稳定判断迟到包、换栏、GUI、断线和新旧 round 的归属。
- 服务端持有玩家、个人库存窗口、cursor、创造模式和真实槽位内容等安全事实，适合在一个主线程事务中完成校验、交换、记账与同步。
- 原版容器差异同步已经是库存视图的权威发布路径；Qz-Miner 只需承载换位命令、回执与 round 关联，不应复制一套库存同步协议。

## 协议与库存边界

- `AutoToolSwapRoundService` 按玩家 UUID、在线 endpoint、`serverRoundId` 和严格递增 `actionSequence` 维护单个 round；重复请求只在身份与内容精确匹配时幂等返回。
- `FROZEN` 是工具已固定但 round 仍可继续接收专用活跃 phase 的稳定活跃态；只有 `CLOSING` 取得单调关闭语义并禁止新 `SWAP/FREEZE`。`PLANNING/RUNNING/FINISHING` 只要求“确保已冻结”：服务端已 FROZEN、同一 FREEZE in-flight 或已成功结算时，客户端只维持本地 FROZEN 投影，不发送第二个 intent。
- 候选由客户端按 `client.autoToolPrioritySelectors` 排序；`ToolHarvestEligibility` 以目标收获结论与“剩余耐久至少 2 点”作为资格硬门，不可损耗工具使用 `Integer.MAX_VALUE`。目标实际效率仍被采样为候选事实，但不再单独否决低效率、可收获的未知工具。服务端仍不相信客户端候选结论，只校验个人库存 window 0、空 cursor、非创造模式、槽位范围、当前热栏、剩余耐久、内容 fingerprint 与可逆 ledger。
- `MinecraftAutoToolSwapInventoryPort` 直接交换 `InventoryPlayer.mainInventory[0..35]`，调用 `markDirty()`，再由 `inventoryContainer.detectAndSendChanges()` 发布原版库存差异。
- 库存比较分两层：每个 SWAP/RESTORE intent 携带捕获当刻的双槽完整 fingerprint，服务端与当前双槽 exact 比较以阻断陈旧请求；ledger 跨 round 只租赁稳定 role（registry id + stable subtype），允许 count、damage、energy 与 NBT 合法变化。空槽只兼容空槽，活动工具允许同 role 或破损后的空槽。
- RESTORE 交换的是校验通过后的两个当前真实栈，不使用 ledger 旧内容回写；因此不会回滚动态变化，也不会复制或吞掉栈。sameRole 只证明角色所有权，不承诺对象 instance identity。原 anchor 非空时 candidate 仍须保持同 role；原 anchor 为空时允许 candidate 被任意当前真实栈占用，RESTORE 将占位栈直接交换到主手并把借用工具送回原槽。该窄例外不放宽 intent 双槽 exact 新鲜度、活动工具 role/empty 或库存安全上下文。
- `ABANDON(5)` 是无法安全 RESTORE 时的显式收口动作：请求使用 ledger 真实双槽与两个 canonical control fingerprint。服务端只接受当前 endpoint/round/sequence、`SWAPPED/FROZEN/CLOSING`、匹配 ledger 槽位；成功时不读取、不交换、不同步库存，只清 ledger/keyDown 并进入 FINISHED。重复相同 intent 复用动作缓存，旧身份或拒绝不得清当前账本。
- `TAKEOVER(6)`/`DECLINE_TAKEOVER(7)` 是 FROZEN 中途的独立同 round 事务。服务端为队首目标建立唯一 pending 并预留 next sequence；客户端下一 ClientTick 使用请求 block id/meta 采样。无 ledger 双槽交换；有 ledger 单次轮转 `A<-D,C<-A,D<-C`，ledger 滚动到新候选且保留最初 anchor。原 anchor 为空、deadline 前精确匹配 pending 的 DECLINE 结算为内部 `DECLINED`，Coordinator 随后重验空手、库存安全与实时采掘权威；非空 anchor 的同结构精确 DECLINE 表示无候选，零库存访问结算为 `SKIP_TARGET` 并记录固定 `no-candidate`。畸形 DECLINE 仍 STOP。
- pending takeover 只约束继续执行动作，不得阻断 round 终裁。服务端先完成 endpoint/round/幂等/sequence 校验，再允许 `CLOSE`、`RESTORE`、`ABANDON` 退休等待门：WAITING 先转 STOP 并输出一次固定诊断，DECLINED/STOP/APPLIED 直接移除，随后严格沿既有 ledger 合同结算。旧 sequence 或旧身份的迟到 TAKEOVER 在任何库存读取、写入、同步和诊断快照前拒绝。
- 合法空 anchor DECLINE 被执行桥消费后，只有同一主线程时刻的库存安全门、空手、完整 0..35 `AutoToolSwapStackState.sameContent` identity 与实时采掘权威都成立，才安装单项 round-scoped 空手回退租约。租约绑定 endpoint/round/generation、当前热栏锚点与不含坐标的 block id + 完整 metadata；不与 ledger/pending 共存，并随关闭、终态或生命周期清理。
- 同一租约后续命中只省略 TAKEOVER/DECLINE 网络往返，不省略每目标 `ChainHarvestRules.canHarvest`。block/meta、任一槽数量/耐久/NBT/内容、选中槽、GUI/cursor、endpoint/round/generation 变化均清旧租约并重新执行真实候选优先决策；A→B→A 必须重新协商 A。诊断不逐目标记录 hit，只在 create/invalidated 与 round close 汇总计数。
- TAKEOVER 写前重新校验 endpoint/round/generation/sequence、pending 目标身份、热栏锚点、exact fingerprint、候选剩余至少 2 点、受保护槽角色及槽位互异。candidate fingerprint 或低耐久拒绝发生在零写入边界且由当前 intent 推进 sequence，门终态为 `SKIP_TARGET`；库存上下文、锚点、ledger role、槽冲突、sync/orphan 等仍 STOP。交换已应用后的同步失败进入 ORPHANED/SYNC_FAILED，禁止重放；APPLIED 被执行桥消费后仍须按新主手实时 `ChainHarvestRules.canHarvest` 复验，失败只跳过当前目标。
- 客户端不调用 `windowClick`，不监听 C0E/S32/S2F/S30 作为自动工具事务确认；动作成功后只观察服务端同步回来的受保护槽位是否达到 ledger 目标布局。
- 首块成功前的普通匹配使用客户端 light 快照中的 `ABSENT` 或 `blockId + metadata` 目标身份；坐标、TileEntity/NBT 不参与。同身份维持 10 tick 扫描水位，block/meta 变化立即触发 latest-target-wins，连续两个 END tick ABSENT 才确认丢失。已有 ledger 时先完成旧 RESTORE，再为最终有效目标 FULL；已发送 SWAP/RESTORE 不取消，也不在旧 ledger 上发送第二个普通 SWAP。
- `serverRoundId` 在服务端激活 PENDING round 时分配，随后作为不可变身份随 `ChainEvent` 传播。工具阶段由 `PacketAutoToolSwapRoundPhase` 单独关联，客户端只接受当前 round 且严格递增的 `phaseSequence`；通用 `PacketChainPhaseSnapshot` 不承担工具关联。
- 采掘资格先检查输入；目标声明 harvestTool 时只采用 Forge 等级语义，禁止 fallback 绕过。仅 null harvestTool 且材质仍要求工具时，直接调用 Minecraft owner 上的稳定 `Item.canHarvestBlock(Block, ItemStack)`；不探测 TiC 或其它模组类型，不解析成员、不扫描类名，调用异常 fail-closed。

## 生命周期边界

- GUI 打开或玩家切换热栏锚点时，若 role 租约仍兼容则对已有换位执行 RESTORE，round 保持 OPEN；若租约不兼容则显式 ABANDON 并按既有安全合同进入 IDLE/WAIT_RELEASE，不把不安全布局误判为已恢复。
- 松开连锁键、配置从启用改为禁用，或专用 round 收到 IDLE 时，按 RESTORE 后 CLOSE 的顺序收口。
- 专用 round 自然进入 IDLE 时，即使物理连锁键仍持续按住，也必须先让旧 round 完整执行 RESTORE→CLOSE。只有 CLOSE 精确结算为 FINISHED、客户端协议已复位到 IDLE，且期间没有松键/快速重按、配置关闭、生命周期复位、拒绝或 orphan，才在下一次 `ClientTick` 创建新 nonce 并先提交 RoundStart；提交成功后由 `KeyListener` 补发 fresh `PacketKeyState(KEY_CHAIN, true)` 激活新服务端 round。旧 round 不复活，也不增加状态机捷径。
- 连接断开、世界替换、协议超时、包失配、ABANDON 拒绝或同步异常时不伪造成功也不盲目发送恢复。客户端进入 ORPHANED 或统一复位 reducer，服务端生命周期清理销毁 round 账本，保留最后一次由服务端原版同步发布的库存状态。
- 四个自动工具 S2C 先按连接 identity 捕获 token，再经客户端主线程的当前连接与当前世界 gate 发布到 adapter。接替请求 publication 不扫描世界/库存、不发送 C2S；TAKEOVER/DECLINE 延迟到下一次 `ClientTick`。

## 规划宽进与执行实时权威边界

- PlanStarted 不再捕获工具能力用于规划 admission。server 与 preview planner 对 `CHAIN`、`AREA_HARVESTABLE_ALL`、`AREA_TUNNEL`、`AREA_SAME_BLOCK`、区段清理及其结构化子模式，只按预算化世界/几何/结构身份宽进：空气、液体、基岩与脚底安全门保留，同块、矿石、原木和 TileEntity 纯值身份仍各自生效；worker 不读实时库存、不调用 `Block.canHarvestBlock(player, meta)` 或未知 Item 回调。
- `PlanningToolCapabilitySnapshot` 保留为未删除内部类型及历史测试接缝，但生产 `ChainPlanningEventBridge` 与 `ChainPlanningRuntimeFactory` 不再捕获、传递或绑定它。主线程 `ChainHarvestRules.canHarvest` 始终按当前玩家、工具、世界、脚底与耐久逐目标终裁；规划确认数表达拓扑候选，不保证全部可破坏。
- 主线程普通 CHAIN/AREA 在执行器检查前以 `peek → takeover gate → poll` 排序消费。非空主手只有实时权威与耐久储备都成立才直通，否则可请求真实候选；空主手先 WAIT 请求候选，只有合法 DECLINED 后可空手兜底。WAIT 不 poll；SKIP_TARGET poll 后不调用执行器；STOP 保持规划协作取消。仅已结算目标拒绝、round 0/租约/APPLIED 的实时权威失败及安全目标失效进入 SKIP_TARGET，未结算事务与会话身份故障不降级。GT 线缆 SPECIAL 与 INTERACT 不接入。
- 空手兜底的稳定候选资格可在上述严格身份内按 round 租赁，使同 key 批次首次最多一次 WAIT；租约命中仍位于 `peek()` 与 `poll()` 之间并逐目标实时复验权威，因此脚底、世界状态和事件语义变化继续 fail-closed。
- 非 GT 普通 `CHAIN/AREA/INTERACT/SPECIAL` 的 `maxBreakPerTick` 都是 poll/processed 预算，不是成功数预算；成功、执行器拒绝/失败与目标跳过共同计数。任一消费均发布 `ExecutionAdvanced`（可为零成功），只有成功执行设置 50ms 节流；同 tick 已消费后出现既有 STOP 时先发布推进再按旧 STOP 收口。规划完成后全跳过也沿正常 `ExecutionFinished → LifecycleCleanup` 收口，掉落窗口不因单目标跳过关闭。GT 线缆的等待、预校验、单 tick 原子执行和旧取消例外不变。
- `CHAIN_LOGGING` 额外区分“连通资格”与“入队资格”：原木 candidate filter 已接受后，即使世界/安全 matcher 软拒绝，该节点也只是不入执行队列、不增加 confirmed，仍可预算化扩展相邻原木；candidate=false 的非原木/非对象组节点继续阻断。后续节点仍各自经过 matcher 与主线程实时权威，被拒节点自身绝不破坏。

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
- 外部接入方不应依赖包 ID、字段布局、round 状态或 sequence 细节作为稳定扩展 API。稳定事实只有服务端库存写权与原版库存差异同步。

## 未完成实机验收

自动化测试、`compileJava`、全量 `test` 与 `check` 已通过，但尚未完成用户实机 client/dedicated 验收。仍需覆盖：

- 同版本客户端与 dedicated server 建连，以及版本不匹配的拒绝/失配收口。
- 首轮立即匹配、每 10 tick 重匹配、当前主手有效时短路、首块成功后冻结。
- 松键与自然 IDLE（含持续按键跨多个 round）、GUI/re-anchor、快速开始后立即收口、工具破损后的 RESTORE。
- 断线、重生、切维度、创造模式与服务端生命周期清理。

在上述矩阵完成前，不把自动工具运行态标记为实机已通过。

## 演进

- 2026-07-25：恢复 planner 宽进语义：生产 server/preview planner 删除冻结工具 admission，只保留世界/几何/结构身份；主线程继续逐目标实时终裁。未知 null-harvestTool 工具改走通用稳定 Item API，效率退出候选硬门；INTERACT/非 GT SPECIAL 与 CHAIN/AREA 统一按 poll 预算并发布零成功推进，`tryHarvestBlock=false` 不再计成功。toolswap v3 wire、配置 schema、五态转移表、既有 STOP 来源与 GT 线缆原子例外不变；client/dedicated 与真实爆破体验仍为 INCOMPLETE。
- 2026-07-24：将普通 CHAIN/AREA 的接替校验拆为目标级 `SKIP_TARGET` 与会话级 STOP。仅实时目标权威、失效 block/meta 及已推进 sequence 的 candidate fingerprint/低耐久/精确无候选结算局部跳过；未结算事务、身份、库存、ledger、sync/orphan 故障不放宽。执行预算改按 poll 计数，零成功消费继续发布推进并自然完成；`CHAIN_LOGGING` 的 matcher 软拒绝节点保留 candidate 连通资格但不入队。wire v3、配置 schema、五态转移表、INTERACT 与 GT SPECIAL 不变，client/dedicated 运行态仍为 INCOMPLETE。
- 2026-07-21：为 TiC `HarvestTool` 族增加无直接依赖的 null-harvestTool 旧式采掘适配；客户端候选与冻结规划仍共用资格入口，显式等级、效率、耐久和执行期服务端权威不变。自动化不替代 Smeltery 真实掉落验证，5.0.23 继续阻断。
- 2026-07-18：新增服务端 round-scoped 单项空手回退租约，以完整 36 槽纯值 identity 消除稳定同 key 批次的逐目标 TAKEOVER/DECLINE；真实候选仍优先、每目标权威不缓存，wire、客户端候选、执行节流、状态机与 GT 线缆路径不变。自动化不替代真实吞吐与 watchdog 复验，运行态仍为 INCOMPLETE。
- 2026-07-18：修复接替 pending 反向阻断松键 CLOSE 的 round 终裁；闭环动作在合法 sequence 后安全退休等待门，迟到 TAKEOVER 保持零库存副作用。同期将 PlanCompleted 成功入队设为规划完成 publication 线性化点，失败固定发布一次 `plan-completion-publication-failed` 取消；wire、版本、配置 schema 与五态转移表不变，hotfix 运行态仍为 INCOMPLETE。
- 2026-07-18：统一规划、客户端候选与执行期采掘能力边界；新增冻结能力集合、空手最低优先级、内部 DECLINED、APPLIED 实时复验和 round=0 直判。同期将 planning STOP/complete 线性化，并以完整 seed 租约刷新三种库存布局对应的预览；wire、协议版本、配置 schema 与五态转移表不变，运行态仍待用户实机。
- 2026-07-16：补齐首块前目标身份与 latest-target-wins。普通 FULL 改为只消费 light 固化的 block/meta；目标变化按唯一 ledger 先 RESTORE 后重匹配，空气采用连续 2 个 END tick 防抖。协议 v3、服务端写权、TAKEOVER 与连锁五态不变。
- 2026-07-16：客户端原子迁移为单一 `AutoToolSwapClientReducer`；删除并行的 controller、transaction enum 与有状态 protocol 子模型。当时的 wire、服务端 round/ledger、库存事务、dispatcher、lifecycle gate 与产品时序不变。
- 2026-07-16：纠正客户端将 `FROZEN` 折叠为 `CLOSING` 的派生错误，并将活跃 phase 的 FREEZE 请求收敛为按 round 幂等；真实 `CLOSING`、自然 IDLE 与 release 的恢复关闭合同不变。
- 2026-07-16：前序协议将请求 exact 新鲜度与跨 round stable-role 租约分层，并新增显式 `ABANDON(5)`，使无法安全恢复的 ledger 可在零库存访问下收口到 FINISHED。
- 2026-07-16：统一剩余耐久至少 2 点的技术门，并把 worker 规划能力判定与主线程执行耐久判定拆分；原 anchor 为空且候选槽被掉落物占用时，RESTORE 改为交换当前真实双槽。协议版本、动作码与 framing 不变，中途接替留待协议 v3。
- 2026-07-16：协议原子升级为 v3，新增同 round TAKEOVER/DECLINE、服务端目标请求、poll 前等待门、连续接替三槽轮转和 `client.autoToolTakeoverEnabled`；运行态仍待用户实机。
