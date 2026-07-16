# 决策记录

> 本目录沉淀关键架构取舍。**按主题归并，不按日期堆叠**——同主题多次决策归一个文件，
> 用文件内「演进」段记录变化，避免 vibe coding 常见的"留下一堆历史性文档"反模式。
>
> 控制论角色：反馈层·决策回写。文档纪律门禁（`scripts/check-doc-discipline.ps1`）机械守卫本约定。

## 核心原则：单进单出

- **新增决策前先看能否归并**到已有主题文件——能归并则在该文件内追加「演进」段，不新建文件
- **文件名按主题**，不按日期：`<主题>.md`（kebab-case，如 `parallel-tick-cooperative-cancellation.md`）
- **禁日期编号文件名**：不得出现 `DECISION-YYYYMMDD-*` 这类按日期追加的命名（门禁断言1 守卫）
- **决策演进覆盖事实**：同主题新决策让旧决策过时，直接改旧段落或用「演进」段标注转向，只留当前真值

## 何时写决策（判定门槛）

满足任一条才写，否则不写：

- 多种方案都可行，但最终必须固定一种
- 某个选择会长期影响目录结构、接口边界或依赖策略
- 未来很可能有人问"为什么当时这么做"
- 某项约束既不是错误（归错误预防），也不是 review（归审查记录），但必须被后续协作者知道

**不写决策的情况**：临时调试结论、一次性试验、过程性进展、能在错误预防或交接.md 一句话说清的事。

## 文件命名与组织

- `<主题>.md`，主题用稳定名词（不用动词短语、不用日期）
- 一个文件承载一个主题的完整决策史（含演进）
- 文件数应有上限（建议 ≤ 10），超过即说明主题切分过细，需归并

## 索引

- `chain-event-bus.md` — **当前架构基石**：v2 连锁事件总线框架（自建事件总线 + 5 态状态机 + ARMED 触发 + 双份预览 + 影子并行迁移 8 阶段），抛弃旧 ChainExecutor 会话式框架
- `drop-release-fallback-chain.md` — 掉落释放四级降级链（玩家位置 → 重生/出生点 → 已记忆兜底 → 连续失败告警 discard），spawn 失败回填缓冲守 I5/I7
- `ore-fortune-cap-fix.md` — 确认 GT / BW / GT++ 时运上限修复必须拦截 `fortune > 3`，不再通过重算 `nextInt` 参数实现
- `optional-mod-reflect-safety.md` — 可选模组兼容层必须以不触发静态初始化、吞掉链接错误、避免扫描客户端签名方法作为服务端安全边界
- `parallel-tick-cooperative-cancellation.md` — 并行 Tick 不能靠超时或强制取消收口，任务必须在内部以预算化安全点协作式暂停、恢复和终止
- `gt-cable-replacement-model.md` — GT 线缆替换执行模型：B1 等规划完成 + B2 单 tick 原子 + B3 预校验放行门，避开跨 tick 电压混压爆炸；关联 NORTH_STAR §8 偏离 D-GTCABLE-ATOM
- `compact-hud-ownership.md` — Miner 只发布不可变 HUD 快照，Qz-UILib 负责 `TOP_LEFT` 渲染；注册属于模组客户端生命周期并跨断线保留
- `auto-tool-swap-server-authority.md` — 自动工具换位由服务端主线程独占库存写权，Qz round/intent/结算与原版库存差异同步分工，并定义同版本和生命周期收口边界
- `jitpack-release-dependencies.md` — Qz-UILib 以 GitHub tag 经 JitPack 标准坐标消费；GTNH Maven 非发布前置，放行以 API/POM/module/`dev` 制品实证为准
