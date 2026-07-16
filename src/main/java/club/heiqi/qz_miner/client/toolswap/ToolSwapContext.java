package club.heiqi.qz_miner.client.toolswap;

/** 单个客户端 tick 的纯事实快照。 */
public final class ToolSwapContext {

    public final long tick;
    public final boolean breakCapable;
    public final boolean creative;
    public final boolean guiOpen;
    /** 当前可安全发送 Qz 服务端工具换位 intent 的客户端事实。 */
    public final boolean inventoryTransactionSafe;
    public final boolean chainActive;
    public final int selectedHotbarSlot;
    public final ToolSwapInventorySnapshot inventory;
    public final ToolSwapTargetIdentity targetIdentity;

    public ToolSwapContext(ToolSwapLightContext light, boolean inventoryTransactionSafe,
            boolean guiOpen, int selectedHotbarSlot, ToolSwapInventorySnapshot inventory) {
        this(light.tick, light.breakCapable, light.creative, guiOpen, inventoryTransactionSafe,
                light.chainActive, selectedHotbarSlot, inventory, light.targetIdentity);
    }

    public ToolSwapContext(long tick, boolean breakCapable, boolean creative, boolean guiOpen,
            boolean inventoryTransactionSafe, boolean chainActive, int selectedHotbarSlot,
            ToolSwapInventorySnapshot inventory) {
        this(tick, breakCapable, creative, guiOpen, inventoryTransactionSafe, chainActive,
                selectedHotbarSlot, inventory, ToolSwapTargetIdentity.ABSENT);
    }

    /** 创建包含固化目标身份的完整客户端事实。 */
    public ToolSwapContext(long tick, boolean breakCapable, boolean creative, boolean guiOpen,
            boolean inventoryTransactionSafe, boolean chainActive, int selectedHotbarSlot,
            ToolSwapInventorySnapshot inventory, ToolSwapTargetIdentity targetIdentity) {
        if (tick < 0L || selectedHotbarSlot < 0 || selectedHotbarSlot > 8 || inventory == null) {
            throw new IllegalArgumentException("tick/selected slot/inventory out of range");
        }
        if (targetIdentity == null) throw new IllegalArgumentException("targetIdentity must not be null");
        this.tick = tick;
        this.breakCapable = breakCapable;
        this.creative = creative;
        this.guiOpen = guiOpen;
        this.inventoryTransactionSafe = inventoryTransactionSafe && !creative && inventory.isTrusted();
        this.chainActive = chainActive;
        this.selectedHotbarSlot = selectedHotbarSlot;
        this.inventory = inventory;
        this.targetIdentity = targetIdentity;
    }
}
