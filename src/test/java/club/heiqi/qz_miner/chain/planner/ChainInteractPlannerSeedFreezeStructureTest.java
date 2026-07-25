package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** RIGHT_CLICK_BLOCK 原事件窗口冻结 seed 的结构合同。 */
public class ChainInteractPlannerSeedFreezeStructureTest {

    /** block、完整 metadata 与 live TileEntity 必须在事件构造前读取并立即纯值化。 */
    @Test
    public void plannerCapturesCompleteSeedBeforePublishingObservation() throws Exception {
        String source = read("src/main/java/club/heiqi/qz_miner/chain/planner/ChainInteractPlanner.java");
        int handler = source.indexOf("public void onPlayerInteract(");
        int captureCall = source.indexOf("freezeInteractSeed(player, event)", handler);
        int eventConstruction = source.indexOf("new RightClickObserved(", captureCall);
        int captureMethod = source.indexOf("private FrozenInteractSeed freezeInteractSeed(", eventConstruction);
        int getBlock = source.indexOf("player.worldObj.getBlock(event.x, event.y, event.z)", captureMethod);
        int getMeta = source.indexOf("player.worldObj.getBlockMetadata(event.x, event.y, event.z)", captureMethod);
        int getTile = source.indexOf("player.worldObj.getTileEntity(event.x, event.y, event.z)", captureMethod);
        int pureValue = source.indexOf("CompatAdapters.captureTileIdentity(observedTileEntity)", captureMethod);

        Assert.assertTrue(handler >= 0 && captureCall > handler && eventConstruction > captureCall);
        Assert.assertTrue(captureMethod > eventConstruction && getBlock > captureMethod);
        Assert.assertTrue(getMeta > getBlock);
        Assert.assertTrue(getTile > getMeta);
        Assert.assertTrue("live TileEntity 读取后必须立即纯值化", pureValue > getTile);
        Assert.assertTrue(source.substring(eventConstruction).contains(
                "frozenSeed.block, frozenSeed.metadata, frozenSeed.tileIdentity"));
        Assert.assertTrue("读取异常必须变为 UNRESOLVED，而不是下一 tick 重猜",
                source.contains("catch (RuntimeException | LinkageError failure)"));
    }

    /** 状态机与规划桥必须优先传播冻结 seed，不得用后来 world 值覆盖。 */
    @Test
    public void stateMachineAndBridgePreferFrozenSeed() throws Exception {
        String stateMachine = read(
                "src/main/java/club/heiqi/qz_miner/chain/statemachine/ChainStateMachine.java");
        int rightClickHandler = stateMachine.indexOf("private void onRightClickObserved(");
        int leftClickHandler = stateMachine.indexOf("private void onLeftClickObserved(", rightClickHandler);
        String rightClickBody = stateMachine.substring(rightClickHandler, leftClickHandler);
        Assert.assertTrue(rightClickBody.contains(
                "event.getSeedBlock(), event.getSeedMeta(), event.getSeedTileIdentity()"));

        String bridge = read(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java");
        int frozenBranch = bridge.indexOf("if (event.getSeedBlock() != null)");
        int frozenSnapshot = bridge.indexOf("event.getSeedTileIdentity()", frozenBranch);
        int liveResolver = bridge.indexOf("new WorldBlockSeedResolver()", frozenBranch);
        Assert.assertTrue("冻结 snapshot 必须先于兼容 resolver", frozenBranch >= 0
                && frozenSnapshot > frozenBranch && liveResolver > frozenSnapshot);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
