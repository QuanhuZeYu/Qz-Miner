# 决策：连锁 origin 参与语义按触发模式分化

## 控制论定位

反馈层·决策。

## 核心结论

连锁遍历器是否让 origin（玩家左键触发点）参与连锁替换，**按触发模式分化**：

- **破坏后事件触发（FloodFill 系 CHAIN / AREA / BoxScan / SectionClear）**：原版破坏事件已先消费掉 origin，origin 不在世界，遍历器 `seed()` 只把 origin 入 `visited` 占位、**不入 frontier**——origin 排除是正确的已验证行为。
- **取消破坏事件触发（GT 线缆 SPECIAL_REPLACE 走 `LeftClickObserved` 预取消原版破坏）**：原版破坏被 `setCanceled` 拦截，origin 仍留在世界，遍历器必须让 origin 入 frontier，由 `step()` 统一走 `canTraverse`/`matcher`/`consumer`，否则 origin 永不进替换队列，被点击那一根线缆漏替换。

## 背景

- `fix/cable-atomic-replace-binding` 分支 GT 线缆 NPE 修复（commit `d325b71`）实机回归时发现：除被点击点外其他线缆正常替换，但 origin 那一根仍留在世界没被替换。
- 根因：`GregTechCableTraverser.seed()` 把 origin 只入 `visited` 不入 `frontier`，是早期从 FloodFill 系抄来的通用范式。FloodFill 系这样写是对的（origin 已被原版破坏），但 GT 线缆走的是 `LeftClickObserved` 取消原版破坏的语义，二者对 origin 的处理必须分化。
- 详见 `errors/ERROR-20260706-cable-origin-not-replaced.md`。

## 候选方案

### A. 语义内化进各专属遍历器 seed()（最终选择）

每个遍历器在 `seed()` 自行决定 origin 入不入 frontier，按其真实触发模式写：
- FloodFill/BoxScan/SectionClear `seed()` 仍只入 visited（origin 已被破坏，排除正确）
- `GregTechCableTraverser.seed()` 在 `visited.add(origin)` 后，若 `canTraverse(origin)` 通过则同时入 `currentFrontier`

### B. 接口层加 `includeOriginInFrontier()` 开关否决

在 `BudgetedChainTraverser`/`AbstractChainTraverser` 接口加一个布尔开关，由调用方根据模式传入。否决理由：
- 违背单一执行原则——同一个 `seed()` 要维护两条分支（入/不入），增加后续维护时"一边补一边漏"的风险
- 把语义开关泄漏到接口，调用方需要懂遍历器内部 frontier 语义才能正确传值，耦合反向
- 现实中只有两类触发模式，遍历器各自内化已足够清晰

### C. 抽象出"触发模式枚举 + 通用 seed 模板"否决

否决理由：过度抽象。当前仅两个语义分支，不需要泛化框架；通用模板会模糊"origin 是否在世界里"这个本质差异，反而让读代码的人更难判断。等出现第三、第四种触发模式时再评估上抽。

## 归属层

- 语义内化进各专属遍历器 `seed()`，**不上升到接口开关**（B/C 否决理由如上）
- 不上升为 NORTH_STAR 不变量：这是遍历器实现细节的语义对齐，未触碰 I1-I10 任意一条（线程主权/协作式取消/预算化遍历/网络收口/掉落回填/反射安全/矿石时运拦截点都没动）。属于遍历层正确性范畴，反馈层决策已足够承载

## 影响范围

- `src/main/java/club/heiqi/qz_miner/chain/planner/GregTechCableTraverser.java`（`seed()` origin 入 frontier + 类注释补语义说明）
- FloodFill / BoxScan / SectionClear 的 origin 排除**不迁移**——它们走破坏后事件，排除 origin 是已验证正确行为

## 未来指引

- 新增"取消破坏"类触发模式（任何走 `LeftClickObserved`/`RightClickObserved` 预取消原版破坏的连锁模式）时，其遍历器 `seed()` 必须显式让 origin 入 frontier
- 新增遍历器时，先确认其触发模式属于哪一类（破坏后事件 / 取消破坏事件），再写 `seed()` 的 origin 处理
- 若未来 FloodFill 系也出现"取消原版破坏"变体，需在那个变体的遍历器里同步内化，不通过接口传染

## 演进

- 2026-07-06 首次确立：GT 线缆 SPECIAL_REPLACE 修复 origin 漏替换，明确触发模式分化语义
