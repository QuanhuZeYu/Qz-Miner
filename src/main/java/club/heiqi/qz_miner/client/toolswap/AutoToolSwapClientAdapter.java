package club.heiqi.qz_miner.client.toolswap;

import java.util.List;
import java.util.concurrent.TimeUnit;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 新版自动工具换位的长寿命客户端 adapter。
 *
 * <p>它是纯 controller 的唯一运行时调用方。每个入口先捕获新鲜事实，drain 命令后才调用
 * 原版 API；出入包只按 connection/world token 接受当前生命周期的数据。</p>
 */
@SideOnly(Side.CLIENT)
public final class AutoToolSwapClientAdapter {

    public static final int TRANSACTION_TIMEOUT_TICKS = 40;
    private static final long ISOLATION_LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(10L);

    /** Minecraft 边界，测试可用纯假实现替换。 */
    public interface GameFacade {
        ToolSwapContext captureContext(long tick, boolean chainActive);
        Object connectionIdentity();
        void executeMode2(int candidateContainerSlot, int anchorHotbarIndex);
    }

    /** phase 投影只读边界。 */
    public interface PhaseSource {
        ToolSwapPhaseSnapshot snapshot();
    }

    private final AutoToolSwapController controller;
    private final GameFacade game;
    private final PhaseSource phaseSource;

    private long clientTick;
    private boolean keyDown;
    private int phaseBaseline;
    private boolean attributedActivity;
    private boolean serverActivityObserved;
    private int activityServerGeneration;
    private ClickIntent clickIntent;
    private int protectedAnchor = -1;
    private int protectedCandidate = -1;
    private int synchronizationCoverage;
    private long lastSynchronizationTick = -1L;
    private long lastIsolationLogNanos = Long.MIN_VALUE;

    public AutoToolSwapClientAdapter(
            boolean enabled, List<ToolSelector> selectors, GameFacade game, PhaseSource phaseSource) {
        if (game == null || phaseSource == null) {
            throw new IllegalArgumentException("game/phaseSource must not be null");
        }
        this.controller = new AutoToolSwapController(enabled, selectors, TRANSACTION_TIMEOUT_TICKS);
        this.game = game;
        this.phaseSource = phaseSource;
    }

    /** 真实按键边沿入口；上升沿会在当前 tick 立即匹配。 */
    public void onChainKeyState(boolean down) {
        if (down == keyDown) {
            return;
        }
        keyDown = down;
        if (down) {
            ToolSwapPhaseSnapshot phase = safePhaseSnapshot();
            phaseBaseline = phase.generation;
            attributedActivity = false;
            serverActivityObserved = false;
            activityServerGeneration = phaseBaseline;
        }
        ToolSwapContext context = game.captureContext(clientTick, true);
        if (context == null) {
            if (!down) {
                controller.reset();
            }
            return;
        }
        controller.onKeyState(down, context);
        executeCommands();
        noteIsolationIfNeeded("key-edge");
    }

    /** 每个 ClientTickEvent.END 恰好调用一次。 */
    public void onClientTick() {
        observeAttributedPhase();
        boolean chainActive = keyDown && !chainEndedByAttributedPhase();
        ToolSwapContext context = game.captureContext(clientTick, chainActive);
        if (context != null) {
            recoverAfterQuietSynchronizationTick(context);
            controller.onTick(context);
            executeCommands();
            noteIsolationIfNeeded("tick");
        }
        if (clientTick != Long.MAX_VALUE) {
            clientTick++;
        }
    }

    /** 配置 static 发布后的显式热更新入口。 */
    public void onConfigChanged(boolean enabled, List<ToolSelector> selectors) {
        controller.onConfigChanged(enabled, selectors);
    }

    /** 本地 onPlayerDestroyBlock=true：立即单向冻结并作为 phase IDLE 结束归因的兜底。 */
    public void onLocalBlockDestroyed() {
        if (!keyDown) {
            return;
        }
        attributedActivity = true;
        activityServerGeneration = Math.max(activityServerGeneration, phaseBaseline);
        controller.onLocalBlockDestroyed(controller.generation());
    }

    /** 精确 C0E intent 捕获；只有同步 windowClick 临界区内的完全匹配包可分配真实 id。 */
    public void onClickWindowPacket(
            Object handler, int windowId, int containerSlot, int button, int mode, int actionNumber) {
        ClickIntent intent = clickIntent;
        if (intent == null || intent.handler != handler || intent.windowId != windowId
                || intent.containerSlot != containerSlot || intent.button != button || intent.mode != mode) {
            return;
        }
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.captureForConnection(handler);
        if (token == null || !ClientConnectionLifecycle.isWorldCurrentAndActive(token)) {
            return;
        }
        controller.onPacketIdAssigned(intent.generation, actionNumber, clientTick);
        intent.captured = true;
    }

    /** vanilla RETURN 后的 S32 观察。 */
    public void onTransactionAck(
            ClientConnectionLifecycle.Token token, int windowId, int actionNumber, boolean accepted) {
        if (!isCurrentWorld(token) || windowId != 0) {
            return;
        }
        controller.onTransactionAck(controller.generation(), actionNumber, accepted, clientTick);
        noteIsolationIfNeeded(accepted ? "ack" : "rejected");
    }

    /** vanilla RETURN 后的 S2F 覆盖观察；只记录两个受保护槽。 */
    public void onSetSlot(ClientConnectionLifecycle.Token token, int windowId, int containerSlot) {
        if (!isCurrentWorld(token) || windowId != 0 || protectedAnchor < 0 || protectedCandidate < 0) {
            return;
        }
        int anchorContainer = ToolSwapMinecraftFacade.toContainerSlot(protectedAnchor);
        int candidateContainer = ToolSwapMinecraftFacade.toContainerSlot(protectedCandidate);
        if (containerSlot == anchorContainer) {
            synchronizationCoverage |= 1;
        }
        if (containerSlot == candidateContainer) {
            synchronizationCoverage |= 2;
        }
        if (containerSlot == anchorContainer || containerSlot == candidateContainer) {
            lastSynchronizationTick = clientTick;
        }
    }

    /** vanilla RETURN 后的 S30 window-0 观察；完整窗口覆盖两个受保护槽。 */
    public void onWindowItems(ClientConnectionLifecycle.Token token, int windowId) {
        if (!isCurrentWorld(token) || windowId != 0 || protectedAnchor < 0 || protectedCandidate < 0) {
            return;
        }
        synchronizationCoverage = 3;
        lastSynchronizationTick = clientTick;
    }

    /**
     * 连接/世界卸载/接管统一清理。突发断线不跨生命周期发送恢复，旧回调全部 no-op。
     */
    public void resetForLifecycle() {
        controller.reset();
        keyDown = false;
        clickIntent = null;
        protectedAnchor = -1;
        protectedCandidate = -1;
        synchronizationCoverage = 0;
        lastSynchronizationTick = -1L;
        attributedActivity = false;
        serverActivityObserved = false;
        phaseBaseline = 0;
        activityServerGeneration = 0;
    }

    AutoToolSwapController controllerForTests() {
        return controller;
    }

    private void observeAttributedPhase() {
        if (!keyDown) {
            return;
        }
        ToolSwapPhaseSnapshot snapshot = safePhaseSnapshot();
        if (snapshot.generation > phaseBaseline && isActivePhase(snapshot.phase)) {
            attributedActivity = true;
            serverActivityObserved = true;
            activityServerGeneration = Math.max(activityServerGeneration, snapshot.generation);
            controller.onServerActivity(controller.generation());
        }
    }

    private boolean chainEndedByAttributedPhase() {
        if (!attributedActivity) {
            return false;
        }
        ToolSwapPhaseSnapshot snapshot = safePhaseSnapshot();
        return snapshot.phase == ChainPhase.IDLE
                && snapshot.generation >= activityServerGeneration
                && snapshot.generation >= phaseBaseline
                && (serverActivityObserved || snapshot.generation > phaseBaseline);
    }

    private static boolean isActivePhase(ChainPhase phase) {
        return phase == ChainPhase.PLANNING || phase == ChainPhase.RUNNING || phase == ChainPhase.FINISHING;
    }

    private ToolSwapPhaseSnapshot safePhaseSnapshot() {
        ToolSwapPhaseSnapshot snapshot = phaseSource.snapshot();
        return snapshot == null ? new ToolSwapPhaseSnapshot(ChainPhase.IDLE, 0) : snapshot;
    }

    private void executeCommands() {
        for (int rounds = 0; rounds < 8; rounds++) {
            List<ToolSwapCommand> commands = controller.drainCommands();
            if (commands.isEmpty()) {
                return;
            }
            for (ToolSwapCommand command : commands) {
                protectedAnchor = command.anchorSlot;
                protectedCandidate = command.candidateSlot;
                if (command.type == ToolSwapCommand.Type.VERIFY_SLOTS) {
                    ToolSwapContext context = game.captureContext(clientTick, keyDown && !chainEndedByAttributedPhase());
                    if (context != null && command.transactionId != null) {
                        controller.onSlotsObserved(
                                command.generation, command.transactionId.intValue(), context.inventory);
                    }
                } else {
                    executeClick(command);
                }
            }
        }
        throw new IllegalStateException("tool swap command drain did not quiesce");
    }

    private void executeClick(ToolSwapCommand command) {
        Object handler = game.connectionIdentity();
        if (handler == null || clickIntent != null) {
            return;
        }
        synchronizationCoverage = 0;
        lastSynchronizationTick = -1L;
        int containerSlot = ToolSwapMinecraftFacade.toContainerSlot(command.candidateSlot);
        ClickIntent intent = new ClickIntent(
                handler, command.generation, 0, containerSlot, command.anchorSlot, 2);
        clickIntent = intent;
        try {
            game.executeMode2(containerSlot, command.anchorSlot);
        } finally {
            clickIntent = null;
        }
        // 未捕获时保持 WAIT_PACKET_ID，由核心统一超时；不得猜 transaction counter。
        if (!intent.captured) {
            MyMod.LOG.debug("[AutoToolSwap] Vanilla click emitted no attributable C0E; waiting for timeout");
        }
    }

    private void recoverAfterQuietSynchronizationTick(ToolSwapContext context) {
        if (controller.transactionState() != ToolSwapTransactionState.SYNC_ISOLATION
                || synchronizationCoverage != 3 || lastSynchronizationTick < 0L
                || clientTick <= lastSynchronizationTick) {
            return;
        }
        synchronizationCoverage = 0;
        lastSynchronizationTick = -1L;
        controller.onSynchronizationRecovered(context.inventory);
    }

    private boolean isCurrentWorld(ClientConnectionLifecycle.Token token) {
        return token != null && ClientConnectionLifecycle.isWorldCurrentAndActive(token);
    }

    private void noteIsolationIfNeeded(String source) {
        if (controller.transactionState() != ToolSwapTransactionState.SYNC_ISOLATION) {
            return;
        }
        long now = System.nanoTime();
        if (lastIsolationLogNanos == Long.MIN_VALUE || now - lastIsolationLogNanos >= ISOLATION_LOG_INTERVAL_NS) {
            lastIsolationLogNanos = now;
            MyMod.LOG.warn("[AutoToolSwap] Inventory transaction entered synchronization isolation, source={}", source);
        }
    }

    /** 同步 windowClick 临界区的精确包意图。 */
    private static final class ClickIntent {
        private final Object handler;
        private final long generation;
        private final int windowId;
        private final int containerSlot;
        private final int button;
        private final int mode;
        private boolean captured;

        private ClickIntent(Object handler, long generation, int windowId, int containerSlot, int button, int mode) {
            this.handler = handler;
            this.generation = generation;
            this.windowId = windowId;
            this.containerSlot = containerSlot;
            this.button = button;
            this.mode = mode;
        }
    }
}
