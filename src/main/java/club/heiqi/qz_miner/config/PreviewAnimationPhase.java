package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewAnimationPhase} 的取值域。
 *
 * <p>稳定文本 id 用于 YAML 与 UI；ids 提供 schema 选项，fromId 精确解析，未知值交调用方报错。</p>
 * <p>声明 order/hash 相位来源，供后续生产绘制接入。</p>
 */
public enum PreviewAnimationPhase {

    /** 相位来自出现序号 appearOrder。 */
    ORDER("order"),

    /** 相位来自坐标 + 代次的稳定哈希。 */
    HASH("hash");

    /** 默认档位。 */
    private static final PreviewAnimationPhase DEFAULT_VALUE = ORDER;

    private final String id;

    PreviewAnimationPhase(String id) {
        this.id = id;
    }

    /** @return 配置 / YAML 稳定 id */
    public String id() {
        return id;
    }

    /** @return 全部合法 id（Schema options 用，顺序与声明一致） */
    public static String[] ids() {
        PreviewAnimationPhase[] values = values();
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
    public static PreviewAnimationPhase fromId(String id) {
        for (PreviewAnimationPhase value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }

    /** @return 默认档位 */
    public static PreviewAnimationPhase defaultValue() {
        return DEFAULT_VALUE;
    }
}
