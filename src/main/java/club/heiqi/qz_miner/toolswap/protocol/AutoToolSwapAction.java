package club.heiqi.qz_miner.toolswap.protocol;

import java.util.HashMap;
import java.util.Map;

/** 客户端请求的自动工具换位动作；wire code 不依赖声明顺序。 */
public enum AutoToolSwapAction {
    SWAP(1),
    RESTORE(2),
    FREEZE(3),
    CLOSE(4),
    ABANDON(5),
    TAKEOVER(6),
    DECLINE_TAKEOVER(7);

    private static final Map<Integer, AutoToolSwapAction> BY_WIRE_CODE = indexByWireCode();

    private final int wireCode;

    AutoToolSwapAction(int wireCode) {
        this.wireCode = wireCode;
    }

    /** @return 稳定的协议数值。 */
    public int wireCode() {
        return wireCode;
    }

    /** 从 wire code 解码；未知值必须拒绝，不能猜测默认动作。 */
    public static AutoToolSwapAction fromWireCode(int wireCode) {
        AutoToolSwapAction value = BY_WIRE_CODE.get(Integer.valueOf(wireCode));
        if (value == null) {
            throw new IllegalArgumentException("unknown auto tool swap action wire code: " + wireCode);
        }
        return value;
    }

    private static Map<Integer, AutoToolSwapAction> indexByWireCode() {
        Map<Integer, AutoToolSwapAction> values = new HashMap<Integer, AutoToolSwapAction>();
        for (AutoToolSwapAction value : values()) {
            if (values.put(Integer.valueOf(value.wireCode), value) != null) {
                throw new IllegalStateException("duplicate auto tool swap action wire code");
            }
        }
        return values;
    }
}
