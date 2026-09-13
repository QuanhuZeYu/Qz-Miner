package club.heiqi.qz_miner.chain.client.render;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

/**
 * 预览后端能力探测结果（不可变）。
 *
 * <p>{@link #detect()} 只允许在渲染线程调用：GL20 存在 + GLSL 版本可解析 + 顶点属性数量足够；
 * 任何异常（含无上下文、原生库缺失）都兜底为 {@link #UNSUPPORTED}（全 false），不抛给渲染帧。</p>
 *
 * <p>回退触发只看真实 GL 能力与上次失败，不看是否加载了某个模组：legacy 路径在 Angelica /
 * shader pack 环境下本来就能工作（Lead 裁定 2026-09-13）。</p>
 *
 * <p>T26 / B4.3：同时给出 shader 与 legacy 两条路径的支持性——legacy 依赖 VAO（GL30 /
 * ARB_vertex_array_object）+ GL20 顶点属性 + attrib 0/1，缺一即为
 * {@link #isLegacySupported()} false；无 VAO 环境不得再假定 legacy 可用。
 * {@link #detectDetailed()} 额外返回失败原因，供封装层写入 describe() 诊断。</p>
 */
public final class ChainPreviewGlCapabilities {

    /** shader 路径至少需要 attrib 0（aPos）与 attrib 1（aAux）。 */
    public static final int REQUIRED_MAX_VERTEX_ATTRIBS = 2;

    /** 全 false 兜底值。 */
    public static final ChainPreviewGlCapabilities UNSUPPORTED =
        new ChainPreviewGlCapabilities(false, "", "", 0, false);

    private final boolean shaderSupported;
    private final boolean legacySupported;
    private final String glVersion;
    private final String glslVersion;
    private final int maxVertexAttribs;
    private final boolean vaoSupported;

    /**
     * 兼容构造：legacy 支持性按 VAO 支持性推定（历史调用方只需 shader 判据）。
     *
     * @param shaderSupported 着色器路径是否可用
     * @param glVersion       GL_VERSION
     * @param glslVersion     GL_SHADING_LANGUAGE_VERSION
     * @param maxVertexAttribs GL_MAX_VERTEX_ATTRIBS
     * @param vaoSupported    VAO 是否可用
     */
    public ChainPreviewGlCapabilities(
            boolean shaderSupported,
            String glVersion,
            String glslVersion,
            int maxVertexAttribs,
            boolean vaoSupported) {
        this(shaderSupported, vaoSupported, glVersion, glslVersion, maxVertexAttribs, vaoSupported);
    }

    /**
     * 完整构造（T26 / B4.3：legacy 路径可用性显式入参，不再由调用方假定）。
     *
     * @param shaderSupported 着色器路径是否可用
     * @param legacySupported 固定管线 + VAO 路径是否可用
     * @param glVersion       GL_VERSION
     * @param glslVersion     GL_SHADING_LANGUAGE_VERSION
     * @param maxVertexAttribs GL_MAX_VERTEX_ATTRIBS
     * @param vaoSupported    VAO 是否可用
     */
    public ChainPreviewGlCapabilities(
            boolean shaderSupported,
            boolean legacySupported,
            String glVersion,
            String glslVersion,
            int maxVertexAttribs,
            boolean vaoSupported) {
        this.shaderSupported = shaderSupported;
        this.legacySupported = legacySupported;
        this.glVersion = glVersion == null ? "" : glVersion;
        this.glslVersion = glslVersion == null ? "" : glslVersion;
        this.maxVertexAttribs = Math.max(0, maxVertexAttribs);
        this.vaoSupported = vaoSupported;
    }

    /**
     * 纯函数：由探测到的原始值计算支持性，便于离线断言。
     *
     * @param gl20Available    GL20 上下文能力是否存在
     * @param glVersion        GL_VERSION 原始串
     * @param glslVersion      GL_SHADING_LANGUAGE_VERSION 原始串
     * @param maxVertexAttribs GL_MAX_VERTEX_ATTRIBS
     * @param vaoSupported     是否支持 vertex array object
     * @return 不可变能力结果
     */
    public static ChainPreviewGlCapabilities probe(
            boolean gl20Available,
            String glVersion,
            String glslVersion,
            int maxVertexAttribs,
            boolean vaoSupported) {
        boolean shaderSupported = gl20Available
            && versionAtLeast(glVersion, 2, 1)
            && versionAtLeast(glslVersion, 1, 20)
            && maxVertexAttribs >= REQUIRED_MAX_VERTEX_ATTRIBS;
        // legacy 路径：glVertexAttribPointer（GL20）+ VAO（GL30 / ARB）+ attrib 0/1
        boolean legacySupported = gl20Available
            && vaoSupported
            && maxVertexAttribs >= REQUIRED_MAX_VERTEX_ATTRIBS;
        return new ChainPreviewGlCapabilities(
            shaderSupported,
            legacySupported,
            glVersion,
            glslVersion,
            maxVertexAttribs,
            vaoSupported);
    }

    /**
     * 渲染线程内探测当前 GL 上下文能力；任何异常 → {@link #UNSUPPORTED}。
     *
     * @return 探测结果，永不为 null
     */
    /** 探测结果：能力值 + 失败原因（T26 / B4.3：探测失败可观察，不静默降级为「不支持」）。 */
    public static final class ProbeResult {

        private final ChainPreviewGlCapabilities capabilities;
        private final String failureReason;

        private ProbeResult(ChainPreviewGlCapabilities capabilities, String failureReason) {
            this.capabilities = capabilities == null ? UNSUPPORTED : capabilities;
            this.failureReason = failureReason == null ? "" : failureReason;
        }

        /** @return 探测成功结果 */
        public static ProbeResult success(ChainPreviewGlCapabilities capabilities) {
            return new ProbeResult(capabilities, "");
        }

        /**
         * @param capabilities 兜底能力值（通常 {@link #UNSUPPORTED}）
         * @param reason       失败原因（诊断用）
         * @return 探测失败结果
         */
        public static ProbeResult failure(ChainPreviewGlCapabilities capabilities, String reason) {
            return new ProbeResult(capabilities, reason == null || reason.isEmpty() ? "unspecified" : reason);
        }

        /** @return 能力值（永不为 null） */
        public ChainPreviewGlCapabilities getCapabilities() {
            return capabilities;
        }

        /** @return 失败原因（成功时为空串） */
        public String getFailureReason() {
            return failureReason;
        }

        /** @return 本次探测是否失败（上下文缺失 / 抛异常 / 结果缺失） */
        public boolean isFailure() {
            return !failureReason.isEmpty();
        }

        @Override
        public String toString() {
            return "ProbeResult{failure=" + isFailure()
                + (isFailure() ? ", reason=" + failureReason : "")
                + ", caps=" + capabilities.describe() + '}';
        }
    }

    public static ChainPreviewGlCapabilities detect() {
        return detectDetailed().getCapabilities();
    }

    /**
     * 渲染线程内探测 + 失败原因（T26 / B4.3：探测失败必须可观察，不能静默当成「不支持」）。
     *
     * @return 探测结果，永不为 null；上下文缺失 / 异常时为 UNSUPPORTED + failureReason
     */
    public static ProbeResult detectDetailed() {
        try {
            ContextCapabilities capabilities = GLContext.getCapabilities();
            if (capabilities == null) {
                return ProbeResult.failure(UNSUPPORTED, "GLContext capabilities unavailable (no context)");
            }
            String glVersion = GL11.glGetString(GL11.GL_VERSION);
            String glslVersion = GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION);
            int maxVertexAttribs = 0;
            try {
                maxVertexAttribs = GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS);
            } catch (Throwable ignored) {
                maxVertexAttribs = 0;
            }
            boolean vaoSupported = capabilities.OpenGL30 || capabilities.GL_ARB_vertex_array_object;
            return ProbeResult.success(probe(
                capabilities.OpenGL20,
                glVersion,
                glslVersion,
                maxVertexAttribs,
                vaoSupported));
        } catch (Throwable failure) {
            String reason = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
            return ProbeResult.failure(UNSUPPORTED, reason);
        }
    }

    /** @return GL20 + GLSL 1.20 且 attrib 数量足够 */
    public boolean isShaderSupported() {
        return shaderSupported;
    }

    /**
     * @return legacy（固定管线 + VAO）路径是否可用：GL20 + VAO + attrib 0/1 齐备；
     *         无 VAO 环境不得假定 legacy 可用（T26 / B4.3）
     */
    public boolean isLegacySupported() {
        return legacySupported;
    }

    /** @return GL_VERSION 原始串（可能为空） */
    public String getGlVersion() {
        return glVersion;
    }

    /** @return GL_SHADING_LANGUAGE_VERSION 原始串（可能为空） */
    public String getGlslVersion() {
        return glslVersion;
    }

    /** @return GL_MAX_VERTEX_ATTRIBS，探测失败为 0 */
    public int getMaxVertexAttribs() {
        return maxVertexAttribs;
    }

    /** @return 是否支持 VAO（属性 0 的持久绑定依赖它） */
    public boolean isVaoSupported() {
        return vaoSupported;
    }

    /** @return 诊断文本（版本、attrib 数量） */
    public String describe() {
        return "shaderSupported=" + shaderSupported
            + ", legacySupported=" + legacySupported
            + ", gl='" + glVersion + "'"
            + ", glsl='" + glslVersion + "'"
            + ", maxVertexAttribs=" + maxVertexAttribs
            + ", vao=" + vaoSupported;
    }

    /**
     * 解析版本串并比较（纯函数，容忍 {@code "3.3.0 NVIDIA 552.22"} 这类后缀）。
     *
     * @param version       版本串，可为 null
     * @param requiredMajor 要求的主版本
     * @param requiredMinor 要求的次版本
     * @return 可解析且 {@code >= required} 时为 true
     */
    public static boolean versionAtLeast(String version, int requiredMajor, int requiredMinor) {
        int[] parsed = parseVersion(version);
        if (parsed == null) {
            return false;
        }
        if (parsed[0] != requiredMajor) {
            return parsed[0] > requiredMajor;
        }
        return parsed[1] >= requiredMinor;
    }

    /**
     * 解析前两段版本号；OpenGL ES 串与无数字串返回 null。
     *
     * @param version 版本串，可为 null
     * @return {major, minor}；无法解析时 null
     */
    static int[] parseVersion(String version) {
        if (version == null) {
            return null;
        }
        String text = version.trim();
        if (text.isEmpty() || text.startsWith("OpenGL ES")) {
            return null;
        }
        int length = text.length();
        int index = 0;
        while (index < length && !isDigit(text.charAt(index))) {
            index++;
        }
        if (index >= length) {
            return null;
        }
        int major = 0;
        while (index < length && isDigit(text.charAt(index))) {
            major = major * 10 + (text.charAt(index) - '0');
            index++;
        }
        int minor = 0;
        if (index < length && text.charAt(index) == '.') {
            index++;
            while (index < length && isDigit(text.charAt(index))) {
                minor = minor * 10 + (text.charAt(index) - '0');
                index++;
            }
        }
        return new int[] { major, minor };
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    @Override
    public String toString() {
        return "ChainPreviewGlCapabilities{" + describe() + '}';
    }
}
