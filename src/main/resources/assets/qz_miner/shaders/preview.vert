#version 120

/*
 * 连锁预览条柱 · 顶点主路径（GL 2.1 / GLSL 1.20 基线）
 *
 * 顶点属性契约（接口冻结文档 §A）：
 *   attribute 0 aPos   3 x float32  相对 meshOrigin 的方块坐标 + 偏移 x barThickness
 *   attribute 3 aDirection 4 x int8(normalized) 显式面方向（xyz；每组面一个顶点，恒为单位面法线）
 *   attribute 1 aAux   4 x uint8 normalized
 *                        x = semanticClass（0..255，255 = 未定义）
 *                        y = tubeEdge（0..3，255 = 未定义）
 *                        z/w = appearOrder u16 小端（0xFFFF = 未定义）
 *
 *   **§A 修订（T51）**：原契约的「attribute 2 aColor 4 x float32（既有颜色流）」已从着色器路径移除。
 *   那份颜色流只服务 legacy 固定管线（其逐顶点 α 是 CPU 烘焙值）；着色器路径的颜色由
 *   aAux.semanticClass + uColor* 调色板在顶点阶段决定，从不读取 aColor——编译器因此把它整体优化掉
 *   （location = -1），契约里「保留该槽」的写法与实现不符，还会让下游以为这份颜色流仍参与计算。
 *   槽位编号同样不再写进契约：GLSL 1.20 无 layout 限定符，槽位是链接期事实，由
 *   ChainPreviewShaderProgram#resolveAttributeLocations 运行时解析，见
 *   docs/反馈层/errors/ERROR-20260914-preview-attribute-slot-assumption.md。
 *
 * 功能优先级与落点（接口冻结文档 §F）：
 *   1) 距离淡出      —— 顶点侧按 quadratic 曲线写入 vColor.a，片元直用，零 CPU 上传
 *   2) 屏幕最小宽度  —— 顶点侧沿面法线 aDirection 外扩，把条柱的屏幕投影宽抬到 uMinScreenWidthPx 像素；
 *                        与真描边**共用同一份世界空间预算** maxWidenWorld：最小宽度优先取用，
 *                        描边只能使用剩余额度（见 main() 与 ChainPreviewShaderMath.maxWidenWorld）
 *   3) 逐波生长      —— 读 aAux 的 appearOrder 归一化后与 uAnimProgress 逐顶点比较（不要求索引有序）
 *   4) 语义颜色      —— 顶点按 semanticClass 选 uColor* 并写进 vColor.rgb（片元只做插值输出）
 *                        选色必须在顶点：varying 是 smooth 插值的，片元用 == 比较会丢色（F1）
 *   5) 亚像素柔化    —— 横向屏幕宽度不足时收敛边缘 alpha
 *   6) 真描边（B3.x）—— OUTLINE 档的描边壳段沿面法线外扩 uOutlineWidthPx，且只使用最小宽度未占用的
 *                        剩余预算（仅着色器路径；预算耗尽时精确为 0）
 *   7) 面朝向明暗（face shading）—— 顶点按 aDirection.xyz 查表乘进 color；**默认关闭**（uFaceShading=0），
 *                        开启后与 legacy 颜色流用同一张亮度表（字面常量 × 同一 palette 常量）
 *   8) 相机矩阵       —— 一律走显式 uniform uModelViewProjection / uModelView（T48c-A）：
 *                        真机（GLSM 模拟固定管线 + no-error context）内建矩阵与真实相机矩阵失同步，
 *                        且失败不可观测；显式化后矩阵可读、可断言，后端可自检并回退 legacy
 *        **能力差异（登记）**：auto 档回退 legacy 时 OUTLINE 没有真描边，退化为既有
 *        「两 pass 叠色」行为——固定管线做外扩必须改 CPU 几何，会破坏 B4.1 的增量/差分等价。
 *
 * 距离淡出必须与 CPU 端 ChainPreviewMeshBuilder.VisualParameters.alphaFor 的 quadratic
 * 形状一致（d <= fadeStart → uMaxAlpha；d >= fadeEnd → uMinAlpha；之间按 t^2 插值），
 * 否则 legacy 与 shader 两档观感分叉。
 *
 * 实机验证记录（本节是注释：**注释改动不触发重验**；只有 GLSL 逻辑变化才需要重验）
 *   理由：加/改本标记本身若算「逻辑改动」，标记就永远落不下来——会形成死循环。
 *   口径：改 GLSL 逻辑 → 真机确认无异常 → 在列表末尾追加一行（并按仓库规范跑完整 build）。
 *   格式：@ <短commit> <日期> <验证了什么>
 *
 *    @ c390b681 2026-09-14  布局 / 最小宽度 / 真描边 / 面朝向明暗 —— 真机无异常
 */

attribute vec3 aPos;
// T51：byte x4 归一化属性（xyz = 面法线，w = 对齐保留位），GL 按 c/127 映射到 [-1, 1]。
attribute vec4 aDirection;
attribute vec4 aAux;

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
uniform float uFaceShading;      // 面朝向明暗（face shading）开关：0 = 关闭（默认，等于接线前观感），1 = 开启
                                 // 关闭时必须**完全不进入乘色分支**，保证逐字节等于现状

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

/**
 * 面朝向亮度系数（view-independent face shading）：与 Java 侧
 * club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder#faceShading 同表、同判定顺序。
 *
 * 取值（顶 1.00 / +Z 0.90 / −Z 0.84 / ±X 0.78 / 底 0.72）与 Java 侧逐字相同：两侧都是
 * 「同一个十进制字面常量 × 同一 palette 常量」的单次 IEEE 单精度乘法，故结果逐位一致。
 * 系数一旦改成表达式（mix / lerp / 点积），这条性质立即失效——两侧会因运算顺序分叉。
 *
 * 零方向 / 未定义方向返回 1.0：不得压暗无方向顶点（T51 后顶点按面分裂，正常路径不产生）。
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
    // 屏幕最小宽度与描边沿 Mesh 提供的显式面方向位移；零方向顶点恒等退化，
    // 保证共享多面顶点不会被错误推向任一轴。
    vec3 displaced = aPos;
    float pixelsPerWorldUnit = max(uPixelScale / max(1e-4, -(uModelView * vec4(aPos, 1.0)).z), 1e-6);
    // 世界空间位移预算（A2）：**最小宽度与真描边共用同一份**，单一真源是
    // ChainPreviewShaderMath.maxWidenWorld（= max(0.0, 0.5 - uBarThickness)）。
    // 推导（接口冻结 §L / F-1 独立复算）：条柱自身厚度 t 与单侧外扩量 w 共同计入相邻条柱的占用，
    // 中心距 1 格时既有口径的间隙式 1 - 2(t + w) 在上界处恰好为 0；而真实几何到达半径
    // t/2 + w = 0.5 - t/2 < 0.5，比「不越出自身方块」更保守。不用固定 0.5 是因为默认厚度下
    // 相邻条柱会重叠 0.09 格。上界把它收敛到 0.955 格总宽（t=0.045），仍不粘连。
    float maxWidenWorld = max(0.0, 0.5 - uBarThickness);
    // 联合上界（T52 已修）：两项位移**消耗同一份预算**，故联合位移恒 <= maxWidenWorld。
    // Lead 裁定「最小宽度优先、描边让位」：最小宽度是功能性需求（保证远距可见性），真描边是
    // 装饰性的，两者争同一份世界空间预算时功能优先。修复前两者各自取 maxWidenWorld，
    // 联合可达 2×(0.5 - t)：t=0.045 时单侧位移 0.91 格、到达半径 0.9325 格，越出自身方块
    // 并使相邻条柱重叠（Python 复算见工作站 temp/qz-miner-t52-joint-budget.py）。
    float minWidthWiden = 0.0;
    if (uMinScreenWidthPx > 0.0) {
        // A1 修复：条柱的屏幕投影宽 = **总厚度** × pixelsPerWorldUnit。
        // 旧式写 2.0 * uBarThickness 把激活阈值抬到 2t×ppwu、位移量减半，
        // 于是配置 minW 只能交付 minW/2 像素（8px 档实测约 4px，Python 复算见
        // temp/qz-miner-minwidth-a1a2-recheck.py）。
        float widthPx = max(uBarThickness * pixelsPerWorldUnit, 1e-6);
        minWidthWiden = min(0.5 * uBarThickness * max(0.0, uMinScreenWidthPx / widthPx - 1.0), maxWidenWorld);
    }
    displaced = aPos + aDirection.xyz * minWidthWiden;
    if (uOutlineWidthPx > 0.0) {
        // 描边只能用最小宽度未占用的剩余额度：min-width 吃满预算时此处精确为 0（让位而非叠加）。
        float remainingWiden = max(0.0, maxWidenWorld - minWidthWiden);
        displaced = displaced + aDirection.xyz * min(uOutlineWidthPx / pixelsPerWorldUnit, remainingWiden);
    }

    // 历史做法（从 aPos 的绝对值近似横向轴）已彻底移除：横向轴只认 Mesh 显式提供的 aDirection。
    //
    // T49 曾用 if (false) 占位保留那段代码，理由是 GLSL 对 if (false && …) 不做死代码豁免、
    // 被关掉的语句同样要过语义检查，于是占位块能顺带护住 uniform 的声明与链接检查。该理由
    // 已被取代：上面的活跃位移实打实引用了 uModelView / uPixelScale / uBarThickness，
    // 保护作用由活跃分支承担，占位块遂删除（它同时是「读起来像在用 lateralAxis」的误导源）。
    //
    // 已修登记（T52 联合上界）：上面的消耗式预算就是原先的「未决点」——最小宽度与真描边不再
    // 各自取上界，而是共用 maxWidenWorld，联合位移恒 <= 该上界，min-width 吃满时描边让位为 0。
    // 参考模型 ChainPreviewShaderMath.displaceVertex / outlineWidenWithinBudget 与之同形；
    // 回归锁见 ChainPreviewShaderOutlineTest#combinedDisplacementNeverExceedsTheSharedWorldBudget
    // 与 #outlineYieldsToMinWidthWhenBudgetIsExhausted（t=0.045 时旧行为到达 0.9325 格已不复现）。
    //
    // 本次是 GLSL **逻辑**变更：按头部「实机验证记录」口径，需真机确认无异常后才追加标记行；
    // 现有标记行 c390b681 覆盖的是本次变更之前的行为，不得被读作已覆盖本变更。
    //
    // 教训保留：T49 曾因误删 pixelsPerWorldUnit 声明导致 GLSL 编译失败，再被回退链吞成
    // 「观感正常」。改动本段后必须过 glslang 闸门，且必须确认上方位移在真机上生效。

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
    // 面朝向明暗：门控关闭（默认，uFaceShading = 0）时整段不执行 ⇒ 输出逐字节等于现状。
    // 开启时乘的是与 legacy 颜色流同一张表的同一组字面常量（见 faceShading 函数）。
    if (uFaceShading > 0.5) {
        color = color * faceShading(aDirection.xyz);
    }
    vColor = vec4(color, alpha);

    // 关键：必须对 displaced 做投影。此前这里写 ftransform()（内部用 aPos），
    // 使上面的横向钳制算完即丢——B2.1 最小宽度在 shader 路径静默失效。
    // MVP 来自显式 uniform（uModelViewProjection）；内建 gl_ModelViewProjectionMatrix 在真机
    // 环境下与真实相机矩阵失同步，见文件头部 uniform 段与 §F 的能力差异登记。
    gl_Position = uModelViewProjection * vec4(displaced, 1.0);
}
