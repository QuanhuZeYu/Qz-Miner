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
 * <p>失败语义（接口冻结 §F）：{@link #ensureReady()} 只尝试一次；失败后置
 * {@link #isUnavailable()} 为 true，后续调用直接返回 false，<strong>不会每帧重试</strong>。
 * 所有 GL 异常在此处被捕获，绝不向渲染帧抛出。原因保存在 {@link #getLastFailureMessage()}，
 * 由后端写进 {@code describe()}。</p>
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
    private static final int ATTRIB_COLOR = 2;

    private final Map<String, Integer> uniformLocations = new LinkedHashMap<String, Integer>();
    private final Set<String> missingUniforms = new HashSet<String>();

    private boolean initialized;
    private boolean unavailable;
    private int initializationAttempts;
    private int shaderProgramId;
    private int vertexShaderId;
    private int fragmentShaderId;
    private String lastFailureMessage = "";

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

    /**
     * 视口缓存诊断文本（describe 使用）。
     *
     * @return 形如 {@code viewport=1920x1080, pixelScale=935.3}
     */
    public String describePixelScaleCache() {
        return "viewport=" + cachedViewportWidth + "x" + cachedViewportHeight
                + ", pixelScale=" + cachedPixelScale;
    }

    // ---------------------------------------------------------------- uniform 设置

    public void setOriginRel(float x, float y, float z) {
        setUniform3f("uOriginRel", x, y, z);
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

    public void setSemanticColor(int semanticClass, float red, float green, float blue) {
        switch (semanticClass) {
            case 0:
                setUniform3f("uColorPrimary", red, green, blue);
                setUniform1f("uColorPrimaryEnabled", 1.0F);
                break;
            case 1:
                setUniform3f("uColorSecondary", red, green, blue);
                setUniform1f("uColorSecondaryEnabled", 1.0F);
                break;
            case 2:
                setUniform3f("uColorRemote", red, green, blue);
                setUniform1f("uColorRemoteEnabled", 1.0F);
                break;
            case 3:
                setUniform3f("uColorTruncated", red, green, blue);
                setUniform1f("uColorTruncatedEnabled", 1.0F);
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- 内部

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

    /** 固定属性槽位，与接口冻结 §A 的 attribute 0/1/2 一一对应。 */
    private void bindAttributeLocations() {
        GL20.glBindAttribLocation(shaderProgramId, ATTRIB_POSITION, "aPos");
        GL20.glBindAttribLocation(shaderProgramId, ATTRIB_AUX, "aAux");
        GL20.glBindAttribLocation(shaderProgramId, ATTRIB_COLOR, "aColor");
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
