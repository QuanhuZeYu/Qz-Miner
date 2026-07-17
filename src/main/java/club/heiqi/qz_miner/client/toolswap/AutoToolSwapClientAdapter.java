package club.heiqi.qz_miner.client.toolswap;

import java.util.List;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.ActionResultEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.ConfigEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.Effect;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.EffectResultEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.KeyStateEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.LocalBlockDestroyedEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.ResetEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.RoundPhaseEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.RoundResultEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.TickEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.TakeoverRequestEvent;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 自动工具客户端的薄 runtime adapter。
 *
 * <p>本类只采样 Minecraft 事实、执行 reducer effect 与进行网络 I/O；所有可跨调用保留的业务事实均由
 * {@link AutoToolSwapClientReducer} 独占。S2C publication 只推进 reducer，绝不在 callback 内发送 C2S。</p>
 */
@SideOnly(Side.CLIENT)
public final class AutoToolSwapClientAdapter {

    static final String RELEASE_CAPTURE_FAILED_MARKER =
            "[AutoToolSwap] release fact capture failed; preserving round for retry";

    private static final AutoToolSwapClientReducer.DiagnosticSink PRODUCTION_DIAGNOSTIC_SINK =
            new AutoToolSwapClientReducer.DiagnosticSink() {
                @Override
                public void log(String message) {
                    // 原因探针保留在文件级 DEBUG，避免默认终端输出刷屏。
                    MyMod.LOG.debug(message);
                }
            };

    /** Minecraft 事实读取边界。 */
    public interface GameFacade {
        ToolSwapLightContext captureLightContext(long tick, boolean chainActive);
        ToolSwapContext captureContext(ToolSwapLightContext light, ToolSwapCapturePlan plan,
                int anchorSlot, int candidateSlot, int targetBlockId, int targetBlockMetadata);
        boolean isChainKeyPhysicallyDown();
    }

    private final AutoToolSwapClientReducer reducer;
    private final GameFacade game;
    private final AutoToolSwapClientTransport transport;
    private final AutoToolSwapClientReducer.DiagnosticSink diagnosticSink;

    public AutoToolSwapClientAdapter(boolean enabled, List<ToolSelector> selectors, GameFacade game,
            AutoToolSwapClientTransport transport) {
        this(enabled, true, selectors, game, transport);
    }

    /** 创建带独立接替开关的客户端 adapter。 */
    public AutoToolSwapClientAdapter(boolean enabled, boolean takeoverEnabled, List<ToolSelector> selectors,
            GameFacade game, AutoToolSwapClientTransport transport) {
        this(enabled, takeoverEnabled, selectors, game, transport, PRODUCTION_DIAGNOSTIC_SINK);
    }

    /** 创建可注入诊断出口的 adapter，供包内纯 JVM 合同验证。 */
    AutoToolSwapClientAdapter(boolean enabled, boolean takeoverEnabled, List<ToolSelector> selectors,
            GameFacade game, AutoToolSwapClientTransport transport,
            AutoToolSwapClientReducer.DiagnosticSink diagnosticSink) {
        if (game == null || transport == null) {
            throw new IllegalArgumentException("game and transport must not be null");
        }
        if (diagnosticSink == null) throw new IllegalArgumentException("diagnosticSink must not be null");
        reducer = new AutoToolSwapClientReducer(enabled, takeoverEnabled, selectors, diagnosticSink);
        this.game = game;
        this.transport = transport;
        this.diagnosticSink = diagnosticSink;
    }

    /** 真实按键边沿入口；上升沿的 RoundStart effect 仍先于外层 KeyState。 */
    public void onChainKeyState(boolean down) {
        if (down == reducer.isKeyDown()) return;
        ToolSwapLightContext light = game.captureLightContext(reducer.clientTick(), down);
        if (light == null) {
            if (!down) publishFailedRelease();
            return;
        }
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.capture();
        long worldGeneration = ClientConnectionLifecycle.isWorldCurrentAndActive(token)
                ? token.worldGeneration() : -1L;
        ToolSwapCapturePlan plan = reducer.capturePlanForKeyState(down, light, worldGeneration);
        ToolSwapContext context = game.captureContext(light, plan,
                reducer.protectedAnchorSlot(), reducer.protectedCandidateSlot(), 0, 0);
        if (context == null) {
            if (!down) publishFailedRelease();
            return;
        }
        executeEffects(reducer.reduce(new KeyStateEvent(down, context, worldGeneration)));
    }

    /**
     * 每个 ClientTickEvent.END 调用一次。
     *
     * @return 仅当 deferred RoundStart 已成功提交并产生一次 fresh-key effect 时返回 true
     */
    public boolean onClientTick() {
        boolean physical = game.isChainKeyPhysicallyDown();
        ToolSwapLightContext light = game.captureLightContext(reducer.clientTick(), reducer.chainActive());
        ToolSwapContext context = null;
        if (light != null) {
            ToolSwapCapturePlan plan = reducer.capturePlanForTick(light, physical);
            context = game.captureContext(light, plan,
                    reducer.protectedAnchorSlot(), reducer.protectedCandidateSlot(),
                    reducer.pendingTargetBlockId(), reducer.pendingTargetBlockMetadata());
        }
        return executeEffects(reducer.reduce(new TickEvent(context, physical)));
    }

    /** 配置热更新。 */
    public void onConfigChanged(boolean enabled, List<ToolSelector> selectors) {
        onConfigChanged(enabled, true, selectors);
    }

    /** 配置热更新；关闭接替只影响尚未提交的接替请求。 */
    public void onConfigChanged(boolean enabled, boolean takeoverEnabled, List<ToolSelector> selectors) {
        executeEffects(reducer.reduce(new ConfigEvent(enabled, takeoverEnabled, selectors)));
    }

    /** 本地首块成功入口；lifecycle identity 只在 runtime 边界采样。 */
    public void onLocalBlockDestroyed() {
        boolean physical = game.isChainKeyPhysicallyDown();
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.capture();
        boolean active = physical && ClientConnectionLifecycle.isWorldCurrentAndActive(token);
        long worldGeneration = active ? token.worldGeneration() : -1L;
        executeEffects(reducer.reduce(new LocalBlockDestroyedEvent(active, worldGeneration)));
    }

    /** ClientProxy 主线程 gate 后发布的 RoundResult 原始字段。 */
    public void onRoundResult(int protocolVersion, long clientNonce, long serverRoundId, int resultCode,
            int roundState, long nextActionSequence, long serverTick, boolean rawValid) {
        reducer.reduce(new RoundResultEvent(protocolVersion, clientNonce, serverRoundId, resultCode,
                roundState, nextActionSequence, serverTick, rawValid));
    }

    /** ClientProxy 主线程 gate 后发布的 ActionResult 原始字段。 */
    public void onActionResult(int protocolVersion, long serverRoundId, long actionSequence, int actionCode,
            int resultCode, int roundState, int anchorSlot, int candidateSlot, long nextActionSequence,
            long serverTick, boolean rawValid) {
        reducer.reduce(new ActionResultEvent(protocolVersion, serverRoundId, actionSequence, actionCode,
                resultCode, roundState, anchorSlot, candidateSlot, nextActionSequence, serverTick, rawValid));
    }

    /** ClientProxy 主线程 gate 后发布的专用 round phase。 */
    public void onRoundPhase(int protocolVersion, long serverRoundId, long phaseSequence, int phaseOrdinal,
            int generation, long serverTick, boolean rawValid) {
        reducer.reduce(new RoundPhaseEvent(protocolVersion, serverRoundId, phaseSequence, phaseOrdinal,
                generation, serverTick, rawValid));
    }

    /** S2C callback 仅发布接替请求事实；发送动作留到下一次 ClientTick。 */
    public void onTakeoverRequest(int protocolVersion, long serverRoundId, long actionSequence,
            int generation, int targetX, int targetY, int targetZ, int targetBlockId,
            int targetBlockMetadata, long serverTick, long deadlineTick, boolean rawValid) {
        reducer.reduce(new TakeoverRequestEvent(protocolVersion, serverRoundId, actionSequence,
                generation, targetX, targetY, targetZ, targetBlockId, targetBlockMetadata,
                serverTick, deadlineTick, rawValid));
    }

    /** 生命周期复位仅清 reducer；绝不跨连接发送恢复包。 */
    public void resetForLifecycle() {
        reducer.reduce(new ResetEvent());
    }

    AutoToolSwapClientReducer reducerForTests() {
        return reducer;
    }

    /** 发布无上下文松键事实；只保留恢复义务，不伪造生命周期复位。 */
    private void publishFailedRelease() {
        try {
            diagnosticSink.log(RELEASE_CAPTURE_FAILED_MARKER);
        } catch (RuntimeException ignored) {
            // 诊断失败不得改变 release 收口。
        } catch (LinkageError ignored) {
            // 日志实现不可用时仍须保留协议账本。
        }
        executeEffects(reducer.reduce(new KeyStateEvent(false, null, -1L)));
    }

    /** 执行 effect，并将执行结果作为新事件同步回 reducer。 */
    private boolean executeEffects(List<Effect> initialEffects) {
        boolean freshKey = false;
        List<Effect> effects = initialEffects;
        for (int round = 0; round < 16 && !effects.isEmpty(); round++) {
            java.util.ArrayList<Effect> following = new java.util.ArrayList<Effect>();
            for (Effect effect : effects) {
                if (effect.type() == Effect.Type.FRESH_KEY) {
                    freshKey = true;
                    continue;
                }
                if (effect.type() == Effect.Type.CAPTURE) {
                    ToolSwapLightContext light = game.captureLightContext(
                            reducer.clientTick(), reducer.chainActive());
                    ToolSwapContext captured = light == null ? null : game.captureContext(light,
                            effect.capturePlan(), effect.anchorSlot(), effect.candidateSlot(),
                            effect.targetBlockId(), effect.targetBlockMetadata());
                    following.addAll(reducer.reduce(new EffectResultEvent(effect, captured != null, captured)));
                    continue;
                }
                boolean submitted = effect.type() == Effect.Type.BEGIN_ROUND
                        ? sendRound(effect.clientNonce()) : sendIntent(effect.intent());
                following.addAll(reducer.reduce(new EffectResultEvent(effect, submitted, null)));
            }
            effects = following;
        }
        if (!effects.isEmpty()) throw new IllegalStateException("tool swap effect execution did not quiesce");
        return freshKey;
    }

    private boolean sendRound(long nonce) {
        try {
            return transport.sendRoundStart(nonce);
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
}
