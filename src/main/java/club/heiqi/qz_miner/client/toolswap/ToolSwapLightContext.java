package club.heiqi.qz_miner.client.toolswap;

/** 不遍历库存即可读取的客户端事实。 */
public final class ToolSwapLightContext {

    public final long tick;
    public final boolean breakCapable;
    public final boolean creative;
    public final boolean guiOpen;
    public final boolean chainActive;
    public final int selectedHotbarSlot;

    public ToolSwapLightContext(long tick, boolean breakCapable, boolean creative, boolean guiOpen,
            boolean chainActive, int selectedHotbarSlot) {
        if (tick < 0L || selectedHotbarSlot < 0 || selectedHotbarSlot > 8) {
            throw new IllegalArgumentException("tick/selected slot out of range");
        }
        this.tick = tick;
        this.breakCapable = breakCapable;
        this.creative = creative;
        this.guiOpen = guiOpen;
        this.chainActive = chainActive;
        this.selectedHotbarSlot = selectedHotbarSlot;
    }
}
