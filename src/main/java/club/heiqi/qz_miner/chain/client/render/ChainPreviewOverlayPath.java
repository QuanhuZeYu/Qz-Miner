package club.heiqi.qz_miner.chain.client.render;

/**
 * 「GL 能力 → 覆盖层路径」选择纯函数（T26 / B4.3；无 GL、无状态，可离线直连断言）。
 *
 * <p>与 {@link ChainPreviewBackendSelector} 的分工：selector 负责「配置 + 能力 + 上次失败 → 后端 id」，
 * 本类在其上补一层「该后端在本机能力下是否真的可用」的判据——legacy 路径依赖 VAO
 * （GL30 / ARB_vertex_array_object）、GL20 顶点属性与 ≥2 个 attrib，缺一不可；不满足时必须显式降级为
 * {@link Path#UNAVAILABLE} 并给出诊断，而不是继续选择一个必然失败的后端。</p>
 *
 * <p>真正的固定管线（无 VAO）回退路径仍留后续批次（登记）；本类只负责「不可用就不选择」。</p>
 */
public final class ChainPreviewOverlayPath {

    /** 覆盖层可用路径。 */
    public enum Path {
        /** 着色器路径。 */
        SHADER,
        /** 固定管线 + VAO 路径（历史实现）。 */
        LEGACY,
        /** 两条路径都不可用：本帧显式降级不绘制，附诊断原因。 */
        UNAVAILABLE
    }

    /** 不可变决策结果。 */
    public static final class Decision {

        private final Path path;
        private final String reason;

        private Decision(Path path, String reason) {
            this.path = path;
            this.reason = reason == null ? "" : reason;
        }

        /** @return 选中的路径 */
        public Path getPath() {
            return path;
        }

        /** @return 后端 id（shader / legacy）；{@link Path#UNAVAILABLE} 时为 null */
        public String getBackendId() {
            if (path == Path.SHADER) {
                return ChainPreviewBackendSelector.SHADER;
            }
            if (path == Path.LEGACY) {
                return ChainPreviewBackendSelector.LEGACY;
            }
            return null;
        }

        /** @return 是否存在可用路径 */
        public boolean isUsable() {
            return path != Path.UNAVAILABLE;
        }

        /** @return 是否两条路径都不可用（显式降级） */
        public boolean isUnavailable() {
            return path == Path.UNAVAILABLE;
        }

        /** @return 决策原因（永不为空，供一次性诊断日志与 describe()） */
        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return "OverlayPathDecision{path=" + path + ", reason=" + reason + '}';
        }
    }

    private ChainPreviewOverlayPath() {
    }

    /**
     * 纯函数：能力 + 配置 + 上次失败 → 路径决策。
     *
     * @param configured         后端档位（auto / shader / legacy，null 与未知值按 auto）
     * @param capabilities       能力探测结果，可为 null（视为不可用，不假设 legacy 可用）
     * @param shaderAttemptFailed 上次 shader 后端加载 / 初始化是否失败
     * @return 决策结果（永不为 null）
     */
    public static Decision decide(
            String configured,
            ChainPreviewGlCapabilities capabilities,
            boolean shaderAttemptFailed) {
        String selected = ChainPreviewBackendSelector.select(configured, capabilities, shaderAttemptFailed);
        String explanation = ChainPreviewBackendSelector.explain(
            configured, capabilities, shaderAttemptFailed);
        if (ChainPreviewBackendSelector.SHADER.equals(selected)) {
            return new Decision(Path.SHADER, "shader path selected (" + explanation + ")");
        }
        String unavailable = legacyUnavailableReason(capabilities);
        if (unavailable != null) {
            return new Decision(
                Path.UNAVAILABLE,
                "no usable preview path: legacy backend requires " + unavailable
                    + " (configured=" + ChainPreviewBackendSelector.normalize(configured) + ")");
        }
        return new Decision(Path.LEGACY, "legacy path selected (" + explanation + ")");
    }

    /**
     * 纯函数：legacy 路径不可用的原因。
     *
     * <p>判定按「VAO → attrib → GL20」逐项显式校验，不单纯信任 {@code isLegacySupported()}：
     * 手工构造的矛盾能力（flag=true 但缺 VAO / attrib 不足）同样被拒绝，保证「缺一不可」成立。</p>
     *
     * @param capabilities 能力探测结果，可为 null
     * @return 不可用原因；可用时为 null
     */
    public static String legacyUnavailableReason(ChainPreviewGlCapabilities capabilities) {
        if (capabilities == null) {
            return "capabilities unavailable";
        }
        if (!capabilities.isVaoSupported()) {
            return "VAO support (GL30 / ARB_vertex_array_object)";
        }
        if (capabilities.getMaxVertexAttribs() < ChainPreviewGlCapabilities.REQUIRED_MAX_VERTEX_ATTRIBS) {
            return "GL_MAX_VERTEX_ATTRIBS >= " + ChainPreviewGlCapabilities.REQUIRED_MAX_VERTEX_ATTRIBS;
        }
        if (!capabilities.isLegacySupported()) {
            return "GL20 context";
        }
        return null;
    }
}
