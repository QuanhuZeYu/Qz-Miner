package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewColorSource} 的取值域。
 *
 * <p>id 是 YAML / UI 选项的稳定文本，不随枚举序数变化；{@link #ids()} 供 Schema options，
 * {@link #fromId(String)} 供语义校验收窄（未知返回 null，由调用方报错，不夹取）。</p>
 *
 * <p>builtin 档必须与历史行为逐字节一致，config 档才读取四个 RGB 键。</p>
 */
public enum PreviewColorSource {

    /** 内置常量色 + 距离 α，等于历史行为（本轮目标默认）。 */
    BUILTIN("builtin"),

    /** 使用 clientPreviewColor* 四个 RGB 键的语义配色。 */
    CONFIG("config");

    /** 本轮目标默认（配置键位与默认值（真源：QzMinerConfigDefaults））。 */
    private static final PreviewColorSource DEFAULT_VALUE = BUILTIN;

    private final String id;

    PreviewColorSource(String id) {
        this.id = id;
    }

    /** @return 配置 / YAML 稳定 id */
    public String id() {
        return id;
    }

    /** @return 全部合法 id（Schema options 用，顺序与声明一致） */
    public static String[] ids() {
        PreviewColorSource[] values = values();
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
    public static PreviewColorSource fromId(String id) {
        for (PreviewColorSource value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }

    /** @return 本轮默认档（Lead 裁定：不得超前于实现） */
    public static PreviewColorSource defaultValue() {
        return DEFAULT_VALUE;
    }
}
