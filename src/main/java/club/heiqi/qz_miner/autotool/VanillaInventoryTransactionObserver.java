package club.heiqi.qz_miner.autotool;

/** 客户端原版库存包的单一活动观察者入口。 */
public final class VanillaInventoryTransactionObserver {
    /** 观察实际外发点击与服务端库存响应。 */
    public interface Observer {
        void onClickPacket(int windowId, int slot, int button, int mode, short actionNumber);
        void onConfirmTransaction(int windowId, short actionNumber, boolean accepted);
        void onWindowItems(int windowId);
    }

    private static Observer active;

    private VanillaInventoryTransactionObserver() {}

    public static void setActive(Observer observer) { active = observer; }
    public static void clear(Observer observer) { if (active == observer) active = null; }
    public static void clickPacket(int windowId, int slot, int button, int mode, short action) {
        if (active != null) active.onClickPacket(windowId, slot, button, mode, action);
    }
    public static void confirm(int windowId, short action, boolean accepted) {
        if (active != null) active.onConfirmTransaction(windowId, action, accepted);
    }
    public static void windowItems(int windowId) { if (active != null) active.onWindowItems(windowId); }
}
