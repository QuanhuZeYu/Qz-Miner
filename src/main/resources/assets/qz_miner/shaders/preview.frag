#version 120

/*
 * 连锁预览条柱 · 片元路径（GL 2.1 / GLSL 1.20 基线）
 *
 * 语义颜色按 uniform 提供，避免 GLSL 1.20 的数组索引限制（1.20 要求非恒定索引
 * 可访问 uniform 数组，但数组下标必须是常量表达式；这里改用逐类别 select，
 * 类别只有 4 个，开销可忽略且完全兼容 1.20）。
 *
 * clientPreviewColorSource=builtin 时宿主把 uColorPrimary 设为 0.25/0.90/1.00
 * 且四个类别同色，逐像素与现状常量色等价；alpha 始终来自距离淡出/生长的顶点值。
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

/** 类别 id 与 aAux 契约 §D 一致：0 主 / 1 子模式 / 2 远端 / 3 截断 / 255 未定义。 */
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
    // vColor.rgb 在顶点阶段已是「语义基色 × alpha」，此处不得再乘一次 alpha。
    gl_FragColor = vec4(vColor.rgb, vColor.a);
}
