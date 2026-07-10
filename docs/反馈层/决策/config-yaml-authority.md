# 决策：配置权威迁至 YAML + UILib 4.5.2 硬依赖

## 结论

- 配置权威文件：`config/qz_miner.yaml`（UILib `ConfigManager` + YAML Persistence）。
- 旧 Forge `config/qz_miner.cfg` 仅作一次性导入源；导入成功后退役为时间戳 `.imported.bak`；导入失败时重建/持久化 schema 默认 YAML。成功迁移与失败恢复都必须先严格退役 cfg、再发布 manager/current/Config static；退役失败首次启动 fail-fast 且零静态发布，后续启动以已存在 YAML 为权威并忽略仍在的 cfg。
- Qz-UILib 升至 `4.5.2:dev`，`@Mod` 依赖改为 `required-after:qz_uilib`；发布包仍用 `devOnlyNonPublishable`，不内嵌 UILib。
- 客户端配置页：`ConfigSchema` → 长寿命 `ConfigManager` → `ConfigUI.buildScreen` → `McScreenBridge`。
- **单 YAML Authority**；只要 YAML 路径已是文件即取得最高优先级，零长度 YAML 作为结构化空 MAP，表示所有字段缺失并使用 schema 默认，不读取或退役 cfg。原始文件先经 `RawYamlPreflight` 按 Schema NodeType 检查，再进入 Authority 宽松转换；显式 null、错误 section/字段类型拒绝，未知字段不拒绝。
- UILib 4.5.2 三参 bootstrap 注入无副作用 DraftValidator；finite、整数、范围与 alpha 跨字段非法在写盘前返回 INVALID，保留草稿，Authority/YAML/current/runtime/event/network 均不变。
- `ValidatedSnapshot` 是 Authority 的只读派生发布载荷。`BATCH_SAVE` 成功回调按 manager 身份同步捕获一次 Authority 全表并替换 `currentValidatedSnapshot`，该唯一快照身份同时作为 publication epoch token；异步 client/server 任务执行时在与 current 更新相同的 monitor 内做身份 freshness 检查并完成受控短发布，陈旧任务 no-op，不依赖 dispatcher FIFO。受控动作不得等待外部线程或触发配置提交；当前客户端网络请求仅单向入队。dispatcher 参数在投递前全量预检，运行时调度异常为 best-effort，已接受任务不可回滚。
- 运行字段分侧发布：client 经 `ClientMainThreadDispatcher`，general 仅在服务端主线程（集成服 BATCH_SAVE → `ServerMainThreadDispatcher`；远程客户端不写 general static）；`serverStarting` 只发布 current snapshot 的 general。
- radius/maxBlocks 请求值取严格校验快照后走 `PacketChainConfigRequest`（单机可跳包）。
- 删除 Forge `GuiConfig` 降级页与 `ConfigChangedEvent` 保存链；全部 19 字段（含 `greeting`）保留；默认单一源 `QzMinerConfigDefaults`。
- 现有 YAML 的语法/raw/语义错误统一先 required backup、再删除、默认重建并复验；cfg 导入产物也重载执行 raw+语义复验。备份失败 fail-fast，绝不删除原文件或回退 cfg 运行。
- `@Mod`：`required-after:qz_uilib@[4.5.2,);`

## 为什么

- UILib 4.5 已移除 `ForgeConfigTemplateScreen` 与 HTML-like 配置模板，旧双轨适配不可用。
- 双轨 cfg/YAML 与「UILib 可选」会长期漂移；用户明确选择硬依赖换单一权威。
- 运行时读取面仍为 `Config` 静态字段，便于既有调用方零迁移；但发布必须分侧，避免客户端线程写 general 破坏 I4 精神。

## 不变量影响

- **I6**：对 `qz_uilib` 的软可选前提经 `NORTH_STAR.md` 偏离 `D-YAML-CONFIG-AUTHORITY` 显式撤销；其他可选模组反射边界不变。
- **I4**：配置网络包仍经既有路径；**运行字段发布** general 走服务端主线程 dispatcher，client 走客户端主线程 dispatcher，远程客户端不得以本地 general static 冒充服务端权威。
- 服务端代码禁止引用 `club.heiqi.config.ui`、屏幕桥接壳、LWJGL；仅 client GUI 包可引用。

## 演进

- 2026-07-10：用户拍板 YAML 权威 + UILib 4.5.1 硬依赖 + 保留 greeting；落地分支 `refactor/yaml-config-uilib-4.5`。
- 2026-07-10：reviewer 纠偏——分侧回灌、磁盘 fail-fast、语义严格校验、Defaults 单一源、版本区间依赖。
- 2026-07-10：接入已发布 UILib 4.5.2 DraftValidator；增加 raw NodeType preflight，把语义拒绝前移到提交事务，last-valid 改为只读 current 派生快照并删除事后恢复。
- 2026-07-10：reviewer 纠偏——空 YAML 取得权威、cfg 退役前禁止发布、current snapshot 身份 freshness gate、listener 精确 manager+实例退订及 I4 客户端主线程收口。
