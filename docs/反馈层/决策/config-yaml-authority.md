# 决策：配置权威迁至 YAML + UILib 4.6.0 硬依赖

## 结论

- 配置权威文件：`config/qz_miner.yaml`（UILib `ConfigManager` + YAML Persistence）。
- 旧 Forge `config/qz_miner.cfg` 仅作一次性导入源；导入成功后退役为时间戳 `.imported.bak`；导入失败时重建/持久化 schema 默认 YAML。成功迁移与失败恢复都必须先严格退役 cfg、再发布 manager/current/Config static；`yamlFile` 也只随成功 manager commit 发布。提交前失败保持全部 bootstrap/static 状态不变，后续启动以已存在 YAML 为权威并忽略仍在的 cfg。
- Qz-UILib 的权威远端来源为 JitPack 标准坐标 `com.github.QuanhuZeYu:Qz-UILib:<tag>:dev`；活动配置已使用 `com.github.QuanhuZeYu:Qz-UILib:4.6.0:dev` 并移除 Maven Local/旧 group fallback，主动发布 GTNH Maven 不是 Miner 前置。`@Mod` 最低依赖为 `required-after:qz_uilib@[4.6.0,)`；发布包仍用 non-publishable 开发依赖且不内嵌 UILib。JitPack API/POM/module/main 已恢复，但 canonical `dev` 普通 GET 仍受陈旧边缘 `404` 阻断；只有其返回 `200 1993882`、同 SHA branch CI 全绿，且 tag 后另行核验 Release/assets，才完成发布证据。
- 客户端配置页：`ConfigSchema` → 长寿命 `ConfigManager` → `ConfigUI.buildScreen` → `McScreenBridge`。
- **单 YAML Authority**；只要 YAML 路径已是文件即取得最高优先级，零长度 YAML 作为结构化空 MAP，表示所有字段缺失并使用 schema 默认，不读取或退役 cfg。原始文件先经 `RawYamlPreflight` 按 Schema NodeType 检查，再进入 Authority 宽松转换；显式 null、错误 section/字段类型拒绝，未知字段不拒绝。
- UILib 4.6.0 bootstrap 注入无副作用 DraftValidator 与每 screen editor Registry；finite、整数、范围、alpha 跨字段与 `client.objectGroups` selector/mode/容量/交集语义非法在写盘前返回 INVALID，保留草稿，Authority/YAML/current/runtime/event/network 均不变；成功 `reloadDraftFromDisk()` 发布 `RELOAD`，与 `BATCH_SAVE` 共用捕获和分侧 mailbox 回灌；`DraftBuffer.resetFieldToDefault` 按真实 schema 恢复结构化默认值。
- `client.objectGroups.members` 使用 UILib 4.6.0 `LIST_MEMBERS` 管理选择器：外层常驻已配置/无效/重复摘要与管理入口，raw 默认折叠为高级编辑。portal 宽高受视口约束，搜索固定在顶部，当前规则/搜索结果按 3:5 目标动态分区；overlay 打开期间约束焦点，关闭后恢复原焦点。编辑按稳定 ID 只替换目标成员，新增则追加；删除由成员行发起并经二次确认，raw 仍可作高级无损修正入口。重复 registry 只提示、不自动合并，旧 aggregate codec 的同 registry 归一仅作为兼容接口保留，不定义当前 UI 语义。合法未知 selector 展示 canonical；malformed 在常规 UI 只展示通用说明、不泄露 raw，但高级 raw 保持无损。
- `ValidatedSnapshot` 是 Authority 的只读派生载荷；每次 bootstrap/capture 成功后以不回退的 `epoch` 包装成 `CommittedSnapshot` 并 volatile 发布。`commitManager` 先构造可能失败的 `CommittedSnapshot`（epoch overflow），再 `applyAll`/发布 yaml/current/manager，保证溢出零发布。client/server 各持有可关闭的无锁 latest-wins 单消费者 mailbox；publication 在所有 bootstrap/manager/mailbox monitor 外执行，开始前无锁复核全局 current identity。调度拒绝或异常保留 pending 并诊断，后续 submit 可恢复；publication 失败不推进 `processedEpoch`，stale current 未 publication 也会推进该水位。listener 替换/reset 先关闭旧 mailbox 再精确退订；替换完成后在 `SUBSCRIPTION_LOCK` 内 `captureCommittedSnapshot` 重新捕获 Authority（覆盖 COW 下已保存但 current 尚未 capture 的窗口），再锁外 dispatch。
- 运行字段分侧发布：client 经 `ClientMainThreadDispatcher`，general 仅在服务端主线程（集成服 BATCH_SAVE/RELOAD → `ServerMainThreadDispatcher`；远程客户端不写 general static）；`serverStarting` 只发布 current snapshot 的 general。
- C2S：`PacketChainConfigRequest` Handler 只捕获原始 int 与端点身份，经 `ServerChainConfigRequestDispatch` → `ServerMainThreadDispatcher.tryRunLatest` 进入与 FIFO 隔离的 keyed latest-wins 泳道（固定容量、START 每 tick 最多 64 槽、END 不 drain）。key = UUID + 弱引用对象 identity（UUID 相等且 referent 存活且 `==`；hash 仅分桶）；泳道 ConcurrentHashMap.replace/remove 线性化；pending 弱持有端点；过期弱键可 purge；消费时主线程重取在线玩家并要求实例身份匹配后再整包校验/夹上限/写入。非法、容量拒绝、过期会话诊断限频汇总，不逐包成功 debug。
- S2C：`PacketChainConfigSync` Handler 在 Netty 线程只转交三个原始 int 与 `ctx.netHandler`（common `INetHandler`）。`ClientProxy` 按 **handler 对象 `==` identity** `captureForConnection` 后经 `ClientChainConfigSyncDispatch` 投递；不匹配/inactive 直接丢弃且不做 dispatcher rejection warn。主线程任务内**先整包数值校验**（radius/maxBlocks>0 且 matchedCount≥0），**再**在 lifecycle 线性化边界（`publishIfConnectionCurrentAndActive`）内复核 **connection** 仍 current 且 active 并 publication；check 与 publication 共享同一 monitor，禁止 check 后裸调用。connect 以 `event.handler` 绑定 identity；相同 active handler 重复 connect no-op；disconnect 仅当前 handler 转 inactive；client world load/unload 绑定 world identity（首次 bind 不废弃连接级 config token）。dispatcher 拒绝时 `RateLimitedRejectDiagnostics` 仅 CAS 获胜线程限频汇总，不跨 lifecycle 重试。非法包保留旧三字段；**不**检查 matchedCount≤maxBlocks。LOGIN 的 matchedCount=0 合法。connect 初始化将 server radius/maxBlocks 回落 validated local snapshot、matchedCount=0。禁止 Netty 线程写 `ChainClientState`；publication 回调禁阻塞/禁反向调用 lifecycle 入口。完整 identity/world scope 见 `docs/反馈层/决策/client-connection-identity.md`。
- C2S keyed drain：`ServerMainThreadDispatcher` START 外层不可重入 guard，嵌套 START 跳过 keyed lane；普通 FIFO 不变。KeyedLatestTaskLane 线性化契约为「最后线性化成功值最终执行」，非「每个 submit true 最终可达」。
- 对象组同步：对象组请求的 revision、rules、groupCount 必须来自同一个不可变 `CommittedSnapshot`；listener publication 和连接初始化均只捕获一次该包装，禁止发送时重新读取全局 current。客户端按 `(connectionGeneration, requestedRevision)` 保存最多 8 个完整请求快照，ACK 只消费对应记录；成功确认从记录发布规则，不能用未发送或未确认的 current 配置替代。`PacketObjectGroupConfigSync` 固定 25 字节，只捕获 raw/valid，不持有 Forge `ByteBuf`；包携带 `requestedRevision` 与 `authoritativeRevision`，截断、尾随和非法 raw 均丢弃。客户端主线程校验协议版本、revision、accepted byte、groupCount，并要求成功确认的 groupCount 等于对应请求快照数量；结果按 `(requestedRevision, authoritativeRevision, accepted)` 严格单调，旧 ACK 忽略。连接接管/断开清空 pending；旧连接包由 connection identity gate 丢弃。
- 服务端停止：`serverStopping` 先同步 `PlayerManager.clearAllPlayersOnServerStopping()` 完成玩家生命周期清理，再 `ServerMainThreadDispatcher.onServerStopping()`；其他 `clearAllPlayers` 路径语义不变。
- 删除 Forge `GuiConfig` 降级页与 `ConfigChangedEvent` 保存链；当前 20 字段（含 `greeting` 与结构化 `client.objectGroups`）由同一 YAML 权威管理；默认单一源 `QzMinerConfigDefaults`。
- 现有 YAML 的语法/raw/语义错误统一先 required backup、再删除、默认重建并复验；cfg 导入产物也重载执行 raw+语义复验。备份失败 fail-fast，绝不删除原文件或回退 cfg 运行。
- `@Mod`：`required-after:qz_uilib@[4.6.0,);`

## 为什么

- UILib 4.5 已移除 `ForgeConfigTemplateScreen` 与 HTML-like 配置模板，旧双轨适配不可用。
- 双轨 cfg/YAML 与「UILib 可选」会长期漂移；用户明确选择硬依赖换单一权威。
- 运行时读取面仍为 `Config` 静态字段，便于既有调用方零迁移；但发布必须分侧，避免客户端线程写 general 破坏 I4 精神。
- C2S 每包入无界 FIFO 可被远程放大主线程队列；keyed latest-wins 背压是网络信任边界的必要护栏。

## 不变量影响

- **I6**：Qz-UILib 是硬依赖，不属于 I6 可选模组反射适用域；其他可选模组继续守 I6 反射安全边界。
- **I4**：配置网络 Handler 只捕获原始数据，最终整包校验与状态写入均在对应主线程；C2S 经 keyed lane 背压（START drain 不可重入），S2C 经客户端 lifecycle **连接 identity** token + dispatcher 收口。
- **I7**：服务端停止时玩家清理先于 dispatcher 关闭，避免 stop 后 FIFO 拒绝导致生命周期清理丢失；客户端断线/卸载经 lifecycle gate 清预览/GPU/phase/pending。
- **对象组生命周期**：服务端规则按玩家 UUID 独立保存；统一玩家状态移除时随状态清理。每次任务只使用启动时冻结的组快照，配置 reload 只影响下一任务。
- **运行时扩展语义**：对象组不占用独立 `ChainSubMode`，仅映射到七个既有模式。主线程以 mode+seed 从已接受玩家规则解析唯一组并冻结为 registry→16-bit metadata mask；规划与已确认客户端预览共享纯快照解析和同一运行时工厂。candidate 为 `Q OR X`；matcher 先按 Q 短路，五个采掘模式为 `Q OR (X AND ChainHarvestRules.canHarvest)`，`INTERACT_BASE` 为 `Q OR X`，`INTERACT_CROP` 为 `Q OR (X AND non-null/non-air/non-liquid)`。空扩展、非支持模式或 pending 均返回原判定实例；traverser/executor/drop/state machine 不变。
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
- 2026-07-10：S2C identity 闭环——token 绑定 `INetHandler`/`World` 对象 `==`；三 S2C 传 `ctx.netHandler`；connect init / disconnect cleanup 携 generation gate；见 `client-connection-identity.md`。
- 2026-07-11：Qz-Miner 接入 Maven Local 的 Qz-UILib `4.5.3-beta-1:dev`；`ClientConfigChangeListener` 同时处理 `BATCH_SAVE`/`RELOAD`，成功磁盘回载经同一 Authority capture 与分侧 mailbox 回灌。
- 2026-07-11：升级 Qz-UILib `4.5.3-beta-2` 并接入结构化对象组：Schema identity、DraftValidator 语义门禁、完整 C2S latest-wins 同步、S2C revision 确认和服务端冻结组快照。
- 2026-07-11：对象组 S2C 纠偏：固定长度 raw/valid framing、requested/authoritative 双 revision、主线程语义校验、严格单调乱序结果与重连 epoch 水位；补齐 groupCount 和 common proxy 签名门禁。
- 2026-07-11：对象组 P1 纠偏：完整 `CommittedSnapshot` 贯穿 publication/连接发送；客户端按连接代际保存有界请求快照，ACK 按对应 revision 消费并发布服务端实际确认的规则，连接清理回收 pending。
- 2026-07-11：开发与最低运行依赖升级至 Qz-UILib `4.5.3-beta-3`，继续使用 Maven Local `dev` 制品；不改变配置权威与对象组协议。
- 2026-07-11：对象组 runtime 从独立模式收口为七个既有模式的冻结筛选扩展，删除第一批兼容 API；Picker 交互留待后续。
- 2026-07-11：第一批对象模式扩展升级至 Qz-UILib `4.5.3-beta-4`；schema 增加多选 modes，模型固定 7-bit 注册表并规范化 selector，C2S 升至 wire v2。runtime、模式枚举和预览留待第二批。
- 2026-07-11：升级至 Qz-UILib `4.5.3-beta-6` 并接入 `qz_miner:block-selector`。Picker 每屏固化客户端方块 registry 搜索和 ItemStack 图标；Codec 接收并返回完整 members list，同 registry mask 在首位置规范合并，wildcard 覆盖，无法解析和其它 registry 的 raw 原样保留。
- 2026-07-12：升级至 Qz-UILib `4.5.3-beta-7` 双参 Codec。方块 Picker Codec 删除行间缓存，以本次 canonical 替换同 registry 旧 selector 而非 OR；其它 raw 原位保留，并补齐中文 presentation 与面向玩家的对象组顶层文案。
- 2026-07-12：开发与最低运行依赖曾升级至 Qz-UILib `4.5.3-beta-8`，Maven Local `dev` 制品 SHA-256 为 `10B84984997FC691557BFDAF4E519B525A98E7AEA95DB0D719A06C8CA13122EE`；该版本仅为历史演进，不再是当前依赖。
- 2026-07-12：当前开发与最低运行依赖升级至 Qz-UILib `4.5.3-beta-10`，Picker 收敛为 ALL/SELECTED 且保留既有 canonical；不改变配置权威与对象组协议。
- 2026-07-12：当前开发与最低运行依赖升级至 Qz-UILib `4.5.3-beta-11`，修复长对象组 identity 遮挡 header 操作按钮；不改变配置权威与对象组协议。
- 当前开发与最低运行依赖为 Qz-UILib `4.6.0`；真实 schema 回归确认字段 reset 可恢复三个 vanilla 对象组默认值。
- 2026-07-15：已删除旧自动工具预选配置对象；旧 YAML 键按既有未知字段宽松读取合同忽略，不增加专用迁移。
- 2026-07-13：对象组成员接入 UILib 4.6.0 `LIST_MEMBERS`；当前 UI 改为按稳定 ID 单项替换或新增追加，不再采用 aggregate codec 的同 registry 自动归一语义。
- 2026-07-14：成员管理选择器补齐常驻摘要/管理入口、受约束 portal、顶部搜索、3:5 动态分区、稳定 ID 编辑、二次确认删除、重复诊断、malformed 常规界面隐藏与 overlay 焦点约束；raw 仅作为默认折叠的高级无损入口。
