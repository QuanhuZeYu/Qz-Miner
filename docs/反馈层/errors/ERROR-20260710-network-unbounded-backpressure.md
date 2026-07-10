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

## 修复方案

- 提取 `KeyedLatestTaskLane`：与 FIFO 隔离的 keyed latest-wins 泳道；固定容量（新 key 满拒、已有 key 可更新）；START 每 tick 最多 64 槽，END 不 drain；start/stop 不可复用 identity。
- `ServerChainConfigRequestDispatch`：key = UUID + 玩家实例 identity；pending 弱持有端点；消费时主线程重取在线玩家并要求实例身份匹配后再整包校验/写入；诊断限频汇总。
- `PacketChainConfigRequest.Handler` 只捕获原始 int 与端点，不直接每包入普通 FIFO。
- 服务端停止先同步清理玩家，再关闭 dispatcher。

## 预防措施

- 可被远程放大的入包路径默认按端点 latest-wins + 容量/预算，而非无界 FIFO。
- 消费前必须重取会话并校验实例身份；禁止仅用 UUID 假定端点仍有效。
- 诊断限频/汇总，禁逐包成功 debug / 非法 warn。
- 守 I4/I7。
