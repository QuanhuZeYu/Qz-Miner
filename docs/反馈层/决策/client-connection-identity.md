# 决策：客户端连接 identity 生命周期

## 结论

- 客户端连接生命周期 token 绑定**真实连接 identity**：生产值为 `FMLNetworkEvent` 的 `event.handler` / `MessageContext.netHandler`（common `INetHandler` 实例），按对象引用 `==` 判定；**禁止** `identityHashCode` / `equals` 作为身份。
- API 核验：同一连接上 connect/disconnect 的 `event.handler` 与入包 `ctx.netHandler` 为同一 common `INetHandler` 实例；不同连接不同实例；disconnect/close 可重复且跨旧新 channel **无全局顺序**。
- Token 至少含：不可复用 `connectionGeneration`、不可复用 `worldGeneration`、`connectionActive` / `worldActive`、`connectionIdentity` / `worldIdentity`。
- `connect(handler)`：相同 active handler 重复事件 no-op（返回当前 token）；不同 handler 创建新 active connection token。
- `disconnect(handler)`：仅 `handler == current connection` 且 active 时转 inactive；旧连接迟到 / 重复 disconnect **no-op 且不得调度新清理**。
- World scope（仅 client 类内）：`WorldEvent.Load` 绑定远端 world 对象；**首次绑定不废弃连接级 config token**（同 `connectionGeneration`）；世界替换推进 `worldGeneration`。`Unload` 仅当 `event.world == current world` 且连接仍 current 时推进；旧 world / 重复 unload / disconnect 后 unload no-op。同连接切维度：connection 保持 active，旧 world token 失效。
- Gate：统一 monitor 线性化；禁止 check 后裸写。连接级（config）/ 世界级（phase、preview）/ inactive-disconnect cleanup / world-unbind cleanup 分 narrow gate。短小本地 publication（config 三字段、event-bus offer）可在 monitor 内执行；回调**禁阻塞、禁反向 lifecycle 入口**。不引入第二把锁。
- 收敛协议：init/cleanup/S2C 动作均经客户端主线程 dispatcher；token 在入口捕获、主线程 gate 后执行。后继 connect reset / disconnect cleanup 使旧排队任务 no-op。**不谎称** monitor 外长动作与 lifecycle 严格互斥——可测试的收敛靠 token generation + 主线程 gate。
- connect 初始化：入口 `connect(event.handler)` 建 token，任务携该 token；主线程仅 connection current+active 时 reset 连接投影（server radius/maxBlocks 回落当前 validated local snapshot、`matchedCount=0`）并（非单人）发 C2S。A init 排队后 disconnect/connect B → A init no-op，不向 B 发包。
- disconnect cleanup：仅成功转移才排队，携 inactive token；主线程仅该 connection 仍 current 时清理（停预览、释放 GPU、清 phase、`clientChainEventBus.clearPending()`）。B connect 后 A cleanup no-op。world unload cleanup 同理。
- 三个 S2C（NetworkMain 注册表客户端方向仅此三）：
  - `PacketChainConfigSync`：Handler 传 `ctx.netHandler`；ClientProxy `captureForConnection` → 主线程整包校验 → connection-active gate 写状态。
  - `PacketChainPhaseSnapshot`：传 `ctx.netHandler`；主线程 world-active gate 后才 publish client event bus；**禁止 Netty 直接写/发布语义状态**。
  - `PacketLootGamesMinesweeperPreviewResponse`：传 `ctx.netHandler`；主线程 world-active gate 后才应用 preview。
- `CommonProxy` dedicated no-op；方法描述符仅 common 类型（`INetHandler`，非 `NetHandlerPlayClient`）。Packet/Handler 字节码不得引用 `net.minecraft.client.*`、client dispatcher 或 LWJGL（由 `CommonNetworkClassBoundaryTest` 字节码/签名断言）。
- `ChainEventBus.clearPending()`：清 pending 不破坏订阅；客户端 lifecycle cleanup 调用，防旧 phase 随后 drain 回写。

## 为什么

- 仅「单调 id + 全局 capture」无法区分迟到旧连接事件与新连接；disconnect/close 无全局顺序时，旧 cleanup 会清掉新连接的 preview/phase。
- S2C 若用全局 `capture()`，B 已 connect 后迟到的 A 包可能误绑 B 的 current token。
- phase/preview 必须绑 world scope：同连接切维度后旧世界排队任务不得写新世界投影。

## 不变量影响

- **I4**：三个 S2C Handler 只捕获原始数据 + common `INetHandler`；主线程整包校验/gate 后写状态；Netty 不碰 `ChainClientState` / 投影容器 / event-bus 语义 publish。
- **I7**：断线/世界卸载经 `ClientMainThreadDispatcher` + lifecycle gate 停预览、释 GPU、清 phase/pending。
- **I6**：common 包/Proxy 描述符与字节码不拉 client 签名类。

## 演进

- 2026-07-10：S2C 初版 token 守卫（无连接 identity）。
- 2026-07-10：advance/publication 统一 monitor；`advanceKeepActive` 防 disconnect 后复活 active。
- 2026-07-10：绑定真实 `INetHandler`/`World` identity + connection/world generation；三 S2C 传 `ctx.netHandler`；init/cleanup 携 token gate；`clearPending` 防旧 phase 回写。
