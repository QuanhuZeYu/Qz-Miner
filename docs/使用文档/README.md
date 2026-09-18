# 使用文档

本文件是 Qz-Miner 的对外使用说明：配置项语义与联机版本边界。安装、操作与模式见根 [README.md](../../README.md)。

每个配置项只回答四件事：效果、默认值与合法范围、何时生效、限制。键名即 `config/qz_miner.yaml` 中的配置路径：`general.*` 是服务端权威配置；`client.*` 多数为本机客户端配置，但 `autoToolSwapEnabled` / `autoToolPrioritySelectors` 虽沿用 `client.*` 路径名，生效权威在服务端自己的已提交配置，以各条目说明为准。

## 配置项

- `general.tickBudgetMs`：planning、客户端 preview 与非 GT 普通执行共享的每 Tick soft deadline，默认 `15ms`，合法范围 `1..40ms`。只在安全点观察，已经开始的事务会执行完，不承诺硬实时中断。
- 系统规模由容量、队列回压与 `chainMaxBlocks` 共同约束。精确 `Blocks.air` 不触发模式匹配、也不扩展连锁范围。
- `client.tunnelDirectionSource`：每位玩家的 `AREA_TUNNEL` 方向偏好，默认 `look_direction`。`look_direction` 取玩家视线中绝对值最大的轴；`hit_face` 取左键命中方块面的反向，也就是从被点击表面朝方块内部开掘。六个视线轴与六个命中面均受支持。
  - 方向偏好以服务端确认值为准：保存后存在确认延迟，期间不乐观切换预览方向。`hit_face` 的命中只与随后同维度、同坐标的破坏事件匹配一次，缺失或失配时回退到该次破坏时冻结的视线方向；松键、切换模式与玩家生命周期清理都会使未消费命中失效。
  - 与 5.2 及更旧端不建立连接；旧格式数据固定降级为 `look_direction`。
- `client.autoToolSwapEnabled`：是否启用自动工具换位，默认 `true`；生效值取自**服务器自己的**已提交配置，远程客户端不会上传本地值。活动连锁 session 冻结创建时的策略，服务器 reload 只影响下一 session；创造模式不换位。
- `client.autoToolPrioritySelectors`：自动工具候选的有序优先级列表，不是白名单。支持 `<namespace:path>@*`、`<namespace:path>@<meta>` 与 `ore:<name>`；先按最早命中的规则排序，同优先级及未命中的合格候选按个人库存槽位 `0..35` 排序。服务端只读取自己的已提交列表，远程客户端值不会覆盖服务端。
  - **逐目标判定**：每个目标前重读方块与当前手；当前手能收获且至少保留 2 点耐久时零换位，低耐久、无候选、目标漂移或采掘拒绝只跳过当前目标。未声明 harvestTool 的未知工具按通用 `Item.canHarvestBlock` 判定，不依赖模组白名单。GT 线缆 SPECIAL 与 INTERACT 不接入自动工具换位。
  - **规划边界**：普通 `CHAIN` 在规划启动时冻结当时的主手、背包工具与空手能力，无冻结能力可收获的节点断链；`AREA` 类按空间/结构宽进并逐个尝试。执行中工具损坏不改写已规划拓扑。
  - **收口边界**：自然完成、松键、取消、登出、重生、切维度与服务停止前都会先恢复借用工具或明确分类冲突，再清执行状态；打开非个人库存 GUI、切换热栏锚点或受保护槽出现未知布局时 fail closed。系统不会为第三方库存改写、合并或覆盖物品，也不会伪报恢复成功。
  - **预览**：只观察、不阻塞服务端执行，收敛可能晚于执行本身，客户端库存显示与预览允许在批内短暂滞后。
- `AREA_CUBOID_CLEAR`：切换到该子模式并松开连锁键后，左键命中方块选择 point1，右键命中方块选择 point2；两个动作都会取消原版破坏/交互。按住连锁键时左键恢复正常触发语义，触发方块可以位于选区外。
  - 服务端重验坐标、模式、按键与射线命中，只接受体积不超过当前 `chainMaxBlocks` 的同维度选区；客户端只渲染服务端确认结果，不乐观显示本地点击。
  - 两点齐全后常驻渲染选区 AABB 的 6 面与 12 边；执行轮次冻结当时 bounds，执行中重选只影响下一轮。
- **服务端配置命令**：权限等级 `4` 的 `/qzminer config list [prefix]`、`get <path>`、`set <path> <value...>`、`reload` 只允许显式 `general.*` scalar 白名单（STRING/NUMBER/BOOLEAN）；`parallelBudgetMode`、`parallelSliceBudgetMs` 等 CHOICE 键不在白名单内，只能通过 `config/qz_miner.yaml` 或配置界面修改。`set`/`reload` 成功后热发布，并在 `chainMaxBlocks` 下调时重裁在线玩家的已接受值与超限选区。
- `client.objectGroups`：每个客户端玩家自己的对象组列表。每行必须有唯一非空 `id` 和至少一个成员；成员可选择全部、单个或多个 metadata，也可直接使用完整 registry 语法，例如 `minecraft:log@0`、`minecraft:log@*`、`minecraft:log@[0,16,24902,65535,16777216,2147483647]`。单值与集合只接受十进制 `0..Integer.MAX_VALUE`；负数和超出 int 的文本拒绝。
  - **管理入口**：`members` 使用 Qz-UILib 的成员管理选择器，配置行常驻「已配置/无效/重复」摘要，原始列表默认折叠在「高级编辑原始规则」中。管理浮层受当前视口约束，打开时焦点限制在浮层内、关闭后恢复原界面焦点。每个成员拥有稳定 ID：编辑只替换目标成员，新选择追加为新成员；删除按稳定 ID 提交，提交失败（编码/事务拒绝）零写并显示错误。
  - **诊断与无损性**：重复 registry 会显示提示但不会自动合并。malformed 成员只显示通用错误说明，不泄露原始文本；高级 raw 仍无损保留并可用于修正。合法但当前未枚举的 selector 继续以 canonical 文本显示。
  - **候选边界**：Picker 枚举客户端 `Block.blockRegistry` 中全部合法方块，因此空气、火、传送门等技术块也会出现。正常方块只采用 `getSubBlocks` 实际暴露的非负 int 物品子类型，高 metadata 会去重排序显示；无 ItemBlock 的方块仍提供逻辑 meta 0，可选择 `@0` 或 `@*`，但不伪造物品身份并使用占位图标。Picker 不依赖世界或 NEI，不凭空枚举 `0..Integer.MAX_VALUE`，也不推断其它未暴露 metadata；对象组匹配只使用 registry + metadata，不使用 NBT、TileEntity 或矿辞推断。每个 selector 仍受 1024-byte 字符串边界，组数、成员数与 payload 上限不变。
- `INTERACT` 在 HUD 中显示为“范围交互”，滚轮顺序固定为“同类方块 → 液体源 → 全部作物 → 未成熟作物施肥”，默认仍为同类方块。四个子模式统一以宽泛右键为入口，既观察 `RIGHT_CLICK_BLOCK`，也在 `RIGHT_CLICK_AIR` 的物品动作生效前从当前射线解析语义目标；AIR event 的占位坐标不作为 seed。四模式的服务端规划与客户端预览继续使用可按 deadline 恢复的完整立方盒扫，范围边长为 `2 x radius + 1`，无需目标相邻；候选拒绝只跳过当前坐标。
  - **同类方块**：严格匹配触发时冻结的 block、完整 metadata 与方块实体纯值身份；对象组仍可作为旧扩展。
  - **液体源**：只匹配与触发 source 同种的当前静态可排液 source。服务端 BLOCK/AIR 入口与客户端预览都使用包含液体的共享射线；每目标重新读取当前非空手持物，经精确液体射线、Forge `RIGHT_CLICK_AIR` 与正常 Item 使用。系统不按桶、工业单元或未知物品类型预判接收能力，由物品自行决定是否处理；不直接 `drain`、修改液体块、搜索背包或构造容器。
  - **全部作物**：包含可靠识别的成熟和未成熟作物；对象组仍可扩展到当前非空、非空气、非液体方块。
  - **未成熟作物施肥**：只接受当前可靠确认的 `IMMATURE`，`MATURE/UNKNOWN` 均拒绝；使用每目标当前手持物走正常目标化方块右键，不调用兼容层施肥 API。
  - **执行边界**：规划接受不等于执行授权。服务端主线程在每目标调用前重验 live 身份，并继续守 `blockExists`、世界保护、编辑权限与 Forge event；当前手持在每个目标动态读取，单目标无动作、拒绝、返回 false、耗尽或异常不停止后续队列。同一动作若同时出现 BLOCK/AIR 观测，由既有连锁状态门收口迟到观测。真实模组与连续运行态的覆盖情况见文末「验证边界」。
- 对象组是现有模式的筛选扩展，不是独立滚轮模式。可扩展模式仍恰好为连锁基础/矿石/伐木、区域同类/矿石、交互基础/全部作物；液体源与未成熟作物施肥不取得对象组 bit。原模式匹配始终保留，对象组无命中时行为不变。保存、RELOAD 或连接建立后，客户端发送同一 `CommittedSnapshot` 中的 revision 与完整配置；HUD 的 `Confirmed` 只表示服务端已接受该请求。服务端按玩家隔离规则，并在任务启动时冻结扩展，运行中的 reload 不改变任务；客户端预览只使用服务端已确认规则，pending 时回退原模式。
- HUD 布局编辑：打开聊天输入框后，工具栏的「编辑 HUD」按钮进入 UILib 布局编辑子模式，拖动连锁状态 HUD 预览调整屏幕位置，缩放 `- / 1:1 / +` 只在编辑子模式提供。拖动、边界夹取、草稿/提交/取消与 Esc 优先级全部归 UILib 编辑宿主，Miner 只声明可编辑目标与预览内容。提交后布局由 UILib 持久化到配置目录下的 `qz_miner-hud-layout.txt`（编解码、schemaVersion 判定与损坏降级归 UILib），设计上不随退出游戏丢失。
- 并行执行预算（`general` 段，服务端权威）：`parallelBudgetMode` 默认 `deadline`（沿用 `tickBudgetMs` 共享 soft deadline），可选 `slice`；`parallelSliceBudgetMs` 默认 `4`，合法 `1..40`，仅 `slice` 档生效。
- `general.harvestExhaustionPerBlock`（`general` 段，服务端权威）：连锁（CHAIN）与爆破（AREA）经执行器破坏的每个方块的饥饿值消耗，按**实测增量覆盖**成该配置值（原版 exhaustion 单位），默认 `0.025`（等于原版每方块消耗，逐值等于改动前行为），合法 `-40..40`（`40` 等于原版 exhaustion 累加上限，`-40` 与之对称）。结算在破坏前后各读一次 exhaustion，追加「配置值 − 原版本次实际增量」，因此 `0` 表示完全不消耗、负值表示净回补（落到原版 `FoodStats.addExhaustion` 上是减少 exhaustion 累加池，该法只有 `40.0` 上界、不夹取下界），且与方块是否覆写 `harvestBlock` 无关。已知边界：exhaustion 累加封顶 `40`，基线接近上限时原版消耗与追加都会被截断，净消耗可以小于配置值（减少方向不受影响）；池的兑付由原版 `FoodStats.onUpdate` 限制为每 tick 至多 4 点，故「配置值 × 每 tick 破坏数 > 4」时饥饿条的实际流失速率受该上限约束（配置值仍如实记入池）。不覆盖玩家手动挖掉的起点方块与原版单方块挖掘（仍按原版 0.025）；`INTERACT` 右键与 GT 线缆 `SPECIAL` 不结算；创造模式与假玩家不消耗。服务端主线程在破坏成功时结算，配置页保存或 `/qzminer config reload` 后下一次破坏即生效。

### 连锁预览观感档位

`client.clientPreview*` 全部为本机客户端配置、不参与网络同步。各键的效果、默认值与限制如下；其中 `clientPreviewVersionedInputs`、`clientPreviewPresentationOverlay`、`clientPreviewAnimationPhase`、`clientPreviewFadeMode` 当前无消费者，改动只触发一次配置重读、观感与行为不变。

- `clientPreviewRenderBackend`：预览渲染后端，默认 `auto`（能力探测通过用 shader，否则 legacy）；可选 `auto`/`shader`/`legacy`。需要固定管线时显式设为 `legacy`；显式 `shader` 探测失败仍回退 legacy 并记录一次诊断，不在每帧重试。
- `clientPreviewBarThickness`：预览条柱粗细，默认 `0.045`，合法 `0.005..0.2`。
- `clientPreviewColorSource`：颜色来源，默认 `builtin`（内置六色 + 距离 α）；`config` 时使用下面六个 RGB 键做语义配色。
- `clientPreviewColorChain` / `clientPreviewColorArea` / `clientPreviewColorInteract`：CHAIN / AREA / INTERACT 三个大模式默认子模式本地预测的颜色，默认 `0x40E6FF` / `0xE8503C` / `0x58E07A`。
- `clientPreviewColorSecondary` / `clientPreviewColorRemote` / `clientPreviewColorTruncated`：扩展子模式本地预测 / 远端预测 / 截断目标的颜色，默认 `0xB08CFF` / `0x8FA9D0` / `0xF8C858`。六键合法范围均 `0x000000..0xFFFFFF`。
- `clientPreviewDepthMode`：深度通道，默认 `xray`（恒可见）；可选 `occlude`（参与深度测试）/ `outline`（主体遮挡 + 置顶轮廓）。只改绘制通道，不改拓扑。
- `clientPreviewAnimation`：预览动画，默认 `off`；可选 `flow`（整体流动）/ `wave`（按出现顺序逐波生长）。档位为 `flow`/`wave` 且时长 `> 0` 时，预览出现与结束带淡入淡出（两个后端都生效）；逐波生长按每条条柱的出现序号逐顶点推进，只有着色器后端具备（legacy 固定管线整体绘制）。
- `clientPreviewAnimationDurationMs`：单代动画时长（毫秒），默认 `120`，合法 `0..2000`（`0` 瞬时完成）；仅 `flow`/`wave` 档生效。
- `clientPreviewAnimationPhase`：相位来源，默认 `order`（出现序号，也是 `wave` 生长实际使用的相位）；可选 `hash`（坐标 + 代次稳定哈希）。渲染路径不读该键：`hash` 与 `order` 观感相同，只保留键位。
- `clientPreviewFadeMode`：距离淡出刷新档位，默认 `timer`（1 Hz 兜底）；可选 `signal`（相机位移达阈值或兜底时间到期时提升刷新）/ `gpu`（着色器逐帧计算，当前与 `timer` 行为相同）。
- `clientPreviewFadeRefreshDistance`：`signal` 档相机位移阈值（格），默认 `0.5`，合法 `0..8`；达到阈值才提升刷新，`0` 等价每帧刷新；`timer`/`gpu` 档不读此键。
- `clientPreviewFadeFallbackMs`：`signal` 档无位移时的兜底刷新间隔（毫秒），默认 `250`，合法 `50..5000`；`timer`/`gpu` 档不读此键。
- `clientPreviewMinScreenWidthPx`：条柱在屏幕上的最小宽度（像素），默认 `0`（关闭钳制），合法 `0..8`；`1..8` 时着色器后端沿面法线单向外扩，把条柱投影宽抬到该像素值，消除远距亚像素闪烁（legacy 固定管线无此能力）。外扩与描边共用同一世界上界 `max(0, 0.5 − 厚度)`。
- `clientPreviewOutlineWidthPx`：`outline` 深度档描边壳沿面方向的外扩宽度（物理像素），默认 `1.5`，合法 `0..8`；`0` 关闭描边，`>0` 时着色器后端在主体之外绘制描边壳（legacy 固定管线无此能力）。与外扩世界上界同口径。
- `clientPreviewFaceShading`：是否按面朝向烘焙明暗（顶面最亮、底面最暗），默认 `true`（用户 2026-09-14 裁定默认档需要体积感）。两个后端共用同一张亮度表，面朝向是视角无关量，转动相机不改变明暗。默认 `true` 的真机观感确认尚未完成（真机观感属 **INCOMPLETE**）。
- `clientPreviewTruncationSignal`：预览因目标上限或远端上限被截断时，是否在 HUD 显示截断行（截断数量与原因），默认 `false`；打开后随预览 header 每 tick 更新。
- `clientPreviewMaxTargetsHardCap`：预览目标数量硬顶，默认 `4096`，合法 `1..4096`；实际预览数量取该值、`clientPreviewMaxTargets` 与服务端 `chainMaxBlocks` 的较小值。
- `clientPreviewLod`：极端规模 LOD，默认 `off`；`auto` 才启用距离合并与 alpha 剔除。
- `clientPreviewLodMinAlpha`：LOD alpha 剔除阈值，默认 `0.05`，合法 `0..1`；低于阈值的条柱不参与构建。
- `clientPreviewSuppressVanillaHighlight`：预览激活且瞄准同一目标时取消原版方块高亮，避免双重指示，默认 `false`。
- `clientPreviewOrderMinBrightness`：连锁序亮度权重下限，默认 `0.55`，合法 `0..1`；按条柱的出现序号把**颜色亮度**从 `1.0` 线性降到该值，不改动透明度，仅着色器后端生效；`1.0` 关闭该能力。
- `clientPreviewInteriorDim`：内部结构亮度系数，默认 `0.65`，合法 `0..1`；把内部格线（junction 补块与共享顶点）的颜色亮度乘上该系数，外轮廓保持原亮度，不改动透明度，描边 pass 不参与，仅着色器后端生效；`1.0` 关闭该能力。
- `clientPreviewVersionedInputs`：是否使用版本化预览输入快照（含目标去抖），默认 `false`（逐字段比较）。当前无消费者：开关只触发一次配置重读，观感与行为不变。
- `clientPreviewPresentationOverlay`：是否启用统一表现投影覆盖层（HUD 截断 / 进度 / 远端失败的单一事实源），默认 `false`。当前无消费者：开启不改变 HUD 与预览行为。
- `clientPreviewExecutionProgress`：是否在 HUD 展示执行进度（已执行 / 匹配），默认 `false`；进度由**客户端世界采样**得到（零协议改动、可回退），开启后随投影 header 每 tick 更新。关闭时**不采样、不计数**。采样口径如实标注：只统计「目标位置所在区块已加载且方块已消失为空气」的目标，因此破坏 / 采掘类模式准确，交互类与替换类目标不产生空气态、不计入。
- `clientPreviewBackendDiagnostics`：是否在 HUD 显示预览后端诊断行（当前生效后端与一次性回退原因），默认 `false`；数据来自渲染线程发布的后端状态快照，关闭时不触碰该快照。
- `clientPreviewRemoteTimeoutMs`：远端预览请求超时（毫秒），默认 `5000`，合法 `250..60000`；超时丢弃陈旧响应并清预览激活。
- **重复目标语义**：`clientPreviewMaxTargetsHardCap` 的 4096 配额按**唯一坐标**占用——同一坐标重复进入预览只保留一次，不占配额、不进入拓扑，语义类别取首次出现（去重先于配额）；HUD「预览已匹配」与内部计数仍按**原始读取数**。两者口径不同是刻意的：网格侧关注几何唯一性与内存上界，HUD 侧关注上游实际送来的目标数。`lod=auto` 的远处散点剔除同样不占配额。

## 联机版本边界

- 兼容范围是一个**版本区间** `[X.Y.0-alpha, X.(Y+1).0-alpha)`（`X.Y` = 当前构建版本的 `major.minor`）：由构建从制品版本派生、写进 `@Mod.acceptableRemoteVersions`，判定复用 FML 的 Maven 版本序语义。区间内的 patch、prerelease 与 build qualifier 一律互通——stable、prerelease、branch/dirty dev 都在同一区间内。
- 区间外的版本一律拒绝，含相邻族的最小形态 `X.(Y+1).0-alpha`。
- 判定沿用 Maven 版本语义，因此**不做额外的严格语法校验**：`X.Y` 等价于 `X.Y.0` 而被接受，前导零、溢出、Unicode 等形态按该语义解析后参与比较，不再单独拒绝。
- 远端版本表完全缺少精确 `qz_miner` key 时 FML 双向放行；若 key 存在，则双方版本都必须落在各自声明的区间内。missing 放行不是无 Mod 运行保证，SimpleNetworkWrapper channel 或业务主动发送仍可能失败。
- 当前区间已冻结 17 个 packet discriminator/Side、现有 wire/protocol/ordinal/code/mask 与 20-path schema；后续不兼容变化必须升新 minor——升 minor 即自动换区间。真实 mixed-patch/missing client/dedicated 仍为 **INCOMPLETE**，不因自动化通过而升级证据等级。
- GTNH 基线：当前为**单基线构建**，唯一真源是 `dependencies.gradle` 的 `elytraModpackVersion { setGtnhVersion("2.9.0-beta-3") }`（对齐 Qz-UILib），CI 与发布不使用基线矩阵。同一份 jar 的运行期兼容范围含 `2.8.0` / `2.8.4` / `2.9.0-beta-2` / `2.9.0-beta-3`，依据是源码对 GTNH 侧组件零静态链接 + 跨基线编译实证 + 宿主能力核验（适配器可用性、反射档案成员面、普通矿时运注入点形状）；这是编译期与静态证据，真机运行态未验证，不因核验通过升级证据等级。机制取舍与上游依赖缺陷依据见 [反馈层/errors/](../反馈层/errors/ERROR-20260911-gtnh-single-baseline-matrix-retirement.md) 与 [ERROR-20260817](../反馈层/errors/ERROR-20260817-gtnh-284-baseline-upstream-pom-defect.md)。

## 验证边界

- 服务端换位与库存发布路径已有自动化证据；真实 client/dedicated 运行态、大批次连续接替、第三方库存冲突，以及交互类目标的真实模组覆盖（vanilla bucket / GT 或 IC2 单元 / 第三方 Item / GT CropCard / EFR / 保护插件）仍为 **INCOMPLETE**。
- HUD 布局持久化已实现，跨重启实机验证尚未完成。
- 连锁/爆破的饥饿值消耗覆盖（`general.harvestExhaustionPerBlock`）已通过纯 JVM 契约测试与浮点网格验证（未触顶时净消耗与配置值偏差 ≤ 1e-5，全枚举实测最坏约 3.8e-6）；真机手感（饥饿条变化、exhaustion 接近 `40` 上限时的截断表现、与第三方覆写 `harvestBlock` 方块的组合）尚未实机验证。

## 维护规则

- 内容必须与实现一致，不写未经验证的能力。
- 公开接口、配置项和示例发生变化时同步更新。
