package club.heiqi.qz_miner.toolswap.protocol;

/** 自动工具换位服务端协议的固定常量与槽位校验。 */
public final class AutoToolSwapProtocol {

    public static final int PROTOCOL_VERSION = 3;
    public static final long NO_SERVER_ROUND_ID = 0L;
    public static final long FIRST_ACTION_SEQUENCE = 1L;
    public static final int INVENTORY_FIRST_SLOT = 0;
    public static final int INVENTORY_LAST_SLOT = 35;
    public static final int INVENTORY_SLOT_COUNT = 36;
    public static final int HOTBAR_FIRST_SLOT = 0;
    public static final int HOTBAR_LAST_SLOT = 8;
    public static final String CANONICAL_EMPTY_ROLE_KEY = "qz_miner:empty";
    /** 自动工具目标身份可表达的最大正方块 ID。 */
    public static final int MAX_BLOCK_ID = Integer.MAX_VALUE;
    /** 自动工具目标身份可表达的最大非负 metadata。 */
    public static final int MAX_BLOCK_METADATA = Integer.MAX_VALUE;

    private AutoToolSwapProtocol() {
    }

    /** @return slot 是否属于玩家个人 inventory 的 0..35 槽位。 */
    public static boolean isInventorySlot(int slot) {
        return slot >= INVENTORY_FIRST_SLOT && slot <= INVENTORY_LAST_SLOT;
    }

    /** @return slot 是否属于选中热键栏可用的 0..8 槽位。 */
    public static boolean isHotbarSlot(int slot) {
        return slot >= HOTBAR_FIRST_SLOT && slot <= HOTBAR_LAST_SLOT;
    }
}
