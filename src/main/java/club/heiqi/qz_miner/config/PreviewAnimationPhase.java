package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewAnimationPhase} 的取值域。
 *
 * <p>id 是 YAML / UI 选项的稳定文本，不随枚举序数变化；{@link #ids()} 供 Schema options，
 * {@link #fromId(String)} 供语义校验收窄（未知返回 null，由调用方报错，不夹取）。</p>
 *
 * <p>order 用 appearOrder 段；hash 用坐标稳定哈希，两档都不引入波表。</p>
 */
public enum PreviewAnimationPhase {

    /** 相位来自出现序号 appearOrder（本轮目标默认）。 */
    ORDER("order"),

    /** 相位来自坐标 + 代次的稳定哈希。 */
    HASH("hash");

    /** 本轮目标默认（接口冻结 §E）。 */
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

    /** @return 本轮默认档（Lead 裁定：不得超前于实现） */
    public static PreviewAnimationPhase defaultValue() {
        return DEFAULT_VALUE;
    }
}
