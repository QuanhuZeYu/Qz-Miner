package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewRenderBackend} 的取值域。
 *
 * <p>稳定文本 id 用于 YAML 与 UI；ids 提供 schema 选项，fromId 精确解析，未知值交调用方报错。</p>
 * <p>后端选择器综合请求档位、GL 能力与历史失败结果决定实际路径。</p>
 */
public enum PreviewRenderBackend {

    /** 能力探测通过则 shader，否则 legacy。 */
    AUTO("auto"),

    /** 显式请求着色器后端；探测失败仍回退 legacy 并记录一次诊断。 */
    SHADER("shader"),

    /** 使用固定管线后端。 */
    LEGACY("legacy");

    /** 默认档位。 */
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

    /** @return 默认档位 */
    public static PreviewRenderBackend defaultValue() {
        return DEFAULT_VALUE;
    }
}
