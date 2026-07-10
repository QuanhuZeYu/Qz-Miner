# 网络入包无界背压

## 错误现象

- 远程客户端可对 `PacketChainConfigRequest` 等高频配置类入包持续刷包。
- 每包向 `ServerMainThreadDispatcher` 无界 FIFO 投递一个任务，ServerTick 内全量排空，放大主线程负载。
- 非法请求逐包 WARN / 合法请求逐包 debug 可造成日志放大。

## 触发场景

- 多人服或恶意客户端连续发送连锁配置请求。
- 同一玩家会话重连/克隆后旧端点任务仍被消费，或过期会话强引用存活。

## 根本原因

- 网络 Handler 仅做「入主线程」收口，未对可被远程放大的路径做 per-endpoint 背压。
- 普通 FIFO 语义适合低频控制面，不适合 latest-wins 配置投影。
- 槽位若用 `AtomicReference` 间接持有任务，drain `remove` 后仍可对已脱离 map 的槽 `set`，出现 accepted-but-lost。
- 端点键若仅用 `System.identityHashCode` 表达会话身份，碰撞或 GC 后可能误合槽/永久占容量。

## 修复方案

- 提取 `KeyedLatestTaskLane`：与 FIFO 隔离的 keyed latest-wins 泳道；`ConcurrentHashMap<K,Runnable>` 上 `replace(key, observed, next)` / `remove(key)` 可线性化更新；固定容量（新 key 满拒、已有 key 可更新）；START 每 tick 最多 64 槽，END 不 drain；start/stop 不可复用 identity。
- `ServerChainConfigRequestDispatch`：key = UUID + 弱引用对象 identity（相等要求 UUID 相等且双方 referent 存活且 `==`，hash 仅分桶、不单独决定相等）；pending 弱持有端点；过期弱键实现 `StaleDetectableKey`，在 submit/drain/生命周期点可 purge 释放容量；消费时主线程重取在线玩家并要求实例身份匹配后再整包校验/写入；诊断限频汇总。
- `PacketChainConfigRequest.Handler` 只捕获原始 int 与端点，不直接每包入普通 FIFO。
- 服务端停止先同步清理玩家，再关闭 dispatcher。

## 预防措施

- 可被远程放大的入包路径默认按端点 latest-wins + 容量/预算，而非无界 FIFO。
- keyed 更新必须可线性化（replace/remove），禁写已脱离 map 的槽位；`submit==true` 的值必须最终可达。
- 消费前必须重取会话并校验实例身份；禁止仅用 UUID 或仅用 identityHashCode 假定端点仍有效。
- 弱键过期须可回收，避免 GC 后永久占容量。
- 诊断限频/汇总，禁逐包成功 debug / 非法 warn。
- 守 I4/I7。
