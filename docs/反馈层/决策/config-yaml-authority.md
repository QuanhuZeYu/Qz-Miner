# 决策：配置权威迁至 YAML + UILib 4.5 硬依赖

## 结论

- 配置权威文件：`config/qz_miner.yaml`（UILib `ConfigManager` + YAML Persistence）。
- 旧 Forge `config/qz_miner.cfg` 仅作一次性导入源；导入成功后退役为时间戳 `.imported.bak`；导入失败或坏 YAML 时备份原文件并重建/持久化 schema 默认 YAML，**绝不**继续以 cfg 为运行时权威。
- Qz-UILib 升至 `4.5.1:dev`，`@Mod` 依赖改为 `required-after:qz_uilib`；发布包仍用 `devOnlyNonPublishable`，不内嵌 UILib。
- 客户端配置页：`ConfigSchema` → 长寿命 `ConfigManager` → `ConfigUI.buildScreen` → `McScreenBridge`；`BATCH_SAVE` 单次订阅回灌静态字段后走既有 `PacketChainConfigRequest` 同步。
- 删除 Forge `GuiConfig` 降级页与 `ConfigChangedEvent` 保存链；所有既有静态字段（含 `greeting`）保留。

## 为什么

- UILib 4.5 已移除 `ForgeConfigTemplateScreen` 与 HTML-like 配置模板，旧双轨适配不可用。
- 双轨 cfg/YAML 与「UILib 可选」会长期漂移；用户明确选择硬依赖换单一权威。
- 运行时读取面仍为 `Config` 静态字段，便于既有调用方零迁移。

## 不变量影响

- **I6**：对 `qz_uilib` 的软可选前提经 `NORTH_STAR.md` 偏离 `D-YAML-CONFIG-AUTHORITY` 显式撤销；其他可选模组反射边界不变。
- **I4**：配置同步网络路径不变，仍经既有 dispatcher/收口语义。
- 服务端代码禁止引用 `club.heiqi.config.ui`、`McScreenBridge`、LWJGL；仅 client GUI 包可引用。

## 演进

- 2026-07-10：用户拍板 YAML 权威 + UILib 4.5.1 硬依赖 + 保留 greeting；落地分支 `refactor/yaml-config-uilib-4.5`。
