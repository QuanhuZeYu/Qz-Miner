# TiC 工具在 null harvestTool 目标上的栈级假阴性

## 错误现象

TiC 镐对 Smeltery metadata 2 的效率与耐久均满足自动工具门，玩家实际破坏也可正常掉落，但共享 `ToolHarvestEligibility` 只采用 `ForgeHooks.canToolHarvestBlock`，得到 false，导致客户端候选与冻结规划共同拒绝该工具。

## 触发场景

- 目标材质要求工具，但 `Block.getHarvestTool(metadata)` 返回 null。
- 当前 Item 的运行时父类属于 TiC `HarvestTool` 族，并由旧式 `Item.canHarvestBlock` 实现材料级采掘判断。
- Qz-Miner 在候选或规划阶段把 Forge 栈级结论误当成完整工具语义。

## 根本原因

Forge 的工具等级表依赖目标声明 harvestTool；目标未声明时，栈级查询无法表达 TiC 工具自己的旧式材料判断。共享层若直接对所有 Item 调用旧式 API，又会把一个模组的兼容规则错误扩散为通用 fallback，并可能绕过显式等级权威。

## 修复方案

新增四态 `ToolHarvestCompatAdapter` registry。目标有显式 harvestTool 时仍只走 Forge；null harvestTool 且材质要求工具时，唯一 TiC adapter 只遍历已加载 Item 父类并按完整类名识别 `HarvestTool` 族，再通过稳定 Minecraft `Item.canHarvestBlock` 虚调用取得结果。只有 `ALLOW` 放行，其余状态及异常全部 fail-closed。

## 预防措施

- 可选工具兼容保持单模组、单 adapter、单一完整类名，不增加直接依赖、import、可选类加载或成员反射。
- 回归必须覆盖 TiC 允许/拒绝、非 TiC 旧式 true、相似类名、显式等级零 fallback、低效率/低耐久/破损及类形、调用、registry 异常。
- 自动化通过只证明共享判定和隔离边界；真实 Smeltery 掉落仍需用户实机验证，完成前版本发布保持阻断。
