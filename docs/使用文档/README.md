# 使用文档

本目录用于记录面向使用者或接入方的公开说明。

## 当前状态

- 当前仓库的对外使用说明暂以根目录 `README.md` 为主。
- 若后续新增配置说明、接入方式或示例流程，再补充到本目录。

## 配置项

- `general.parallelTickServerWorkBudgetUnits`：服务端并行 Tick 任务单个分片的工作预算单位，默认 `640`。调大可让服务端规划单片推进更多工作，但可能增加并行窗口等待时间；调小更平滑但规划完成更慢。
- `client.parallelTickClientWorkBudgetUnits`：客户端并行 Tick 任务单个分片的工作预算单位，默认 `640`。调大可让客户端预览更快完成，但可能增加单片耗时；调小更平滑但预览收敛更慢。
- 服务端规划与客户端预览对原版 `Blocks.air` 使用固定 1024:1 候选计费：同一次规划累计确认 1024 个空气坐标才扣 1 个正 work budget，余数跨分片与 tick 保留，规划自然结束时不补收。每个空气坐标仍先检查取消、窗口与时间边界；shell/slice 切换、frontier、重复坐标、matcher、consumer，以及 null、液体、基岩、业务拒绝和模组 air-like 方块仍按正常预算处理。因此该折扣提高大空区扫描吞吐，但不代表整次遍历总成本只有 `air/1024`。
- `client.tunnelDirectionSource`：每位玩家的 `AREA_TUNNEL` 方向偏好，默认 `look_direction`。`look_direction` 取玩家视线中绝对值最大的轴；`hit_face` 取左键命中方块面的反向，也就是从被点击表面朝方块内部开掘。六个视线轴与六个命中面均受支持。
  - 服务端只使用已经整包接受并回执的偏好；客户端预览也只使用服务端 ACK 后的 accepted 值，保存后等待 ACK 期间不会乐观切换方向。
  - `hit_face` 的左键命中只与随后同维度、同坐标的破坏事件匹配一次；缺失、非法或失配时回退该次破坏时冻结的视线方向。松键、切换模式/子模式及玩家生命周期清理都会使未消费命中失效。
  - 新旧端混连时固定降级为 `look_direction`：旧 C2S 8 字节与旧 S2C 12 字节仍可读取；新 C2S 为 16 字节、新 S2C 为 20 字节，并保留旧字段前缀。运行态的新新、旧新、新旧、旧旧四象限仍待实机验证。
- `client.autoToolSwapEnabled`：是否启用自动工具换位，默认 `true`。路径名为兼容既有 schema 保持不变，但新普通 `CHAIN/AREA` 热路读取的是**服务器自己的**已提交 YAML 值；远程客户端不会把本地 enable 上传给服务器。活动连锁 session 冻结创建时策略，服务器 reload 只影响下一 session。创造模式不换位。
- `client.autoToolTakeoverEnabled`：默认 `true`，只控制新客户端连接旧 5.1 server 时是否响应旧式 `AutoToolSwapTakeoverRequest` fallback；新 server 的本地批量选择不读取该开关，也不发送接替请求。
- `client.autoToolPrioritySelectors`：自动工具候选的有序优先级列表，不是白名单。支持 `<namespace:path>@*`、`<namespace:path>@<meta>` 与 `ore:<name>`；先按最早命中的规则排序，同优先级及未命中的合格候选按个人库存槽位 `0..35` 排序。新 server 使用自己的列表；客户端本地列表只供旧 server fallback，双方不会互相覆盖。
  - **运行节奏**：普通 `CHAIN/AREA` 每个服务端 tick 建立本地 batch，并在每个目标前实时重读 block/meta、当前手和个人库存 `0..35`。当前手能收获且至少保留 2 点耐久时零换位；否则服务器可在同一 tick 完成首次二槽交换及后续三槽轮转，并继续消费到既有 `maxBreakPerTick` poll budget，不等待客户端网络往返。无候选、目标/候选漂移、低耐久或 mutation 后实时采掘拒绝只跳当前目标；后来补入库存仍可影响尚未消费目标。未声明 harvestTool 的未知工具使用通用 `Item.canHarvestBlock`，不依赖 TiC/模组白名单。GT 线缆 SPECIAL 与 INTERACT 不接入。
  - **规划边界**：普通 `CHAIN` 在规划启动时冻结当时的主手、背包全部工具和空手能力；无冻结能力可收获的节点断链。`AREA` 爆破、隧道、同块区域、矿石区域和区段清理仍按空间/结构宽进并逐个交服务端主线程尝试；confirmed/预览表示“待尝试目标”，不是全部可破坏承诺。执行中工具损坏不改写已规划拓扑。
  - **库存与同步**：客户端按键激活 round 后直接发送零库存 mutation 的 `FREEZE`，不再做初始预挖 SWAP。一个服务端 batch 内可多次换位，但每玩家每 server tick 最多发布一次完整 window 0；普通批次在 tick END 可见，terminal 批次先恢复最终布局再发布。同步失败只在下一 tick 重发完整库存，不回滚或重放换位，因此客户端库存显示与预览允许在批内短暂滞后。
  - **收口边界**：自然完成、松键、取消、STOP、watchdog、登出、重生、切维度、clone 与服务停止都先由服务端恢复借用工具或明确分类冲突，再清执行状态。打开非个人库存 GUI、切换热栏锚点或受保护槽出现未知第三布局会 fail closed；系统不会为第三方库存改写搬运、合并或覆盖物品，也不会伪报恢复成功。完整库存连续发送失败只保留无写权的可见性重试，不无限阻塞连锁 cleanup。
  - **混合 patch**：旧 client 连接新 server 时，首个旧 `SWAP` 会以 `REJECTED + FROZEN` 零库存收口，后续由服务器本地选择；新 client 连接旧 server 时仍响应真实旧 `AutoToolSwapTakeoverRequest`，但没有初始预挖 SWAP 且仍有逐目标 RTT。四象限都必须属于合法 5.1 family 并遵守同一 v4 wire；这是功能降级，不是 v3/v4 协商。
  - **预览与验收状态**：新热路的预览是 observer，不阻塞服务端执行，可能到后续 phase/采样或最终 vanilla inventory publication 才收敛；旧 server fallback 仍沿既有布局可见后刷新。服务端本地 ledger、poll budget、publication gate 与 lifecycle 已有自动化证据，但真实 client/dedicated、大批次连续接替、四象限 mixed patch、HUD/预览和第三方库存冲突仍为 **INCOMPLETE**。
- `client.objectGroups`：每个客户端玩家自己的对象组列表。每行必须有唯一非空 `id` 和至少一个成员；成员可选择全部、单个或多个 metadata，也可直接使用完整 registry 语法，例如 `minecraft:log@0`、`minecraft:log@*`、`minecraft:log@[0,16,24902,65535,16777216,2147483647]`。单值与集合只接受十进制 `0..Integer.MAX_VALUE`；负数和超出 int 的文本拒绝。
  - **管理入口**：`members` 使用 Qz-UILib 4.6.0 的成员管理选择器。配置行常驻“已配置/无效/重复”摘要与管理入口，原始列表默认折叠在“高级编辑原始规则”中。
  - **portal 布局**：管理 portal 的宽、高受当前视口约束；搜索固定在顶部，当前规则与搜索结果按 3:5 目标动态分区。overlay 打开时焦点约束在 portal 内，关闭后恢复原界面焦点。
  - **编辑与删除**：每个成员拥有稳定 ID；编辑只替换目标成员，新选择追加为新成员。删除可在管理 portal 内发起，并须经过“删除”→“确认删除”两步，不再要求只去 raw 列表删除。
  - **诊断与无损性**：重复 registry 会显示提示但不会自动合并。malformed 成员在常规配置行和 portal 中只显示通用错误说明，不泄露原始文本；高级 raw 仍无损保留并可用于修正。合法但当前未枚举的 selector 继续以 canonical 文本显示。
  - **候选边界**：Picker 枚举客户端 `Block.blockRegistry` 中全部合法方块，因此空气、火、传送门等技术块也会出现。正常方块只采用 `getSubBlocks` 实际暴露的非负 int 物品子类型，高 metadata 会去重排序显示；无 ItemBlock 的方块仍提供逻辑 meta 0，可选择 `@0` 或 `@*`，但不伪造物品身份并使用占位图标。Picker 不依赖世界或 NEI，不会为了完整值域凭空枚举 `0..Integer.MAX_VALUE`，也不推断其它未暴露 metadata；对象组匹配只使用 registry + metadata，不使用 NBT、TileEntity 或矿辞推断。每个 selector 仍受 1024-byte 字符串边界，组数、成员数与 payload 上限不变。
- `INTERACT` 在 HUD 中显示为“范围交互”，滚轮顺序固定为“同类方块 → 液体源 → 全部作物 → 未成熟作物施肥”，默认仍为同类方块。四个子模式统一以宽泛右键为入口，既观察 `RIGHT_CLICK_BLOCK`，也在 `RIGHT_CLICK_AIR` 的物品动作生效前从当前射线解析语义目标；AIR event 的占位坐标不作为 seed。四模式的服务端规划与客户端预览继续使用预算化完整立方盒扫，范围边长为 `2 x radius + 1`，无需目标相邻；候选拒绝只跳过当前坐标。
  - **同类方块**：严格匹配触发时冻结的 block、完整 metadata 与方块实体纯值身份；对象组 X 仍可作为旧扩展。
  - **液体源**：只匹配与触发 source 同种的当前静态可排液 source。服务端 BLOCK/AIR 入口与客户端预览都使用包含液体的共享射线；每目标重新读取当前非空手持物，经精确液体射线、Forge `RIGHT_CLICK_AIR` 与正常 Item 使用。系统不按桶、工业单元或未知物品类型预判接收能力，由物品自行决定是否处理；不直接 `drain`、修改液体块、搜索背包或构造容器。
  - **全部作物**：包含可靠识别的成熟和未成熟作物；对象组 X 仍可扩展到当前非空、非空气、非液体方块。
  - **未成熟作物施肥**：只接受当前可靠确认的 `IMMATURE`，`MATURE/UNKNOWN` 均拒绝；使用每目标当前手持物走正常目标化方块右键，不调用兼容层施肥 API。
  - **执行边界**：规划接受不等于执行授权。服务端主线程在每目标调用前重验 live 身份，并继续守 `blockExists`、世界保护、编辑权限与 Forge event；当前手持在每个目标动态读取，单目标无动作、拒绝、返回 false、耗尽或异常不停止后续队列。同一动作若同时出现 BLOCK/AIR 观测，由既有连锁状态门收口迟到观测，不额外建立复杂事务。真实 vanilla bucket / GT 或 IC2 单元 / 第三方 Item / GT CropCard / EFR / 保护插件、client/dedicated 与连续四模式运行态仍为 **INCOMPLETE**。
- 对象组是现有模式的筛选扩展，不是独立滚轮模式。可扩展模式仍恰好为连锁基础/矿石/伐木、区域同类/矿石、交互基础/全部作物；液体源与未成熟作物施肥不取得对象组 bit。原模式匹配始终保留，对象组无命中时行为不变。保存、RELOAD 或连接建立后，客户端发送同一 `CommittedSnapshot` 中的 revision 与完整配置；HUD 的 `Confirmed` 只表示服务端已接受该请求。服务端按玩家隔离规则，并在任务启动时冻结扩展，运行中的 reload 不改变任务；客户端预览只使用服务端已确认规则，pending 时回退原模式。
- 游戏内无界面打开时，按住连锁键滚轮切换子模式；同时按住游戏设置中的潜行键则切换主模式。该组合键会独占滚轮，不改变快捷栏选中槽；未按连锁键或打开界面时保留原版滚轮行为。

## 5.1 联机版本边界

- 连接双方都安装 Qz-Miner 时，完整合法的 `5.1.x[-prerelease][+build]` 版本忽略 patch 与
  qualifier 互通；stable、prerelease、branch/dirty dev 都属于同一 family。
- `5.0.x`、`5.10.x`、缺段、前导零、overflow、空 qualifier、Unicode 或前后垃圾版本均拒绝。
- 远端版本表完全缺少精确 `qz_miner` key 时 Forge checker 双向放行；若 key 存在，则本地和远端
  都必须是合法 5.1 family。missing 放行不是无 Mod 运行保证，SimpleNetworkWrapper channel 或业务
  主动发送仍可能失败。
- 5.1 family 已冻结 16 个 packet discriminator/Side、现有 wire/protocol/ordinal/code/mask 与
  24-path schema；后续不兼容变化必须升新 minor。真实 mixed/missing client/dedicated 仍为
  **INCOMPLETE**，不因自动化通过而升级证据等级。

## 维护规则

- 内容必须与实现一致，不写未经验证的能力。
- 公开接口、配置项和示例发生变化时同步更新。
