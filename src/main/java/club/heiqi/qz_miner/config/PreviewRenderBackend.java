package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewRenderBackend} 的取值域。
 *
 * <p>id 是 YAML / UI 选项的稳定文本，不随枚举序数变化；{@link #ids()} 供 Schema options，
 * {@link #fromId(String)} 供语义校验收窄（未知返回 null，由调用方报错，不夹取）。</p>
 *
 * <p>配置键取值覆盖全部三档；auto 之外的档位由 ChainPreviewBackendSelector 决定实际后端。</p>
 */
public enum PreviewRenderBackend {

    /** 能力探测通过则 shader，否则 legacy（本轮目标默认）。 */
    AUTO("auto"),

    /** 显式请求着色器后端；探测失败仍回退 legacy 并记录一次诊断。 */
    SHADER("shader"),

    /** 恒定固定管线后端（回退到今天的渲染路径）。 */
    LEGACY("legacy");

    /** 本轮目标默认（配置键位与默认值（真源：QzMinerConfigDefaults））。 */
    private static final PreviewRenderBackend DEFAULT_VALUE = AUTO;

    private final String id;

    PreviewRenderBackend(String id) {
        this.id = id;
    }

    /** @return 配置 / YAML 稳定 id */
    public String id() {
        return id;
    }

    /** @return 全部合法 id（Schema options 用，顺序与声明一致） */
    public static String[] ids() {
        PreviewRenderBackend[] values = values();
        String[] ids = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            ids[i] = values[i].id;
        }
        return ids;
    }

    /**
     * @param id 配置文本
     * @return 匹配项；未知或 null 返回 null
     */
    public static PreviewRenderBackend fromId(String id) {
        for (PreviewRenderBackend value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }

    /** @return 本轮默认档（Lead 裁定：不得超前于实现） */
    public static PreviewRenderBackend defaultValue() {
        return DEFAULT_VALUE;
    }
}
