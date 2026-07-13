package club.heiqi.qz_miner.autotool;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeTrigger;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.client.KeyListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;

/** 客户端自动工具选择适配器，负责快照装配和原版库存事务命令执行。 */
public final class AutoToolClientController implements VanillaInventoryTransactionBridge.Listener {
    /** 可替换的客户端事实和快捷栏写入口。 */
    public interface Facade {
        boolean enabled();
        boolean breakBlockMode();
        boolean validInteractionContext();
        AutoToolCoordinator.Phase phase();
        Object target();
        List<? extends AutoToolCoordinator.Candidate<Object>> candidates();
        int currentHotbarSlot();
        void selectHotbarSlot(int slot);
        Object inventoryIdentity(int slot);
        int minimumDurabilityReserve();
        int inventoryAnchorHotbarSlot();
    }

    /** 可替换的库存事务端口，供 headless 测试隔离静态 observer。 */
    public interface BridgePort {
        boolean beginSwap(int sourceSlot, int anchorSlot, Object source, Object anchor, Object desired);
        boolean requestRestore(Object sourceAtAnchor, Object anchorAtSource, Object desired);
        void tickTimeout();
        void resetLifecycle();
    }

    private final Facade facade;
    private final AutoToolCoordinator<Object> coordinator;
    private final BridgePort bridge;
    private boolean keyHeld;
    private boolean resettingLifecycle;
    private int expectedHotbarSlot = -1;
    private int originalHotbarSlot = -1;
    private int inventorySourceSlot = -1;
    private int inventoryAnchorSlot = -1;

    /** 使用 Minecraft 运行时事实和原版 windowClick 事务桥构造控制器。 */
    public AutoToolClientController() {
        this(new ProductionFacade(), null);
    }

    /** 使用指定端口构造控制器。bridge 为空时创建生产事务桥。 */
    public AutoToolClientController(Facade facade, BridgePort bridge) {
        if (facade == null) throw new IllegalArgumentException("facade");
        this.facade = facade;
        this.coordinator = new AutoToolCoordinator<Object>();
        this.bridge = bridge == null ? vanillaBridge() : bridge;
    }

    /** 推进一个客户端 tick。 */
    public void tick() {
        bridge.tickTimeout();
        execute(coordinator.tick(snapshot()));
    }

    /** 更新连锁键周期；状态变化时立即推进一次。 */
    public void onChainKeyChanged(boolean held) {
        if (keyHeld == held) return;
        keyHeld = held;
        tick();
    }

    /** 生命周期边界清空本地协调和事务状态，不尝试恢复物品。 */
    public void resetLifecycle() {
        keyHeld = false;
        resettingLifecycle = true;
        try {
            bridge.resetLifecycle();
        } finally {
            resettingLifecycle = false;
        }
        AutoToolCoordinator.Snapshot<Object> reset = snapshot();
        reset.lifecycleReset = true;
        coordinator.tick(reset);
        clearExpectedSlots();
    }

    /** 转发客户端点击包观察。 */
    public void onClickPacket(int windowId, int slot, int button, int mode, short actionNumber) {
        if (bridge instanceof VanillaBridgePort) {
            ((VanillaBridgePort) bridge).delegate.onClickPacket(windowId, slot, button, mode, actionNumber);
        }
    }

    /** 转发服务端事务确认。 */
    public void onConfirmTransaction(int windowId, short actionNumber, boolean accepted) {
        if (bridge instanceof VanillaBridgePort) {
            ((VanillaBridgePort) bridge).delegate.onConfirmTransaction(windowId, actionNumber, accepted);
        }
    }

    /** 转发 window items 重同步。 */
    public void onWindowItems(int windowId) {
        if (bridge instanceof VanillaBridgePort) ((VanillaBridgePort) bridge).delegate.onWindowItems(windowId);
    }

    /** 接收库存事务桥状态并推进协调器。 */
    @Override public void onStatusChanged(VanillaInventoryTransactionBridge.Status status) {
        if (resettingLifecycle) return;
        if (status == VanillaInventoryTransactionBridge.Status.ACTIVE) {
            execute(coordinator.confirm(snapshot()));
        } else if (status == VanillaInventoryTransactionBridge.Status.IDLE) {
            inventorySourceSlot = inventoryAnchorSlot = -1;
            execute(coordinator.confirm(snapshot()));
        } else {
            clearExpectedSlots();
            execute(coordinator.rejectOrTimeout());
        }
    }

    private AutoToolCoordinator.Snapshot<Object> snapshot() {
        AutoToolCoordinator.Snapshot<Object> result = new AutoToolCoordinator.Snapshot<Object>();
        result.enabled = facade.enabled();
        result.keyHeld = keyHeld;
        result.breakBlockMode = facade.breakBlockMode();
        result.validInteractionContext = facade.validInteractionContext();
        result.phase = facade.phase();
        result.target = facade.target();
        result.candidates = result.validInteractionContext ? facade.candidates()
                : Collections.<AutoToolCoordinator.Candidate<Object>>emptyList();
        result.currentHotbarSlot = facade.currentHotbarSlot();
        result.manualOverride = expectedHotbarSlot >= 0 && result.currentHotbarSlot != expectedHotbarSlot;
        result.minimumDurabilityReserve = facade.minimumDurabilityReserve();
        result.inventoryAnchorHotbarSlot = facade.inventoryAnchorHotbarSlot();
        return result;
    }

    private void execute(AutoToolCoordinator.Command command) {
        switch (command.type) {
            case SELECT_HOTBAR:
                originalHotbarSlot = facade.currentHotbarSlot();
                expectedHotbarSlot = command.slot;
                facade.selectHotbarSlot(command.slot);
                execute(coordinator.confirm(snapshot()));
                break;
            case SELECT_INVENTORY:
                originalHotbarSlot = facade.currentHotbarSlot();
                inventorySourceSlot = command.slot;
                inventoryAnchorSlot = command.anchorHotbarSlot;
                if (!bridge.beginSwap(command.slot, command.anchorHotbarSlot,
                        facade.inventoryIdentity(toInventorySlot(command.slot)),
                        facade.inventoryIdentity(command.anchorHotbarSlot), coordinator.state().latestDesiredTarget)) {
                    execute(coordinator.rejectOrTimeout());
                }
                break;
            case RESTORE_HOTBAR:
                if (expectedHotbarSlot >= 0 && facade.currentHotbarSlot() == expectedHotbarSlot) {
                    facade.selectHotbarSlot(command.slot);
                }
                clearExpectedSlots();
                execute(coordinator.confirm(snapshot()));
                break;
            case RESTORE_INVENTORY:
                if (!bridge.requestRestore(facade.inventoryIdentity(inventoryAnchorSlot),
                        facade.inventoryIdentity(toInventorySlot(inventorySourceSlot)),
                        coordinator.state().latestDesiredTarget)) execute(coordinator.rejectOrTimeout());
                break;
            case PAUSE:
            case RESET:
                clearExpectedSlots();
                break;
            default:
                break;
        }
    }

    private BridgePort vanillaBridge() {
        final VanillaBridgePort port = new VanillaBridgePort();
        port.delegate = new VanillaInventoryTransactionBridge<Object>(new MinecraftInventoryClickTransport(), this);
        return port;
    }

    private void clearExpectedSlots() {
        expectedHotbarSlot = originalHotbarSlot = -1;
        inventorySourceSlot = inventoryAnchorSlot = -1;
    }

    private static int toInventorySlot(int containerSlot) { return containerSlot < 36 ? containerSlot : containerSlot - 36; }

    private static final class VanillaBridgePort implements BridgePort {
        private VanillaInventoryTransactionBridge<Object> delegate;
        public boolean beginSwap(int source, int anchor, Object sourceId, Object anchorId, Object desired) {
            return delegate.beginSwap(source, anchor, sourceId, anchorId, desired);
        }
        public boolean requestRestore(Object source, Object anchor, Object desired) {
            return delegate.requestRestore(source, anchor, desired);
        }
        public void tickTimeout() { delegate.tickTimeout(); }
        public void resetLifecycle() { delegate.resetLifecycle(); }
    }

    /** Minecraft 静态状态的生产读取适配。 */
    private static final class ProductionFacade implements Facade {
        public boolean enabled() { return Config.autoToolSelection != null && Config.autoToolSelection.enabled; }
        public boolean breakBlockMode() {
            return MyMod.chainStateService != null && ChainSubModeRegistry.getTrigger(
                    MyMod.chainStateService.getClientState().getSelectedSubMode()) == ChainSubModeTrigger.BREAK_BLOCK;
        }
        public boolean validInteractionContext() {
            Minecraft mc = Minecraft.getMinecraft();
            EntityPlayer player = mc == null ? null : mc.thePlayer;
            return mc != null && player != null && mc.theWorld != null && mc.currentScreen == null
                    && player.openContainer == player.inventoryContainer && player.inventory.getItemStack() == null
                    && mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        }
        public AutoToolCoordinator.Phase phase() {
            ChainPhase phase = ClientProxy.clientPhaseProjection == null ? ChainPhase.IDLE
                    : ClientProxy.clientPhaseProjection.getCurrentPhase();
            if (phase == ChainPhase.ARMED) return AutoToolCoordinator.Phase.ARMED;
            if (phase == ChainPhase.PLANNING || phase == ChainPhase.RUNNING || phase == ChainPhase.FINISHING) {
                return AutoToolCoordinator.Phase.LOCKED;
            }
            return AutoToolCoordinator.Phase.IDLE;
        }
        public Object target() {
            Minecraft mc = Minecraft.getMinecraft();
            MovingObjectPosition hit = mc == null ? null : mc.objectMouseOver;
            return hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK ? null
                    : new Target(hit.blockX, hit.blockY, hit.blockZ);
        }
        public List<? extends AutoToolCoordinator.Candidate<Object>> candidates() {
            Minecraft mc = Minecraft.getMinecraft();
            MovingObjectPosition hit = mc.objectMouseOver;
            List<ToolSelectionPolicy.Candidate> values = MinecraftToolCandidateFactory.create(
                    mc.thePlayer, mc.theWorld, hit.blockX, hit.blockY, hit.blockZ);
            List<AutoToolCoordinator.Candidate<Object>> result = new ArrayList<AutoToolCoordinator.Candidate<Object>>(36);
            for (ToolSelectionPolicy.Candidate value : values) result.add(new RuntimeCandidate(value, inventoryIdentity(value.slot())));
            return result;
        }
        public int currentHotbarSlot() { return Minecraft.getMinecraft().thePlayer.inventory.currentItem; }
        public void selectHotbarSlot(int slot) { Minecraft.getMinecraft().thePlayer.inventory.currentItem = slot; }
        public Object inventoryIdentity(int slot) {
            ItemStack stack = Minecraft.getMinecraft().thePlayer.inventory.mainInventory[slot];
            return stack;
        }
        public int minimumDurabilityReserve() { return Config.autoToolSelection.minimumRemainingDurability; }
        public int inventoryAnchorHotbarSlot() { return currentHotbarSlot(); }
    }

    private static final class RuntimeCandidate implements AutoToolCoordinator.Candidate<Object> {
        private final ToolSelectionPolicy.Candidate value; private final Object identity;
        private RuntimeCandidate(ToolSelectionPolicy.Candidate value, Object identity) { this.value = value; this.identity = identity; }
        public int slot() { return value.slot(); }
        public boolean canHarvest() { return value.canHarvest(); }
        public int silkTouchLevel() { return value.silkTouchLevel(); }
        public int fortuneLevel() { return value.fortuneLevel(); }
        public double baseSpeed() { return value.baseSpeed(); }
        public int efficiencyLevel() { return value.efficiencyLevel(); }
        public int remainingDurability() { return value.remainingDurability(); }
        public int sourceContainerSlot() { return MinecraftToolCandidateFactory.toContainerPlayerSlot(value.slot()); }
        public Object identity() { return identity; }
    }

    private static final class Target {
        private final int x; private final int y; private final int z;
        private Target(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Target)) return false;
            Target target = (Target) other; return x == target.x && y == target.y && z == target.z;
        }
        @Override public int hashCode() { return (x * 31 + y) * 31 + z; }
    }
}
