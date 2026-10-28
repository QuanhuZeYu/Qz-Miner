#version 120

/*
 * 在顶点阶段计算预览条柱的位移、语义色、亮度与距离 alpha（GL 2.1 / GLSL 1.20）。
 *
 * aPos 是相对网格锚点的位置，aDirection 是 normalized int8 面法线，aAux 保存
 * semanticClass、tubeEdge 与小端 u16 appearOrder。属性槽位由链接后运行时查询决定。
 * 顶点先选语义色，再由片元插值输出，适配 GLSL 1.20 的 smooth varying。
 *
 * 最小屏幕宽度与描边沿显式面方向外扩，共享世界位移预算，最小宽度优先。
 * shader 路径支持真实描边；legacy OUTLINE 使用两 pass 叠色。
 * 距离 alpha 与 CPU VisualParameters.alphaFor 同用 quadratic 曲线，生长和亮度共用序号解码。
 * rgb 依次应用面明暗、连锁序权重及内部格线系数，alpha 独立取距离、生长与全局包络。
 * 相机矩阵由 Java 显式读回并上传，适配 Angelica GLSM 的固定管线模拟。
 * 亚像素边缘柔化保留为待实现能力。
 *
 * 实机记录仅作为对应逻辑版本的运行证据；后续 GLSL 逻辑变化经实机确认后追加记录。
 * 本次整理只改注释，沿用以下既有证据：
 *    @ c390b681 2026-09-14  布局 / 最小宽度 / 真描边 / 面朝向明暗 —— 真机无异常
 *    @ 60d9dbd9 2026-09-14  默认档复验（最小屏幕宽度 0 / 面朝向明暗关，含 d60b94a4 起的宽度-描边共用世界预算）—— 真机无异常
 *    @ 767097b1 2026-09-14  面朝向明暗默认改开后首次真机执行（默认档：xray / 最小屏幕宽度 0）—— 真机无异常、观感可接受
 *                            未覆盖：OUTLINE 档（含宽度 0 收敛为单遍的修复）、最小宽度非零档
 *    @ 019aabd5 2026-09-14  面明暗默认开 + 连锁序渐弱（乘颜色亮度）+ 六色语义调色板 —— 真机无异常
 *                            同批覆盖：OUTLINE 档与描边宽度 0（收敛为单遍、不再叠色）、最小屏幕宽度非零档、
 *                            配置界面四项（小数点输入 / 开关开·关配色 / HEX 输入 / 大模式配色与中文 label）
 */

attribute vec3 aPos;
// normalized byte 面法线，第四项为对齐保留位。
attribute vec4 aDirection;
attribute vec4 aAux;

// Java 侧显式读取相机矩阵并相乘上传，后端用平移列与相机相对原点检查来源可信性。
uniform mat4 uModelViewProjection; // 投影 × modelview（列主序，GL 约定）
uniform mat4 uModelView;           // modelview；横向偏移与深度换算用

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
uniform float uFadeAlpha;        // 全局淡入淡出包络，1 为不透明
                                 // Java 链接时初始化不透明值，每帧上传当前包络
uniform float uOutlineWidthPx;   // 描边外扩宽度（物理像素），零关闭描边，最小宽度独立生效
                                 // 普通 pass 使用零描边宽度
uniform float uFaceShading;      // 面朝向明暗开关，大于 0.5 时开启
                                 // 关闭时保留面明暗前的 rgb
uniform float uOrderMinBrightness;    // 连锁序 rgb 权重下限，1 使用恒等亮度
                                 // 关闭时 orderWeight 保持初始恒等值；
                                 // 序号未定义或总数缺省时同样使用恒等权重；
                                 // 权重作用 rgb，alpha 独立计算
uniform float uInteriorDim;      // 主体内部格线的 rgb 系数，1 使用恒等亮度
                                 // 关闭时保留内部格线原亮度；
                                 // 按 tubeEdge 阈值作用内部格线 rgb，alpha 独立；
                                 // 描边 pass 保留轮廓亮度
                                 // 阈值 4.0 与 Java 侧 ChainPreviewShaderMath.INTERIOR_TUBEEDGE_THRESHOLD
                                 // 同源：0..3 = 贯通管面槽位（外轮廓），255 = AUX_UNDEFINED（内部格线）

// Java 提供六槽语义色；builtin 的 CHAIN 槽保留精确 float 基色，其余取配置默认 RGB。
uniform vec3 uColorChain;
uniform vec3 uColorArea;
uniform vec3 uColorInteract;
uniform vec3 uColorSecondary;
uniform vec3 uColorRemote;
uniform vec3 uColorTruncated;

varying vec4 vColor;

/** 还原 0..255 的量化通道：normalized uint8 attribute × 255 再四舍五入。 */
float auxChannel(float value) {
    return floor(value * 255.0 + 0.5);
}

/**
 * 按 ChainPreviewSemanticClass 的稳定 id 选择颜色，未知值使用 CHAIN 色。
 * 顶点选色使 smooth varying 只插值颜色；GLSL 1.20 的类别数值插值会落在整数之间。
 * 类别 id 与 Java 常量同步维护。
 */
vec3 previewSemanticColor(float semanticClass) {
    if (semanticClass == 1.0) {
        return uColorArea;
    }
    if (semanticClass == 2.0) {
        return uColorInteract;
    }
    if (semanticClass == 3.0) {
        return uColorSecondary;
    }
    if (semanticClass == 4.0) {
        return uColorRemote;
    }
    if (semanticClass == 5.0) {
        return uColorTruncated;
    }
    return uColorChain;
}

/**
 * 与 ChainPreviewMeshBuilder.faceShading 保持同一面系数表和判定顺序。
 * 字面常量乘法保留 CPU/GPU 的舍入顺序，零方向使用恒等亮度。
 */
float faceShading(vec3 normal) {
    if (normal.y > 0.5) {
        return 1.00;
    }
    if (normal.y < -0.5) {
        return 0.72;
    }
    if (normal.z > 0.5) {
        return 0.90;
    }
    if (normal.z < -0.5) {
        return 0.84;
    }
    if (normal.x > 0.5 || normal.x < -0.5) {
        return 0.78;
    }
    return 1.00;
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
    if (uFadeStart < uFadeEnd) {
        fade = fadeAlpha(length(cameraRelative));
    }

    // 生长与连锁序亮度共享一次 appearOrder 解码，任一消费启用时读取。
    // 未定义序号保持已出现与恒等亮度。
    float growth = 1.0;
    float orderWeight = 1.0;
    bool growthActive = uAnimProgress < 1.0 && uAppearSpan > 0.0;
    bool orderWeightActive = uOrderMinBrightness < 1.0 && uAppearSpan > 0.0;
    if (growthActive || orderWeightActive) {
        float appearOrder = auxChannel(aAux.z) + auxChannel(aAux.w) * 256.0;
        if (appearOrder < 65535.0) {
            // 两个消费者共用序号格，保持条柱内生长和亮度的边界一致。
            float orderFloor = floor(min(appearOrder, uAppearSpan));
            if (growthActive) {
                // 在序号格内形成有界线性生长完成度。
                growth = clamp(uAnimProgress * uAppearSpan - orderFloor, 0.0, 1.0);
            }
            if (orderWeightActive) {
                // 目标总数归一化序号，沿起点到远端递减 rgb 权重。
                float t = clamp(orderFloor / max(uAppearSpan, 1.0), 0.0, 1.0);
                orderWeight = 1.0 - (1.0 - uOrderMinBrightness) * t;
            }
        }
    }
    // 逻辑版本的实机覆盖范围见头部既有记录。

    // 显式面法线决定外扩方向；零方向保留原顶点位置，覆盖 junction 和跨轴连接。
    vec3 displaced = aPos;
    float pixelsPerWorldUnit = max(uPixelScale / max(1e-4, -(uModelView * vec4(aPos, 1.0)).z), 1e-6);
    // 世界位移预算与 ChainPreviewShaderMath.maxWidenWorld 同源，控制相邻条柱占用。
    float maxWidenWorld = max(0.0, 0.5 - uBarThickness);
    // 最小宽度优先占用共享预算，描边仅使用剩余额度。
    float minWidthWiden = 0.0;
    if (uMinScreenWidthPx > 0.0) {
        // 按条柱总厚度计算投影宽度，再换算单侧外扩量。
        float widthPx = max(uBarThickness * pixelsPerWorldUnit, 1e-6);
        minWidthWiden = min(0.5 * uBarThickness * max(0.0, uMinScreenWidthPx / widthPx - 1.0), maxWidenWorld);
    }
    displaced = aPos + aDirection.xyz * minWidthWiden;
    if (uOutlineWidthPx > 0.0) {
        // 最小宽度占满预算时，描边外扩归零。
        float remainingWiden = max(0.0, maxWidenWorld - minWidthWiden);
        displaced = displaced + aDirection.xyz * min(uOutlineWidthPx / pixelsPerWorldUnit, remainingWiden);
    }

    // 共享预算参考模型见 ChainPreviewShaderMath.displaceVertex / outlineWidenWithinBudget。
    // GLSL 逻辑变更先经 glslang 编译，再核对实机位移；后端回退可能掩盖 shader 编译失败。

    // 非预乘颜色交 GL_SRC_ALPHA 混合；alpha 仅组合距离、生长与全局包络。
    float alpha = fade * growth * uFadeAlpha;
    // 描边统一用 CHAIN 色，其余按顶点语义色输出。
    vec3 color = previewSemanticColor(auxChannel(aAux.x));
    if (uOutlineWidthPx > 0.0) {
        color = uColorChain;
    }
    // 按与 legacy 相同的面系数表应用亮度。
    if (uFaceShading > 0.5) {
        color = color * faceShading(aDirection.xyz);
    }
    // 连锁序权重独立作用 rgb，保留先面明暗、后序号权重的乘法顺序。
    color = color * orderWeight;
    // 主体的非管面顶点应用内部亮度；0..3 管面与描边保持轮廓亮度，alpha 独立。
    if (uInteriorDim < 1.0 && uOutlineWidthPx <= 0.0 && auxChannel(aAux.y) >= 4.0) {
        color = color * uInteriorDim;
    }
    // 内部亮度逻辑的实机覆盖按头部对应版本记录核对。
    vColor = vec4(color, alpha);

    // 投影使用已外扩的位置和显式 MVP，保留最小宽度及描边位移。
    gl_Position = uModelViewProjection * vec4(displaced, 1.0);
}
