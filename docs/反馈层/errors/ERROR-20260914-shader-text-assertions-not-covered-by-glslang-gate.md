# ERROR-20260914 改着色器只跑 glslang 闸门，漏掉源码文本断言

## 现象

提交 `c9d9ff74`（移除 preview.vert 里被否定的 aPos 近似横向轴实现）时，
我只跑了 `validateShaders`：

```
GLSL ok   [preview.frag]
GLSL ok   [preview.vert]
BUILD SUCCESSFUL
```

看起来全绿，提交了。**但仓库里还有第二层 shader 验证没跑**——它们读 `.vert` 源码做文本断言，
不在 `validateShaders` 任务里：

- `ChainPreviewShaderOutlineTest`（门控 / 厚度上界 / 只改位移）
- `ChainPreviewShaderContractTest`（属性契约 / 显式矩阵 / 各 §条款）

这 4 条断言钉住的正是我删掉的那段代码的文本，于是它们**静默漂移**。直到下一批配置改动
跑完整 `build` 才暴露出来，被误读成"新批次引入的失败"。

## 根因

本仓对着色器有**两层独立验证**：

| 层 | 做什么 | 何时跑 |
|---|---|---|
| `validateShaders`（glslang） | 语法 / 语义可编译 | `build` 与 `test` 的前置任务 |
| 源码文本断言测试 | 契约条款是否仍在源码里（属性声明、门控条件、位移表达式…） | 只有 `test` |

**改了 shader 只跑第一层 = 只证明它还能编译，没证明契约还在。**

## 教训

**改 `src/main/resources/.../shaders/*` 之后必须跑完整 `build`，不能只跑 `validateShaders`。**
判断标准不是"编译过了"，而是"钉住这份源码的断言全都还成立"。

## 修法

4 处断言按「**意图保留、断言对象随实现迁移**」同步，而不是简单改成匹配新文本：

- `everyOutlineBranchIsGatedByPositiveWidth`：改为断言 min-width 一处 + 描边两处
  （位移 / 配色）各自 `> 0.0` 独立门控 + 位移沿 `aDirection.xyz`——比原来更强；
- `glslUsesSameThicknessDependentCap`：断言对象迁移到上界子式 `max(0.0, 0.5 - uBarThickness)`
  （新式是内层 `min`，两个操作数非负故不需要下界 clamp），并保留"不得回到固定 0.5 上界"的反向断言；
- `outlineOnlyDisplacesVertices`：`displaced = displaced + aDirection.xyz *`；
- `vertexStageUsesExplicitCameraMatricesOnly`：删掉「横向投影取自显式 modelview」——
  该断言的**对象已不存在**（顶点阶段不再推导横向轴），显式矩阵的其余断言保留。
