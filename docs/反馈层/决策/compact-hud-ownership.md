# 决策：紧凑 HUD 所有权

## 结论

- Miner 不再订阅 Forge overlay 或直接处理字体、坐标与格式码，只通过 `QzMinerHudSnapshotProvider` 发布不可变语义行。
- `ClientProxy.init` 是 `qz_miner:chain-status` 的唯一注册所有者，锚点固定为 `TOP_LEFT`。注册句柄属于模组客户端生命周期，跨世界和断线保留，不进入网络 handler close 或连接清理路径。
- `KeyListener` 只更新按键、模式与请求状态；可见性由 provider 根据本地按键及 `PLANNING / RUNNING / FINISHING` 投影决定。
- UILib 负责 GUI 打开时隐藏、布局和实际绘制。Miner 的 common/server 边界不得引用 `club.heiqi.uilib.ui.hud.api`。

## 信息契约

行顺序固定为状态、主模式、可选子模式、服务端限制、服务端匹配、对象组同步、可选预览、可选 AREA 尺寸。行 ID 稳定，用于 UILib keyed 协调；每次读取返回不可变快照。

## 依赖与发布

该能力要求 Qz-UILib `4.6.0`。当前 4.6.0 仅 Maven Local 可解析，远端尚未发布；Miner 正式发布因此受阻，直到远端正式坐标可复现解析。

## 不变量核对

- I4：网络 handler 不注册、关闭或直接改写 HUD，状态更新仍经客户端主线程 dispatcher。
- I7：断线清理客户端投影和预览资源，但保留模组级 HUD 注册，重连不会重复注册。
