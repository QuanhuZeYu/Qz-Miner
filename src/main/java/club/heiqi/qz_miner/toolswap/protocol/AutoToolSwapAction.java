package club.heiqi.qz_miner.toolswap.protocol;

import java.util.HashMap;
import java.util.Map;

/** 客户端请求的自动工具换位动作，wire code 显式固定。 */
public enum AutoToolSwapAction {
    FREEZE(3),
    CLOSE(4);

    private static final Map<Integer, AutoToolSwapAction> BY_WIRE_CODE = indexByWireCode();

    private final int wireCode;

    AutoToolSwapAction(int wireCode) {
        this.wireCode = wireCode;
    }

    /** @return 稳定的协议数值。 */
    public int wireCode() {
        return wireCode;
    }

    /** 按固定 wire code 解码，未知值抛出异常交入包边界拒绝。 */
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
