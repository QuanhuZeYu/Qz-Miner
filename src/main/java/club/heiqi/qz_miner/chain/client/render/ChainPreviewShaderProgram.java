package club.heiqi.qz_miner.chain.client.render;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import club.heiqi.uilib.gl.shader.ShaderProgramSupport;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * 连锁预览着色器程序：{@code preview.vert} + {@code preview.frag} 的编译、链接、验证与 uniform 缓存。
 *
 * <p>编译/链接/验证一律走 UILib 的 {@link ShaderProgramSupport}（{@code readText} /
 * {@code compileShader} / {@code linkAndValidateProgram}），本类<strong>不</strong>实现第二套
 * GLSL 编译链接逻辑，也不重复实现 uniform 缓存语义（沿用 UILib 的
 * 「命中缓存 → 未命中查询 → 缺失记入 missing 集合」三段式，缺失 uniform 静默跳过）。</p>
 *
 * <p>失败语义（功能优先级与落点（真源：preview.vert 头部落点表））：{@link #ensureReady()} 只尝试一次；失败后置
 * {@link #isUnavailable()} 为 true，后续调用直接返回 false，<strong>不会每帧重试</strong>。
 * 所有 GL 异常在此处被捕获，绝不向渲染帧抛出。原因保存在 {@link #getLastFailureMessage()}，
 * 由后端写进 {@code describe()}。</p>
 *
 * <p><strong>相机矩阵（T48c-A）</strong>：着色器不再依赖固定管线内建
 * {@code gl_ModelViewProjectionMatrix} / {@code gl_ModelViewMatrix}（真机 GTNH 2.9 + Angelica
 * GLSM 模拟固定管线 + no-error context 下内建数与真实相机矩阵失同步）。改由
 * {@link #readCameraMatrices} 每帧从 {@code GL_PROJECTION_MATRIX} / {@code GL_MODELVIEW_MATRIX}
 * 读回，后端在 CPU 侧相乘成 MVP 后经 {@link #setModelViewProjection} / {@link #setModelView}
 * 上传；后端另做「modelview 平移列模长 ≈ |origin − renderPos|」自检，失败即一次性不可用并回退 legacy。</p>
 *
 * <p>线程契约：所有方法只在渲染线程调用（GPU 资源释放必须走渲染线程）。</p>
 */
public final class ChainPreviewShaderProgram {

    private static final String VERTEX_RESOURCE = "assets/qz_miner/shaders/preview.vert";
    private static final String FRAGMENT_RESOURCE = "assets/qz_miner/shaders/preview.frag";

    private static final String READ_ERROR_PREFIX = "读取预览着色器失败: ";
    private static final String COMPILE_VERTEX_ERROR_PREFIX = "预览顶点着色器编译失败: ";
    private static final String COMPILE_FRAGMENT_ERROR_PREFIX = "预览片元着色器编译失败: ";
    private static final String LINK_ERROR_PREFIX = "预览着色器链接失败: ";
    private static final String VALIDATE_ERROR_PREFIX = "预览着色器验证失败: ";

    private static final int ATTRIB_POSITION = 0;
    private static final int ATTRIB_AUX = 1;
    /** 外扩方向槽位的「请求」编号（T51）。编号本身不进入契约：实际槽位以运行时查询为准。 */
    private static final int ATTRIB_DIRECTION = 2;

    /** T50 运行时解析出的属性槽位（链接后查询；-1 = 未解析或被优化掉）。 */
    private int positionAttributeLocation = -1;
    private int auxAttributeLocation = -1;
    private int directionAttributeLocation = -1;

    private static final String MISSING_UNIFORM_PREFIX = "预览着色器缺少必备 uniform: ";

    /**
     * 必备 uniform：链接后 location 为 -1 即视为程序不可用（T48c-C）。
     *
     * <p>为什么必须硬失败：{@link #setUniformMatrix4} / {@link #setUniform1f} 对 missing 的 uniform 是
     * 「静默跳过上传」。若 {@code uModelViewProjection} 因链接/优化行为缺失，shader 会拿默认零矩阵绘制，
     * 而后端的平移列自检读的是<b>驱动矩阵</b>（不是 uniform 值）⇒ <b>自检通过、画面全错</b>。
     * 链接完成后一次性校验即可封死这条通路。</p>
     *
     * <p><b>清单分两级（T49）</b>：{@link #REQUIRED_UNIFORMS} 缺失即整体不可用；
     * {@link #CAPABILITY_UNIFORMS} 缺失只登记「该能力关闭」。原先不分级时，把「按契约保留但当前
     * 关闭」的分支所引用的 uniform 也当必备，会让整个着色器后端被判不可用——2026-09-13 真机
     * 「shader 档什么都不画」正是这条：{@code if (false && …)} 死块里的 uModelView / uPixelScale /
     * uMinScreenWidthPx 被 GLSL 编译器优化掉（规范允许），location = -1 ⇒ 校验抛异常 ⇒ 程序不可用。</p>
     */
    private static final String[] REQUIRED_UNIFORMS = {
        "uModelViewProjection",
        "uOriginRel",
        "uFadeStart",
        "uFadeEnd",
        "uMinAlpha",
        "uMaxAlpha",
        "uAnimProgress",
        "uAppearSpan",
        "uFadeAlpha",
        "uColorChain",
        "uColorArea",
        "uColorInteract",
        "uColorSecondary",
        "uColorRemote",
        "uColorTruncated",
        // T51：以下四项随「aDirection 位移」从能力型升为必备型——最小屏幕宽度与真描边现在
        // 是活代码（不再被 if (false && …) 包住），编译器不会再优化掉它们；缺任一即无法算出
        // 正确的外扩量，宁可判程序不可用回退 legacy，也不画出偏移错误的几何。
        "uModelView",
        "uPixelScale",
        "uBarThickness",
        "uMinScreenWidthPx",
        "uOutlineWidthPx",
        // 面朝向明暗：开关本身是活引用（uFaceShading > 0.5 门控乘色分支），
        // 缺失会让「开着明暗但常量没传」静默退化成关闭态——宁可判程序不可用回退 legacy。
        "uFaceShading",
        // 连锁序渐弱：门控本身是活引用（uOrderMinBrightness < 1.0 才进入 orderWeight 分支），
        // 缺失会让「配置了非 1.0 的下限」静默退化成恒等——宁可判程序不可用回退 legacy，
        // 也不画出一份与配置不符、且看不出哪里不对的亮度分布。
        "uOrderMinBrightness",
        // 内部结构压暗：门控本身是活引用（uInteriorDim < 1.0 且非描边 pass 才进入乘色分支），
        // 缺失会让「配置了非 1.0 的系数」静默退化成恒等——宁可判程序不可用回退 legacy，
        // 也不画出一份与配置不符、且看不出哪里不对的内部/轮廓对比。
        "uInteriorDim",
    };

    /**
     * 能力型 uniform：只被「按契约保留、但当前关闭」的分支引用（{@code if (false && …)} 或恒假路径）。
     *
     * <p>GLSL 规范允许编译器优化掉未被使用的 uniform ⇒ {@code glGetUniformLocation} 返回 -1。
     * 这类 uniform 缺失是预期状态，不得据此判定程序不可用。</p>
     *
     * <p><b>T51 现状：清单为空。</b>原属本级的 uModelView / uPixelScale / uBarThickness /
     * uMinScreenWidthPx 已随 aDirection 位移升为 {@link #REQUIRED_UNIFORMS}（否则位移量算错也没人发现）；
     * uOutlineWidthPx 一直是活引用（描边壳段与主色分支都用它）。本级保留为空数组是为了让
     * 「新 uniform 必须登记」的契约检查继续有明确落点：将来再出现「契约保留但当前关闭」的能力，
     * 仍按同样方式登记在这里并出现在 {@link #getCapabilityReport()} 里。</p>
     */
    private static final String[] CAPABILITY_UNIFORMS = {};

    private final Map<String, Integer> uniformLocations = new LinkedHashMap<String, Integer>();
    private final Set<String> missingUniforms = new HashSet<String>();

    private boolean initialized;
    private boolean unavailable;
    private int initializationAttempts;
    private int shaderProgramId;
    private int vertexShaderId;
    private int fragmentShaderId;
    private String lastFailureMessage = "";
    /** 是否发生过矩阵 uniform 缺失（诊断用；正常路径恒为 false）。 */
    private boolean missingMatrices;

    /** 能力型 uniform 的对账结果（缺失 ⇒ 对应能力关闭；见 {@link #CAPABILITY_UNIFORMS}）。 */
    private String capabilityReport = "capabilities=unknown";

    /** @return 能力型 uniform 的对账结果，供后端 describe() 与真机日志一眼可见。 */
    public String getCapabilityReport() {
        return capabilityReport;
    }

    /** @return 硬必备清单副本（包内可见，供契约测试对账「新 uniform 必须登记」）。 */
    static String[] requiredUniforms() {
        return REQUIRED_UNIFORMS.clone();
    }

    /** @return 能力型清单副本（包内可见，供契约测试对账「新 uniform 必须登记」）。 */
    static String[] capabilityUniforms() {
        return CAPABILITY_UNIFORMS.clone();
    }

    /**
     * 惰性初始化：编译、链接、验证并绑定固定属性槽位。
     *
     * @return 是否可用；false 时 {@link #getLastFailureMessage()} 为原因
     */
    public boolean ensureReady() {
        if (unavailable) {
            return false;
        }
        if (initialized) {
            return shaderProgramId != 0;
        }
        initialized = true;
        initializationAttempts++;
        try {
            shaderProgramId = GL20.glCreateProgram();
            if (shaderProgramId == 0) {
                throw new IllegalStateException("glCreateProgram 返回 0");
            }
            uniformLocations.clear();
            missingUniforms.clear();
            // 属性槽位必须在 glLinkProgram 之前绑定，链接后再绑对已链接程序无效。
            bindAttributeLocations();
            compileAndLink();
            // 安全初值：uniform 未赋值时为 0，会让 uFadeAlpha 把整链 alpha 归零。
            setFadeAlpha(INITIAL_FADE_ALPHA);
            // 同类安全初值：0 对本 uniform 不是恒等（见 INITIAL_ORDER_MIN_BRIGHTNESS），必须写 1.0。
            setOrderMinBrightness(INITIAL_ORDER_MIN_BRIGHTNESS);
            // 同上：0 会把内部格线直接压成纯黑（见 INITIAL_INTERIOR_DIM），必须写 1.0。
            setInteriorDim(INITIAL_INTERIOR_DIM);
            // 必备 uniform 校验放在最后：缺失即抛 ⇒ 由下方 catch 收敛为「程序不可用」⇒ 后端一次性回退。
            verifyRequiredUniforms();
            // T50：属性槽位同样必须问驱动要，不能假设 0/1/2（原因见 resolveAttributeLocations）。
            resolveAttributeLocations();
            return true;
        } catch (Throwable failure) {
            // 编译 / 链接 / 验证失败，甚至 LWJGL native 不可用（UnsatisfiedLinkError /
            // ExceptionInInitializerError）都收敛为「不可用」，绝不向渲染帧抛出；
            // 失败后 unavailable=true，后续调用不再重试。
            unavailable = true;
            lastFailureMessage = failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.toString();
            return false;
        } finally {
            if (unavailable) {
                // 清理本身也可能失败（GL 上下文不可用），必须在独立 catch 内收敛，
                // 否则「失败返回 false」会退化成「失败再抛一次」。
                try {
                    releaseResources();
                } catch (Throwable cleanupFailure) {
                    // 忽略：资源已在驱动侧随上下文失效，句柄本轮作废。
                }
                shaderProgramId = 0;
                vertexShaderId = 0;
                fragmentShaderId = 0;
            }
        }
    }

    /**
     * 初始化尝试次数（诊断与测试用）。
     *
     * <p>契约「失败后不每帧重试」的可观测证据：无论 {@link #ensureReady()} 被调用多少次，
     * 本计数恒为 0 或 1。</p>
     */
    public int getInitializationAttempts() {
        return initializationAttempts;
    }

    /** 是否已判定不可用（失败后不再重试）。 */
    public boolean isUnavailable() {
        return unavailable;
    }

    /** 是否已成功初始化。 */
    public boolean isReady() {
        return !unavailable && shaderProgramId != 0;
    }

    /** 诊断用 program id；未初始化时 0。 */
    public int getProgramId() {
        return shaderProgramId;
    }

    /** 最近一次初始化失败原因；没有失败时为空字符串。 */
    public String getLastFailureMessage() {
        return lastFailureMessage;
    }

    /** 绑定本程序；未就绪或 GL 不可用时是安全空操作。 */
    public void use() {
        if (!isReady()) {
            return;
        }
        try {
            GL20.glUseProgram(shaderProgramId);
        } catch (Throwable failure) {
            // GL 类初始化失败（native 缺失 / 上下文丢失）不得冒泡到渲染帧。
            unavailable = true;
            if (lastFailureMessage.isEmpty()) {
                lastFailureMessage = failure.getClass().getSimpleName();
            }
        }
    }

    /**
     * 释放 GL 资源；可被帧外生命周期调用（dispose 是接口契约里唯一允许自行捕获绑定的路径）。
     */
    public void dispose() {
        try {
            releaseResources();
        } catch (Throwable failure) {
            // dispose 可能在帧外生命周期调用，GL 上下文可能已失效：不得向上抛。
        }
        shaderProgramId = 0;
        initialized = false;
        unavailable = false;
        initializationAttempts = 0;
        lastFailureMessage = "";
    }

    /**
     * 读取 GL 投影矩阵并解析像素缩放系数（GLSL {@code uPixelScale}）。
     *
     * <p>渲染线程内读 GL_PROJECTION_MATRIX 与 GL_VIEWPORT；读取不改写矩阵栈，
     * 不依赖 renderer 的帧级围栏之外的状态。</p>
     *
     * @return uPixelScale；读取失败或非法时返回 0（shader 侧等价于关闭最小宽度钳制）
     */
    public float readPixelScale() {
        // 口径（Lead 裁定）：着色器路径每帧 1 次 GL_VIEWPORT 查询；只有视口尺寸变化时
        // 才读一次 GL_PROJECTION_MATRIX 并重算 pixel scale。缓冲为实例字段，每帧 0 次分配。
        //
        // 关键约束：这些缓冲不能在 static 初始化里创建。任何 static 字段初始化一旦碰到
        // LWJGL 的 BufferUtils，就会在加载期触发 native 库加载；无 GL 环境（headless 测试、
        // CLI、未初始化上下文）会抛 ExceptionInInitializerError，JVM 随后把本类永久标记为
        // 不可用（NoClassDefFoundError），真机上等于「一次失败永久失去该后端」。
        try {
            // 缓冲由实例字段复用（渲染线程单线程调用，安全）：每帧 0 次分配。
            // 刻意不在 static 初始化里创建 —— 那会触发 LWJGL native 加载，
            // 让无 GL 环境下的类初始化永久失败（见类注释）。
            if (viewportBuffer == null) {
                viewportBuffer = BufferUtils.createIntBuffer(16);
                projectionBuffer = BufferUtils.createFloatBuffer(16);
            }
            GL11.glGetInteger(GL11.GL_VIEWPORT, viewportBuffer);
            int viewportWidth = viewportBuffer.get(2);
            int viewportHeight = viewportBuffer.get(3);
            if (!pixelScaleValid || viewportWidth != cachedViewportWidth || viewportHeight != cachedViewportHeight) {
                GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projectionBuffer);
                cachedPixelScale = ChainPreviewShaderMath.pixelScale(projectionBuffer.get(5), viewportHeight);
                cachedViewportWidth = viewportWidth;
                cachedViewportHeight = viewportHeight;
                pixelScaleValid = true;
            }
            return cachedPixelScale;
        } catch (Throwable failure) {
            // 读不到视口/投影时返回 0：shader 侧等价于关闭最小宽度钳制，不影响可绘制性。
            pixelScaleValid = false;
            return 0.0F;
        }
    }

    /** 视口查询缓冲：实例级复用，首次调用时创建（不在 static 初始化期触 LWJGL）。 */
    private IntBuffer viewportBuffer;
    /** 投影矩阵读回缓冲：实例级复用，仅在 viewport 尺寸变化时使用。 */
    private FloatBuffer projectionBuffer;
    /** 视口尺寸缓存：尺寸不变则跳过投影矩阵读回。 */
    private int cachedViewportWidth = -1;
    private int cachedViewportHeight = -1;
    private float cachedPixelScale;
    private boolean pixelScaleValid;
    /** 相机矩阵回读缓冲（每帧复用，渲染线程单线程调用 ⇒ 零每帧分配）：投影 / modelview。 */
    private FloatBuffer cameraProjectionBuffer;
    private FloatBuffer cameraModelViewBuffer;
    /** uModelView / uModelViewProjection 的列主序上传缓冲（每帧复用）。 */
    private FloatBuffer matrixUploadBuffer;

    /**
     * 视口缓存诊断文本（describe 使用）。
     *
     * @return 形如 {@code viewport=1920x1080, pixelScale=935.3}
     */
    public String describePixelScaleCache() {
        return "viewport=" + cachedViewportWidth + "x" + cachedViewportHeight
                + ", pixelScale=" + cachedPixelScale;
    }

    /**
     * 读取固定管线矩阵栈的投影矩阵与 modelview，供 CPU 侧相乘出 MVP（T48c-A）。
     *
     * <p><b>调用时机不可移动</b>：必须在 {@code ChainPreviewRenderer.drawPreview} 的
     * {@code glTranslated(origin − renderPos)} <b>之后</b>（即真正的 draw 内）调用——那时栈上的
     * modelview 才与被弃用的内建 {@code gl_ModelViewProjectionMatrix} 同义。读取不改写矩阵栈。</p>
     *
     * <p>失败（GL 不可用 / 上下文丢失 / native 缺失）返回 false，且<b>不</b>把本程序锁成不可用：
     * 「矩阵来源是否可信 ⇒ 是否放弃绘制 ⇒ 是否回退 legacy」由后端统一裁决，程序层只回答
     * 「读到了没有」。</p>
     *
     * @param outProjection 输出列主序投影矩阵（长度 &ge; 16）
     * @param outModelView  输出列主序 modelview（长度 &ge; 16）
     * @return 是否读取成功
     */
    public boolean readCameraMatrices(float[] outProjection, float[] outModelView) {
        if (unavailable) {
            return false;
        }
        if (!hasMatrixCapacity(outProjection) || !hasMatrixCapacity(outModelView)) {
            return false;
        }
        try {
            // 缓冲惰性创建：绝不能在 static 初始化里碰 BufferUtils（见 readPixelScale 的注释）。
            if (cameraProjectionBuffer == null) {
                cameraProjectionBuffer = BufferUtils.createFloatBuffer(ChainPreviewShaderMatrixMath.MATRIX_ELEMENTS);
                cameraModelViewBuffer = BufferUtils.createFloatBuffer(ChainPreviewShaderMatrixMath.MATRIX_ELEMENTS);
            }
            readMatrix(GL11.GL_PROJECTION_MATRIX, cameraProjectionBuffer, outProjection);
            readMatrix(GL11.GL_MODELVIEW_MATRIX, cameraModelViewBuffer, outModelView);
            return true;
        } catch (Throwable failure) {
            // 读取失败不锁存：否则一次瞬时失败会永久失去着色器后端（回退决策属于后端契约）。
            return false;
        }
    }

    /** glGetFloat 回读一个列主序 4×4 矩阵：先归一化写入位置，再把 16 个元素取进数组。 */
    private static void readMatrix(int pname, FloatBuffer buffer, float[] out) {
        buffer.clear();
        GL11.glGetFloat(pname, buffer);
        buffer.position(0);
        buffer.get(out, 0, ChainPreviewShaderMatrixMath.MATRIX_ELEMENTS);
    }

    private static boolean hasMatrixCapacity(float[] matrix) {
        return matrix != null && matrix.length >= ChainPreviewShaderMatrixMath.MATRIX_ELEMENTS;
    }

    /**
     * 宿主安全初值：{@code ensureReady()} 链接成功后写入的不透明包络（{@code uFadeAlpha}）。
     *
     * <p>GLSL uniform 未赋值时为 0，会让整链 alpha 归零（观感是「着色器档什么都不画」）；
     * {@code 1} 与「未启用动画」逐值同义（乘 1 不改变结果），故初值必须是它而不是 0。</p>
     */
    static final float INITIAL_FADE_ALPHA = 1.0F;

    /**
     * 宿主安全初值：{@code uOrderMinBrightness} 的恒等值（{@link ChainPreviewShaderMath#ORDER_MIN_BRIGHTNESS_OFF}）。
     *
     * <p>与 {@link #INITIAL_FADE_ALPHA} 同一个坑：GLSL uniform 未赋值时为 {@code 0}，而 {@code 0}
     * 对这个 uniform <b>不是</b>恒等——{@code 0 < 1.0} 会让 orderWeight 分支直接生效、最远处亮度权重降到 0
     * （表型是「远处条柱整条纯黑」：几何与 alpha 都还在，只是颜色被压没）。写入 {@code 1.0} 后，
     * 「宿主漏设」退化为「本能力关闭」，即接线前观感。</p>
     */
    static final float INITIAL_ORDER_MIN_BRIGHTNESS = ChainPreviewShaderMath.ORDER_MIN_BRIGHTNESS_OFF;

    /**
     * 宿主安全初值：{@code uInteriorDim} 的恒等值（{@link ChainPreviewShaderMath#INTERIOR_DIM_OFF}）。
     *
     * <p>与 {@link #INITIAL_ORDER_MIN_BRIGHTNESS} 同一个坑，且表型更重：GLSL uniform 未赋值时为
     * {@code 0}，{@code 0 < 1.0} 会让内部压暗分支直接生效、内部格线乘 0 ——
     * 那是<b>整片纯黑</b>（不是变暗），几何与 alpha 都还在，但颜色被压没。写入 {@code 1.0} 后，
     * 「宿主漏设」退化为「本能力关闭」，即接线前观感。</p>
     */
    static final float INITIAL_INTERIOR_DIM = ChainPreviewShaderMath.INTERIOR_DIM_OFF;

    /**
     * 包络 uniform 的合法域收敛：clamp 到 [0,1]，NaN 收敛为不透明（{@link #INITIAL_FADE_ALPHA}）。
     *
     * <p>写入边界（{@link #setFadeAlpha(float)}）与后端取值路径（backend 的 {@code fadeAlphaFor}）
     * 共用本函数，避免两处各写一份 clamp 规则而漂移。NaN 走「不透明」而不是传播：异常配置不得让
     * 整条链路静默消失。</p>
     *
     * @param fadeAlpha 原始包络（可为 NaN / 越界）
     * @return [0,1] 的包络
     */
    static float sanitizeFadeAlpha(float fadeAlpha) {
        if (Float.isNaN(fadeAlpha)) {
            return INITIAL_FADE_ALPHA;
        }
        return fadeAlpha < 0.0F ? 0.0F : (fadeAlpha > 1.0F ? 1.0F : fadeAlpha);
    }

    // ---------------------------------------------------------------- uniform 设置

    public void setOriginRel(float x, float y, float z) {
        setUniform3f("uOriginRel", x, y, z);
    }

    /**
     * 上传列主序 MVP（= 投影 × modelview），驱动 {@code gl_Position}（T48c-A）。
     *
     * <p>语义等价于固定管线内建 {@code gl_ModelViewProjectionMatrix}，但值由 CPU 侧显式算出：
     * 在「固定管线由 Angelica GLSM 生成着色器模拟」的真机环境下内建数与真实相机矩阵失同步，
     * 显式上传后可观测、可断言、可自检。</p>
     *
     * @param columnMajor 16 元素列主序矩阵；null 或长度不足静默忽略
     * @return 是否真的上传成功（location &lt; 0 / 参数非法 / GL 失败都返回 false ⇒ 调用方必须放弃本帧）
     */
    public boolean setModelViewProjection(float[] columnMajor) {
        return setUniformMatrix4("uModelViewProjection", columnMajor);
    }

    /**
     * 上传列主序 modelview（相机相对坐标），驱动横向偏移与深度换算（T48c-A）。
     *
     * @param columnMajor 16 元素列主序矩阵；null 或长度不足静默忽略
     * @return 是否真的上传成功（location &lt; 0 / 参数非法 / GL 失败都返回 false）
     */
    public boolean setModelView(float[] columnMajor) {
        return setUniformMatrix4("uModelView", columnMajor);
    }

    public void setPixelScale(float pixelScale) {
        setUniform1f("uPixelScale", pixelScale);
    }

    public void setFadeCurve(float fadeStart, float fadeEnd, float minAlpha, float maxAlpha) {
        setUniform1f("uFadeStart", fadeStart);
        setUniform1f("uFadeEnd", fadeEnd);
        setUniform1f("uMinAlpha", minAlpha);
        setUniform1f("uMaxAlpha", maxAlpha);
    }

    /**
     * 设置生长参数。
     *
     * @param animationProgress 出现序号归一化进度 [0,1]；>= 1 表示整段可见（跳过 appearOrder 比较）
     * @param totalTargets      同代目标总数（序号归一化分母）；&lt;= 0 表示无序号信息（关闭生长比较）
     */
    public void setAnimation(float animationProgress, float totalTargets) {
        setUniform1f("uAnimProgress", animationProgress);
        setUniform1f("uAppearSpan", totalTargets);
    }

    public void setMinScreenWidthPx(float minScreenWidthPx) {
        setUniform1f("uMinScreenWidthPx", minScreenWidthPx);
    }

    public void setBarThickness(float barThickness) {
        setUniform1f("uBarThickness", barThickness);
    }

    /**
     * 设置真描边外扩宽度（B3.x）。
     *
     * <p>{@code 0} 表示关闭描边（xray / occlude / OUTLINE 主体段），此时顶点位移矩阵恒等，
     * 与启用本功能前逐值一致。宽度在 host 侧已收敛到 [0, MAX_OUTLINE_WIDTH_PX]。</p>
     *
     * @param outlineWidthPx 物理像素宽度；非正数视为关闭
     */
    public void setOutlineWidthPx(float outlineWidthPx) {
        setUniform1f("uOutlineWidthPx", ChainPreviewShaderMath.outlineWidthPx(outlineWidthPx));
    }

    /**
     * 设置面朝向明暗开关（face shading）。
     *
     * <p>{@code 0} 表示关闭（默认）：GLSL 侧整段乘色分支不执行，输出逐字节等于接线前。
     * 传 {@code 1} 才进入 {@code color * faceShading(aDirection.xyz)}。</p>
     *
     * @param faceShading 0 = 关闭，1 = 开启（其余值按 {@code > 0.5} 判定）
     */
    public void setFaceShading(float faceShading) {
        setUniform1f("uFaceShading", faceShading > 0.5F ? 1.0F : 0.0F);
    }

    /**
     * 设置连锁序**亮度**权重下限（appearOrder 亮度权重）。
     *
     * <p>{@code 1.0} 表示关闭：GLSL 侧 {@code uOrderMinBrightness >= 1.0} 时完全不进入 orderWeight 分支，
     * 输出逐值等于接线前。权重的消费位置是<b>颜色亮度</b>（{@code color * orderWeight}），
     * 与 alpha 链路无关（用户裁定：距离淡出与连锁序是两个独立维度）。
     * 写入前按 host 侧口径收敛
     * （{@link ChainPreviewShaderMath#orderMinBrightness(float)}：clamp 到 [0,1]，NaN / Infinity → 1.0），
     * 不把非法值送进 uniform。</p>
     *
     * @param orderMinBrightness [0,1] 的亮度权重下限；{@code 1.0} = 关闭本能力
     */
    public void setOrderMinBrightness(float orderMinBrightness) {
        setUniform1f("uOrderMinBrightness", ChainPreviewShaderMath.orderMinBrightness(orderMinBrightness));
    }

    /**
     * 设置内部结构亮度系数（内部格线压暗）。
     *
     * <p>{@code 1.0} 表示关闭：GLSL 侧 {@code uInteriorDim >= 1.0} 时完全不进入内部压暗分支，
     * 输出逐值等于接线前。系数的消费位置是<b>颜色 rgb 上的一次乘法</b>
     * （{@code if (auxChannel(aAux.y) >= 4.0 && uOutlineWidthPx <= 0.0) color = color * uInteriorDim;}），
     * 与 alpha 链路无关，也不作用于描边 pass（描边色即外轮廓）。
     * 写入前按 host 侧口径收敛
     * （{@link ChainPreviewShaderMath#interiorDim(float)}：clamp 到 [0,1]，NaN / Infinity → 1.0），
     * 不把非法值送进 uniform。</p>
     *
     * @param interiorDim [0,1] 的内部结构亮度系数；{@code 1.0} = 关闭本能力
     */
    public void setInteriorDim(float interiorDim) {
        setUniform1f("uInteriorDim", ChainPreviewShaderMath.interiorDim(interiorDim));
    }

    /**
     * 设置淡入淡出包络（B3.2）。
     *
     * <p>{@code fadeAlpha = 1} 表示完全不透明，与启用动画前逐值一致。宿主必须每帧显式设置：
     * GLSL uniform 未赋值时为 0，若宿主漏设会让整链透明（因此 {@link #ensureReady()} 成功后
     * 会先写入安全初值 {@link #INITIAL_FADE_ALPHA}）。收敛规则见 {@link #sanitizeFadeAlpha(float)}。</p>
     *
     * @param fadeAlpha [0,1]；越界被 clamp，NaN 收敛为不透明
     */
    public void setFadeAlpha(float fadeAlpha) {
        setUniform1f("uFadeAlpha", sanitizeFadeAlpha(fadeAlpha));
    }

    /**
     * 设置某个调色板槽位的颜色。
     *
     * <p>槽位 → uniform 名的对应关系必须与 {@code preview.vert} 的 {@code previewSemanticColor()}
     * 以及 {@link ChainPreviewShaderMath#paletteIndexFor(int)} 的分派一致；未知槽位一律落到
     * {@code uColorChain}（与 CPU 参考模型的兜底槽一致，不静默丢弃写入）。</p>
     *
     * @param paletteSlot {@link ChainPreviewShaderMath#PALETTE_CHAIN} 等 6 个槽位
     * @param red         0..1
     * @param green       0..1
     * @param blue        0..1
     */
    public void setSemanticColor(int paletteSlot, float red, float green, float blue) {
        if (paletteSlot == ChainPreviewShaderMath.PALETTE_AREA) {
            setUniform3f("uColorArea", red, green, blue);
        } else if (paletteSlot == ChainPreviewShaderMath.PALETTE_INTERACT) {
            setUniform3f("uColorInteract", red, green, blue);
        } else if (paletteSlot == ChainPreviewShaderMath.PALETTE_SECONDARY) {
            setUniform3f("uColorSecondary", red, green, blue);
        } else if (paletteSlot == ChainPreviewShaderMath.PALETTE_REMOTE) {
            setUniform3f("uColorRemote", red, green, blue);
        } else if (paletteSlot == ChainPreviewShaderMath.PALETTE_TRUNCATED) {
            setUniform3f("uColorTruncated", red, green, blue);
        } else {
            setUniform3f("uColorChain", red, green, blue);
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 校验必备 uniform 的 location；缺失即抛 {@link IllegalStateException}。
     *
     * <p>由 {@link #ensureReady()} 统一收敛：异常 ⇒ {@code unavailable = true} + 原因进
     * {@link #getLastFailureMessage()} ⇒ 后端 ensureReady() 返回 false ⇒ renderer 既有的一次性永久
     * 回退 legacy（不新增回退机制）。失败只判一次，不每帧重试。</p>
     */
    private void verifyRequiredUniforms() {
        StringBuilder missing = null;
        for (String name : REQUIRED_UNIFORMS) {
            if (getUniformLocation(name) >= 0) {
                continue;
            }
            if (missing == null) {
                missing = new StringBuilder();
            } else {
                missing.append(", ");
            }
            missing.append(name);
        }
        if (missing != null) {
            throw new IllegalStateException(MISSING_UNIFORM_PREFIX + missing);
        }
        // 能力型：缺失不失败，只登记成能力状态。缺失 = 「这段能力当前关闭」的正常表现，
        // 不是故障；把它当硬失败会让整个着色器后端不可用（真机表型：什么都不画）。
        StringBuilder unavailable = null;
        for (String name : CAPABILITY_UNIFORMS) {
            if (getUniformLocation(name) >= 0) {
                continue;
            }
            if (unavailable == null) {
                unavailable = new StringBuilder();
            } else {
                unavailable.append(',');
            }
            unavailable.append(name);
        }
        capabilityReport = unavailable == null
                ? "capabilities=all-available"
                : "capabilities=enhanced-geometry:off(missing=" + unavailable + ')';
    }

    private void compileAndLink() {
        vertexShaderId = ShaderProgramSupport.compileShader(
                ShaderProgramSupport.readText(getClass(), VERTEX_RESOURCE, READ_ERROR_PREFIX),
                GL20.GL_VERTEX_SHADER,
                COMPILE_VERTEX_ERROR_PREFIX);
        try {
            fragmentShaderId = ShaderProgramSupport.compileShader(
                    ShaderProgramSupport.readText(getClass(), FRAGMENT_RESOURCE, READ_ERROR_PREFIX),
                    GL20.GL_FRAGMENT_SHADER,
                    COMPILE_FRAGMENT_ERROR_PREFIX);
            GL20.glAttachShader(shaderProgramId, vertexShaderId);
            GL20.glAttachShader(shaderProgramId, fragmentShaderId);
            ShaderProgramSupport.linkAndValidateProgram(shaderProgramId, LINK_ERROR_PREFIX, VALIDATE_ERROR_PREFIX);
        } finally {
            deleteShader(vertexShaderId);
            deleteShader(fragmentShaderId);
            vertexShaderId = 0;
            fragmentShaderId = 0;
        }
    }

    /**
     * 请求固定属性槽位（顶点属性契约（真源：preview.vert 头部属性段） 的 attribute 0/1/2）。
     *
     * <p><b>这只是「请求」，不是「事实」</b>：真机实测（Angelica GLSM + lwjgl3ify + core profile）
     * 下本调用返回成功却不生效，驱动把 {@code aPos} 分到了槽位 1、{@code aAux} 分到了槽位 2
     * （GLSL 1.20 兼容档里 {@code gl_Vertex} 占住槽位 0 之后的默认分配）。因此槽位一律以
     * {@link #resolveAttributeLocations()} 的查询结果为准，本方法只作为「请求」保留。</p>
     */
    private void bindAttributeLocations() {
        GL20.glBindAttribLocation(shaderProgramId, ATTRIB_POSITION, "aPos");
        GL20.glBindAttribLocation(shaderProgramId, ATTRIB_AUX, "aAux");
        GL20.glBindAttribLocation(shaderProgramId, ATTRIB_DIRECTION, "aDirection");
    }

    /**
     * T50：链接后向驱动查询三个属性的真实槽位。
     *
     * <p><b>为什么必须查询</b>：{@code glBindAttribLocation} 在本环境实测不生效（无报错、无
     * Unmapped 警告，但查询结果是 1/2/-1）。此前 Java 侧把顶点数据写死在槽位 0、aux 写死在 1，
     * 于是 GPU 把 <b>aux 的 uint8 字节值当作顶点坐标</b>读——所有顶点落进 [0,1]³ 的小盒子，
     * 屏幕上就是「瞄准方块上的一小块色斑」，而数据回读、绑定回读、矩阵回读全部自洽。
     * 这是「离线全绿、真机错位」的最后一层，只有把槽位当运行时事实才能根治。</p>
     *
     * <p>{@code aPos} / {@code aAux} 缺一不可：缺失即抛，由 {@code ensureReady} 收敛为
     * 「程序不可用」⇒ 后端一次性回退 legacy，绝不留错误空间的一帧。</p>
     */
    private void resolveAttributeLocations() {
        positionAttributeLocation = GL20.glGetAttribLocation(shaderProgramId, "aPos");
        auxAttributeLocation = GL20.glGetAttribLocation(shaderProgramId, "aAux");
        directionAttributeLocation = GL20.glGetAttribLocation(shaderProgramId, "aDirection");
        if (positionAttributeLocation < 0 || auxAttributeLocation < 0 || directionAttributeLocation < 0) {
            throw new IllegalStateException("属性槽位解析失败：aPos=" + positionAttributeLocation
                + ", aAux=" + auxAttributeLocation + ", aDirection=" + directionAttributeLocation);
        }
        if (positionAttributeLocation == auxAttributeLocation
                || positionAttributeLocation == directionAttributeLocation
                || auxAttributeLocation == directionAttributeLocation) {
            throw new IllegalStateException("属性槽位冲突：aPos=" + positionAttributeLocation
                + ", aAux=" + auxAttributeLocation + ", aDirection=" + directionAttributeLocation);
        }
    }

    /** @return 顶点位置属性的运行时槽位（>= 0；未就绪时为 -1）。 */
    public int getPositionAttributeLocation() {
        return positionAttributeLocation;
    }

    /** @return 顶点辅助属性（semanticClass / tubeEdge / appearOrder）的运行时槽位（>= 0；未就绪时为 -1）。 */
    public int getAuxAttributeLocation() {
        return auxAttributeLocation;
    }

    /** @return 外扩方向属性的运行时槽位（>= 0；未就绪时为 -1）。 */
    public int getDirectionAttributeLocation() {
        return directionAttributeLocation;
    }


    private void releaseResources() {
        deleteShader(vertexShaderId);
        deleteShader(fragmentShaderId);
        vertexShaderId = 0;
        fragmentShaderId = 0;
        GL20.glUseProgram(0);
        if (shaderProgramId != 0) {
            GL20.glDeleteProgram(shaderProgramId);
        }
        uniformLocations.clear();
        missingUniforms.clear();
        positionAttributeLocation = -1;
        auxAttributeLocation = -1;
        directionAttributeLocation = -1;
    }

    private static void deleteShader(int shaderId) {
        if (shaderId != 0) {
            GL20.glDeleteShader(shaderId);
        }
    }

    private void setUniform1f(String name, float value) {
        int location = getUniformLocation(name);
        if (location == -1) {
            return;
        }
        try {
            GL20.glUniform1f(location, value);
        } catch (Throwable failure) {
            unavailable = true;
        }
    }

    /**
     * 上传列主序 4×4 uniform。
     *
     * <p>{@code location < 0} 时返回 false 而不是静默成功：矩阵是「缺失即画面全错」的必备 uniform，
     * 调用方（后端）必须据此放弃本帧并回退，而不是发出一帧零矩阵的绘制（T48c-C）。</p>
     *
     * @return 是否上传成功
     */
    private boolean setUniformMatrix4(String name, float[] columnMajor) {
        if (!hasMatrixCapacity(columnMajor)) {
            return false;
        }
        int location = getUniformLocation(name);
        if (location == -1) {
            missingMatrices = true;
            return false;
        }
        try {
            if (matrixUploadBuffer == null) {
                matrixUploadBuffer = BufferUtils.createFloatBuffer(ChainPreviewShaderMatrixMath.MATRIX_ELEMENTS);
            }
            matrixUploadBuffer.clear();
            matrixUploadBuffer.put(columnMajor, 0, ChainPreviewShaderMatrixMath.MATRIX_ELEMENTS);
            matrixUploadBuffer.flip();
            // transpose = false：数组已是 GL 约定的列主序，绝不能"顺手"转置。
            GL20.glUniformMatrix4(location, false, matrixUploadBuffer);
            return true;
        } catch (Throwable failure) {
            unavailable = true;
            return false;
        }
    }

    /**
     * 是否发生过「矩阵 uniform 缺失导致未上传」。
     *
     * <p>正常情况下不可能为 true（{@link #verifyRequiredUniforms()} 已把缺失挡在 ensureReady 之外），
     * 保留它是为了让「矩阵未上传」这件事在诊断上可观测，而不是静默画一帧零矩阵。</p>
     *
     * @return 是否发生过未上传
     */
    public boolean hasMissingMatrixUniforms() {
        return missingMatrices;
    }

    private void setUniform3f(String name, float x, float y, float z) {
        int location = getUniformLocation(name);
        if (location == -1) {
            return;
        }
        try {
            GL20.glUniform3f(location, x, y, z);
        } catch (Throwable failure) {
            unavailable = true;
        }
    }

    int getUniformLocation(String name) {
        if (unavailable) {
            return -1;
        }
        Integer cached = uniformLocations.get(name);
        if (cached != null) {
            return cached.intValue();
        }
        if (missingUniforms.contains(name)) {
            return -1;
        }
        int location;
        try {
            location = GL20.glGetUniformLocation(shaderProgramId, name);
        } catch (Throwable failure) {
            // GL 类不可初始化 / 上下文丢失：收敛为「无此 uniform」，不得冒泡到渲染帧。
            unavailable = true;
            if (lastFailureMessage.isEmpty()) {
                lastFailureMessage = failure.getClass().getSimpleName();
            }
            return -1;
        }
        if (location == -1) {
            missingUniforms.add(name);
            return -1;
        }
        uniformLocations.put(name, Integer.valueOf(location));
        return location;
    }
}
