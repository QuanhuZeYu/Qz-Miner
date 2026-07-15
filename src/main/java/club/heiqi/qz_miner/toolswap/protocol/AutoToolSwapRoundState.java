package club.heiqi.qz_miner.toolswap.protocol;

import java.util.HashMap;
import java.util.Map;

/** 服务端工具换位 round 的可序列化状态。 */
public enum AutoToolSwapRoundState {
    PENDING_KEY(1),
    OPEN(2),
    SWAPPED(3),
    FROZEN(4),
    CLOSING(5),
    FINISHED(6),
    ORPHANED(7);

    private static final Map<Integer, AutoToolSwapRoundState> BY_WIRE_CODE = indexByWireCode();

    private final int wireCode;

    AutoToolSwapRoundState(int wireCode) {
        this.wireCode = wireCode;
    }

    /** @return 稳定的协议数值。 */
    public int wireCode() {
        return wireCode;
    }

    /** 从 wire code 解码；未知值必须拒绝。 */
    public static AutoToolSwapRoundState fromWireCode(int wireCode) {
        AutoToolSwapRoundState value = BY_WIRE_CODE.get(Integer.valueOf(wireCode));
        if (value == null) {
            throw new IllegalArgumentException("unknown auto tool swap round state wire code: " + wireCode);
        }
        return value;
    }

    private static Map<Integer, AutoToolSwapRoundState> indexByWireCode() {
        Map<Integer, AutoToolSwapRoundState> values = new HashMap<Integer, AutoToolSwapRoundState>();
        for (AutoToolSwapRoundState value : values()) {
            if (values.put(Integer.valueOf(value.wireCode), value) != null) {
                throw new IllegalStateException("duplicate auto tool swap round state wire code");
            }
        }
        return values;
    }
}
