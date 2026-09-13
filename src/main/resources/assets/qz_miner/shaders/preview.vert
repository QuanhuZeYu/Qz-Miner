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
 *            shader 路径不再消费其 rgb：颜色由 uColor* + semanticClass 在顶点阶段决定。
 *            属性槽保留（契约 §A 与 VAO 布局不动），避免拆槽带来的绑定/兼容风险。
 *
 * 功能优先级与落点（接口冻结文档 §F）：
 *   1) 距离淡出      —— 顶点侧按 quadratic 曲线写入 vColor.a，片元直用，零 CPU 上传
 *   2) 屏幕最小宽度  —— 顶点侧沿横向偏移等比放大，与 glTranslated 相机相对坐标一致
 *   3) 逐波生长      —— 读 aAux 的 appearOrder 归一化后与 uAnimProgress 逐顶点比较（不要求索引有序）
 *   4) 语义颜色      —— 顶点按 semanticClass 选 uColor* 并写进 vColor.rgb（片元只做插值输出）
 *                        选色必须在顶点：varying 是 smooth 插值的，片元用 == 比较会丢色（F1）
 *   5) 亚像素柔化    —— 横向屏幕宽度不足时收敛边缘 alpha
 *   6) 真描边（B3.x）—— OUTLINE 档的描边壳段沿横向轴外扩 uOutlineWidthPx（仅着色器路径）
 *   7) 相机矩阵       —— 一律走显式 uniform uModelViewProjection / uModelView（T48c-A）：
 *                        真机（GLSM 模拟固定管线 + no-error context）内建矩阵与真实相机矩阵失同步，
 *                        且失败不可观测；显式化后矩阵可读、可断言，后端可自检并回退 legacy
 *        **能力差异（登记）**：auto 档回退 legacy 时 OUTLINE 没有真描边，退化为既有
 *        「两 pass 叠色」行为——固定管线做外扩必须改 CPU 几何，会破坏 B4.1 的增量/差分等价。
 *
 * 距离淡出必须与 CPU 端 ChainPreviewMeshBuilder.VisualParameters.alphaFor 的 quadratic
 * 形状一致（d <= fadeStart → uMaxAlpha；d >= fadeEnd → uMinAlpha；之间按 t^2 插值），
 * 否则 legacy 与 shader 两档观感分叉。
 */

attribute vec3 aPos;
attribute vec4 aAux;
attribute vec4 aColor;

// 相机矩阵（T48c-A）：显式 uniform，由 Java 侧每帧从固定管线栈读取、CPU 相乘后上传。
// 刻意不使用 gl_ModelViewProjectionMatrix / gl_ModelViewMatrix——在「固定管线由 Angelica GLSM
// 用生成着色器模拟 + use_no_error_g_l_context=true」的真机环境下，这两个内建矩阵与真实相机矩阵
// 失同步（整条预览链会被画进错误空间，表现为紧凑一束条柱），且失效时完全不可观测。
// 显式上传后矩阵可读、可断言：后端用「modelview 平移列模长 ≈ |origin − renderPos|」自检来源。
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
uniform float uFadeAlpha;        // 淡入淡出包络（B3.2）[0,1]；1 = 完全不透明（默认档）
                                 // 宿主每帧显式置 1，避免 uniform 未设时默认 0 导致整链透明
uniform float uOutlineWidthPx;   // 真描边（B3.x）外扩宽度（物理像素）；0 = 关闭描边
                                 // xray / occlude 与 OUTLINE 主体 pass 都必须为 0 ⇒ 逐值等于现状

// 语义调色板（按 aAux.x 的 semanticClass 选择，见 §D 类别表）。
// builtin 档四色都是精确基线常量 (0.25, 0.9, 1.0) ⇒ 输出逐字节等于现状。
uniform vec3 uColorPrimary;
uniform vec3 uColorSecondary;
uniform vec3 uColorRemote;
uniform vec3 uColorTruncated;

varying vec4 vColor;

/** 还原 0..255 的量化通道：normalized uint8 attribute × 255 再四舍五入。 */
float auxChannel(float value) {
    return floor(value * 255.0 + 0.5);
}

/**
 * 语义类别 → 颜色，与接口冻结 §D 类别表逐条对应（task-16 冻结值域）：
 *   0 PRIMARY_LOCAL → uColorPrimary
 *   1 SUB_MODE_LOCAL → uColorSecondary
 *   2 REMOTE_PREDICTED → uColorRemote
 *   3 TRUNCATED → uColorTruncated（本轮数据源不产出，保留合法分支）
 *   4 DEFERRED / 5 EXECUTED / 255 UNDEFINED 及任何未知值 → uColorPrimary 兜底
 *
 * <p><b>必须在顶点阶段选色</b>：varying 是 smooth 插值的，一个 quad 内若两顶点类别不同
 * （共享角点取相邻目标的最小类别序），插值结果会落在两整数之间——片元里用
 * {@code vSemantic == 2.0} 精确比较会整片落空、丢失远端/截断色。GLSL 1.20 没有 flat
 * 限定符，所以颜色本身必须逐顶点定下来，片元只用插值后的 vColor.rgb。</p>
 *
 * 类别常量与 Java 侧 {@code club.heiqi.qz_miner.chain.client.ChainPreviewSemanticClass}
 * 同源；GLSL 无法共享 Java 常量，改动时两处必须同步（GLSL 侧保留字面量）。
 */
vec3 previewSemanticColor(float semanticClass) {
    if (semanticClass == 1.0) {
        return uColorSecondary;
    }
    if (semanticClass == 2.0) {
        return uColorRemote;
    }
    if (semanticClass == 3.0) {
        return uColorTruncated;
    }
    return uColorPrimary;
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

    // 几何位置必须保持与 legacy 完全一致。aPos 已经是 MeshBuilder 生成的
    // 条柱/连接块顶点，不能从其相对 origin 的绝对坐标猜测横向轴：长条端点、
    // junction 和跨轴线段都会被误判，导致整面被推离原始几何。
    // 屏幕最小宽度与描边暂不在 shader 顶点阶段改写拓扑几何；这些功能必须基于
    // Mesh 明确提供的 tubeEdge/方向数据重新实现，不能用 aPos 近似替代。
    vec3 displaced = aPos;

    // 保留最小宽度/描边的契约算法，但在方向元数据接入前关闭几何位移。
    // aPos 是世界局部坐标，不能可靠推断条柱横向轴；错误推断会把整面推离 Mesh。
    // 后续启用条件应改为 Mesh 明确提供的方向/边数据，而不是删除这些接口。
    // T49：该声明在 5d2e0008 被误删（使用点仍在），导致 GLSL 编译失败并被回退链吞成「观感正常」。
    // GLSL 对 if (false && …) 不做死代码豁免，被关掉的语句同样要过语义检查——声明必须保留。
    float pixelsPerWorldUnit = 1.0;
    float pixelPerUnitAtDepth = 1.0;
    float lateralMagnitude = 0.0;
    vec3 lateralAxis = vec3(0.0, 0.0, 0.0);
    if (false && (uMinScreenWidthPx > 0.0 || uOutlineWidthPx > 0.0)) {
        float depth = max(1e-4, -(uModelView * vec4(aPos, 1.0)).z);
        pixelPerUnitAtDepth = uPixelScale / depth;
        float magnitudeX = abs(aPos.x);
        float magnitudeY = abs(aPos.y);
        float magnitudeZ = abs(aPos.z);
        lateralMagnitude = min(magnitudeX, min(magnitudeY, magnitudeZ));
        if (lateralMagnitude <= 0.02 * max(uBarThickness, 1e-4)) {
            lateralAxis = vec3(0.0, 0.0, 0.0);
        } else if (magnitudeX <= magnitudeY && magnitudeX <= magnitudeZ) {
            lateralAxis = vec3(sign(aPos.x), 0.0, 0.0);
        } else if (magnitudeY <= magnitudeZ) {
            lateralAxis = vec3(0.0, sign(aPos.y), 0.0);
        } else {
            lateralAxis = vec3(0.0, 0.0, sign(aPos.z));
        }
    }
    if (false && lateralMagnitude > 0.0) {
        float worldLateral = length((uModelView * vec4(lateralAxis, 0.0)).xyz);
        float projectedPerUnit = clamp(worldLateral, 0.05, 1.0);
        pixelsPerWorldUnit = max(pixelPerUnitAtDepth * projectedPerUnit, 1e-6);
        float lateralWidthPx = 2.0 * lateralMagnitude * pixelsPerWorldUnit;
        float widen = 1.0;
        if (uMinScreenWidthPx > 0.0) {
            widen = clamp(uMinScreenWidthPx / max(lateralWidthPx, 1e-6), 1.0, 64.0);
        }
        displaced = aPos + lateralAxis * (lateralMagnitude * (widen - 1.0));
    }
    if (uOutlineWidthPx > 0.0 && lateralMagnitude > 0.0) {
        float outlinePx = clamp(uOutlineWidthPx, 0.0, 8.0);
        float outlineWorld = outlinePx / pixelsPerWorldUnit;
        outlineWorld = clamp(outlineWorld, 0.0, max(0.0, 0.5 - uBarThickness));
        displaced = displaced + lateralAxis * outlineWorld;
    }

    // 颜色与 alpha：vColor.rgb 是「语义类别色」（非预乘），alpha 单独传给混合。
    // 共用混合是 glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)（两后端共用）：
    // 预乘 alpha 会让最终 src 变成 rgb × alpha²（alpha=0.15 时 0.0225 vs 0.15）。
    // 最终 alpha = 距离淡出 × 逐波生长 × 淡入淡出包络（L5）。
    // uFadeAlpha = 1 时与启用动画前逐值一致（乘 1 不改变结果）。
    float alpha = fade * growth * uFadeAlpha;
    // 描边 pass 用主色（outline 轮廓统一色，不参与语义分类）；其余情况按 semanticClass 取色。
    // 取色仍在顶点阶段（F1），alpha 包络 fade × growth × uFadeAlpha 不受描边分支影响。
    vec3 color = previewSemanticColor(auxChannel(aAux.x));
    if (uOutlineWidthPx > 0.0) {
        color = uColorPrimary;
    }
    vColor = vec4(color, alpha);

    // 关键：必须对 displaced 做投影。此前这里写 ftransform()（内部用 aPos），
    // 使上面的横向钳制算完即丢——B2.1 最小宽度在 shader 路径静默失效。
    // MVP 来自显式 uniform（uModelViewProjection）；内建 gl_ModelViewProjectionMatrix 在真机
    // 环境下与真实相机矩阵失同步，见文件头部 uniform 段与 §F 的能力差异登记。
    gl_Position = uModelViewProjection * vec4(displaced, 1.0);
}
