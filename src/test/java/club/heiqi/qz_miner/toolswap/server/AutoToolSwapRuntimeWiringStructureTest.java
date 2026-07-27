package club.heiqi.qz_miner.toolswap.server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 自动工具换位服务、阶段桥和停止清理顺序的结构门禁。 */
public class AutoToolSwapRuntimeWiringStructureTest {

    @Test
    public void myModOwnsOneProjectionAndOneLocalOwnerBeforeStateService() throws Exception {
        String source = source();
        Assert.assertEquals(1, count(source, "new AutoToolSwapRoundService()"));
        Assert.assertEquals(1, count(source, "new AutoToolSwapServerBatchService()"));
        int playerManager = source.indexOf("playerManager = new PlayerManager()");
        int roundService = source.indexOf("autoToolSwapRoundService = new AutoToolSwapRoundService()");
        int localService = source.indexOf("autoToolSwapServerBatchService = new AutoToolSwapServerBatchService()");
        int stateService = source.indexOf("chainStateService = new ChainStateService()");

        Assert.assertTrue(playerManager < roundService);
        Assert.assertTrue(roundService < localService);
        Assert.assertTrue(localService < stateService);
        Assert.assertTrue(source.contains("autoToolSwapServerBatchService.publishPolicy("
                + "ConfigBootstrap.currentCommittedSnapshot())"));
    }

    @Test
    public void localLifecycleBarrierSubscribesBeforeStateMachineAndExecutionUsesLocalOwner() throws Exception {
        String source = source();
        int lifecycle = source.indexOf("autoToolSwapServerBatchService.subscribeLifecycle(chainEventBus)");
        int stateMachine = source.indexOf("chainStateMachine = new ChainStateMachine(chainEventBus)");
        int execution = source.indexOf("chainExecutionEventBridge = ChainExecutionEventBridge.withLocalToolSwap");
        int localArgument = source.indexOf("autoToolSwapServerBatchService);", execution);

        Assert.assertTrue(lifecycle >= 0 && lifecycle < stateMachine);
        Assert.assertTrue(execution >= 0 && localArgument > execution);
    }

    @Test
    public void phaseBridgeSubscribesAfterStateMachineAndBeforeDrainer() throws Exception {
        String source = source();
        int stateMachine = source.indexOf("chainStateMachine = new ChainStateMachine(chainEventBus)");
        int generalProjection = source.indexOf("chainConfigProjectionBridge = new ChainConfigProjectionBridge");
        int phaseBridge = source.indexOf("autoToolSwapRoundPhaseProjectionBridge =");
        int drainer = source.indexOf("new ChainEventBusDrainer(chainEventBus).bootstrap()");

        Assert.assertTrue(stateMachine < generalProjection);
        Assert.assertTrue(generalProjection < phaseBridge);
        Assert.assertTrue(phaseBridge < drainer);
    }

    @Test
    public void serverStopFinalizesLocalOwnerBeforePlayerProjectionAndDispatcherCleanup() throws Exception {
        String source = source();
        int finalizeAll = source.indexOf("autoToolSwapServerBatchService.finalizeAll(");
        int players = source.indexOf("PlayerManager.clearAllPlayersOnServerStopping()");
        int clearAll = source.indexOf("autoToolSwapRoundService.clearAll()");
        int dispatcherStop = source.indexOf("ServerMainThreadDispatcher.onServerStopping()");

        Assert.assertTrue(finalizeAll >= 0);
        Assert.assertTrue(finalizeAll < players);
        Assert.assertTrue(players < clearAll);
        Assert.assertTrue(clearAll >= 0);
        Assert.assertTrue(clearAll < dispatcherStop);
    }

    @Test
    public void serverStartRepublishesCurrentPolicyAfterDispatcherBecomesReady() throws Exception {
        String source = source();
        int dispatcherStart = source.indexOf("ServerMainThreadDispatcher.onServerStarting()");
        int general = source.indexOf("ConfigBootstrap.reapplyGeneralOnServerStarting()", dispatcherStart);
        int policy = source.indexOf("autoToolSwapServerBatchService.publishPolicy("
                + "ConfigBootstrap.currentCommittedSnapshot())", general);

        Assert.assertTrue(dispatcherStart >= 0);
        Assert.assertTrue(dispatcherStart < general);
        Assert.assertTrue(general < policy);
    }

    private static int count(String value, String token) {
        int count = 0;
        for (int at = value.indexOf(token); at >= 0; at = value.indexOf(token, at + token.length())) {
            count++;
        }
        return count;
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/MyMod.java").toPath()), StandardCharsets.UTF_8);
    }
}
