# ERROR-20260913：预览后端跨包抽取（零拷贝访问）与帧级 GL 围栏契约

- 日期：2026-09-13
- 范围：`chain/client/render/**`（新增后端）、`chain/client/ChainPreviewRenderer.java`、`chain/client/ChainPreviewMesh.java`（访问器可见性）
- 现象：接口冻结 §C 要求后端实现落在 `chain/client/render`，但 `ChainPreviewMesh` 的零拷贝访问器 `vertexArray()/colorArray()/indexArray()` 是包私有，后端只能拿到 `getVertices()/getColors()/getIndices()` 的整数组防御性拷贝（颜色流 ≤ 4MiB/次，B1.3 提频后更痛）；同时 `ChainPreviewMeshCache` 内部每个 GL 操作各自 `glGetInteger` 捕获绑定，与 B0.4「帧级围栏」目标冲突。

## 根因

1. 数据类（`chain.client`）与后端实现（`chain.client.render`）分属不同包，接口冻结只覆盖了方法契约，没有覆盖数据访问面。
2. GL 资源实现与状态围栏放在同一个「快照类」里，导致每个 upload/draw 都重复捕获绑定；B0.4 要求围栏提升到帧级后，围栏归属必须显式化，否则实现各自捕获会互相覆盖。

## 修复（task-2 / B0.2 + B0.4）

- 经 Lead 授权把 `vertexArray()/colorArray()/indexArray()` 提升为 public，并写入统一契约：**零拷贝只读视图，仅渲染线程上传路径使用，禁止修改、禁止跨帧持有**（与 geometry-core 的 `auxArray()` 同约定）。
- GL 缓冲实现从 `ChainPreviewMeshCache` 迁入 `chain/client/render/ChainPreviewLegacyBackend.java`，旧类退役；GL 调用序列逐条等价（VAO + attrib 0 + client state 颜色 + GL_QUADS）。
- 帧级围栏 `ChainPreviewGlBindings`：每帧最多捕获一次（3 次 `glGetInteger`：VAO / ARRAY_BUFFER / ELEMENT_ARRAY_BUFFER），帧末恢复一次；矩阵模式不再单独查询（`glPushAttrib(GL_ALL_ATTRIB_BITS)` 的 pop 已覆盖）。`dispose()` 在帧外调用，自带一次围栏；后端不得自行捕获绑定。
- 空网格上传（`uploadTopology(EMPTY)`）只清空索引、不得触发 GL 初始化，保证非渲染路径清理不污染状态。

## 教训

- 接口冻结文档必须同时冻结「实现所在包需要的数据访问面」，否则实现方只能拷贝或绕开包边界。
- 围栏归属必须在接口 javadoc 里写死（谁捕获、谁恢复、哪些入口例外），否则后续 shader 后端很容易再叠一层 `glGetInteger`。
- 逐波生长不能用索引段表达：网格先写 junction 相、后写 tube 相，**索引顺序 ≠ appearOrder 顺序**；legacy 本轮整体绘制，shader 路径按 aAux 逐顶点 appearOrder 比较（Lead 裁定，已写进 draw plan javadoc）。

## 验收

- headless：`ChainPreviewDrawPlanTest`（derive/sanitized/波表归一化/计数器快照/alphaFor 六采样点）、`ChainPreviewBackendSelectorTest`（auto/shader/legacy × 能力/失败 决策表）、`ChainPreviewGlCapabilitiesTest`（版本解析与兜底）、`ChainPreviewGlBindingsTest`（一次捕获=3 次查询、一次恢复=3 次绑定）、`ChainPreviewScaleCountersTest`。
- 真机未实测：帧级围栏下的原版渲染状态回归、shader 路径观感对照留用户/CI 集成窗口。
