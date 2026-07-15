package club.heiqi.qz_miner.toolswap.protocol;

import java.util.HashMap;
import java.util.Map;

/** 服务端对单个自动工具换位动作给出的结果码。 */
public enum AutoToolSwapResultCode {
    ACCEPTED(1),
    APPLIED(2),
    REJECTED(3),
    RESTORE_REQUIRED(4),
    SYNC_FAILED(5);

    private static final Map<Integer, AutoToolSwapResultCode> BY_WIRE_CODE = indexByWireCode();

    private final int wireCode;

    AutoToolSwapResultCode(int wireCode) {
        this.wireCode = wireCode;
    }

    /** @return 稳定的协议数值。 */
    public int wireCode() {
        return wireCode;
    }

    /** 从 wire code 解码；未知值必须拒绝。 */
    public static AutoToolSwapResultCode fromWireCode(int wireCode) {
        AutoToolSwapResultCode value = BY_WIRE_CODE.get(Integer.valueOf(wireCode));
        if (value == null) {
            throw new IllegalArgumentException("unknown auto tool swap result wire code: " + wireCode);
        }
        return value;
    }

    private static Map<Integer, AutoToolSwapResultCode> indexByWireCode() {
        Map<Integer, AutoToolSwapResultCode> values = new HashMap<Integer, AutoToolSwapResultCode>();
        for (AutoToolSwapResultCode value : values()) {
            if (values.put(Integer.valueOf(value.wireCode), value) != null) {
                throw new IllegalStateException("duplicate auto tool swap result wire code");
            }
        }
        return values;
    }
}
