# 决策：客户端连接 identity 生命周期

## 结论

- 客户端连接生命周期 token 绑定**真实连接 identity**：生产值为 `FMLNetworkEvent` 的 `event.handler` / `MessageContext.netHandler`（common `INetHandler` 实例），按对象引用 `==` 判定；**禁止** `identityHashCode` / `equals` 作为身份。
- API 核验：同一连接上 connect/disconnect 的 `event.handler` 与入包 `ctx.netHandler` 为同一 common `INetHandler` 实例；不同连接不同实例；disconnect/close 可重复且跨旧新 channel **无全局顺序**。
- Token 至少含：不可复用 `connectionGeneration`、不可复用 `worldGeneration`、`connectionActive` / `worldActive`、`connectionIdentity` / `worldIdentity`。
- `connect(handler)` / `bindWorld(world)` 返回 `TransitionResult{token, transitioned, replacedPreviousLifecycle}`：
  - 相同 active handler 重复 connect / 相同 world 重复 load：`transitioned=false`（不得调度 init/接管清理）。
  - 首次 connect / 首次 world bind：`transitioned=true, replacedPreviousLifecycle=false`。
  - 不同 connection 接管 / active world 被替换：`transitioned=true, replacedPreviousLifecycle=true`。
- `disconnect(handler)`：仅 `handler == current connection` 且 active 时转 inactive；旧连接迟到 / 重复 disconnect **no-op 且不得调度新清理**。
- World scope（仅 client 类内）：`WorldEvent.Load` 绑定远端 world 对象；**首次绑定不废弃连接级 config token**（同 `connectionGeneration`）；世界替换推进 `worldGeneration`。`Unload` 仅当 `event.world == current world` 且连接仍 current 时推进；旧 world / 重复 unload / disconnect 后 unload no-op。同连接切维度：connection 保持 active，旧 world token 失效。
- Gate：统一 monitor 线性化；禁止 check 后裸写。连接级（config）/ 世界级（phase、preview、自动工具）/ inactive-disconnect cleanup / world-unbind cleanup 分 narrow gate。短小本地 publication（config 三字段、event-bus offer、自动工具 adapter 状态推进）可在 monitor 内执行；回调**禁阻塞、禁反向 lifecycle 入口**。自动工具 publication 不直接发送 C2S，后续命令由下一次 `ClientTick` 执行。不引入第二把锁。
- **接管收敛（I7）**：
  - Listener **仅** `transitioned=true` 时调度连接初始化；重复 connect 不得再次 reset server radius/maxBlocks/matchedCount，不得重复发 C2S。
  - 新 connection B 主线程 init（B token current+active gate 内）：先统一 takeover cleanup（停预览/协作任务、释 GPU mesh、清 phase projection、清 `clientChainEventBus` pending；**不清订阅**），再 reset 连接投影并发 C2S。旧 A disconnect cleanup 在 B 建立后 no-op 也不残留资源。
  - Listener **仅** `transitioned && replacedPreviousLifecycle` 时调度 world 接管清理（首次 bind 不调度，避免首 load 空清理；幂等 cleanup 安全但以 replaced 收敛）。B world gate 内清 preview/GPU/phase/pending；**不**发连接级 C2S、不重置无关连接状态。
  - disconnect / unload / connection-takeover / world-takeover **共用** `cleanupLifecycleResources`，避免重复逻辑。
- 收敛协议：init/cleanup/S2C 动作均经客户端主线程 dispatcher；token 在入口捕获、主线程 gate 后执行。后继 connect reset / disconnect cleanup 使旧排队任务 no-op。**不谎称** monitor 外长动作与 lifecycle 严格互斥——可测试的收敛靠 token generation + 主线程 gate。
- 生产 callback 目前在 lifecycle monitor 内：接管 cleanup **不得**调用任何反向 lifecycle 入口；注释声明受控主线程 callback。连接初始化路径仍有 C2S（P2 残余：monitor 内 I/O；本轮不扩大架构重写），但自动工具三个 S2C 的 publication 只更新 adapter，可能产生的 C2S 延迟到下一次 `ClientTick`。
- 本决策重点覆盖的六个 S2C（对象组配置确认沿既有 connection gate 单独维护，不计入下列自动工具增量）：
  - `PacketChainConfigSync`：Handler 传 `ctx.netHandler`；ClientProxy `captureForConnection` → 主线程整包校验 → connection-active gate 写状态。
  - `PacketChainPhaseSnapshot`：传 `ctx.netHandler`；主线程 world-active gate 后才 publish client event bus；**禁止 Netty 直接写/发布语义状态**。
  - `PacketLootGamesMinesweeperPreviewResponse`：传 `ctx.netHandler`；主线程 world-active gate 后才应用 preview。
  - `PacketAutoToolSwapRoundResult`：传 `ctx.netHandler`；主线程 world-active gate 后仅结算当前 nonce/round 建立结果。
  - `PacketAutoToolSwapActionResult`：传 `ctx.netHandler`；主线程 world-active gate 后仅结算当前 round 的精确 in-flight action。
  - `PacketAutoToolSwapRoundPhase`：传 `ctx.netHandler`；主线程 world-active gate 后只接受当前 round 且严格递增的 `phaseSequence`，不借用通用 phase 投影关联工具事务。
- `CommonProxy` dedicated no-op；方法描述符仅 common 类型（`INetHandler`，非 `NetHandlerPlayClient`）。Packet/Handler 字节码不得引用 `net.minecraft.client.*`、client dispatcher 或 LWJGL（由 `CommonNetworkClassBoundaryTest` 字节码/签名断言）。
- `ChainEventBus.clearPending()`：清 pending 不破坏订阅；客户端 lifecycle cleanup 调用，防旧 phase 随后 drain 回写。

## 为什么

- 仅「单调 id + 全局 capture」无法区分迟到旧连接事件与新连接；disconnect/close 无全局顺序时，旧 cleanup 会清掉新连接的 preview/phase。
- S2C 若用全局 `capture()`，B 已 connect 后迟到的 A 包可能误绑 B 的 current token。
- phase/preview 必须绑 world scope：同连接切维度后旧世界排队任务不得写新世界投影。
- 仅靠「旧 A cleanup 在 B 后 no-op」不够：B 接管时若 A 尚未 cleanup，preview/GPU/phase/pending 会残留在 B 生命周期内；故 B init/world-takeover 必须主动统一清理。
- 重复 connect 若再 reset 投影并重发 C2S，会在有效 S2C 后回退 server 投影、放大网络。

## 不变量影响

- **I4**：上述六个 S2C Handler 只捕获原始数据 + common `INetHandler`；主线程整包校验/gate 后写状态；Netty 不碰 `ChainClientState` / 投影容器 / event-bus 或自动工具协议语义 publication。
- **I7**：断线/世界卸载经 `ClientMainThreadDispatcher` + lifecycle gate 停预览、释 GPU、清 phase/pending；**连接/世界接管**同样经主线程 gate 统一 cleanup，使旧 cleanup no-op 后仍收敛。
- **I6**：common 包/Proxy 描述符与字节码不拉 client 签名类。

## 演进

- 2026-07-10：S2C 初版 token 守卫（无连接 identity）。
- 2026-07-10：advance/publication 统一 monitor；`advanceKeepActive` 防 disconnect 后复活 active。
- 2026-07-10：绑定真实 `INetHandler`/`World` identity + connection/world generation；三 S2C 传 `ctx.netHandler`；init/cleanup 携 token gate；`clearPending` 防旧 phase 回写。
- 2026-07-10：`TransitionResult`（token/transitioned/replacedPreviousLifecycle）；仅 transitioned 调度 init；connection/world 接管统一 cleanup；重复 connect/load 不重复 init/清理。
- 2026-07-15：已删除旧自动工具预选生命周期子项；统一 cleanup 保留对象组、preview、GPU renderer、phase 与 event pending 五项故障隔离。
- 2026-07-16：自动工具三个 S2C 纳入 connection/world token gate；publication 仅推进 adapter，C2S 延迟到下一次 `ClientTick`，生命周期 cleanup 增加自动工具 controller/protocol 复位且不盲恢复库存。
