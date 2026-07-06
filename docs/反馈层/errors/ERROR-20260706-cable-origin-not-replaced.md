# 错误记录：GT 线缆连锁替换 origin 漏替换

## 错误现象

- 用户实机回归 `fix/cable-atomic-replace-binding`（GT 线缆 NPE 修复 commit `d325b71`）后反馈：除被点击那一根外，其他相邻线缆正常替换，但**被点击的 origin 线缆仍留在世界没被替换**。
- 无崩溃、无异常日志——纯逻辑漏处理，静默失败。

## 触发场景

- GT 线缆连锁替换（ChainMode.SPECIAL，子模式 SPECIAL_GT_CABLE_REPLACE）。
- 玩家手持异种线缆，对已有线缆左键触发连锁。
- 100% 复现，非偶发。

## 根本原因

1. **`GregTechCableTraverser.seed()`（`src/main/java/club/heiqi/qz_miner/chain/planner/GregTechCableTraverser.java`）把 origin 只入 `visited`、不入 `currentFrontier`**：早期从 FloodFill 系通用范式抄来，origin 入 visited 占位即返回。
2. **`step()` 只从 `currentFrontier` 取方块**：origin 永远不会被 `consumer.accept()`，永远不会进替换队列 → 静默漏替换。
3. **触发模式语义差异被忽略**：
   - FloodFill 系（CHAIN/AREA/BoxScan/SectionClear）走**破坏后事件**——原版破坏已先消费掉 origin，origin 不在世界，排除 origin 是正确的已验证行为。
   - GT 线缆 SPECIAL_REPLACE 走 `LeftClickObserved` **预取消原版破坏**——`setCanceled` 拦截了原版破坏，origin 仍留在世界，必须参与统一替换。
4. **GT 线缆 NPE 修复（d325b71）只修了替换执行层的双向绑定与连接位掩码直写**，没有同步排查 planner 层 seed 是否对齐 GT 线缆的"取消破坏"语义。

## 修复方案

修复落点：`fix/cable-atomic-replace-binding` 分支本轮 commit（见 git log）。

- **`GregTechCableTraverser.seed()` origin 入 frontier**：在 `context.getVisited().add(origin);` 之后、邻居预展开循环之前，加 `if (context.canTraverse(origin)) { context.getCurrentFrontier().add(origin); }`。
  - 保留邻居预展开循环，改动面最小，不采用"删邻居循环让 step 统一展开"的精炼版（违背纠偏只改误差点纪律）
  - `canTraverse` 对称校验：与邻居入队路径一致，由 visited 保证 step 处理 origin 展开邻居时不重复入队
- **类注释补充语义说明**：明示与 FloodFill 系相反，本遍历器 origin 参与连锁（左键取消原版破坏，origin 仍在世界）
- **决策文档**：`docs/反馈层/决策/chain-origin-inclusion-semantics.md` 沉淀触发模式分化语义

## 预防措施

1. **遍历器 `seed()` 对 origin 的处理必须按触发模式分化**：破坏后事件触发（FloodFill 系）排除 origin 正确；取消破坏事件触发（`LeftClickObserved`/`RightClickObserved` 预取消原版破坏）必须让 origin 入 frontier。新增遍历器时先确认触发模式属于哪一类。
2. **修复触发链路任一层时，要沿链路上下游排查语义是否对齐**：GT 线缆 NPE 修复只动了执行层（adapter/executor），planner 层 seed 是否对齐 GT 线缆的"取消破坏"语义没有同步排查，是本 BUG 漏修的根因。后续修触发链路任何一层时，都要从触发入口（`LeftClickObserved`/`RightClickObserved`/`BreakBlock`）走到 planner seed → step → consumer 全链路对照语义一致性。
3. **静默漏替换是危险失败模式**：无崩溃、无异常日志，只能靠实机观察"被点击那一个是否被替换"发现。GT 线缆类替换必须实机核对 origin + 邻居都替换，不能只看邻居。
4. **GT API 装配依赖运行时**：本修复无法写 JVM 单测——`seed()` 链路调用 `world.getTileEntity`，纯 JVM 无法实例化 `net.minecraft.world.World`（抽象类、无 Mockito）。靠 `runClient21` 实机验证，对齐 `ChainPlanningEventBridgeTest.java:18-19` 既有做法。
