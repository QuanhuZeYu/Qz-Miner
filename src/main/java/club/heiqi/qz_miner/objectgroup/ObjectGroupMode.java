package club.heiqi.qz_miner.objectgroup;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 对象组可用模式的稳定标识与 16-bit wire 位注册表。 */
public final class ObjectGroupMode {

    public static final String CHAIN_BASE = "chain_base";
    public static final String CHAIN_ORE = "chain_ore";
    public static final String CHAIN_LOGGING = "chain_logging";
    public static final String AREA_SAME_BLOCK = "area_same_block";
    public static final String AREA_ORE = "area_ore";
    public static final String INTERACT_BASE = "interact_base";
    public static final String INTERACT_CROP = "interact_crop";
    public static final int KNOWN_MASK = 0x007F;

    private static final Map<String, Integer> BITS;

    static {
        LinkedHashMap<String, Integer> bits = new LinkedHashMap<String, Integer>();
        bits.put(CHAIN_BASE, Integer.valueOf(1));
        bits.put(CHAIN_ORE, Integer.valueOf(2));
        bits.put(CHAIN_LOGGING, Integer.valueOf(4));
        bits.put(AREA_SAME_BLOCK, Integer.valueOf(8));
        bits.put(AREA_ORE, Integer.valueOf(16));
        bits.put(INTERACT_BASE, Integer.valueOf(32));
        bits.put(INTERACT_CROP, Integer.valueOf(64));
        BITS = Collections.unmodifiableMap(bits);
    }

    private ObjectGroupMode() {
    }

    /** @return 按稳定 bit 顺序排列的模式标识。 */
    public static String[] ids() {
        return BITS.keySet().toArray(new String[BITS.size()]);
    }

    /** 将模式标识转为 mask；未知或重复标识拒绝。 */
    public static long toMask(Iterable<?> modes) {
        long mask = 0L;
        if (modes == null) {
            throw new IllegalArgumentException("modes must be a list");
        }
        for (Object raw : modes) {
            if (!(raw instanceof String) || !BITS.containsKey(raw)) {
                throw new IllegalArgumentException("unknown object group mode: " + raw);
            }
            long bit = BITS.get(raw).longValue();
            if ((mask & bit) != 0L) {
                throw new IllegalArgumentException("duplicate object group mode: " + raw);
            }
            mask |= bit;
        }
        return mask;
    }

    /** @return mask 是否仅包含已知 16-bit 模式位。 */
    public static boolean isValidMask(long mask) {
        return mask >= 0L && (mask & ~((long) KNOWN_MASK)) == 0L;
    }
}
