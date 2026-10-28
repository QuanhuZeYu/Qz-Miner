package club.heiqi.qz_miner.config;

/**
 * {@code client.clientPreviewDepthMode} 的取值域。
 *
 * <p>稳定文本 id 用于 YAML 与 UI；ids 提供 schema 选项，fromId 精确解析，未知值交调用方报错。</p>
 * <p>各档配置深度与透明度通道，几何拓扑由绘制计划共享。</p>
 */
public enum PreviewDepthMode {

    /** 穿墙可见通道。 */
    XRAY("xray"),

    /** 参与深度测试，近处遮挡远处。 */
    OCCLUDE("occlude"),

    /** 主体遮挡 pass + 置顶轮廓 pass。 */
    OUTLINE("outline"),

    /** 可见部分不按距离变透明，遮挡部分保留距离透明度；两者均保留动画包络。 */
    DEPTH_OPAQUE("depthOpaque");

    /** 默认档位。 */
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

    /** @return 默认档位 */
    public static PreviewDepthMode defaultValue() {
        return DEFAULT_VALUE;
    }
}
