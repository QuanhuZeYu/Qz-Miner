# 决策：自动工具换位服务端权威事务

## 结论

- 自动工具换位的唯一库存写权属于服务端主线程。客户端只读取库存事实、按本地优先级选择候选并提交 intent，不直接修改库存，也不通过原版容器点击完成事务。
- Qz-Miner 协议负责 round 建立、动作意图、动作结算和专用阶段关联；真实库存视图仍由服务端应用交换后通过原版容器差异同步下发。
- 自动工具协议 v2 作为客户端与服务端共同升级的原子边界，不是允许混合版本调用的公共 API；两端必须使用同一 Qz-Miner 版本，v1 在 RoundStart 信任边界即 fail-closed。

## 实现锚

- `NetworkMain.register()`：注册自动工具两个 C2S 与三个 S2C，定义其在 common 网络层的方向和注册点。
- `ServerAutoToolSwapRequestDispatch`：将 C2S 原始请求投递到服务端主线程，并连接 round 服务、库存端口和 S2C 回执发送。
- `AutoToolSwapRoundService`：维护服务端 round、动作序列和可逆账本，执行请求幂等与动作结算。
- `MinecraftAutoToolSwapInventoryPort`：在服务端玩家个人库存中执行槽位交换，并交由原版容器发布库存差异。
- `ClientProxy`：按 `ctx.netHandler` 捕获连接 token，经客户端主线程 connection/world gate 将三个 S2C 发布给 adapter。
- `AutoToolSwapClientReducer`：客户端 cycle、nonce/round/action/phase 归因、库存双门、关闭原因与重传的唯一可变业务权威，以 Event 输入并输出不可变 Effect。
- `AutoToolSwapClientAdapter`：承接 gate 后的 S2C，只采样 Minecraft 事实、执行 reducer effect 和网络 I/O；后续 C2S 在 `ClientTick` 发送。
- `AutoToolSwapClientProtocolValidator`：无字段，只负责 raw、wire enum、范围与单包结构校验，不判断历史关联。

## 原因

- 客户端库存点击与原版库存包监听会把写权、确认时序和恢复责任分散到两端，无法稳定判断迟到包、换栏、GUI、断线和新旧 round 的归属。
- 服务端持有玩家、个人库存窗口、cursor、创造模式和真实槽位内容等安全事实，适合在一个主线程事务中完成校验、交换、记账与同步。
- 原版容器差异同步已经是库存视图的权威发布路径；Qz-Miner 只需承载换位命令、回执与 round 关联，不应复制一套库存同步协议。

## 协议与库存边界

- `AutoToolSwapRoundService` 按玩家 UUID、在线 endpoint、`serverRoundId` 和严格递增 `actionSequence` 维护单个 round；重复请求只在身份与内容精确匹配时幂等返回。
- `FROZEN` 是工具已固定但 round 仍可继续接收专用活跃 phase 的稳定活跃态；只有 `CLOSING` 取得单调关闭语义并禁止新 `SWAP/FREEZE`。`PLANNING/RUNNING/FINISHING` 只要求“确保已冻结”：服务端已 FROZEN、同一 FREEZE in-flight 或已成功结算时，客户端只维持本地 FROZEN 投影，不发送第二个 intent。
- 候选由客户端按 `client.autoToolPrioritySelectors` 排序；主手短路、候选排序、服务端 SWAP 与主线程连锁执行统一使用 `AutoToolUsabilityPolicy` 的“剩余耐久至少 2 点”门，不可损耗工具使用 `Integer.MAX_VALUE`。服务端仍不相信客户端候选结论，只校验个人库存 window 0、空 cursor、非创造模式、槽位范围、当前热栏、剩余耐久、内容 fingerprint 与可逆 ledger。
- `MinecraftAutoToolSwapInventoryPort` 直接交换 `InventoryPlayer.mainInventory[0..35]`，调用 `markDirty()`，再由 `inventoryContainer.detectAndSendChanges()` 发布原版库存差异。
- 库存比较分两层：每个 SWAP/RESTORE intent 携带捕获当刻的双槽完整 fingerprint，服务端与当前双槽 exact 比较以阻断陈旧请求；ledger 跨 round 只租赁稳定 role（registry id + stable subtype），允许 count、damage、energy 与 NBT 合法变化。空槽只兼容空槽，活动工具允许同 role 或破损后的空槽。
- RESTORE 交换的是校验通过后的两个当前真实栈，不使用 ledger 旧内容回写；因此不会回滚动态变化，也不会复制或吞掉栈。sameRole 只证明角色所有权，不承诺对象 instance identity。原 anchor 非空时 candidate 仍须保持同 role；原 anchor 为空时允许 candidate 被任意当前真实栈占用，RESTORE 将占位栈直接交换到主手并把借用工具送回原槽。该窄例外不放宽 intent 双槽 exact 新鲜度、活动工具 role/empty 或库存安全上下文。
- `ABANDON(5)` 是无法安全 RESTORE 时的显式收口动作：请求使用 ledger 真实双槽与两个 canonical control fingerprint。服务端只接受当前 endpoint/round/sequence、`SWAPPED/FROZEN/CLOSING`、匹配 ledger 槽位；成功时不读取、不交换、不同步库存，只清 ledger/keyDown 并进入 FINISHED。重复相同 intent 复用动作缓存，旧身份或拒绝不得清当前账本。
- 客户端不调用 `windowClick`，不监听 C0E/S32/S2F/S30 作为自动工具事务确认；动作成功后只观察服务端同步回来的受保护槽位是否达到 ledger 目标布局。
- `serverRoundId` 在服务端激活 PENDING round 时分配，随后作为不可变身份随 `ChainEvent` 传播。工具阶段由 `PacketAutoToolSwapRoundPhase` 单独关联，客户端只接受当前 round 且严格递增的 `phaseSequence`；通用 `PacketChainPhaseSnapshot` 不承担工具关联。

## 生命周期边界

- GUI 打开或玩家切换热栏锚点时，若 role 租约仍兼容则对已有换位执行 RESTORE，round 保持 OPEN；若租约不兼容则显式 ABANDON 并按既有安全合同进入 IDLE/WAIT_RELEASE，不把不安全布局误判为已恢复。
- 松开连锁键、配置从启用改为禁用，或专用 round 收到 IDLE 时，按 RESTORE 后 CLOSE 的顺序收口。
- 专用 round 自然进入 IDLE 时，即使物理连锁键仍持续按住，也必须先让旧 round 完整执行 RESTORE→CLOSE。只有 CLOSE 精确结算为 FINISHED、客户端协议已复位到 IDLE，且期间没有松键/快速重按、配置关闭、生命周期复位、拒绝或 orphan，才在下一次 `ClientTick` 创建新 nonce 并先提交 RoundStart；提交成功后由 `KeyListener` 补发 fresh `PacketKeyState(KEY_CHAIN, true)` 激活新服务端 round。旧 round 不复活，也不增加状态机捷径。
- 连接断开、世界替换、协议超时、包失配、ABANDON 拒绝或同步异常时不伪造成功也不盲目发送恢复。客户端进入 ORPHANED 或统一复位 reducer，服务端生命周期清理销毁 round 账本，保留最后一次由服务端原版同步发布的库存状态。
- 三个自动工具 S2C 先按连接 identity 捕获 token，再经客户端主线程的当前连接与当前世界 gate 发布到 adapter。publication 只更新本地协议状态；可能产生的后续 C2S 延迟到下一次 `ClientTick`，不在 lifecycle monitor 内执行网络 I/O。

## 规划与执行耐久边界

- worker 规划 matcher 使用 `ChainHarvestRules.canPlanHarvest`，只判断目标、采掘能力与收获等级，不把当前工具瞬时剩余耐久作为目标入队门。
- 主线程 `BlockHarvestActionExecutor` 继续使用 `ChainHarvestRules.canHarvest` 执行完整的剩余耐久门；工具不足时目标暂不破坏，但不会在 worker 阶段提前消失。
- 本阶段不实现 FROZEN 中途 TAKEOVER/DECLINE 或执行队列等待门；工具耗尽后的中途接替仍由后续协议 v3 独立完成。

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

- 2026-07-16：客户端原子迁移为单一 `AutoToolSwapClientReducer`；删除并行的 controller、transaction enum 与有状态 protocol 子模型。五包 wire、服务端 round/ledger、库存事务、dispatcher、lifecycle gate 与产品时序不变。
- 2026-07-16：纠正客户端将 `FROZEN` 折叠为 `CLOSING` 的派生错误，并将活跃 phase 的 FREEZE 请求收敛为按 round 幂等；真实 `CLOSING`、自然 IDLE 与 release 的恢复关闭合同不变。
- 2026-07-16：协议原子升级为 v2；将请求 exact 新鲜度与跨 round stable-role 租约分层，并新增显式 `ABANDON(5)`，使无法安全恢复的 ledger 可在零库存访问下收口到 FINISHED。五包字段、长度、方向与注册数量不变。
- 2026-07-16：统一剩余耐久至少 2 点的技术门，并把 worker 规划能力判定与主线程执行耐久判定拆分；原 anchor 为空且候选槽被掉落物占用时，RESTORE 改为交换当前真实双槽。协议版本、动作码与 framing 不变，中途接替留待协议 v3。
