#version 120

/*
 * 连锁预览条柱 · 顶点主路径（GL 2.1 / GLSL 1.20 基线）
 *
 * 顶点属性契约（接口冻结文档 §A）：
 *   attribute 0 aPos   3 x float32  相对 meshOrigin 的方块坐标 + 偏移 x barThickness
 *   attribute 1 aAux   4 x uint8 normalized
 *                        x = semanticClass（0..255，255 = 未定义）
 *                        y = tubeEdge（0..3，255 = 未定义）
 *                        z/w = appearOrder u16 小端（0xFFFF = 未定义）
 *   attribute 2 aColor 4 x float32  既有颜色流（legacy 唯一颜色来源）
 *
 * 功能优先级与落点（接口冻结文档 §F）：
 *   1) 距离淡出      —— 顶点侧按 quadratic 曲线写入 vColor.a，片元直用，零 CPU 上传
 *   2) 屏幕最小宽度  —— 顶点侧沿横向偏移等比放大，与 glTranslated 相机相对坐标一致
 *   3) 逐波生长      —— 读 aAux 的 appearOrder 归一化后与 uAnimProgress 逐顶点比较（不要求索引有序）
 *   4) 语义颜色      —— 片元用 uniform 调制色（中性元 = 不调制），顶点只搬运 semanticClass 与基色
 *   5) 亚像素柔化    —— 横向屏幕宽度不足时收敛边缘 alpha
 *
 * 距离淡出必须与 CPU 端 ChainPreviewMeshBuilder.VisualParameters.alphaFor 的 quadratic
 * 形状一致（d <= fadeStart → uMaxAlpha；d >= fadeEnd → uMinAlpha；之间按 t^2 插值），
 * 否则 legacy 与 shader 两档观感分叉。
 */

attribute vec3 aPos;
attribute vec4 aAux;
attribute vec4 aColor;

uniform vec3 uOriginRel;         // meshOrigin - RenderManager.renderPos（相机相对，CPU 侧 double 相减）
uniform float uPixelScale;       // projection[1][1] * viewportHeight * 0.5：单位深度上的像素/世界单位
uniform float uFadeStart;
uniform float uFadeEnd;
uniform float uMinAlpha;
uniform float uMaxAlpha;
uniform float uAnimProgress;     // (出现序号 / 目标总数)；>= 1 表示整段可见（跳过 appearOrder 比较）
uniform float uAppearSpan;       // 同代目标总数（序号归一化分母），<= 0 时关闭生长比较
uniform float uMinScreenWidthPx; // 0 = 关闭屏幕最小宽度钳制
uniform float uBarThickness;

varying vec4 vColor;
varying float vSemantic;

/** 还原 0..255 的量化通道：normalized uint8 attribute × 255 再四舍五入。 */
float auxChannel(float value) {
    return floor(value * 255.0 + 0.5);
}

/** 与 CPU 端同形的 quadratic 距离淡出。 */
float fadeAlpha(float distance) {
    if (distance <= uFadeStart) {
        return uMaxAlpha;
    }
    if (distance >= uFadeEnd) {
        return uMinAlpha;
    }
    float span = max(uFadeEnd - uFadeStart, 1e-4);
    float t = (distance - uFadeStart) / span;
    return uMaxAlpha - (uMaxAlpha - uMinAlpha) * t * t;
}

void main(void) {
    vec3 cameraRelative = uOriginRel + aPos;

    float fade = 1.0;
    float appearOrder = 0.0;
    if (uFadeStart < uFadeEnd) {
        fade = fadeAlpha(length(cameraRelative));
    }

    // 3) 逐波生长：逐顶点比较 order <= round(uAnimProgress * 目标总数)，不要求索引有序（Lead 裁定）。
    //    判据落在「出现序号的一格」内：u=0 时全隐（order=0 的顶点也要等 u 超过 1/total），
    //    u 从 0→1 时可见顶点数单调递增。整式在 u∈[0,1] 上单调不减，故不存在「先显后隐」。
    //    uAnimProgress >= 1 时整段可见，完全不读 appearOrder（避免逐顶点分支拖慢整段绘制）。
    //    0xFFFF（未定义序号）按「已出现」处理，避免无归属顶点在任意进度下出现空洞。
    float growth = 1.0;
    if (uAnimProgress < 1.0 && uAppearSpan > 0.0) {
        appearOrder = auxChannel(aAux.z) + auxChannel(aAux.w) * 256.0;
        if (appearOrder < 65535.0) {
            // 判据：orderFloor <= u × 目标总数 ⟺ order <= round(u × 总数)。
            // 用「序号格」而非「归一化相等」避免 float 边界抖动；u=0 时恒 0（全隐）。
            float orderFloor = floor(min(appearOrder, uAppearSpan));
            growth = clamp(uAnimProgress * uAppearSpan - orderFloor, 0.0, 1.0);
        }
    }

    // 2) 屏幕最小宽度 + 5) 亚像素柔化：只在「横向」偏移上放大，保持条柱纵向长度不变。
    //    横向判据 = 三个轴上绝对偏移最小的那个；由量级差零误判（厚度 vs 格线 0.5）。
    float pixelPerUnitAtDepth = 1.0;
    float lateralMagnitude = 0.0;
    vec3 lateralAxis = vec3(0.0, 0.0, 0.0);
    if (uMinScreenWidthPx > 0.0) {
        float depth = max(1e-4, -(gl_ModelViewMatrix * vec4(aPos, 1.0)).z);
        pixelPerUnitAtDepth = uPixelScale / depth;

        float magnitudeX = abs(aPos.x);
        float magnitudeY = abs(aPos.y);
        float magnitudeZ = abs(aPos.z);
        lateralMagnitude = min(magnitudeX, min(magnitudeY, magnitudeZ));
        if (lateralMagnitude <= 0.02 * max(uBarThickness, 1e-4)) {
            lateralAxis = vec3(0.0, 0.0, 0.0);
        } else if (magnitudeX <= magnitudeY && magnitudeX <= magnitudeZ) {
            // 判据必须是「哪个轴的分量最小」，不能拿 lateralMagnitude（它本身就是最小值）
            // 去和 magnitudeY/Z 比——那条件恒真，位移会永远沿 X 轴，把条柱推出方块。
            lateralAxis = vec3(sign(aPos.x), 0.0, 0.0);
        } else if (magnitudeY <= magnitudeZ) {
            lateralAxis = vec3(0.0, sign(aPos.y), 0.0);
        } else {
            lateralAxis = vec3(0.0, 0.0, sign(aPos.z));
        }
    }

    vec3 displaced = aPos;
    float lateralWidthPx = 0.0;
    if (lateralMagnitude > 0.0) {
        float worldLateral = length((gl_ModelViewMatrix * vec4(lateralAxis, 0.0)).xyz);
        float projectedPerUnit = clamp(worldLateral, 0.05, 1.0);
        lateralWidthPx = 2.0 * lateralMagnitude * pixelPerUnitAtDepth * projectedPerUnit;
        float widen = clamp(uMinScreenWidthPx / max(lateralWidthPx, 1e-6), 1.0, 64.0);
        displaced = aPos + lateralAxis * (lateralMagnitude * (widen - 1.0));
    }

    // S1 修复：vColor.rgb 必须是非预乘基色，且**不参与片元最终颜色**。
    // 共用混合是 glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)（两后端共用）：
    // 若此处预乘 alpha，片元输出该 rgb 会得到 rgb × alpha²（alpha=0.15 时 0.0225 vs 0.15）。
    // 片元改用 uColor* 绝对色输出（Lead 裁定 S2），本处 rgb 仅作为属性契约的搬运
    // （aColor 保持被读取），保留基色便于后续需要按顶点差色时不再改接口。
    float alpha = fade * growth;
    vColor = vec4(aColor.rgb, alpha);
    // 语义类别：255 = 未定义，片元选择器对 255 兜底主色（Lead 裁定）。
    vSemantic = auxChannel(aAux.x);

    // 关键：必须对 displaced 做投影。此前这里写 ftransform()（内部用 aPos），
    // 使上面的横向钳制算完即丢——B2.1 最小宽度在 shader 路径静默失效。
    // 只手写 MVP 乘法（ftransform 的等价语义），不引入额外 uniform。
    gl_Position = gl_ModelViewProjectionMatrix * vec4(displaced, 1.0);
}
