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
 * 颜色模型：uColor* 是**绝对颜色**，由顶点按 semanticClass 选择（语义类别表（真源：ChainPreviewSemanticClass）；六色按大模式区分）。
 *  - colorSource=builtin：CHAIN 槽传精确基线常量 (0.25, 0.90, 1.00) ⇒ 默认大模式逐字节等于现状；
 *    其余五槽为内置显式色（0xRRGGBB → float/255）；
 *  - colorSource=config：六色来自配置（int RGB → float/255；8bit 量化差异只出现在该档）。
 *
 * alpha 不预乘：共用混合是 glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)，
 * 输出 rgb 不含 alpha 才能得到 rgb × alpha（预乘会退化成 alpha²，alpha=0.15 → 0.0225 vs 0.15）。
 *
 * 实机验证记录（本节是注释：**注释改动不触发重验**；只有 GLSL 逻辑变化才需要重验）
 *   理由：加/改本标记本身若算「逻辑改动」，标记就永远落不下来——会形成死循环。
 *   口径：改 GLSL 逻辑 → 真机确认无异常 → 在列表末尾追加一行（并按仓库规范跑完整 build）。
 *   格式：@ <短commit> <日期> <验证了什么>
 *
 *    @ c390b681 2026-09-14  布局 / 最小宽度 / 真描边 / 面朝向明暗 —— 真机无异常
 *    @ 60d9dbd9 2026-09-14  默认档复验（最小屏幕宽度 0 / 面朝向明暗关，含 d60b94a4 起的宽度-描边共用世界预算）—— 真机无异常
 *    @ 767097b1 2026-09-14  面朝向明暗默认改开后首次真机执行（默认档：xray / 最小屏幕宽度 0）—— 真机无异常、观感可接受
 *                            未覆盖：OUTLINE 档（含宽度 0 收敛为单遍的修复）、最小宽度非零档
 *    @ 019aabd5 2026-09-14  面明暗默认开 + 连锁序渐弱（乘颜色亮度）+ 六色语义调色板 —— 真机无异常
 *                            同批覆盖：OUTLINE 档与描边宽度 0（收敛为单遍、不再叠色）、最小屏幕宽度非零档、
 *                            配置界面四项（小数点输入 / 开关开·关配色 / HEX 输入 / 大模式配色与中文 label）
 */

varying vec4 vColor;

void main(void) {
    if (vColor.a <= 0.0039) {
        discard;
    }
    // 非预乘输出：rgb 与 alpha 各自独立，由混合阶段相乘。
    gl_FragColor = vec4(vColor.rgb, vColor.a);
}
