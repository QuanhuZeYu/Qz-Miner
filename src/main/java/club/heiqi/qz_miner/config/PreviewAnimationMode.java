package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewAnimation} 的取值域。
 *
 * <p>id 是 YAML / UI 选项的稳定文本，不随枚举序数变化；{@link #ids()} 供 Schema options，
 * {@link #fromId(String)} 供语义校验收窄（未知返回 null，由调用方报错，不夹取）。</p>
 *
 * <p>wave 保留出现顺序（appearOrder），off 等于历史行为。</p>
 */
public enum PreviewAnimationMode {

    /** 关闭动画（本轮默认，等于历史行为）。 */
    OFF("off"),

    /** 整体流动生长。 */
    FLOW("flow"),

    /** 按出现顺序逐波生长（接线属下一批 B3.1/B3.3）。 */
    WAVE("wave");

    /** 本轮默认（Lead 裁定：默认档位不得超前于实现）。 */
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

    /** @return 本轮默认档（Lead 裁定：不得超前于实现） */
    public static PreviewAnimationMode defaultValue() {
        return DEFAULT_VALUE;
    }
}
