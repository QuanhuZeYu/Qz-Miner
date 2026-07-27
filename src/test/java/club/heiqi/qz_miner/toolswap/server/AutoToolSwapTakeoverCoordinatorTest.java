package club.heiqi.qz_miner.toolswap.server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.execution.ChainExecutionContextRegistry;
import club.heiqi.qz_miner.chain.execution.ChainExecutionEventBridge;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;

/** 旧 coordinator 保持 source-compatible，但新 ordinary runtime 必须完全旁路。 */
public class AutoToolSwapTakeoverCoordinatorTest {

    @Test
    public void roundZeroDormantSurfaceUsesOnlyCurrentAuthorityAndSendsNothing() {
        final AtomicInteger sends = new AtomicInteger();
        AutoToolSwapTakeoverCoordinator coordinator = new AutoToolSwapTakeoverCoordinator(
                new AutoToolSwapRoundService(0L), (endpoint, request) -> sends.incrementAndGet());

        AutoToolSwapTakeoverCoordinator.GateResult result = coordinator.beforePoll(
                UUID.randomUUID(), new Object(), 0L, 1, 1, 64, 1, 1, 0,
                new NoAccessInventory(), 10L, 3,
                new AutoToolSwapTakeoverCoordinator.HarvestAuthority() {
                    @Override public boolean canHarvest() { return true; }
                });

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED, result);
        Assert.assertEquals(0, sends.get());
    }

    @Test
    public void productionExecutionBridgeContainsNoCoordinatorPollOrTakeoverRequestSend() throws Exception {
        String bridge = source("src/main/java/club/heiqi/qz_miner/chain/execution/ChainExecutionEventBridge.java");
        String mod = source("src/main/java/club/heiqi/qz_miner/MyMod.java");

        Assert.assertFalse(bridge.contains(".beforePoll("));
        Assert.assertFalse(bridge.contains("PacketAutoToolSwapTakeoverRequest"));
        Assert.assertTrue(mod.contains("ChainExecutionEventBridge.withLocalToolSwap("));
        Assert.assertTrue(mod.contains("autoToolSwapServerBatchService);"));
        Assert.assertFalse(mod.contains("chainExecutionContextRegistry,\n                autoToolSwapTakeoverCoordinator)"));
    }

    @Test
    public void compatibilityTypeAndCleanupSurfaceRemainAvailable() {
        AutoToolSwapTakeoverCoordinator coordinator =
                new AutoToolSwapTakeoverCoordinator(new AutoToolSwapRoundService(0L));
        coordinator.cleanup(UUID.randomUUID());
        coordinator.clearAll();
        Assert.assertEquals(4, AutoToolSwapTakeoverCoordinator.GateResult.values().length);

        // 旧 source 中常见的 null literal 三参构造必须继续无歧义编译。
        Assert.assertNotNull(new ChainExecutionEventBridge(
                new ChainEventBus(), new ChainExecutionContextRegistry(), null));
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static final class NoAccessInventory implements AutoToolSwapInventoryPort {
        private AssertionError accessed() { return new AssertionError("round 0 must not touch inventory"); }
        @Override public boolean isPlayerAlive() { throw accessed(); }
        @Override public boolean isCreativeMode() { throw accessed(); }
        @Override public boolean hasPersonalInventoryWindow0() { throw accessed(); }
        @Override public boolean isCursorEmpty() { throw accessed(); }
        @Override public int selectedHotbarSlot() { throw accessed(); }
        @Override public AutoToolSwapStackState readInventorySlot(int slot) { throw accessed(); }
        @Override public void swapInventorySlotsAtomically(int first, int second) { throw accessed(); }
        @Override public void syncInventoryDifference() { throw accessed(); }
    }
}
