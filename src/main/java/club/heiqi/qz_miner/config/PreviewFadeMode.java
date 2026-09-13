package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewFadeMode} 的取值域。
 *
 * <p>id 是 YAML / UI 选项的稳定文本，不随枚举序数变化；{@link #ids()} 供 Schema options，
 * {@link #fromId(String)} 供语义校验收窄（未知返回 null，由调用方报错，不夹取）。</p>
 *
 * <p>signal 消除 1 Hz 台阶；timer 等于历史行为；gpu 由着色器逐帧计算。</p>
 */
public enum PreviewFadeMode {

    /** 1 Hz 兜底刷新（本轮默认，等于历史行为）。 */
    TIMER("timer"),

    /** 相机变更信号驱动刷新（接线属下一批 B1.3）。 */
    SIGNAL("signal"),

    /** 距离淡出在着色器内逐帧计算。 */
    GPU("gpu");

    /** 本轮默认（Lead 裁定：默认档位不得超前于实现）。 */
    private static final PreviewFadeMode DEFAULT_VALUE = TIMER;

    private final String id;

    PreviewFadeMode(String id) {
        this.id = id;
    }

    /** @return 配置 / YAML 稳定 id */
    public String id() {
        return id;
    }

    /** @return 全部合法 id（Schema options 用，顺序与声明一致） */
    public static String[] ids() {
        PreviewFadeMode[] values = values();
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
    public static PreviewFadeMode fromId(String id) {
        for (PreviewFadeMode value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }

    /** @return 本轮默认档（Lead 裁定：不得超前于实现） */
    public static PreviewFadeMode defaultValue() {
        return DEFAULT_VALUE;
    }
}
