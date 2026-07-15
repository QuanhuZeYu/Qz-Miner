package club.heiqi.qz_miner.toolswap.server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 自动工具换位服务、阶段桥和停止清理顺序的结构门禁。 */
public class AutoToolSwapRuntimeWiringStructureTest {

    @Test
    public void myModOwnsOneServiceAndWiresItBeforeStateService() throws Exception {
        String source = source();
        Assert.assertEquals(1, count(source, "new AutoToolSwapRoundService()"));
        int playerManager = source.indexOf("playerManager = new PlayerManager()");
        int roundService = source.indexOf("autoToolSwapRoundService = new AutoToolSwapRoundService()");
        int stateService = source.indexOf("chainStateService = new ChainStateService()");

        Assert.assertTrue(playerManager < roundService);
        Assert.assertTrue(roundService < stateService);
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
    public void serverStopClearsRoundLedgerBeforeDispatcherCloses() throws Exception {
        String source = source();
        int clearAll = source.indexOf("autoToolSwapRoundService.clearAll()");
        int dispatcherStop = source.indexOf("ServerMainThreadDispatcher.onServerStopping()");

        Assert.assertTrue(clearAll >= 0);
        Assert.assertTrue(clearAll < dispatcherStop);
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
