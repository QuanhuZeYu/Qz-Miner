#version 120

/*
 * 连锁预览条柱 · 片元路径（GL 2.1 / GLSL 1.20 基线）
 *
 * 职责边界（task-16 F1）：
 *   颜色选择在**顶点阶段**完成（见 preview.vert 的 previewSemanticColor）——varying 是
 *   smooth 插值的，一个 quad 内若两顶点类别不同（共享角点取相邻目标的最小类别序），
 *   插值结果会落在两整数之间；若片元再用 vSemantic == 2.0 精确比较，该段会整片落空、
 *   丢失远端/截断色。GLSL 1.20 没有 flat 限定符，所以颜色必须逐顶点定下来。
 *
 *   因此本单元只做两件事：丢弃不可见片元、把插值后的颜色与 alpha 交给共用混合。
 *   这里**不再**声明 uColor* uniform，也不重复选择类别（避免两处真源分叉）。
 *
 * 颜色模型：uColor* 是**绝对颜色**，由顶点按 semanticClass 选择（接口冻结 §D 类别表）。
 *  - colorSource=builtin：四色都传精确基线常量 (0.25, 0.90, 1.00) ⇒ 逐字节等于现状；
 *  - colorSource=config：四色来自配置（int RGB → float/255；8bit 量化差异只出现在该档）。
 *
 * alpha 不预乘：共用混合是 glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)，
 * 输出 rgb 不含 alpha 才能得到 rgb × alpha（预乘会退化成 alpha²，alpha=0.15 → 0.0225 vs 0.15）。
 */

varying vec4 vColor;

void main(void) {
    if (vColor.a <= 0.0039) {
        discard;
    }
    // 非预乘输出：rgb 与 alpha 各自独立，由混合阶段相乘。
    gl_FragColor = vec4(vColor.rgb, vColor.a);
}
