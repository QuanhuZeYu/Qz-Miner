# 使用文档

本目录用于记录面向使用者或接入方的公开说明。

## 当前状态

- 当前仓库的对外使用说明暂以根目录 `README.md` 为主。
- 若后续新增配置说明、接入方式或示例流程，再补充到本目录。

## 配置项

- `general.parallelTickServerWorkBudgetUnits`：服务端并行 Tick 任务单个分片的工作预算单位，默认 `64`。调大可让服务端规划单片推进更多工作，但可能增加并行窗口等待时间；调小更平滑但规划完成更慢。
- `client.parallelTickClientWorkBudgetUnits`：客户端并行 Tick 任务单个分片的工作预算单位，默认 `640`。调大可让客户端预览更快完成，但可能增加单片耗时；调小更平滑但预览收敛更慢。
- `client.objectGroups`：每个客户端玩家自己的对象组列表。配置页以中文展示已配置规则、搜索、状态选择、结果与错误反馈；每行必须有唯一非空 `id` 和至少一个成员。成员可通过方块搜索 Picker 选择全部、单个或多个 metadata，也可继续使用完整 registry 语法，例如 `minecraft:log@0`、`minecraft:log@*`、`minecraft:log@[0,4,8,12]`。再次编辑同一 registry 会用本次选择替换并归一为唯一规则，不会与旧状态做 OR；其它 registry、无法解析的 raw 和非字符串值保持原位。Picker 枚举客户端 `Block.blockRegistry` 中全部合法方块，因此空气、火、传送门等技术块也会作为可搜索候选出现。正常方块只采用 `getSubBlocks` 实际暴露且处于 0..15 的物品子类型；无 ItemBlock 的方块仍提供逻辑 meta 0，可选择指定状态 `@0` 或全部状态 `@*`，但不伪造物品身份并使用占位图标。Picker 不依赖世界或 NEI，也不推断其它未暴露 metadata。对象组匹配仍只使用 registry + metadata，不使用 NBT、TileEntity 或矿辞推断。
- 对象组是现有模式的筛选扩展，不是独立滚轮模式。可扩展模式为连锁基础/矿石/伐木、区域同类/矿石、交互基础/作物；原模式匹配始终保留，对象组无命中时行为不变。保存、RELOAD 或连接建立后，客户端发送同一 `CommittedSnapshot` 中的 revision 与完整配置；HUD 的 `Confirmed` 只表示服务端已接受该请求。服务端按玩家隔离规则，并在任务启动时冻结扩展，运行中的 reload 不改变任务；客户端预览只使用服务端已确认规则，pending 时回退原模式。

## 维护规则

- 内容必须与实现一致，不写未经验证的能力。
- 公开接口、配置项和示例发生变化时同步更新。
