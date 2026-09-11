# ERROR-20260911：SceneNode.__getChildren() 返回的是「活视图」，跨 reconcile 的行复用断言会静默失真

## 现象

`QzMinerHudWindowTest` 新增「卡片常驻复用 + 行按内容键增量重建」断言后失败：

```
java.lang.AssertionError: 变化行按内容键重建 expected not same
```

而被断言"未变化、应复用"的那一行（同一次 reconcile）却通过了。
生产代码行为正确（行确实被重建），失败来自测试取样方式。

## 根因

- `SceneNode.__getChildren()` 返回 `Collections.unmodifiableList(children)`——**内部 children 列表的活视图**，
  不是快照（`SceneNode.java:1971`）。
- keyed 列表协调的 `SceneNode.applyChildReconcile(finalOrder, insertedOrMoved)` 在该列表上**原地**
  `children.clear(); children.addAll(finalOrder)`（`SceneNode.java:337-362`）。
- 因此测试里"变更前"保存的 `List<SceneNode> rowsBefore = card.__getChildren()` 与"变更后"的
  `rowsAfter` 是同一个视图对象；`rowsBefore.get(4)` 读到的已经是重建后的新节点，
  `assertNotSame` 必然失败，`assertSame`（未变行复用）则恒真通过——**两个断言同时失真**。

## 处置

- 测试在比较前显式快照：`new ArrayList<SceneNode>(rowsOf(card))`，先固定旧子序列再驱动变更。
- 未改生产代码：`__getChildren()` 的活视图语义是 UILib 既有契约（避免每次读取复制子列表），
  业务侧只读遍历不受影响；只有"跨变更保存子序列"的测试需要自行快照。

## 同类风险

任何"先保存 `__getChildren()`，再触发 signal/reconcile，再比较节点身份或顺序"的断言都必须先快照；
`for (SceneNode child : node.__getChildren())` 这类同帧只读遍历不受影响。
