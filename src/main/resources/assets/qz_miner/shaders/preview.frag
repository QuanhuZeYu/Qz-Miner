#version 120

/*
 * 连锁预览条柱 · 片元路径（GL 2.1 / GLSL 1.20 基线）
 *
 * 语义颜色按 uniform 提供，避免 GLSL 1.20 的数组索引限制（1.20 要求非恒定索引
 * 可访问 uniform 数组，但数组下标必须是常量表达式；这里改用逐类别 select，
 * 类别只有 4 个，开销可忽略且完全兼容 1.20）。
 *
 * 颜色模型：uColor* 是**绝对颜色**。builtin 档宿主传入精确基线常量
 * (0.25, 0.90, 1.00)（刻意不用 0x40E6FF 的 8bit 量化值，避免 ≤0.002 色差），
 * 与 legacy 颜色流同值 ⇒ shader 输出与 legacy 逐位一致。
 *
 * 片元**不得**再乘顶点基色 aColor.rgb：顶点流已是 (0.25, 0.9, 1.0)，
 * 再乘一次会得到 (0.0625, 0.81, 1.0)，R 掉到 1/4、肉眼可见偏暗偏蓝
 * （与「builtin 逐字节等于现状」不符）。
 *
 * alpha 始终来自距离淡出 / 生长的顶点值，且**不预乘**：共用混合是
 * glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)，输出 rgb 不含 alpha 才能得到
 * rgb × alpha（预乘会退化成 alpha²，alpha=0.15 → 0.0225 vs 0.15）。
 */

uniform vec3 uColorPrimary;
uniform vec3 uColorSecondary;
uniform vec3 uColorRemote;
uniform vec3 uColorTruncated;
uniform float uColorPrimaryEnabled;
uniform float uColorSecondaryEnabled;
uniform float uColorRemoteEnabled;
uniform float uColorTruncatedEnabled;

varying vec4 vColor;
varying float vSemantic;

/**
 * 返回语义类别的绝对颜色；255 未定义落 uColorPrimary 兜底。
 * 类别 id 与 aAux 契约 §D 一致：0 主 / 1 子模式 / 2 远端 / 3 截断。
 */
vec3 selectSemanticColor() {
    vec3 color = uColorPrimary;
    if (vSemantic > 0.5 && vSemantic < 1.5 && uColorSecondaryEnabled > 0.5) {
        color = uColorSecondary;
    } else if (vSemantic > 1.5 && vSemantic < 2.5 && uColorRemoteEnabled > 0.5) {
        color = uColorRemote;
    } else if (vSemantic > 2.5 && vSemantic < 3.5 && uColorTruncatedEnabled > 0.5) {
        color = uColorTruncated;
    }
    return color;
}

void main(void) {
    if (vColor.a <= 0.0039) {
        discard;
    }
    // 非预乘输出：rgb 直接取语义绝对色（不含 alpha），alpha 单独交给混合。
    // builtin 档 uColorPrimary 精确等于 legacy 常量 (0.25, 0.9, 1.0)，
    // 故最终 src = 常量 × alpha，与 legacy 逐字节一致（见 ChainPreviewShaderMath）。
    gl_FragColor = vec4(selectSemanticColor(), vColor.a);
}
