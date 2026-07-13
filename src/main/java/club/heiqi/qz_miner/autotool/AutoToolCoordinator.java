package club.heiqi.qz_miner.autotool;

import java.util.Collections;
import java.util.List;

/**
 * 自动工具选择的纯逻辑协调器。调用方每 tick 提交快照，并执行返回的单条命令；
 * 库存和快捷栏操作完成后必须通过确认回调推进状态。
 */
public final class AutoToolCoordinator<T> {
    public enum Phase { IDLE, ARMED, LOCKED }
    public enum CommandType { NONE, SELECT_HOTBAR, SELECT_INVENTORY, RESTORE_HOTBAR, RESTORE_INVENTORY, PAUSE, RESET }

    /** 候选事实及运行时桥所需的槽位和身份。 */
    public interface Candidate<T> extends ToolSelectionPolicy.Candidate {
        int sourceContainerSlot();
        T identity();
    }

    /** 一个 tick 的完整输入；不持有 Minecraft 或 Forge 类型。 */
    public static final class Snapshot<T> {
        public boolean enabled;
        public boolean keyHeld;
        public boolean breakBlockMode;
        public boolean validInteractionContext;
        public boolean manualOverride;
        public boolean lifecycleReset;
        public Phase phase = Phase.IDLE;
        public T target;
        public List<? extends Candidate<T>> candidates = Collections.emptyList();
        public int currentHotbarSlot;
        public int minimumDurabilityReserve;
        public int inventoryAnchorHotbarSlot;
    }

    /** 运行时桥应执行的意图；无关字段为 -1。 */
    public static final class Command {
        public static final Command NONE = new Command(CommandType.NONE, -1, -1);
        public final CommandType type;
        public final int slot;
        public final int anchorHotbarSlot;

        private Command(CommandType type, int slot, int anchorHotbarSlot) {
            this.type = type; this.slot = slot; this.anchorHotbarSlot = anchorHotbarSlot;
        }
    }

    private final TargetStabilizer<T> stabilizer = new TargetStabilizer<T>(2, 2);
    private final AutoToolControllerState<T> state = new AutoToolControllerState<T>();
    private boolean previousKeyHeld;

    public AutoToolControllerState<T> state() { return state; }

    /** 处理 tick 并返回至多一条桥命令。 */
    public Command tick(Snapshot<T> snapshot) {
        if (snapshot.lifecycleReset) {
            clear(); previousKeyHeld = snapshot.keyHeld;
            return command(CommandType.RESET, -1, -1);
        }
        boolean newPress = snapshot.keyHeld && !previousKeyHeld;
        previousKeyHeld = snapshot.keyHeld;
        if (newPress && state.status == AutoToolControllerState.Status.PAUSED) clear();
        if (state.status == AutoToolControllerState.Status.PAUSED) return command(CommandType.PAUSE, -1, -1);

        boolean stop = !snapshot.enabled || !snapshot.keyHeld || !snapshot.breakBlockMode
                || !snapshot.validInteractionContext || snapshot.manualOverride;
        if (stop) return requestEnd();
        if (snapshot.phase == Phase.LOCKED) {
            stabilizer.freeze(true);
            return Command.NONE;
        }
        stabilizer.freeze(false);
        if (snapshot.phase == Phase.IDLE && state.status != AutoToolControllerState.Status.IDLE) return requestEnd();

        T desired = stabilizer.update(snapshot.target);
        state.latestDesiredTarget = desired;
        if (isPending()) return Command.NONE;
        if (desired == null) return requestEnd();
        if (state.status == AutoToolControllerState.Status.ACTIVE && !desired.equals(state.activeTarget)) {
            state.endAfterRestore = false;
            return beginRestore();
        }
        if (state.status == AutoToolControllerState.Status.IDLE) return beginSelect(snapshot, desired);
        return Command.NONE;
    }

    /** bridge 确认最近一条选择或恢复命令成功。 */
    public Command confirm(Snapshot<T> latest) {
        if (state.status == AutoToolControllerState.Status.PENDING_SELECT) {
            state.status = AutoToolControllerState.Status.ACTIVE;
            state.activeToolSlot = state.pendingToolSlot;
            state.activeTarget = state.latestDesiredTarget;
            return Command.NONE;
        }
        if (state.status != AutoToolControllerState.Status.PENDING_RESTORE) return Command.NONE;
        boolean end = state.endAfterRestore;
        int original = state.originalHotbarSlot;
        T desired = state.latestDesiredTarget;
        clearActive();
        if (end || desired == null || latest == null) { stabilizer.reset(); return Command.NONE; }
        state.originalHotbarSlot = original;
        return beginSelect(latest, desired);
    }

    /** bridge 拒绝命令或等待超时：本按键周期暂停。 */
    public Command rejectOrTimeout() {
        state.status = AutoToolControllerState.Status.PAUSED;
        return command(CommandType.PAUSE, -1, -1);
    }

    /** 完整库存重同步通知；暂停仍持续到下一次按键周期。 */
    public void resync() { }

    private Command beginSelect(Snapshot<T> snapshot, T desired) {
        int slot = ToolSelectionPolicy.select(snapshot.candidates, snapshot.currentHotbarSlot,
                snapshot.minimumDurabilityReserve);
        if (slot < 0 || slot == snapshot.currentHotbarSlot) return Command.NONE;
        Candidate<T> candidate = find(snapshot.candidates, slot);
        if (state.originalHotbarSlot < 0) state.originalHotbarSlot = snapshot.currentHotbarSlot;
        state.pendingToolSlot = slot;
        state.latestDesiredTarget = desired;
        state.inventorySwapActive = slot > 8;
        state.status = AutoToolControllerState.Status.PENDING_SELECT;
        return slot <= 8 ? command(CommandType.SELECT_HOTBAR, slot, -1)
                : command(CommandType.SELECT_INVENTORY, candidate.sourceContainerSlot(), snapshot.inventoryAnchorHotbarSlot);
    }

    private Command requestEnd() {
        stabilizer.reset(); state.latestDesiredTarget = null; state.endAfterRestore = true;
        if (isPending()) return Command.NONE;
        if (state.status == AutoToolControllerState.Status.ACTIVE) return beginRestore();
        clear(); return Command.NONE;
    }

    private Command beginRestore() {
        state.status = AutoToolControllerState.Status.PENDING_RESTORE;
        return state.inventorySwapActive ? command(CommandType.RESTORE_INVENTORY, -1, -1)
                : command(CommandType.RESTORE_HOTBAR, state.originalHotbarSlot, -1);
    }

    private boolean isPending() {
        return state.status == AutoToolControllerState.Status.PENDING_SELECT
                || state.status == AutoToolControllerState.Status.PENDING_RESTORE;
    }

    private void clear() { stabilizer.reset(); clearActive(); state.status = AutoToolControllerState.Status.IDLE; }
    private void clearActive() {
        state.originalHotbarSlot = -1; state.activeToolSlot = -1; state.pendingToolSlot = -1;
        state.activeTarget = null; state.inventorySwapActive = false; state.endAfterRestore = false;
        state.status = AutoToolControllerState.Status.IDLE;
    }
    private static <T> Candidate<T> find(List<? extends Candidate<T>> candidates, int slot) {
        for (Candidate<T> candidate : candidates) if (candidate.slot() == slot) return candidate;
        throw new IllegalStateException("selected candidate disappeared");
    }
    private static Command command(CommandType type, int slot, int anchor) { return new Command(type, slot, anchor); }
}
