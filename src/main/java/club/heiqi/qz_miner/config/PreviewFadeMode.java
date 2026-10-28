package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewFadeMode} 的取值域。
 *
 * <p>稳定文本 id 用于 YAML 与 UI；ids 提供 schema 选项，fromId 精确解析，未知值交调用方报错。</p>
 * <p>timer 定时刷新，signal 按相机信号刷新，gpu 在着色器逐帧计算距离淡出。</p>
 */
public enum PreviewFadeMode {

    /** 按定时窗口刷新距离淡出。 */
    TIMER("timer"),

    /** 相机变更信号驱动刷新。 */
    SIGNAL("signal"),

    /** 距离淡出在着色器内逐帧计算。 */
    GPU("gpu");

    /** 默认档位。 */
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

    /** @return 默认档位 */
    public static PreviewFadeMode defaultValue() {
        return DEFAULT_VALUE;
    }
}
