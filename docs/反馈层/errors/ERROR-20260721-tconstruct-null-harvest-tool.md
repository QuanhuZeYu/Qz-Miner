# TiC 工具在 null harvestTool 目标上的栈级假阴性

## 错误现象

TiC 镐对 Smeltery metadata 2 可正常破坏并掉落，但目标没有声明 Forge harvestTool。共享 `ToolHarvestEligibility` 曾只采用 Forge 等级路径，得到 false，导致客户端候选与 CHAIN 冻结规划共同拒绝该工具。

## 触发场景

- 目标材质要求工具，但 `Block.getHarvestTool(metadata)` 返回 null。
- 当前 Item 通过旧式 `Item.canHarvestBlock` 实现材料级采掘判断；TiC 只是最早暴露问题的实例，未知模组工具同样可能采用该稳定虚调用。
- Qz-Miner 在候选或规划阶段把 Forge 栈级结论误当成完整工具语义。

## 根本原因

Forge 的工具等级表依赖目标声明 harvestTool；目标未声明时，栈级查询无法表达 Item 自己的旧式材料判断。初版修复把问题归因于 TiC 类族并建立白名单，既漏掉其它合法实现，也把稳定 Minecraft Item API 误当成模组私有兼容规则。

## 修复方案

当前方案已取代 TiC 白名单：目标有显式 harvestTool 时仍只走 Forge，禁止 fallback 绕过等级；null harvestTool 且材质要求工具时，对任意 Item 直接调用 Minecraft 稳定 `Item.canHarvestBlock(Block, ItemStack)`。调用异常 fail-closed。候选与 CHAIN 冻结能力共用该入口，AREA planner 不按工具过滤。

效率不参与收获资格硬门。`getDigSpeed` 的低值、`RuntimeException` 或 `LinkageError` 只记录为 effective=false，不能否决 canHarvest=true 且耐久足够的候选，也不能把完整库存快照标成 untrusted。

## 预防措施

- 不为 TiC 或其它模组维护工具类名白名单，也不为资格识别增加直接依赖、可选类加载、成员解析或反射扫描。
- 回归必须直接断言未知 Item 的稳定虚调用，覆盖允许/拒绝/异常、显式等级零 fallback、低效率、效率采样异常、低耐久与破损；源码注释字符串不得充当调用证据。
- CHAIN 冻结能力与客户端候选共用资格入口，但快照不替代执行期服务端实时权威；自动化也不能替代真实 Smeltery 掉落验证。
