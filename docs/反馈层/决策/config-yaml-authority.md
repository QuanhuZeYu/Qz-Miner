# 决策：配置权威迁至 YAML + UILib 4.5.2 硬依赖

## 结论

- 配置权威文件：`config/qz_miner.yaml`（UILib `ConfigManager` + YAML Persistence）。
- 旧 Forge `config/qz_miner.cfg` 仅作一次性导入源；导入成功后退役为时间戳 `.imported.bak`；导入失败时重建/持久化 schema 默认 YAML。成功迁移与失败恢复都必须先严格退役 cfg、再发布 manager/current/Config static；`yamlFile` 也只随成功 manager commit 发布。提交前失败保持全部 bootstrap/static 状态不变，后续启动以已存在 YAML 为权威并忽略仍在的 cfg。
- Qz-UILib 升至 `4.5.2:dev`，`@Mod` 依赖改为 `required-after:qz_uilib`；发布包仍用 `devOnlyNonPublishable`，不内嵌 UILib。
- 客户端配置页：`ConfigSchema` → 长寿命 `ConfigManager` → `ConfigUI.buildScreen` → `McScreenBridge`。
- **单 YAML Authority**；只要 YAML 路径已是文件即取得最高优先级，零长度 YAML 作为结构化空 MAP，表示所有字段缺失并使用 schema 默认，不读取或退役 cfg。原始文件先经 `RawYamlPreflight` 按 Schema NodeType 检查，再进入 Authority 宽松转换；显式 null、错误 section/字段类型拒绝，未知字段不拒绝。
- UILib 4.5.2 三参 bootstrap 注入无副作用 DraftValidator；finite、整数、范围与 alpha 跨字段非法在写盘前返回 INVALID，保留草稿，Authority/YAML/current/runtime/event/network 均不变。
- `ValidatedSnapshot` 是 Authority 的只读派生载荷；每次 bootstrap/capture 成功后以不回退的 `epoch` 包装成 `CommittedSnapshot` 并 volatile 发布。`commitManager` 先构造可能失败的 `CommittedSnapshot`（epoch overflow），再 `applyAll`/发布 yaml/current/manager，保证溢出零发布。client/server 各持有可关闭的无锁 latest-wins 单消费者 mailbox；publication 在所有 bootstrap/manager/mailbox monitor 外执行，开始前无锁复核全局 current identity。调度拒绝或异常保留 pending 并诊断，后续 submit 可恢复；publication 失败不推进 `processedEpoch`，stale current 未 publication 也会推进该水位。listener 替换/reset 先关闭旧 mailbox 再精确退订；替换完成后在 `SUBSCRIPTION_LOCK` 内 `captureCommittedSnapshot` 重新捕获 Authority（覆盖 COW 下已保存但 current 尚未 capture 的窗口），再锁外 dispatch。
- 运行字段分侧发布：client 经 `ClientMainThreadDispatcher`，general 仅在服务端主线程（集成服 BATCH_SAVE → `ServerMainThreadDispatcher`；远程客户端不写 general static）；`serverStarting` 只发布 current snapshot 的 general。
- C2S：`PacketChainConfigRequest` Handler 只捕获原始 int 与端点身份，经 `ServerChainConfigRequestDispatch` → `ServerMainThreadDispatcher.tryRunLatest` 进入与 FIFO 隔离的 keyed latest-wins 泳道（固定容量、START 每 tick 最多 64 槽、END 不 drain）。key = UUID + 弱引用对象 identity（UUID 相等且 referent 存活且 `==`；hash 仅分桶）；泳道 ConcurrentHashMap.replace/remove 线性化；pending 弱持有端点；过期弱键可 purge；消费时主线程重取在线玩家并要求实例身份匹配后再整包校验/夹上限/写入。非法、容量拒绝、过期会话诊断限频汇总，不逐包成功 debug。
- S2C：`PacketChainConfigSync` Handler 在 Netty 线程只转交三个原始 int；`ClientProxy` 捕获 `ClientConnectionLifecycle` token 后经 `ClientChainConfigSyncDispatch` 投递。inactive token 直接丢弃且不做 dispatcher rejection warn。主线程任务内**先整包数值校验**（radius/maxBlocks>0 且 matchedCount≥0），**再**在 lifecycle 线性化边界（`publishIfCurrentAndActive`）内复核 token 仍为 current 且 active 并 publication；check 与 publication 共享同一 monitor，禁止 check 后裸调用。connect 推进 active、disconnect 推进 inactive、client world unload 在 monitor 内读取线性化时刻 active 标志再推进（disconnect 已提交后 unload 不得复活 active）。dispatcher 拒绝时 `RateLimitedRejectDiagnostics` 仅 CAS 获胜线程限频汇总，不跨 lifecycle 重试。非法包保留旧三字段；**不**检查 matchedCount≤maxBlocks（规划启动后配置可能下调）。LOGIN 的 matchedCount=0 合法。publication 若先于 disconnect 取得线性化边界，属于旧生命周期内完成，不算跨生命周期写；disconnect 不重置 `ChainClientState` 旧字段（现有契约未要求清零）。禁止 Netty 线程写 `ChainClientState`；publication 回调禁阻塞/禁反向调用 lifecycle 入口。
- C2S keyed drain：`ServerMainThreadDispatcher` START 外层不可重入 guard，嵌套 START 跳过 keyed lane；普通 FIFO 不变。KeyedLatestTaskLane 线性化契约为「最后线性化成功值最终执行」，非「每个 submit true 最终可达」。
- 服务端停止：`serverStopping` 先同步 `PlayerManager.clearAllPlayersOnServerStopping()` 完成玩家生命周期清理，再 `ServerMainThreadDispatcher.onServerStopping()`；其他 `clearAllPlayers` 路径语义不变。
- 删除 Forge `GuiConfig` 降级页与 `ConfigChangedEvent` 保存链；全部 19 字段（含 `greeting`）保留；默认单一源 `QzMinerConfigDefaults`。
- 现有 YAML 的语法/raw/语义错误统一先 required backup、再删除、默认重建并复验；cfg 导入产物也重载执行 raw+语义复验。备份失败 fail-fast，绝不删除原文件或回退 cfg 运行。
- `@Mod`：`required-after:qz_uilib@[4.5.2,);`

## 为什么

- UILib 4.5 已移除 `ForgeConfigTemplateScreen` 与 HTML-like 配置模板，旧双轨适配不可用。
- 双轨 cfg/YAML 与「UILib 可选」会长期漂移；用户明确选择硬依赖换单一权威。
- 运行时读取面仍为 `Config` 静态字段，便于既有调用方零迁移；但发布必须分侧，避免客户端线程写 general 破坏 I4 精神。
- C2S 每包入无界 FIFO 可被远程放大主线程队列；keyed latest-wins 背压是网络信任边界的必要护栏。

## 不变量影响

- **I6**：对 `qz_uilib` 的软可选前提经 `NORTH_STAR.md` 偏离 `D-YAML-CONFIG-AUTHORITY` 显式撤销；其他可选模组反射边界不变。
- **I4**：配置网络 Handler 只捕获原始数据，最终整包校验与状态写入均在对应主线程；C2S 经 keyed lane 背压（START drain 不可重入），S2C 经客户端 lifecycle token + dispatcher 收口。
- **I7**：服务端停止时玩家清理先于 dispatcher 关闭，避免 stop 后 FIFO 拒绝导致生命周期清理丢失。
- 服务端代码禁止引用 `club.heiqi.config.ui`、屏幕桥接壳、LWJGL；仅 client GUI 包可引用。

## 演进

- 2026-07-10：用户拍板 YAML 权威 + UILib 4.5.1 硬依赖 + 保留 greeting；落地分支 `refactor/yaml-config-uilib-4.5`。
- 2026-07-10：reviewer 纠偏——分侧回灌、磁盘 fail-fast、语义严格校验、Defaults 单一源、版本区间依赖。
- 2026-07-10：接入已发布 UILib 4.5.2 DraftValidator；增加 raw NodeType preflight，把语义拒绝前移到提交事务，last-valid 改为只读 current 派生快照并删除事后恢复。
- 2026-07-10：reviewer 纠偏——空 YAML 取得权威、cfg 退役前禁止发布、current snapshot 身份 freshness gate、listener 精确 manager+实例退订及 I4 客户端主线程收口。
- 2026-07-10：最终纠偏——发布改为单调 epoch + 分侧无锁 latest-wins mailbox；listener 关闭旧任务并替换 seed；S2C/C2S 在对应主线程做整包最终校验；`yamlFile` 纳入成功 commit。
- 2026-07-10：P0/P1 纠偏——C2S keyed latest-wins 背压；listener replacement 锁内 recapture Authority；`commitManager` 先构造 snapshot 再发布；S2C dispatcher 拒绝传播；mailbox 水位命名 `processedEpoch`；serverStopping 先清理玩家再关 dispatcher。
- 2026-07-10：终审纠偏——KeyedLatestTaskLane 改为 ConcurrentHashMap.replace/remove 线性化协议，消除 accepted-but-lost；EndpointKey 改为弱引用对象 identity（非仅 identityHashCode），过期弱键可 purge。
- 2026-07-10：生命周期/背压纠偏——S2C `ClientConnectionLifecycle` token 守卫；keyed START drain 不可重入；latest-wins 契约改为「最后线性化成功值最终执行」；拒绝诊断 CAS 独占批次。
- 2026-07-10：S2C 终审纠偏——advance/publication 统一 monitor 线性化；`advanceKeepActive` 禁止分离 get/set；publication 经 `publishIfCurrentAndActive` 消除 gate TOCTOU；任务顺序固定为先整包校验再 gate 内 publication。
