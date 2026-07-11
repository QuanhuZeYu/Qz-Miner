# 使用文档

本目录用于记录面向使用者或接入方的公开说明。

## 当前状态

- 当前仓库的对外使用说明暂以根目录 `README.md` 为主。
- 若后续新增配置说明、接入方式或示例流程，再补充到本目录。

## 配置项

- `general.parallelTickServerWorkBudgetUnits`：服务端并行 Tick 任务单个分片的工作预算单位，默认 `64`。调大可让服务端规划单片推进更多工作，但可能增加并行窗口等待时间；调小更平滑但规划完成更慢。
- `client.parallelTickClientWorkBudgetUnits`：客户端并行 Tick 任务单个分片的工作预算单位，默认 `640`。调大可让客户端预览更快完成，但可能增加单片耗时；调小更平滑但预览收敛更慢。
- `client.objectGroups`：每个客户端玩家自己的对象组列表。每行必须有唯一非空 `id` 和至少一个成员；成员使用完整 registry 语法，例如 `minecraft:log@0`、`minecraft:log@*`、`minecraft:log@[0,4,8,12]`。对象组模式只按 registry + metadata 匹配，不使用 NBT、TileEntity 或矿辞推断。
- 对象组模式使用现有连锁子模式滚轮。保存、RELOAD 或连接建立后，客户端发送完整对象组配置；HUD 的 `Confirmed` 才表示服务端已接受该 revision。服务端对象组规则按玩家隔离，进行中的连锁继续使用启动时冻结的组快照。

## 维护规则

- 内容必须与实现一致，不写未经验证的能力。
- 公开接口、配置项和示例发生变化时同步更新。
