package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewAnimation} 的取值域。
 *
 * <p>稳定文本 id 用于 YAML 与 UI；ids 提供 schema 选项，fromId 精确解析，未知值交调用方报错。</p>
 * <p>wave 按 appearOrder 逐波生长，flow 整体生长，off 关闭动画。</p>
 */
public enum PreviewAnimationMode {

    /** 关闭动画。 */
    OFF("off"),

    /** 整体流动生长。 */
    FLOW("flow"),

    /** 按出现顺序逐波生长。 */
    WAVE("wave");

    /** 默认档位。 */
    private static final PreviewAnimationMode DEFAULT_VALUE = OFF;

    private final String id;

    PreviewAnimationMode(String id) {
        this.id = id;
    }

    /** @return 配置 / YAML 稳定 id */
    public String id() {
        return id;
    }

    /** @return 全部合法 id（Schema options 用，顺序与声明一致） */
    public static String[] ids() {
        PreviewAnimationMode[] values = values();
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
    public static PreviewAnimationMode fromId(String id) {
        for (PreviewAnimationMode value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }

    /** @return 默认档位 */
    public static PreviewAnimationMode defaultValue() {
        return DEFAULT_VALUE;
    }
}
