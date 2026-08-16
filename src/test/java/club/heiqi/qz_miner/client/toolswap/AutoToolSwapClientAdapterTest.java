package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 严格 5.3 客户端只发送 round/FREEZE/CLOSE，且不具有库存采样边界。 */
public class AutoToolSwapClientAdapterTest {

    @Test
    public void sendsFreezeThenCloseAndRetriesExactIntent() {
        Game game = new Game();
        Transport transport = new Transport();
        AutoToolSwapClientAdapter adapter = new AutoToolSwapClientAdapter(true, game, transport);

        game.down = true;
        adapter.onChainKeyState(true);
        Assert.assertEquals(1, transport.nonces.size());
        acceptRound(adapter, transport.nonces.get(0).longValue());
        adapter.onClientTick();
        AutoToolSwapIntent freeze = transport.intents.get(0);
        Assert.assertEquals(AutoToolSwapAction.FREEZE, freeze.action());

        for (int i = 0; i <= AutoToolSwapClientReducer.RETRANSMIT_TICKS; i++) adapter.onClientTick();
        Assert.assertSame(freeze, transport.intents.get(1));
        settle(adapter, freeze, AutoToolSwapRoundState.FROZEN);

        game.down = false;
        adapter.onChainKeyState(false);
        adapter.onClientTick();
        AutoToolSwapIntent close = transport.intents.get(2);
        Assert.assertEquals(AutoToolSwapAction.CLOSE, close.action());
        settle(adapter, close, AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, adapter.reducerForTests().state());
    }

    @Test
    public void phaseIdleClosesAndLifecycleResetSendsNothing() {
        Game game = new Game();
        Transport transport = new Transport();
        AutoToolSwapClientAdapter adapter = new AutoToolSwapClientAdapter(true, game, transport);
        game.down = true;
        adapter.onChainKeyState(true);
        acceptRound(adapter, transport.nonces.get(0).longValue());
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.IDLE.ordinal(), 2, 1L, true);
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapAction.CLOSE, transport.intents.get(0).action());
        int sent = transport.nonces.size() + transport.intents.size();
        adapter.resetForLifecycle();
        adapter.onClientTick();
        Assert.assertEquals(sent, transport.nonces.size() + transport.intents.size());
    }

    private static void acceptRound(AutoToolSwapClientAdapter adapter, long nonce) {
        adapter.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, nonce, 9L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                1L, 0L, true);
    }

    private static void settle(AutoToolSwapClientAdapter adapter, AutoToolSwapIntent intent,
            AutoToolSwapRoundState state) {
        adapter.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, intent.serverRoundId(),
                intent.actionSequence(), intent.action().wireCode(), AutoToolSwapResultCode.ACCEPTED.wireCode(),
                state.wireCode(), intent.anchorSlot(), intent.candidateSlot(),
                intent.actionSequence() + 1L, 1L, true);
    }

    private static final class Game implements AutoToolSwapClientAdapter.GameFacade {
        private boolean down;
        @Override public ToolSwapLightContext captureLightContext(long tick, boolean active) {
            return new ToolSwapLightContext(tick, true, false, false, active, 0);
        }
        @Override public boolean isChainKeyPhysicallyDown() { return down; }
    }

    private static final class Transport implements AutoToolSwapClientTransport {
        private final List<Long> nonces = new ArrayList<Long>();
        private final List<AutoToolSwapIntent> intents = new ArrayList<AutoToolSwapIntent>();
        @Override public boolean sendRoundStart(long nonce) { nonces.add(Long.valueOf(nonce)); return true; }
        @Override public boolean sendIntent(AutoToolSwapIntent intent) { intents.add(intent); return true; }
    }
}
