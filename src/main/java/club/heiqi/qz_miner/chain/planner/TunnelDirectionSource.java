package club.heiqi.qz_miner.chain.planner;

/**
 * AREA_TUNNEL 方向来源。
 *
 * <p>稳定配置 id 与 wire code 不依赖枚举 ordinal，旧协议固定降级为 {@link #LOOK_DIRECTION}。</p>
 */
public enum TunnelDirectionSource {

    LOOK_DIRECTION("look_direction", 0),
    HIT_FACE("hit_face", 1);

    private final String id;
    private final int wireCode;

    TunnelDirectionSource(String id, int wireCode) {
        this.id = id;
        this.wireCode = wireCode;
    }

    /** @return 稳定配置 id */
    public String id() {
        return id;
    }

    /** @return 稳定 wire code */
    public int wireCode() {
        return wireCode;
    }

    /** @return legacy/default 方向来源 */
    public static TunnelDirectionSource legacyDefault() {
        return LOOK_DIRECTION;
    }

    /**
     * 严格解析配置 id。
     *
     * @return 命中项；未知或 null 返回 null
     */
    public static TunnelDirectionSource fromId(String id) {
        for (TunnelDirectionSource source : values()) {
            if (source.id.equals(id)) {
                return source;
            }
        }
        return null;
    }

    /**
     * 严格解析 wire code。
     *
     * @return 命中项；未知值返回 null
     */
    public static TunnelDirectionSource fromWireCode(int wireCode) {
        for (TunnelDirectionSource source : values()) {
            if (source.wireCode == wireCode) {
                return source;
            }
        }
        return null;
    }

    /** @return Schema CHOICE 使用的稳定 id */
    public static String[] ids() {
        TunnelDirectionSource[] values = values();
        String[] ids = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            ids[i] = values[i].id;
        }
        return ids;
    }
}
