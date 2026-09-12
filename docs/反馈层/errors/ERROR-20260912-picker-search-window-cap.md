# 选择器搜索命中被 64 窗口夹取（原版「橡木原木」可见性缺陷）

## 错误现象

方块选择器在「全部分类」下搜索「木」，结果里看不到原版的「橡木原木」（`minecraft:log`，本实例中文名由 etfuturum 内嵌 `vanilla_overrides` + `enableLangReplacements=true` 生效，`tile.log.oak.name` = 「橡木原木」）。它**不是没命中**：名字确实含「木」，但排序后落在窗口之外，既不可见也不可滚达；顶栏统计与左侧「全部」徽章显示的是真实命中数，与列表里能滚到的条数不一致。

## 触发场景

- 真实实例：GTNH 2.9.0-beta-2 / MC 1.7.10，243 个 mod；含「木」的方块名条目三个独立口径 = 1055（`tile.*` + txloader 汉化合并）/ 1378（含 `item.*`）/ 568（mods 自带 + etfuturum 内嵌覆盖，最保守）；
- 命中序 = rank 升序 + registry 字典序 tie-break ⇒ `minecraft:` 排在多数 mod 之后，`off(minecraft:log) ≥ 273`；
- 只要「真实命中数 > 64」，排序在 64 名之后的候选全部进入黑洞（不限于「木」）。

## 根本原因

UILib 装配层把 `SearchPickerSpec.maxItems()` 的语义反转成了「搜索 lane 窗口上限」（原 javadoc 自述为「兼容提示值…**搜索返回完整结果**」，且在主干无生产消费者），于是面板 lane 求值把搜索 lane 的窗口总量夹成 `min(matchCount, searchMaxItems)`（默认 64），而浏览 lane 同处求值无上限。候选源 SPI 本身完全支持任意 offset 的惰性分页（`page(query, offset, limit)`，单页物化量 ∝ limit），缺陷**只在 UILib 面板层**，不在注册表数据、也不在 Miner 的候选源接线。

## 修复方案

- UILib：搜索 lane 与浏览 lane 同构 —— `LaneView.totalItems = matchCount(query)`（真实命中数），可见性交给既有窗口切片惰性分页；SPI 路径 `truncated` 不再由窗口上限派生；`maxItems` / `searchMaxItems` 上限概念整链移除（`SearchPickerSpec` / `Values.searchPicker` 重载 / `Registry.register` 重载 / `CandidateSourceValueEditorProvider.searchMaxItems()` / `SearchPickerFieldSupport.searchWindowOf` / `ScenePickerPanel$Props.searchMaxItems` / `Builder.candidateSource(..., int, ...)`）；
- Miner：按新签名接线（`Values.searchPicker(id, LIST_MEMBERS)`、三参 `candidateSource(source, query, version)`），删除 `searchMaxItemsOf` 与相关断言；候选源级新增「大 offset 切片与整取逐项一致」用例；
- 截断文案组保留（`searchFunction` 兼容壳在外部调用方给有限 limit 时仍如实透传 `truncated`），SPI 路径不再触发；
- 规格与发布文档：工作站 `team/P0-ADR-契约与测量.md` §0-R 修订 R-02，UILib `CHANGELOG.md` / `.changelogs/5.0.0.md` 撤回「语义反转」条目并逐项登记删除。

## 预防措施

- 搜索与浏览两条 lane 的总量口径必须同构；任何「窗口上限」型静态量都必须与「末项是否可滚达」的判据成对验证，禁止只断言总量；
- `truncated` 只能在数据面真实截断时置位：总量被本地夹取却渲染「结果已截断」会让用户以为是数据缺失；
- 回归锚点：UILib `ScenePickerPanelSpiWindowTest#searchLaneReachesHitsBeyondFormerWindowCap` / `#searchLaneAndBrowseLaneShareTotalItemsSemantics`、`ScenePickerPanelCategoryFilterTest#searchLaneWindowTotalsTrueHitsWithoutCap`；Miner `BlockPickerCandidateSourceTest#searchPageSlicesFarOffsetInGlobalHitOrder`；
- 独立验证口径：改动前基线 10 红 / 改动后 17 全绿（`temp/picker-lane-a-verify/`），装置自校验（注入失败断言必红）防止空转假绿。
