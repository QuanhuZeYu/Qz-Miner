# ERROR-20260914 GLSL 表达式折行被静态检查器判成「语句缺少终结符」

## 现象

修 A1（最小宽度交付量）时把位移表达式折成两行以便阅读：

```glsl
displaced = aPos + aDirection.xyz
    * min(0.5 * uBarThickness * max(0.0, uMinScreenWidthPx / widthPx - 1.0), maxWidenWorld);
```

`validateShaders`（glslang）**通过**，但完整 `build` 里两条测试红：

```
ChainPreviewShaderContractTest > vertexShaderPassesGlsl120StaticChecks FAILED
    preview.vert 存在静态错误: [error L170: 语句缺少终结符: displaced = aPos + aDirection.xyz,
                               error L174: 语句缺少终结符: displaced = displaced + aDirection.xyz]
ChainPreviewShaderOutlineTest > everyOutlineBranchIsGatedByPositiveWidth FAILED
ChainPreviewShaderOutlineTest > outlineOnlyDisplacesVertices FAILED
```

后两条是源码文本断言要求 `displaced + aDirection.xyz *` 逐字出现在同一行。

## 根因

本仓对 shader 的静态检查有两层，口径不同：

| 层 | 实现对换行表达式的态度 |
|---|---|
| `validateShaders`（glslang） | 正常（GLSL 规范允许表达式跨行） |
| `Glsl120StaticChecker`（测试层自研校验器） | **逐行**判定「语句是否以 `;` 结尾」⇒ 折行必然误报 |

也就是说：**glslang 通过 ≠ 静态检查器通过**，而源码文本断言又是第三种口径（逐字匹配）。

## 教训

1. 改 `preview.vert` 时，长表达式**不要折行**（哪怕为了可读性）；
2. 类似 ERROR-20260914-shader-text-assertions-not-covered-by-glslang-gate：**只跑 `validateShaders` 会漏掉测试层断言**，必须跑完整 `build`；
3. 若将来要让 checker 支持续行，正确做法是按「括号深度 + 行尾是否为运算符」判定语句边界，而不是放宽成「整文件找 `;`」——后者会让真正的漏分号失去检出能力。

## 修法

位移表达式恢复单行；A2 的世界上界抽成同一个单行变量 `maxWidenWorld`，两处位移共用。
