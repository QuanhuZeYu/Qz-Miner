# ERROR-20260914 着色器路径把「请求的属性槽位」当成事实，顶点坐标从 aux 字节读

## 表型

连锁预览切到 `shader` 档后，屏幕只在**瞄准方块附近**出现一小块纯色（`uColorPrimary` = `0x40E6FF` 原色）色斑，
其余几何完全不可见——连 `alpha = 0.15` 的淡色痕迹都没有。`legacy` 档同一份几何显示正常。

## 根因

真机查询：

```
attribSlot{aPos=1, aAux=2, aColor=-1}
```

而 Java 侧按接口契约「aPos = 0 / aAux = 1 / aColor = 2」**硬编码**写属性指针：

```java
glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);              // VBO   —— 没有着色器读它
glVertexAttribPointer(2, 4, GL_FLOAT, false, 16, 0L);              // CBO   —— aAux 实际在这里读
glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, 4, 0L);        // ABO   —— aPos 实际在这里读！
```

于是 GPU 把 **ABO 的 uint8 字节值当作顶点坐标**：所有顶点落在 `[0,1]³` 的小盒子里，
在相机前约 2 单位处投影成一小块——形状还是窄长条，因为 `semanticClass` / `tubeEdge` 只有少数几个取值。

驱动为何这样分配：GLSL 1.20 兼容档下内建属性 `gl_Vertex` 占住槽位 0，用户属性从槽位 1 起分配；
`aColor` 因为顶点着色器**从不读取**它（颜色由 `aAux` + uniform 调色板在顶点阶段决定）被整体优化掉，location = -1。

## 为什么长时间不可见（关键教训）

```
bindAttributeLocations()   // glBindAttribLocation(program, 0, "aPos") —— 无错返回，但不生效
    ↓
compileAndLink()
```

调用时机是正确的（链接前），**没有报错、也没有 GLSM 的 `Unmapped GL call` 警告**——
它"看起来成功了但事实不是"，是本环境里最难发现的一类失败。而所有回读自证都**自洽**：

| 既有探针 | 结果 | 为什么抓不到 |
| --- | --- | --- |
| `upload{vbo=ok,cbo=ok,abo=ok,ebo=ok}` | 通过 | 数据确实写进了各自的 buffer |
| `bindings{a0={buf=VBO,stride=12},...}` | 通过 | 它按 0/1/2 **假设**槽位语义，从不问驱动 |
| `data{bboxEqual=true, vboFirstMismatchAt=-1}` | 通过 | VBO 内容没错，错的是**谁来读它** |
| `uniform{maxAbsDiff=0.000000}` | 通过 | 矩阵上传正确 |
| 离线几何投影（真实 uMVP × 离线几何） | "应该满屏" | 模型少了"槽位错位"这一维 |

只有两类观测能证伪它：

1. `attribSlot{...}`——直接问驱动要槽位（`glGetAttribLocation`）；
2. `coverage{changedPixels=22586, ratio=0.0058, bbox=(1487,605)..(1563,947)}`——
   draw 前后像素差分，实测"这一帧只改了 0.58% 的屏幕，且是窄长条"。

## 修复

槽位改为**运行时事实**：链接后 `glGetAttribLocation` 解析，`glBindAttribLocation` 只作为"请求"保留。

- `ChainPreviewShaderProgram#resolveAttributeLocations()`：查询并校验；`aPos` / `aAux` 缺失即抛，
  由 `ensureReady()` 收敛为"程序不可用"⇒ 一次性回退 legacy，绝不留错误空间的一帧；`aColor = -1` 合法。
- `ChainPreviewShaderBackend`：`attributePosition` / `attributeAux` / `attributeColor` 三个字段贯穿
  `initializeGl()` / `bindVertexLayout()` / draw 的 enable-disable；`attributeColor < 0` 时跳过。

## 防护（防回归）

`ChainPreviewShaderFenceTest`：

- `drawDisablesEveryAttributeItEnabled`：从"断言字面量 0/1/2"改为"断言 enable 与 disable 的实参集合相等"——
  它守的纪律（成对）不变，且不再把被证伪的槽位固化下来；
- `attributePointersUseRuntimeResolvedSlots`（新增）：`initializeGl` / `bindVertexLayout` 里
  `glVertexAttribPointer` 的第一实参必须是运行时解析字段，不得出现字面量。

## 一般化教训

1. **"调用无错返回" ≠ "调用生效"**。本环境（Angelica GLSM + no-error context）里，
   凡是驱动/兼容层可能吞掉的绑定类调用，都必须以**查询结果**为准，而不是以自己发过的请求为准。
2. **GLSL 1.20 没有 `layout(location)`**，属性槽位本就是链接期决定的外部契约；
   兼容档下还有 `gl_Vertex` 之类的内建占用，用户属性未必从 0 开始。
3. **自证要覆盖"谁读谁写"，不能只覆盖"写了什么"**。本轮补上的 `attribSlot`（谁读）与
   `coverage`（实际画了多少）才是决定性观测——这两条临时探针已在取证与 A/B 通过后按既定条件拆除。

## 后续（T51）：§A 契约里的 aColor 顶点流已移除

槽位改为运行时解析后，`aColor = -1` 这一事实暴露出**契约与实现长期不符**：§A 写的是
「attribute 2 aColor 4 x float32（既有颜色流）／属性槽保留」，但顶点着色器从不读取它，
颜色由 `aAux.semanticClass` + `uColor*` 在顶点阶段决定。代价是后端每代仍在
`uploadTopology` 里为 CBO 做一次 `glBufferData` 重分配 + `glBufferSubData` 上传
（262 KB 级），并且这个"保留槽"曾经是硬编码槽位 2 的来源之一——旧代码写死 `(2, …)`
恰好覆盖了驱动分配给 `aAux` 的槽位。

按 §A 修订执行：

- `preview.vert` 删去 `attribute vec4 aColor;`，头部契约段记录修订理由；
- `ChainPreviewShaderBackend` 删除 CBO 的字段 / 创建 / 上传 / 释放 / `describe()` 项；
- `ChainPreviewShaderProgram` 删除 `ATTRIB_COLOR`、该槽的 `glBindAttribLocation`、查询与 getter；
- 契约测试改为 `declaresFrozenVertexAttributes`：恰好两个属性 `aPos(3f)` / `aAux(4通道)`，
  并显式断言 `aColor` 不再存在于 shader 路径。

该颜色流仅剩 legacy 固定管线消费（其逐顶点 α 是 CPU 烘焙值），`ChainPreviewMesh.colorArray()`
与 `getColorFloatCount()` 保持不变。
