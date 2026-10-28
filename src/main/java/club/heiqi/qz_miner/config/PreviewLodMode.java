package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewLod} 的取值域。
 *
 * <p>稳定文本 id 用于 YAML 与 UI；ids 提供 schema 选项，fromId 精确解析，未知值交调用方报错。</p>
 * <p>auto 按目标中心 alpha 双阈值剔除，off 保留全部目标。</p>
 */
public enum PreviewLodMode {

    /** 保留全部 LOD 拓扑与 alpha。 */
    OFF("off"),

    /** 按 clientPreviewLodMinAlpha 与退出阈值决定目标包含集。 */
    AUTO("auto");

    /** 默认档位。 */
    private static final PreviewLodMode DEFAULT_VALUE = OFF;

    private final String id;

    PreviewLodMode(String id) {
        this.id = id;
    }

    /** @return 配置 / YAML 稳定 id */
    public String id() {
        return id;
    }

    /** @return 全部合法 id（Schema options 用，顺序与声明一致） */
    public static String[] ids() {
        PreviewLodMode[] values = values();
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
    public static PreviewLodMode fromId(String id) {
        for (PreviewLodMode value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }

    /** @return 默认档位 */
    public static PreviewLodMode defaultValue() {
        return DEFAULT_VALUE;
    }
}
