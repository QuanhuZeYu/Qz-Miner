package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewColorSource} 的取值域。
 *
 * <p>稳定文本 id 用于 YAML 与 UI；ids 提供 schema 选项，fromId 精确解析，未知值交调用方报错。</p>
 * <p>builtin 使用内置语义配色，config 使用 clientPreviewColor* 配色键。</p>
 */
public enum PreviewColorSource {

    /** 使用内置语义配色。 */
    BUILTIN("builtin"),

    /** 使用 clientPreviewColor* 配置键的语义配色。 */
    CONFIG("config");

    /** 默认档位。 */
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

    /** @return 默认档位 */
    public static PreviewColorSource defaultValue() {
        return DEFAULT_VALUE;
    }
}
