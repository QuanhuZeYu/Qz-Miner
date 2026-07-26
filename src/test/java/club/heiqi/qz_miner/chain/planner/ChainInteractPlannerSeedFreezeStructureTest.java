package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 宽泛右键目标解析与原事件窗口冻结 seed 的结构合同。 */
public class ChainInteractPlannerSeedFreezeStructureTest {

    /** 仅宽泛 RIGHT_CLICK 子模式接收 BLOCK/AIR，其他 Forge action 不进入解析。 */
    @Test
    public void plannerAcceptsBlockAndAirOnlyForBroadRightClickTrigger() throws Exception {
        String source = plannerSource();
        int handler = source.indexOf("public void onPlayerInteract(");
        int actionGate = source.indexOf("event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK", handler);
        int airGate = source.indexOf("event.action != PlayerInteractEvent.Action.RIGHT_CLICK_AIR", actionGate);
        int triggerGate = source.indexOf(
                "ChainSubModeRegistry.getTrigger(selectedSubMode) != ChainSubModeTrigger.RIGHT_CLICK", airGate);
        int resolve = source.indexOf("resolveInteractTarget(player, event, selectedSubMode)", triggerGate);

        Assert.assertTrue(actionGate > handler && airGate > actionGate);
        Assert.assertTrue(triggerGate > airGate && resolve > triggerGate);
        Assert.assertFalse(source.substring(handler, resolve).contains("ChainSubModeTrigger.RIGHT_CLICK_BLOCK"));
    }

    /** 非液体 BLOCK 保留 event 坐标；AIR 与所有液体动作只读共享 ray 命中。 */
    @Test
    public void blockAndAirUseSeparateTargetSourcesWithLiquidFlag() throws Exception {
        String source = plannerSource();
        int resolver = source.indexOf("private ResolvedInteractTarget resolveInteractTarget(");
        int rayResolver = source.indexOf("private ResolvedInteractTarget resolveRayTarget(", resolver);
        int blockResolver = source.indexOf("private ResolvedInteractTarget resolveBlockEventTarget(", rayResolver);
        int trace = source.indexOf("private MovingObjectPosition tracePlayerTarget(", blockResolver);
        String dispatchBody = source.substring(resolver, rayResolver);
        String rayBody = source.substring(rayResolver, blockResolver);
        String blockBody = source.substring(blockResolver, trace);

        Assert.assertTrue(dispatchBody.contains(
                "selectedSubMode == ChainSubMode.INTERACT_LIQUID_SOURCE"));
        Assert.assertTrue(dispatchBody.contains(
                "includeLiquids || event.action == PlayerInteractEvent.Action.RIGHT_CLICK_AIR"));
        Assert.assertTrue(dispatchBody.contains("resolveRayTarget(player, includeLiquids)"));
        Assert.assertTrue(dispatchBody.contains("resolveBlockEventTarget(player, event)"));
        Assert.assertTrue(rayBody.contains(
                "hit.blockX, hit.blockY, hit.blockZ, normalizeFace(hit.sideHit), hit.hitVec"));
        Assert.assertFalse("AIR/ray 解析不得读取 Forge 占位坐标", rayBody.contains("event."));
        Assert.assertTrue(blockBody.contains("event.x, event.y, event.z"));
        Assert.assertTrue(blockBody.contains("normalizeFace(event.face)"));
        Assert.assertTrue(source.substring(trace).contains(
                "InteractionRayTrace.trace(player, reach, includeLiquids)"));
    }

    /** block、完整 metadata 与 live TileEntity 必须按已解析目标在 publish 前立即纯值化。 */
    @Test
    public void plannerCapturesCompleteSeedBeforePublishingObservation() throws Exception {
        String source = plannerSource();
        int handler = source.indexOf("public void onPlayerInteract(");
        int targetResolve = source.indexOf("resolveInteractTarget(player, event, selectedSubMode)", handler);
        int captureCall = source.indexOf("freezeInteractSeed(player, resolvedTarget)", targetResolve);
        int eventConstruction = source.indexOf("new RightClickObserved(", captureCall);
        int captureMethod = source.indexOf("private FrozenInteractSeed freezeInteractSeed(", eventConstruction);
        int getBlock = source.indexOf("player.worldObj.getBlock(target.x, target.y, target.z)", captureMethod);
        int getMeta = source.indexOf("player.worldObj.getBlockMetadata(target.x, target.y, target.z)", captureMethod);
        int getTile = source.indexOf("player.worldObj.getTileEntity(target.x, target.y, target.z)", captureMethod);
        int pureValue = source.indexOf("CompatAdapters.captureTileIdentity(observedTileEntity)", captureMethod);

        Assert.assertTrue(handler >= 0 && targetResolve > handler && captureCall > targetResolve);
        Assert.assertTrue(eventConstruction > captureCall);
        Assert.assertTrue(captureMethod > eventConstruction && getBlock > captureMethod);
        Assert.assertTrue(getMeta > getBlock);
        Assert.assertTrue(getTile > getMeta);
        Assert.assertTrue("live TileEntity 读取后必须立即纯值化", pureValue > getTile);
        Assert.assertTrue(source.substring(eventConstruction).contains(
                "resolvedTarget.x, resolvedTarget.y, resolvedTarget.z"));
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

    private static String plannerSource() throws Exception {
        return read("src/main/java/club/heiqi/qz_miner/chain/planner/ChainInteractPlanner.java");
    }
}
