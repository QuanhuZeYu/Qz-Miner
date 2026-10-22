# 配置合同

## 权威与提交

- `config/qz_miner.yaml` 是持久化权威入口；保留已发布的 `general.*` / `client.*` 路径、合法值含义及 canonical 文本。默认值和逐键取值声明集中在生产 schema，不在文档复制清单。
- 配置用 UILib 5.0 的类型 schema 与不可变文档表达；运行态只消费完整的已提交快照，派生字段不反向成为另一权威。缺项按 schema 默认，显式空值不视为缺项；合法旧整数表示（含整值小数）与对象组须保持有效值。
- 保存先完成类型、范围、对象与跨键校验，再原子落盘，成功后才发布新权威、revision 与运行快照；校验或写盘失败不发布。GUI 文件 I/O 在后台执行，排队任务轮到时回所属主线程重新确认页面管理资格，通过后冻结候选进入原事务，worker 不读取游戏权限。草稿记录打开时的已提交文档基线；若其它入口已经提交，旧草稿拒绝覆盖并保留输入，用户须明确放弃后重新载入。reload 先解析并验证整个文件，再一次发布，不部分生效或静默夹取非法值。
- 未知字段也属非法输入，reload 拒绝；启动遇非法文档须先成功备份原件再生成默认，备份或落盘失败不发布。只有明确支持的旧预算键可迁移，不以忽略未知键维持表面兼容。
- `general.*` 服务端权威；`client.*` 通常本机有效，但 `client.autoToolSwapEnabled` 与 `client.autoToolPrioritySelectors` 始终取服务器自己的已提交值，远端客户端不能覆盖。远端 GUI 对这些服务器管理项只读，未同步字段明确显示当前值未知，不以本机 YAML 的值充当远端事实。活动任务冻结启动时策略与对象组，reload 影响后续任务；即时限制下调时重裁在线已接受值与超限选区。
- `client.hudEnabled/hudAnchor/hudOffsetX/hudOffsetY/hudScale` 只控制本机 HUD，默认值与合法域以生产 schema 为唯一声明；位置使用屏幕锚点、逻辑偏移和内容缩放持久化，布局编辑和普通配置页共用原提交入口。
- 成功保存、reload 与连接建立后的同步使用同一提交中的 revision 和完整配置；服务端按玩家校验隔离，客户端使用服务端已确认规则，pending 时回到原模式。同步与热发布回所属主线程，不混用不同提交的数据。

## 命令

权限等级 `4` 的 `/qzminer config list [prefix]`、`get <path>`、`set <path> <value...>`、`reload` 共用配置提交入口。list/get/set 只开放显式 `general.*` scalar 白名单（STRING/NUMBER/BOOLEAN），不因前缀相同自动开放 CHOICE、列表、对象或 `client.*`；reload 校验整个 YAML。白名单之外通过 YAML 管理。

## 稳定文本与对象组

- `client.objectGroups` 保持对象列表，每组有唯一非空 `id`、`members` 与缺省为空列表的 `modes`；成员是 `LIST<STRING>` 的 `namespace:path@meta` canonical 文本，只支持 `@*`、`@<meta>`、`@[集合]`。metadata 是十进制 `0..Integer.MAX_VALUE`，集合非空、不重复并按数值排序；合法但未注册的名字按文本保留。匹配只看 registry + metadata，不推断 NBT、TileEntity 或矿辞。
- 对象组最多 64 组、每组 1..128 成员、总计最多 2048 成员；ID 最长 64 字符、registry 最长 256 字符、canonical 最长 1024 UTF-8 bytes，wire payload 最多 `32 * 1024` bytes。共享模式且 selector 重叠的组拒绝发布。
- 扩展仅适用 `chain_base/chain_ore/chain_logging/area_same_block/area_ore/interact_base/interact_crop`；原匹配始终保留，无组命中时行为不变，不新增滚轮模式。
- 工具优先列表 `client.autoToolPrioritySelectors` 另支持 `namespace:path@*`、`namespace:path@<meta>` 与 `ore:<name>`，不支持集合。它表达有序优先级而非白名单，未命中候选仍参与；对象组不接受 `ore:`。

## 验收

用真实 YAML 覆盖旧数值、对象组与 canonical 往返；非法类型、跨键冲突、输入超限及写盘失败均不改变已发布快照。命令拒绝权限不足和白名单外路径；reload 全量验证，远端不能改服务端工具策略，同一次同步不得拼接不同 revision。
