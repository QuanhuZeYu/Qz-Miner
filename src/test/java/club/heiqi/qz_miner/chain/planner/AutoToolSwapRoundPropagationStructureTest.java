package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 连锁输入事件必须在 publish 时固化当前工具 round 的结构门禁。 */
public class AutoToolSwapRoundPropagationStructureTest {

    @Test
    public void plannersAndModePacketsCaptureEndpointBoundRoundBeforeEventConstruction() throws Exception {
        assertRoundCapturedBeforeEvent(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanner.java",
                "new BlockBreakObserved(");
        assertRoundCapturedBeforeEvent(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainInteractPlanner.java",
                "new RightClickObserved(");
        assertRoundCapturedBeforeEvent(
                "src/main/java/club/heiqi/qz_miner/chain/planner/GregTechCableReplacePlanner.java",
                "new LeftClickObserved(");
        assertRoundCapturedBeforeEvent(
                "src/main/java/club/heiqi/qz_miner/network/PacketChainModeSwitch.java",
                "new ModeSwitched(");
        assertRoundCapturedBeforeEvent(
                "src/main/java/club/heiqi/qz_miner/network/PacketChainSubModeSwitch.java",
                "new ModeSwitched(");
    }

    private static void assertRoundCapturedBeforeEvent(String path, String eventConstructor) throws Exception {
        String source = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        int capture = source.indexOf("currentRoundId(player.getUniqueID(), player)");
        int event = source.indexOf(eventConstructor);
        Assert.assertTrue(path, capture >= 0);
        Assert.assertTrue(path, capture < event);
        Assert.assertTrue(path, source.substring(event).contains("serverRoundId"));
    }
}
