package club.heiqi.qz_miner.client.toolswap;

import java.util.List;

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
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 自动工具客户端的无库存薄 adapter，只执行 round/FREEZE/CLOSE effect。 */
@SideOnly(Side.CLIENT)
public final class AutoToolSwapClientAdapter {

    /** 不遍历库存的 Minecraft 事实读取边界。 */
    public interface GameFacade {
        ToolSwapLightContext captureLightContext(long tick, boolean chainActive);
        boolean isChainKeyPhysicallyDown();
    }

    private final AutoToolSwapClientReducer reducer;
    private final GameFacade game;
    private final AutoToolSwapClientTransport transport;

    public AutoToolSwapClientAdapter(boolean enabled, GameFacade game, AutoToolSwapClientTransport transport) {
        if (game == null || transport == null) {
            throw new IllegalArgumentException("game and transport must not be null");
        }
        reducer = new AutoToolSwapClientReducer(enabled);
        this.game = game;
        this.transport = transport;
    }

    /** 真实按键边沿入口；上升沿提交 RoundStart，控制动作留到后续 ClientTick。 */
    public void onChainKeyState(boolean down) {
        if (down == reducer.isKeyDown()) return;
        ToolSwapLightContext context = down
                ? game.captureLightContext(reducer.clientTick(), true) : null;
        executeEffects(reducer.reduce(new KeyStateEvent(down, context)));
    }

    /** 每个 ClientTickEvent.END 调用一次。 */
    public boolean onClientTick() {
        boolean physical = game.isChainKeyPhysicallyDown();
        ToolSwapLightContext context = game.captureLightContext(reducer.clientTick(), reducer.chainActive());
        return executeEffects(reducer.reduce(new TickEvent(context, physical)));
    }

    /** 客户端仅保留总开关，不再读取 fallback 开关或候选 selector。 */
    public void onConfigChanged(boolean enabled) {
        executeEffects(reducer.reduce(new ConfigEvent(enabled)));
    }

    /** 本地首块成功只请求 projection FREEZE，不采样库存。 */
    public void onLocalBlockDestroyed() {
        boolean physical = game.isChainKeyPhysicallyDown();
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.capture();
        executeEffects(reducer.reduce(new LocalBlockDestroyedEvent(
                physical && ClientConnectionLifecycle.isWorldCurrentAndActive(token))));
    }

    public void onRoundResult(int protocolVersion, long clientNonce, long serverRoundId, int resultCode,
            int roundState, long nextActionSequence, long serverTick, boolean rawValid) {
        reducer.reduce(new RoundResultEvent(protocolVersion, clientNonce, serverRoundId, resultCode,
                roundState, nextActionSequence, serverTick, rawValid));
    }

    public void onActionResult(int protocolVersion, long serverRoundId, long actionSequence, int actionCode,
            int resultCode, int roundState, int anchorSlot, int candidateSlot, long nextActionSequence,
            long serverTick, boolean rawValid) {
        reducer.reduce(new ActionResultEvent(protocolVersion, serverRoundId, actionSequence, actionCode,
                resultCode, roundState, anchorSlot, candidateSlot, nextActionSequence, serverTick, rawValid));
    }

    public void onRoundPhase(int protocolVersion, long serverRoundId, long phaseSequence, int phaseOrdinal,
            int generation, long serverTick, boolean rawValid) {
        reducer.reduce(new RoundPhaseEvent(protocolVersion, serverRoundId, phaseSequence, phaseOrdinal,
                generation, serverTick, rawValid));
    }

    /** 生命周期复位只清本连接 projection，不跨连接发送控制包。 */
    public void resetForLifecycle() { reducer.reduce(new ResetEvent()); }

    AutoToolSwapClientReducer reducerForTests() { return reducer; }

    private boolean executeEffects(List<Effect> initialEffects) {
        boolean freshKey = false;
        List<Effect> effects = initialEffects;
        for (int pass = 0; pass < 8 && !effects.isEmpty(); pass++) {
            java.util.ArrayList<Effect> following = new java.util.ArrayList<Effect>();
            for (Effect effect : effects) {
                if (effect.type() == Effect.Type.FRESH_KEY) {
                    freshKey = true;
                    continue;
                }
                boolean submitted = effect.type() == Effect.Type.BEGIN_ROUND
                        ? sendRound(effect.clientNonce()) : sendIntent(effect.intent());
                following.addAll(reducer.reduce(new EffectResultEvent(effect, submitted)));
            }
            effects = following;
        }
        if (!effects.isEmpty()) throw new IllegalStateException("tool swap effects did not quiesce");
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
