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
 * <p>每个入口先捕获轻量事实，由纯核心选择库存采样计划，再执行不可变快照捕获。
 * 网络回调只按 connection/world token 接受当前生命周期的数据。</p>
 */
@SideOnly(Side.CLIENT)
public final class AutoToolSwapClientAdapter {

    public static final int TRANSACTION_TIMEOUT_TICKS = 40;
    private static final long ISOLATION_LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(10L);

    /** Minecraft 边界，测试可用纯假实现替换。 */
    public interface GameFacade {
        ToolSwapLightContext captureLightContext(long tick, boolean chainActive);
        ToolSwapContext captureContext(ToolSwapLightContext light, ToolSwapCapturePlan plan,
                int anchorSlot, int candidateSlot);
        boolean isChainKeyPhysicallyDown();
        Object connectionIdentity();
        ToolSwapClickResult executeMode2(int candidateContainerSlot, int anchorHotbarIndex);
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
    private ChainPhase phaseBaselinePhase = ChainPhase.IDLE;
    private int phaseBaseline;
    private boolean baselineArmedLeft;
    private boolean armedObserved;
    private int armedGeneration;
    private boolean localActivityObserved;
    private boolean serverActivityObserved;
    private int activityServerGeneration;
    private ClickIntent clickIntent;
    private int protectedAnchor = -1;
    private int protectedCandidate = -1;
    private int synchronizationCoverage;
    private long lastSynchronizationTick = -1L;
    private long lastIsolationLogNanos = Long.MIN_VALUE;
    private long preEdgeDestroyTick = -1L;
    private ClientConnectionLifecycle.Token preEdgeDestroyToken;

    public AutoToolSwapClientAdapter(
            boolean enabled, List<ToolSelector> selectors, GameFacade game, PhaseSource phaseSource) {
        if (game == null || phaseSource == null) {
            throw new IllegalArgumentException("game/phaseSource must not be null");
        }
        this.controller = new AutoToolSwapController(enabled, selectors, TRANSACTION_TIMEOUT_TICKS);
        this.game = game;
        this.phaseSource = phaseSource;
    }

    /** 真实按键边沿入口；上升沿会按计划在当前 tick 立即匹配。 */
    public void onChainKeyState(boolean down) {
        if (down == keyDown) {
            return;
        }
        boolean preFrozen = down && consumePreEdgeDestroyLatch();
        clearPreEdgeDestroyLatch();
        keyDown = down;
        if (down) {
            ToolSwapPhaseSnapshot phase = safePhaseSnapshot();
            phaseBaselinePhase = phase.phase;
            phaseBaseline = phase.generation;
            baselineArmedLeft = phase.phase != ChainPhase.ARMED;
            armedObserved = false;
            armedGeneration = phaseBaseline;
            localActivityObserved = preFrozen;
            serverActivityObserved = false;
            activityServerGeneration = phaseBaseline;
        }
        ToolSwapLightContext light = game.captureLightContext(clientTick, down);
        if (light == null) {
            if (!down) {
                controller.reset();
            }
            return;
        }
        ToolSwapCapturePlan plan = controller.capturePlanForKeyState(down, light, preFrozen);
        ToolSwapContext context = capture(light, plan,
                controller.protectedAnchorSlot(), controller.protectedCandidateSlot());
        if (context == null) {
            if (!down) {
                controller.reset();
            }
            return;
        }
        controller.onKeyState(down, context, preFrozen);
        executeCommands();
        noteIsolationIfNeeded("key-edge");
    }

    /** 每个 ClientTickEvent.END 恰好调用一次。 */
    public void onClientTick() {
        ToolSwapPhaseSnapshot phase = safePhaseSnapshot();
        observeAttributedPhase(phase);
        boolean chainActive = keyDown && !chainEndedByAttributedPhase(phase);
        ToolSwapLightContext light = game.captureLightContext(clientTick, chainActive);
        if (light != null) {
            ToolSwapCapturePlan plan = controller.capturePlanForTick(light);
            ToolSwapContext context = capture(light, plan,
                    controller.protectedAnchorSlot(), controller.protectedCandidateSlot());
            if (context != null) {
                recoverAfterQuietSynchronizationTick(context);
                controller.onTick(context);
                executeCommands();
                noteIsolationIfNeeded("tick");
            }
        }
        if (clientTick != Long.MAX_VALUE) {
            clientTick++;
        }
        clearPreEdgeDestroyLatch();
    }

    /** 配置 static 发布后的显式热更新入口。 */
    public void onConfigChanged(boolean enabled, List<ToolSelector> selectors) {
        controller.onConfigChanged(enabled, selectors);
    }

    /** 本地 onPlayerDestroyBlock=true；边沿前信号只锁存当前 tick 与当前 world token。 */
    public void onLocalBlockDestroyed() {
        if (!game.isChainKeyPhysicallyDown()) {
            clearPreEdgeDestroyLatch();
            return;
        }
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.capture();
        if (!ClientConnectionLifecycle.isWorldCurrentAndActive(token)) {
            clearPreEdgeDestroyLatch();
            return;
        }
        if (!keyDown) {
            preEdgeDestroyTick = clientTick;
            preEdgeDestroyToken = token;
            return;
        }
        localActivityObserved = true;
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

    /** 连接/世界卸载/接管统一清理；不跨生命周期发送恢复。 */
    public void resetForLifecycle() {
        controller.reset();
        keyDown = false;
        clickIntent = null;
        protectedAnchor = -1;
        protectedCandidate = -1;
        synchronizationCoverage = 0;
        lastSynchronizationTick = -1L;
        phaseBaselinePhase = ChainPhase.IDLE;
        phaseBaseline = 0;
        baselineArmedLeft = false;
        armedObserved = false;
        armedGeneration = 0;
        localActivityObserved = false;
        serverActivityObserved = false;
        activityServerGeneration = 0;
        clearPreEdgeDestroyLatch();
    }

    AutoToolSwapController controllerForTests() {
        return controller;
    }

    private void observeAttributedPhase(ToolSwapPhaseSnapshot snapshot) {
        if (!keyDown) {
            return;
        }
        if (!armedObserved) {
            if (phaseBaselinePhase == ChainPhase.ARMED && !baselineArmedLeft) {
                if (snapshot.phase != ChainPhase.ARMED) {
                    baselineArmedLeft = true;
                }
                return;
            }
            if (snapshot.phase == ChainPhase.ARMED && snapshot.generation >= phaseBaseline) {
                armedObserved = true;
                armedGeneration = snapshot.generation;
            }
            return;
        }
        if (snapshot.generation > armedGeneration && isActivePhase(snapshot.phase)) {
            serverActivityObserved = true;
            activityServerGeneration = Math.max(activityServerGeneration, snapshot.generation);
            controller.onServerActivity(controller.generation());
        }
    }

    private boolean chainEndedByAttributedPhase(ToolSwapPhaseSnapshot snapshot) {
        if (snapshot.phase != ChainPhase.IDLE) {
            return false;
        }
        if (serverActivityObserved && snapshot.generation >= activityServerGeneration) {
            return true;
        }
        return localActivityObserved && snapshot.generation > phaseBaseline;
    }

    private static boolean isActivePhase(ChainPhase phase) {
        return phase == ChainPhase.PLANNING || phase == ChainPhase.RUNNING || phase == ChainPhase.FINISHING;
    }

    private ToolSwapPhaseSnapshot safePhaseSnapshot() {
        ToolSwapPhaseSnapshot snapshot = phaseSource.snapshot();
        return snapshot == null ? new ToolSwapPhaseSnapshot(ChainPhase.IDLE, 0) : snapshot;
    }

    private ToolSwapContext capture(ToolSwapLightContext light, ToolSwapCapturePlan plan,
            int anchorSlot, int candidateSlot) {
        return game.captureContext(light, plan, anchorSlot, candidateSlot);
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
                    ToolSwapContext context = captureProtected(command);
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

    private ToolSwapContext captureProtected(ToolSwapCommand command) {
        ToolSwapPhaseSnapshot phase = safePhaseSnapshot();
        boolean chainActive = keyDown && !chainEndedByAttributedPhase(phase);
        ToolSwapLightContext light = game.captureLightContext(clientTick, chainActive);
        return light == null ? null : capture(light, ToolSwapCapturePlan.PROTECTED,
                command.anchorSlot, command.candidateSlot);
    }

    private void executeClick(ToolSwapCommand command) {
        ToolSwapContext preflight = captureProtected(command);
        if (preflight == null || !controller.onClickPreflight(command.generation, preflight.inventory)) {
            return;
        }
        Object handler = game.connectionIdentity();
        if (handler == null || clickIntent != null) {
            controller.onClickNotStarted(command.generation);
            return;
        }
        synchronizationCoverage = 0;
        lastSynchronizationTick = -1L;
        int containerSlot = ToolSwapMinecraftFacade.toContainerSlot(command.candidateSlot);
        ClickIntent intent = new ClickIntent(
                handler, command.generation, 0, containerSlot, command.anchorSlot, 2);
        clickIntent = intent;
        ToolSwapClickResult result;
        try {
            result = game.executeMode2(containerSlot, command.anchorSlot);
        } catch (RuntimeException failure) {
            controller.onClickMayHaveStartedWithoutCompletion(command.generation);
            MyMod.LOG.warn("[AutoToolSwap] Vanilla mode-2 click failed after final preflight", failure);
            return;
        } catch (LinkageError failure) {
            controller.onClickMayHaveStartedWithoutCompletion(command.generation);
            MyMod.LOG.warn("[AutoToolSwap] Vanilla mode-2 click linkage failure after final preflight", failure);
            return;
        } finally {
            clickIntent = null;
        }
        if (result != ToolSwapClickResult.VANILLA_CALLED) {
            controller.onClickNotStarted(command.generation);
            return;
        }
        if (!intent.captured) {
            controller.onClickMayHaveStartedWithoutCompletion(command.generation);
            MyMod.LOG.debug("[AutoToolSwap] Vanilla click emitted no attributable C0E; isolated");
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

    private boolean consumePreEdgeDestroyLatch() {
        return preEdgeDestroyTick == clientTick
                && preEdgeDestroyToken != null
                && game.isChainKeyPhysicallyDown()
                && ClientConnectionLifecycle.isWorldCurrentAndActive(preEdgeDestroyToken);
    }

    private void clearPreEdgeDestroyLatch() {
        preEdgeDestroyTick = -1L;
        preEdgeDestroyToken = null;
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
