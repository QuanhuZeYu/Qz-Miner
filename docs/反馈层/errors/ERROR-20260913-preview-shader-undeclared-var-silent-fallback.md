# ERROR-20260913 连锁预览：着色器源码未声明变量 ⇒ 编译失败被回退链吞成"观感正常"

- 日期：2026-09-13（回退提交日）
- 组件：`src/main/resources/assets/qz_miner/shaders/preview.vert`（`5d2e0008` 引入）+ shader 后端一次性回退链（T48c-B）
- 影响：`clientPreviewRenderBackend=shader`（真机配置即此档）下着色器主路径**无法编译**，首次 `ensureReady()` 即永久回退 legacy；真机画面由固定管线渲染 ⇒ 观感正常，但最小屏幕宽度 / 真描边 / 语义色等 shader 专属能力全部静默缺失

## 现象

- 真机（GTNH 2.9 + Angelica GLSM + lwjgl3ify，`run/client` dev 档）预览画面正常、无塌缩。
- 日志只有一行一次性 WARN，之后走固定管线：

  `[ChainPreview] shader backend unavailable, fallback to legacy (configured=…, selected=…, reason=ensureReady-failed, caps=[…])`

- 因为既没有错误帧、也没有异常抛出，这个状态极易被读成"着色器路径已修好"，并让人把下一轮改动造成的塌缩归因到新代码上。

## 根因（静态判定）

`5d2e0008`（禁用 aPos 轴推断位移）把 `float pixelsPerWorldUnit = 1.0;` 的**声明**连同位移代码一起删除，却保留了两处使用：

- `preview.vert:176`：`pixelsPerWorldUnit = max(pixelPerUnitAtDepth * projectedPerUnit, 1e-6);`
- `preview.vert:186`：`float outlineWorld = outlinePx / pixelsPerWorldUnit;`

GLSL 对 `if (false && …)` 分支**不做死代码豁免**：整段函数体仍要过语义检查，未声明标识符是硬编译错误。UILib `ShaderProgramSupport.compileShader` 直接 `glShaderSource` + `glCompileShader`（无 preamble），失败即抛 `IllegalStateException` ⇒ `ChainPreviewShaderBackend.ensureReady()` 捕获后置 `unavailable` ⇒ renderer 走 T48c-B 的一次性永久回退 legacy。

⇒ **用 `false &&` 关掉一段逻辑时，被关掉的代码仍必须可编译**（声明、类型、函数签名都在检查范围内）。

## 为什么离线全绿也拦不住

- `gradlew build` 只编译 Java、跑 JVM 测试，**不编译 GLSL**；
- 着色器源码契约测试只断言属性/uniform 名与字符串存在性，不做"声明-使用"一致性检查；
- 编译失败被回退链吞成一条 WARN 是**设计如此**（宁可回退也不画错帧），但没有失败计数进 HUD，肉眼不可见。

## 顺带确证：方向元数据与本次塌缩无关

真机配置 `clientPreviewMinScreenWidthPx=0.0`、`clientPreviewDepthMode=xray` ⇒ `uMinScreenWidthPx=0`、`uOutlineWidthPx=0`（xray 不是描边壳段）⇒ `6af1d6dd` 里两处位移分支都进不去，`displaced === aPos`。

即：`aDirection` 属性、方向校验、方向位移在真机上是**恒等变换**，塌缩必然发生在 shader 路径的投影/顶点属性环节（显式 MVP uniform 与真实相机矩阵的同步、或 VAO 属性布局），与方向数据无关。这条判据把后续排查面从"两处位移算法 + 矩阵"收敛到"矩阵/属性"一条线。

## 验证方法（真机，10 秒）

启动后搜日志：

- `[ChainPreview] fallback to legacy` ⇒ 着色器未生效（本 ERROR 描述的状态）；
- `[ChainPreview] backend in use: id=shader` ⇒ 着色器路径真的在画，画面问题属 GL 侧；
- 两条都没有 ⇒ 预览根本没启动。

## 教训 / 预防

1. **着色器类改动的验收口径必须包含真机后端自证**（`backend in use: id=shader`）；只跑 `build` 不算验证，契约测试绿也不等于能编译。
2. 死代码分支不能靠 `false &&` 长期保留过不了语义检查的代码：要么修好声明，要么整段删除、由契约测试锁行为字符串。
3. 待办：给着色器源码加一条离线语法/语义校验（标识符声明-使用一致性，或引入 GLSL 校验器），把这类错误从"真机才发现"降到"CI 就能拦"。

## 关联

- 引入：`5d2e0008`
- 复现编译器：`6af1d6dd`（重新声明变量、接入 `aDirection`，着色器路径重新生效 ⇒ 真机塌缩复现）
- 回退：`35ff545e`（工作树逐字节回到 `5d2e0008`，交用户实机验证）
- 相关：`ERROR-20260913-ffp-builtin-matrix-desync.md`（同一现象族：着色器被画进错误空间）
