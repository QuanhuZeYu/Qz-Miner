package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewLod} 的取值域。
 *
 * <p>id 是 YAML / UI 选项的稳定文本，不随枚举序数变化；{@link #ids()} 供 Schema options，
 * {@link #fromId(String)} 供语义校验收窄（未知返回 null，由调用方报错，不夹取）。</p>
 *
 * <p>off 等于历史行为；auto 才启用距离合并与 alpha 剔除。</p>
 */
public enum PreviewLodMode {

    /** 关闭 LOD 与 alpha 剔除（本轮目标默认，等于历史行为）。 */
    OFF("off"),

    /** 按距离合并与 clientPreviewLodMinAlpha 剔除。 */
    AUTO("auto");

    /** 本轮目标默认（配置键位与默认值（真源：QzMinerConfigDefaults））。 */
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

    /** @return 本轮默认档（Lead 裁定：不得超前于实现） */
    public static PreviewLodMode defaultValue() {
        return DEFAULT_VALUE;
    }
}
