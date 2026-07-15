package club.heiqi.qz_miner.client.toolswap;

import java.util.List;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.client.toolswap.protocol.AutoToolSwapClientProtocolPhase;
import club.heiqi.qz_miner.client.toolswap.protocol.AutoToolSwapClientProtocolPhaseSnapshot;
import club.heiqi.qz_miner.client.toolswap.protocol.AutoToolSwapClientProtocolSettlement;
import club.heiqi.qz_miner.client.toolswap.protocol.AutoToolSwapClientProtocolSnapshot;
import club.heiqi.qz_miner.client.toolswap.protocol.AutoToolSwapClientProtocolState;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 自动工具换位的客户端运行态 adapter。
 *
 * <p>所有入口都运行在客户端主线程。S2C 回包由 ClientProxy 在 lifecycle/world gate 后发布；adapter 不读取
 * 通用 phase 投影，也不保留 vanilla inventory packet 路径。</p>
 */
@SideOnly(Side.CLIENT)
public final class AutoToolSwapClientAdapter {

    public static final int TRANSACTION_TIMEOUT_TICKS = 40;
    private static final int RETRANSMIT_TICKS = 20;

    /** Minecraft 事实读取边界。 */
    public interface GameFacade {
        ToolSwapLightContext captureLightContext(long tick, boolean chainActive);
        ToolSwapContext captureContext(ToolSwapLightContext light, ToolSwapCapturePlan plan,
                int anchorSlot, int candidateSlot);
        boolean isChainKeyPhysicallyDown();
    }

    private final AutoToolSwapController controller;
    private final GameFacade game;
    private final AutoToolSwapClientTransport transport;
    private final AutoToolSwapClientProtocolState protocol;
    private long clientTick;
    private boolean keyDown;
    private boolean dedicatedRoundEnded;
    private long roundSentTick = -1L;
    private boolean roundRetransmitted;
    private long actionSentTick = -1L;
    private boolean actionRetransmitted;
    private long preEdgeDestroyTick = -1L;
    private ClientConnectionLifecycle.Token preEdgeDestroyToken;

    public AutoToolSwapClientAdapter(boolean enabled, List<ToolSelector> selectors, GameFacade game,
            AutoToolSwapClientTransport transport, AutoToolSwapClientProtocolState protocol) {
        if (game == null || transport == null || protocol == null) {
            throw new IllegalArgumentException("game, transport, and protocol must not be null");
        }
        controller = new AutoToolSwapController(enabled, selectors, TRANSACTION_TIMEOUT_TICKS);
        this.game = game;
        this.transport = transport;
        this.protocol = protocol;
    }

    /** 真实按键边沿入口；上升沿先发送 round，再由外层发送 KeyState。 */
    public void onChainKeyState(boolean down) {
        if (down == keyDown) return;
        boolean preFrozen = down && consumePreEdgeDestroyLatch();
        clearPreEdgeDestroyLatch();
        keyDown = down;
        if (!down) protocol.markClosing();
        ToolSwapLightContext light = game.captureLightContext(clientTick, chainActive());
        if (light == null) {
            if (!down) resetForLifecycle();
            return;
        }
        ToolSwapContext context = capture(light, controller.capturePlanForKeyState(down, light, preFrozen),
                controller.protectedAnchorSlot(), controller.protectedCandidateSlot());
        if (context == null) {
            if (!down) resetForLifecycle();
            return;
        }
        controller.onKeyState(down, context, preFrozen);
        executeCommands();
    }

    /** 每个 ClientTickEvent.END 调用一次。 */
    public void onClientTick() {
        ToolSwapLightContext light = game.captureLightContext(clientTick, chainActive());
        if (light != null) {
            ToolSwapContext context = capture(light, controller.capturePlanForTick(light),
                    controller.protectedAnchorSlot(), controller.protectedCandidateSlot());
            if (context != null) {
                controller.onTick(context);
                executeCommands();
            }
        }
        retryOrOrphan();
        if (clientTick != Long.MAX_VALUE) clientTick++;
        clearPreEdgeDestroyLatch();
    }

    /** 配置热更新。 */
    public void onConfigChanged(boolean enabled, List<ToolSelector> selectors) {
        controller.onConfigChanged(enabled, selectors);
        executeCommands();
    }

    /** 本地成功破坏的首块锁存/冻结；立即 drain 使 FREEZE 不等下一 tick。 */
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
        controller.onLocalBlockDestroyed(controller.generation());
        executeCommands();
    }

    /** ClientProxy 主线程 gate 后发布的 RoundResult 原始字段。 */
    public void onRoundResult(int protocolVersion, long clientNonce, long serverRoundId, int resultCode,
            int roundState, long nextActionSequence, long serverTick, boolean rawValid) {
        AutoToolSwapClientProtocolSnapshot snapshot = protocol.onRoundResult(protocolVersion, clientNonce,
                serverRoundId, resultCode, roundState, nextActionSequence, serverTick, rawValid);
        if (snapshot == null) return;
        if (snapshot.phase() == AutoToolSwapClientProtocolPhase.OPEN
                || snapshot.phase() == AutoToolSwapClientProtocolPhase.CLOSING) {
            controller.onRoundAccepted();
            executeCommands();
        } else if (snapshot.phase() == AutoToolSwapClientProtocolPhase.IDLE) {
            controller.onRoundRejected();
        } else if (snapshot.phase() == AutoToolSwapClientProtocolPhase.ORPHANED) {
            controller.protocolOrphaned();
        }
    }

    /** ClientProxy 主线程 gate 后发布的 ActionResult 原始字段。 */
    public void onActionResult(int protocolVersion, long serverRoundId, long actionSequence, int actionCode,
            int resultCode, int roundState, int anchorSlot, int candidateSlot, long nextActionSequence,
            long serverTick, boolean rawValid) {
        if (protocol.inFlightIntent() == null) return;
        AutoToolSwapClientProtocolSettlement settlement = protocol.onActionResult(protocolVersion, serverRoundId,
                actionSequence, actionCode, resultCode, roundState, anchorSlot, candidateSlot,
                nextActionSequence, serverTick, rawValid);
        if (settlement == null) return;
        actionSentTick = -1L;
        actionRetransmitted = false;
        controller.onActionSettled(settlement.intent().action(), settlement.result().outcome(), clientTick);
        executeCommands();
    }

    /** ClientProxy 主线程 gate 后发布的专用 round phase。 */
    public void onRoundPhase(int protocolVersion, long serverRoundId, long phaseSequence, int phaseOrdinal,
            int generation, long serverTick, boolean rawValid) {
        AutoToolSwapClientProtocolPhaseSnapshot snapshot = protocol.onRoundPhase(protocolVersion, serverRoundId,
                phaseSequence, phaseOrdinal, generation, serverTick, rawValid);
        if (snapshot == null) return;
        if (snapshot.phase() == ChainPhase.IDLE) dedicatedRoundEnded = true;
        controller.onDedicatedPhase(snapshot.phase());
        executeCommands();
    }

    /** 生命周期复位清 controller、protocol、重发水位和首块锁存，且绝不发送恢复包。 */
    public void resetForLifecycle() {
        controller.reset();
        protocol.reset();
        keyDown = false;
        dedicatedRoundEnded = false;
        roundSentTick = -1L;
        roundRetransmitted = false;
        actionSentTick = -1L;
        actionRetransmitted = false;
        clearPreEdgeDestroyLatch();
    }

    AutoToolSwapController controllerForTests() { return controller; }
    AutoToolSwapClientProtocolState protocolForTests() { return protocol; }

    private void executeCommands() {
        for (int rounds = 0; rounds < 8; rounds++) {
            List<ToolSwapCommand> commands = controller.drainCommands();
            if (commands.isEmpty()) return;
            for (ToolSwapCommand command : commands) execute(command);
        }
        throw new IllegalStateException("tool swap command drain did not quiesce");
    }

    private void execute(ToolSwapCommand command) {
        if (command.type == ToolSwapCommand.Type.BEGIN_ROUND) {
            long nonce = protocol.beginRound();
            if (nonce <= 0L || !sendRound(nonce, true)) protocolFailure();
            return;
        }
        if (command.type == ToolSwapCommand.Type.SEND_SWAP || command.type == ToolSwapCommand.Type.SEND_RESTORE) {
            ToolSwapContext context = captureProtected(command);
            if (context == null || !controller.onActionPreflight(command, context)) {
                controller.onActionNotStarted(command.type == ToolSwapCommand.Type.SEND_SWAP
                        ? AutoToolSwapAction.SWAP : AutoToolSwapAction.RESTORE);
                return;
            }
            SlotSnapshot anchor = context.inventory.slot(command.anchorSlot);
            SlotSnapshot candidate = context.inventory.slot(command.candidateSlot);
            AutoToolSwapAction action = command.type == ToolSwapCommand.Type.SEND_SWAP
                    ? AutoToolSwapAction.SWAP : AutoToolSwapAction.RESTORE;
            AutoToolSwapIntent intent = protocol.beginAction(action, command.anchorSlot, command.candidateSlot,
                    anchor.contentFingerprint(), candidate.contentFingerprint());
            if (intent == null || !sendIntent(intent)) protocolFailure();
            else {
                controller.onActionStarted(action);
                actionSentTick = clientTick;
                actionRetransmitted = false;
            }
            return;
        }
        AutoToolSwapAction action = command.type == ToolSwapCommand.Type.SEND_FREEZE
                ? AutoToolSwapAction.FREEZE : AutoToolSwapAction.CLOSE;
        AutoToolSwapIntent intent = protocol.beginControlAction(action);
        if (intent == null || !sendIntent(intent)) protocolFailure();
        else {
            controller.onActionStarted(action);
            actionSentTick = clientTick;
            actionRetransmitted = false;
        }
    }

    private ToolSwapContext captureProtected(ToolSwapCommand command) {
        ToolSwapLightContext light = game.captureLightContext(clientTick, chainActive());
        return light == null ? null : capture(light, ToolSwapCapturePlan.PROTECTED,
                command.anchorSlot, command.candidateSlot);
    }

    private ToolSwapContext capture(ToolSwapLightContext light, ToolSwapCapturePlan plan,
            int anchorSlot, int candidateSlot) {
        return game.captureContext(light, plan, anchorSlot, candidateSlot);
    }

    private boolean sendRound(long nonce, boolean initialSend) {
        try {
            if (!transport.sendRoundStart(nonce)) return false;
            if (initialSend) {
                roundSentTick = clientTick;
                roundRetransmitted = false;
            }
            return true;
        } catch (RuntimeException failure) {
            return false;
        } catch (LinkageError failure) {
            return false;
        }
    }

    private boolean sendIntent(AutoToolSwapIntent intent) {
        try {
            return transport.sendIntent(intent);
        } catch (RuntimeException failure) {
            return false;
        } catch (LinkageError failure) {
            return false;
        }
    }

    private void retryOrOrphan() {
        AutoToolSwapClientProtocolSnapshot snapshot = protocol.snapshot();
        if ((snapshot.phase() == AutoToolSwapClientProtocolPhase.WAIT_ROUND
                || snapshot.phase() == AutoToolSwapClientProtocolPhase.WAIT_ROUND_CLOSING) && roundSentTick >= 0L) {
            if (clientTick - roundSentTick >= TRANSACTION_TIMEOUT_TICKS) {
                protocolFailure();
            } else if (!roundRetransmitted && clientTick - roundSentTick >= RETRANSMIT_TICKS) {
                if (sendRound(snapshot.pendingNonce(), false)) roundRetransmitted = true;
                else protocolFailure();
            }
            return;
        }
        AutoToolSwapIntent intent = protocol.inFlightIntent();
        if (intent == null || actionSentTick < 0L) return;
        if (clientTick - actionSentTick >= TRANSACTION_TIMEOUT_TICKS) {
            protocolFailure();
        } else if (!actionRetransmitted && clientTick - actionSentTick >= RETRANSMIT_TICKS) {
            if (sendIntent(intent)) actionRetransmitted = true;
            else protocolFailure();
        }
    }

    private void protocolFailure() {
        protocol.abandonCurrentRound();
        controller.protocolOrphaned();
        roundSentTick = -1L;
        actionSentTick = -1L;
    }

    private boolean chainActive() { return keyDown && !dedicatedRoundEnded; }

    private boolean consumePreEdgeDestroyLatch() {
        return preEdgeDestroyTick == clientTick && preEdgeDestroyToken != null
                && game.isChainKeyPhysicallyDown()
                && ClientConnectionLifecycle.isWorldCurrentAndActive(preEdgeDestroyToken);
    }

    private void clearPreEdgeDestroyLatch() {
        preEdgeDestroyTick = -1L;
        preEdgeDestroyToken = null;
    }
}
