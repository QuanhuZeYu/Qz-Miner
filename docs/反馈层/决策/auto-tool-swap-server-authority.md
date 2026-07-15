# 决策：自动工具换位服务端权威事务

## 结论

- 自动工具换位的唯一库存写权属于服务端主线程。客户端只读取库存事实、按本地优先级选择候选并提交 intent，不直接修改库存，也不通过原版容器点击完成事务。
- Qz-Miner 协议负责 round 建立、动作意图、动作结算和专用阶段关联；真实库存视图仍由服务端应用交换后通过原版容器差异同步下发。
- 自动工具协议包作为客户端与服务端共同升级的原子边界，不是允许混合版本调用的公共 API；两端必须使用同一 Qz-Miner 版本。

## 原因

- 客户端库存点击与原版库存包监听会把写权、确认时序和恢复责任分散到两端，无法稳定判断迟到包、换栏、GUI、断线和新旧 round 的归属。
- 服务端持有玩家、个人库存窗口、cursor、创造模式和真实槽位内容等安全事实，适合在一个主线程事务中完成校验、交换、记账与同步。
- 原版容器差异同步已经是库存视图的权威发布路径；Qz-Miner 只需承载换位命令、回执与 round 关联，不应复制一套库存同步协议。

## 协议与库存边界

- `AutoToolSwapRoundService` 按玩家 UUID、在线 endpoint、`serverRoundId` 和严格递增 `actionSequence` 维护单个 round；重复请求只在身份与内容精确匹配时幂等返回。
- 候选由客户端按 `client.autoToolPrioritySelectors` 排序；服务端不相信候选结论，只校验个人库存 window 0、空 cursor、非创造模式、槽位范围、当前热栏、剩余耐久、内容 fingerprint 与可逆 ledger。
- `MinecraftAutoToolSwapInventoryPort` 直接交换 `InventoryPlayer.mainInventory[0..35]`，调用 `markDirty()`，再由 `inventoryContainer.detectAndSendChanges()` 发布原版库存差异。
- 客户端不调用 `windowClick`，不监听 C0E/S32/S2F/S30 作为自动工具事务确认；动作成功后只观察服务端同步回来的受保护槽位是否达到 ledger 目标布局。
- `serverRoundId` 在服务端激活 PENDING round 时分配，随后作为不可变身份随 `ChainEvent` 传播。工具阶段由 `PacketAutoToolSwapRoundPhase` 单独关联，客户端只接受当前 round 且严格递增的 `phaseSequence`；通用 `PacketChainPhaseSnapshot` 不承担工具关联。

## 生命周期边界

- GUI 打开或玩家切换热栏锚点时，只对已有换位执行 RESTORE，round 保持 OPEN；GUI 关闭或 re-anchor 完成后可在同一 round 继续匹配。
- 松开连锁键、配置从启用改为禁用，或专用 round 收到 IDLE 时，按 RESTORE 后 CLOSE 的顺序收口。
- 连接断开、世界替换、协议超时、包失配或同步异常时不盲目发送恢复。客户端清空 controller/protocol，服务端生命周期清理销毁 round 账本，保留最后一次由服务端原版同步发布的库存状态。
- 三个自动工具 S2C 先按连接 identity 捕获 token，再经客户端主线程的当前连接与当前世界 gate 发布到 adapter。publication 只更新本地协议状态；可能产生的后续 C2S 延迟到下一次 `ClientTick`，不在 lifecycle monitor 内执行网络 I/O。

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
- 松键与自然 IDLE、GUI/re-anchor、快速开始后立即收口、工具破损后的 RESTORE。
- 断线、重生、切维度、创造模式与服务端生命周期清理。

在上述矩阵完成前，不把自动工具运行态标记为实机已通过。
