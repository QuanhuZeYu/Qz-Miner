package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewDepthMode} 的取值域。
 *
 * <p>id 是 YAML / UI 选项的稳定文本，不随枚举序数变化；{@link #ids()} 供 Schema options，
 * {@link #fromId(String)} 供语义校验收窄（未知返回 null，由调用方报错，不夹取）。</p>
 *
 * <p>xray 等于历史行为；其余档位只改绘制通道，不改拓扑。</p>
 */
public enum PreviewDepthMode {

    /** 恒可见通道（本轮目标默认，等于历史行为）。 */
    XRAY("xray"),

    /** 参与深度测试，近处遮挡远处。 */
    OCCLUDE("occlude"),

    /** 主体遮挡 pass + 置顶轮廓 pass。 */
    OUTLINE("outline");

    /** 本轮目标默认（接口冻结 §E）。 */
    private static final PreviewDepthMode DEFAULT_VALUE = XRAY;

    private final String id;

    PreviewDepthMode(String id) {
        this.id = id;
    }

    /** @return 配置 / YAML 稳定 id */
    public String id() {
        return id;
    }

    /** @return 全部合法 id（Schema options 用，顺序与声明一致） */
    public static String[] ids() {
        PreviewDepthMode[] values = values();
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
    public static PreviewDepthMode fromId(String id) {
        for (PreviewDepthMode value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return null;
    }

    /** @return 本轮默认档（Lead 裁定：不得超前于实现） */
    public static PreviewDepthMode defaultValue() {
        return DEFAULT_VALUE;
    }
}
