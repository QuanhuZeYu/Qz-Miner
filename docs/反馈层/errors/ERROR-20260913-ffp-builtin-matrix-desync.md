# ERROR-20260913 连锁预览：固定管线内建矩阵在 Angelica(GLSM)+lwjgl3ify 真机环境失同步

- 日期：2026-09-13
- 组件：`chain/client/render/ChainPreviewShaderBackend` + `shaders/preview.vert`（预览着色器主路径）
- 影响：`clientPreviewRenderBackend=auto`（默认）下预览被画进错误空间，真机表现为「整条链只剩一根窄竖条」，HUD 读数正常 ⇒ 功能观感上完全不可用

## 现象（真机取证）

- 环境：GTNH 2.9 + Angelica 2.2.10 + lwjgl3ify + LWJGL 3.4.2；`angelica-options.json` 中 `use_no_error_g_l_context=true`；日志可见 `[GLSM/]: FFP variant compiled …`（固定管线由 Angelica 的 GLSM 用生成着色器模拟，不是驱动 FFP）。
- 截图逐像素测量：青色预览对象 bbox 77px × 455px（画面 2559×1553），位于准心方块处；内部 4 条子条、颜色叠加约 3 层 α≈0.78（即大量条柱在极小区域内重叠），而 HUD 显示「预览已匹配 61 个方块」。
- 按 1 格 ≈ 382px 反推：垂直 1.0 格（正常），水平仅 0.17 格 ⇒ **水平方向被压缩 15~85×**，是「画进错误空间」的表型，不是「缺几何」。
- 日志另有一次 `GL ERROR 1282 (Invalid operation) @ Post render`，时间戳与预览第一帧一致（后续定位为帧围栏 `glPushAttrib/glPushClientAttrib` 一类固定管线调用在该环境下的行为，已加一次性诊断）。

## 定位过程（为何一开始没抓到）

- 状态/快照/发布链路、增量与全量几何逐字节等价、draw plan 字段、深度档默认、alpha 曲线、`barThickness`、面朝向表与 `cuboidCorners` 参数序：全部核对无误（离线可证的部分都是对的）。
- 水平地毯几何探针（`ChainPreviewCarpetGeometryProbeTest`）：包围盒 17.045×1.045×17.045、竖管 244 = 61×4、退化面 0 ⇒ **CPU 网格完全正确**，把嫌疑收敛到 GL 侧。
- 真机日志中**没有任何** shader fallback WARN ⇒ 确认故障发生时跑的是**着色器后端**（legacy = 改造前的固定管线路径，不受影响）。
- 结论：故障只在「外部 GLSL 程序读固定管线内建矩阵」这一条新依赖上。

## 根因

`preview.vert` 通过 `gl_ModelViewProjectionMatrix` / `gl_ModelViewMatrix` 取相机矩阵。在「固定管线由 GLSM 用生成着色器模拟 + no-error GL context」的环境里，这两个内建矩阵与实际相机矩阵失同步（最可能是保持默认单位阵），导致整份网格被画进局部坐标空间；且失效时**没有任何可观测信号**（no-error context 连 GL 错误都吞掉），所以只有真机画面能暴露。

## 修复

- 顶点着色器不再使用任何固定管线内建矩阵：新增 `uniform mat4 uModelViewProjection / uModelView`，`gl_Position`、横向投影、深度换算全部走显式 uniform。
- Java 侧每次 draw（renderer 完成 `glTranslated(origin − renderPos)` 之后）用 `glGetFloatv(GL_PROJECTION_MATRIX / GL_MODELVIEW_MATRIX)` 回读、CPU 侧 4×4 相乘后上传；纯函数 `ChainPreviewShaderMatrixMath` + 单测覆盖列主序、乘法、平移列模长。
- **自检 + 回退**：回读后校验「modelview 平移列模长 ≈ |planOrigin − renderPos|」+ 线性部分刚性/正交性 + 投影有限性；不通过或必备 uniform 缺失 ⇒ 走既有 `ensureReady()==false` ⇒ 一次性永久回退 legacy（不再重试）。
- 可观测性：后端首次就绪打 `backend in use: id=…`；回退打一次性 WARN（含 reason）；`-Dqz_miner.preview.diagnostics=true` 时另打矩阵快照（P/MV/MVP + expected/actual + 相机 + origin + 锚点顶点投影）。

## 教训 / 预防

1. **不要依赖固定管线内建 uniform 的语义**：在 Angelica / GLSM / lwjgl3ify / core-like context 组合下，`gl_ModelViewProjectionMatrix`、`glPushAttrib` 等固定管线能力都可能是「被模拟或被删除」的；跨栈能力必须以显式 uniform + 自检声明。
2. **「能够编译链接」不等于「能正确渲染」**：后端能力探测必须包含可回读、可断言的数值不变量（本次用相机矩阵平移列模长），否则错误帧完全静默。
3. **回退链路要复用既有契约**：把失败表达为既有 `ensureReady()==false`，直接复用已审查过的一次性永久回退，比新增一套探测/回退机制风险低得多。
4. **离线的几何正确 ≠ 真机正确**：所有离线测试都通过时，要把「GL 语义」单列为未验证面，并在默认档上准备可验证的回退路径。

## 相关

- 提交：`a2263145`（显式矩阵 + 自检 + 复用回退出口）、`daf6b745`（就绪回退纯决策 + 围栏诊断 + legacy 上传解绑 + 后端可观测日志）、T48c-C（必备 uniform 校验 + 自检加固 + 诊断快照）。
- 复核：`temp/chain-preview/review/T48c/verify-preview-verifier.md`（独立验证：有条件通过 + 残余风险登记）；`temp/chain-preview/review/T48/`（几何探针）。
- 验收：`temp/chain-preview/verify/真机验收清单.md` §10。
