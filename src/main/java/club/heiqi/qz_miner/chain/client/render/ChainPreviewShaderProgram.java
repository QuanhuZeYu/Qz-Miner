package club.heiqi.qz_miner.chain.client.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * 在渲染线程管理业务 preview.vert/preview.frag 的编译、链接、验证、uniform 缓存与句柄释放。
 * UILib GlApi 绑定独立 context，此处沿世界 renderer 的 GL20 上下文工作。
 *
 * <p>初始化尝试一次，失败锁存原因供后端降级；各句柄独立清理，并恢复进入前的 program 绑定。
 * 相机矩阵在 renderer 完成原点换基后显式读回、相乘并上传，适配 Angelica GLSM 的固定管线模拟。
 * shader 接口按必备项与能力项校验，属性槽位使用链接后的运行时查询结果。</p>
 *
 * <p>UILib 5 迁移后的真实 GL 状态恢复、资源重载与退出重入待游戏实机验证。</p>
 */
public final class ChainPreviewShaderProgram {

    private static final String VERTEX_RESOURCE = "assets/qz_miner/shaders/preview.vert";
    private static final String FRAGMENT_RESOURCE = "assets/qz_miner/shaders/preview.frag";

    private static final String READ_ERROR_PREFIX = "读取预览着色器失败: ";
    private static final String COMPILE_VERTEX_ERROR_PREFIX = "预览顶点着色器编译失败: ";
    private static final String COMPILE_FRAGMENT_ERROR_PREFIX = "预览片元着色器编译失败: ";
    private static final String LINK_ERROR_PREFIX = "预览着色器链接失败: ";
    private static final String VALIDATE_ERROR_PREFIX = "预览着色器验证失败: ";
    private static final int INFO_LOG_MAX_LENGTH = 8192;

    private static final int ATTRIB_POSITION = 0;
    private static final int ATTRIB_AUX = 1;
    /** 外扩方向槽位的请求编号，实际槽位以链接后查询为准。 */
    private static final int ATTRIB_DIRECTION = 2;

    /** 链接后查询的属性槽位，-1 表示未解析或被优化掉。 */
    private int positionAttributeLocation = -1;
    private int auxAttributeLocation = -1;
    private int directionAttributeLocation = -1;

    private static final String MISSING_UNIFORM_PREFIX = "预览着色器缺少必备 uniform: ";

    /** 必备 uniform 链接后统一校验，缺失按程序不可用处理，使矩阵和视觉配置都有真实上传落点。 */
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
        // 显式面方向外扩需要相机、像素尺度和条柱尺寸，统一列入必备项。
        "uModelView",
        "uPixelScale",
        "uBarThickness",
        "uMinScreenWidthPx",
        "uOutlineWidthPx",
        // 面明暗开关参与当前乘色路径。
        "uFaceShading",
        // 连锁序权重下限参与当前亮度路径。
        "uOrderMinBrightness",
        // 内部亮度系数参与当前主体格线乘色。
        "uInteriorDim",
    };

    /** 能力型 uniform 缺失时登记该能力关闭；当前清单为空，活跃位移及亮度 uniform 列入必备项。 */
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
            verifyRequiredUniforms();
            // 属性槽位必须查询驱动，不能假设绑定请求一定生效。
            resolveAttributeLocations();
            initializeSafeUniforms();
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
                releaseResources();
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
     * 释放 GL 资源；可由帧外生命周期调用，只在本 program 当前绑定时解除，不改写其它 program。
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
     * renderer 执行 glTranslated(origin − renderPos) 后读回投影与 modelview，供 CPU 相乘并上传。
     * 读取失败返回 false，程序可用性与后端回退由调用方判断。
     *
     * @param outProjection 输出列主序投影矩阵，长度至少 16
     * @param outModelView 输出列主序 modelview，长度至少 16
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
            // 渲染调用时惰性创建缓冲，将 native 加载留在可捕获失败的入口。
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

    /** 链接后将 uFadeAlpha 初始化为不透明包络，覆盖 GLSL uniform 默认零带来的全链透明。 */
    static final float INITIAL_FADE_ALPHA = 1.0F;

    /** 将 uOrderMinBrightness 初始化为恒等亮度，覆盖 uniform 默认零产生的远端黑色。 */
    static final float INITIAL_ORDER_MIN_BRIGHTNESS = ChainPreviewShaderMath.ORDER_MIN_BRIGHTNESS_OFF;

    /** 将 uInteriorDim 初始化为恒等亮度，覆盖 uniform 默认零产生的内部黑色。 */
    static final float INITIAL_INTERIOR_DIM = ChainPreviewShaderMath.INTERIOR_DIM_OFF;

    /** 将包络收窄到 [0,1]，NaN 回退不透明；写入与后端取值共用。 */
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
     * 上传列主序 MVP（= 投影 × modelview），驱动 {@code gl_Position}。
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
     * 上传列主序 modelview，驱动相机空间偏移与深度换算。
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

    /** 上传物理像素描边宽度，零关闭描边外扩，最小宽度独立生效；写入前按统一规则收窄。 */
    public void setOutlineWidthPx(float outlineWidthPx) {
        setUniform1f("uOutlineWidthPx", ChainPreviewShaderMath.outlineWidthPx(outlineWidthPx));
    }

    /** 设置面明暗开关，大于 0.5 时按面法线乘色，其余使用原 rgb。 */
    public void setFaceShading(float faceShading) {
        setUniform1f("uFaceShading", faceShading > 0.5F ? 1.0F : 0.0F);
    }

    /** 将连锁序 rgb 权重下限收窄到 [0,1]，非法值回退恒等亮度。 */
    public void setOrderMinBrightness(float orderMinBrightness) {
        setUniform1f("uOrderMinBrightness", ChainPreviewShaderMath.orderMinBrightness(orderMinBrightness));
    }

    /** 将主体内部格线 rgb 系数收窄到 [0,1]，非法值回退恒等亮度；描边与 alpha 独立计算。 */
    public void setInteriorDim(float interiorDim) {
        setUniform1f("uInteriorDim", ChainPreviewShaderMath.interiorDim(interiorDim));
    }

    /** 上传本帧全局淡入淡出包络，收窄规则复用 sanitizeFadeAlpha；链接时先写不透明初值。 */
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

    /** 校验必备 uniform，缺失抛异常交 ensureReady 锁存失败原因，后端统一处理降级。 */
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
        boolean linked = false;
        try {
            vertexShaderId = compilePreviewShader(
                    VERTEX_RESOURCE, GL20.GL_VERTEX_SHADER, COMPILE_VERTEX_ERROR_PREFIX);
            fragmentShaderId = compilePreviewShader(
                    FRAGMENT_RESOURCE, GL20.GL_FRAGMENT_SHADER, COMPILE_FRAGMENT_ERROR_PREFIX);
            GL20.glAttachShader(shaderProgramId, vertexShaderId);
            GL20.glAttachShader(shaderProgramId, fragmentShaderId);
            GL20.glLinkProgram(shaderProgramId);
            if (GL20.glGetProgrami(shaderProgramId, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
                throw new IllegalStateException(LINK_ERROR_PREFIX
                        + GL20.glGetProgramInfoLog(shaderProgramId, INFO_LOG_MAX_LENGTH));
            }
            GL20.glValidateProgram(shaderProgramId);
            if (GL20.glGetProgrami(shaderProgramId, GL20.GL_VALIDATE_STATUS) != GL11.GL_TRUE) {
                throw new IllegalStateException(VALIDATE_ERROR_PREFIX
                        + GL20.glGetProgramInfoLog(shaderProgramId, INFO_LOG_MAX_LENGTH));
            }
            linked = true;
        } finally {
            // 失败时 shader 可能只完成部分 attach；标记删除后随失败 program 一并释放。
            releaseShaders(linked);
        }
    }

    /** 只读取本预览程序的打包资源，不引入另一套可配置 shader 加载器。 */
    private static String readPreviewShader(String resource) {
        try (InputStream input = ChainPreviewShaderProgram.class.getResourceAsStream("/" + resource)) {
            if (input == null) {
                throw new IOException("missing resource " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(READ_ERROR_PREFIX + resource, failure);
        }
    }

    /** 创建 shader 并在编译失败时释放尚未移交的句柄。 */
    private static int compilePreviewShader(String resource, int type, String errorPrefix) {
        String source = readPreviewShader(resource);
        int shaderId = GL20.glCreateShader(type);
        if (shaderId == 0) {
            throw new IllegalStateException(errorPrefix + "glCreateShader returned 0");
        }
        boolean compiled = false;
        try {
            GL20.glShaderSource(shaderId, source);
            GL20.glCompileShader(shaderId);
            if (GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
                throw new IllegalStateException(errorPrefix
                        + GL20.glGetShaderInfoLog(shaderId, INFO_LOG_MAX_LENGTH));
            }
            compiled = true;
            return shaderId;
        } finally {
            if (!compiled) {
                deleteShader(shaderId);
            }
        }
    }

    /** glUniform 只作用于当前 program；初始化发生在绘制围栏之外，必须就地保存并恢复绑定。 */
    private void initializeSafeUniforms() {
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            GL20.glUseProgram(shaderProgramId);
            setFadeAlpha(INITIAL_FADE_ALPHA);
            setOrderMinBrightness(INITIAL_ORDER_MIN_BRIGHTNESS);
            setInteriorDim(INITIAL_INTERIOR_DIM);
            if (unavailable) {
                throw new IllegalStateException("预览着色器安全初值上传失败");
            }
        } finally {
            GL20.glUseProgram(previousProgram);
        }
    }

    /**
     * 链接前请求属性槽位，链接后以 resolveAttributeLocations 的实际查询为准。
     * Angelica GLSM + lwjgl3ify + core profile 实机可对成功请求重新分配槽位。
     */
    private void bindAttributeLocations() {
        GL20.glBindAttribLocation(shaderProgramId, ATTRIB_POSITION, "aPos");
        GL20.glBindAttribLocation(shaderProgramId, ATTRIB_AUX, "aAux");
        GL20.glBindAttribLocation(shaderProgramId, ATTRIB_DIRECTION, "aDirection");
    }

    /**
     * 链接后查询真实属性槽位并验证必备位置和语义流；缺失交 ensureReady 收敛为不可用。
     * 槽位错误会将 aux 字节误作坐标读取，数据、绑定和矩阵诊断均可能仍正常。
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
        releaseShaders(false);
        int programId = shaderProgramId;
        shaderProgramId = 0;
        // 每个 native 句柄独立释放：一个调用失败不能阻断另一个句柄或 Java 缓存的清理。
        if (programId != 0) {
            try {
                if (GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) == programId) {
                    GL20.glUseProgram(0);
                }
            } catch (Throwable ignored) {
                // 上下文失效时继续尝试删除，其他 program 沿用原绑定。
            }
            try {
                GL20.glDeleteProgram(programId);
            } catch (Throwable ignored) {
                // 上下文不可用时由驱动回收，Java 句柄本轮已作废。
            }
        }
        uniformLocations.clear();
        missingUniforms.clear();
        positionAttributeLocation = -1;
        auxAttributeLocation = -1;
        directionAttributeLocation = -1;
    }

    private void releaseShaders(boolean attached) {
        int vertex = vertexShaderId;
        int fragment = fragmentShaderId;
        vertexShaderId = 0;
        fragmentShaderId = 0;
        if (attached) {
            detachShader(vertex);
            detachShader(fragment);
        }
        deleteShader(vertex);
        deleteShader(fragment);
    }

    private void detachShader(int shaderId) {
        if (shaderProgramId == 0 || shaderId == 0) {
            return;
        }
        try {
            GL20.glDetachShader(shaderProgramId, shaderId);
        } catch (Throwable ignored) {
            // 上下文失效时继续独立释放 shader 与 program。
        }
    }

    private static void deleteShader(int shaderId) {
        if (shaderId == 0) {
            return;
        }
        try {
            GL20.glDeleteShader(shaderId);
        } catch (Throwable ignored) {
            // 保留原始编译/链接失败，后续句柄继续独立回收。
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

    /** 上传列主序矩阵；参数非法、location 缺失或 GL 异常返回 false，调用方据此放弃本帧。 */
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
            // 数组已按 GL 列主序排列，transpose 取 false。
            GL20.glUniformMatrix4(location, false, matrixUploadBuffer);
            return true;
        } catch (Throwable failure) {
            unavailable = true;
            return false;
        }
    }

    /** @return 是否发生矩阵 uniform 缺失，用于诊断本帧上传失败 */
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
            // GL 类初始化或上下文失败时锁存不可用状态并保留诊断。
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
