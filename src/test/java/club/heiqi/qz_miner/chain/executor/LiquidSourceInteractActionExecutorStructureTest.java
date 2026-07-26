package club.heiqi.qz_miner.chain.executor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeBootstrap;

/** 目标化液体执行器的权限、宽泛物品、精确射线、事件与恢复结构合同。 */
public class LiquidSourceInteractActionExecutorStructureTest {

    /** canExecute 必须依次守通用权限、live source 与当前非空手持，但不预判物品类型。 */
    @Test
    public void canExecuteOrdersPermissionSourceAndCurrentItemGates() throws Exception {
        String source = readSource();
        int canExecute = source.indexOf("public boolean canExecute(");
        int execute = source.indexOf("public boolean execute(", canExecute);
        String body = source.substring(canExecute, execute);
        int permission = body.indexOf("PERMISSION_EXECUTOR.canExecute(player, session, target)");
        int sourceGate = body.indexOf("ChainLiquidRules.matchesSource(", permission);
        int currentStack = body.indexOf("ItemStack currentStack = player.getCurrentEquippedItem();", sourceGate);
        int itemGate = body.indexOf("hasUsableCurrentStack(currentStack)", currentStack);

        Assert.assertTrue(permission >= 0 && sourceGate > permission);
        Assert.assertTrue(currentStack > sourceGate && itemGate > currentStack);
        Assert.assertTrue(source.contains(
                "return currentStack != null && currentStack.stackSize > 0;"));
        Assert.assertFalse(source.contains("LiquidContainerUsePolicy"));
        Assert.assertFalse(source.contains("FluidContainerRegistry"));
        Assert.assertFalse(source.contains("IFluidContainerItem"));
        Assert.assertTrue(body.contains("catch (RuntimeException | LinkageError failure)"));
    }

    /** execute 必须按 current Item→pose→shared exact ray→AIR event→Item→restore→sync 顺序执行。 */
    @Test
    public void executeUsesExactTargetRayAndRestoresBeforeInventorySync() throws Exception {
        String source = readSource();
        int execute = source.indexOf("public boolean execute(");
        int helper = source.indexOf("private static String resolveSeedFluidName", execute);
        String body = source.substring(execute, helper);
        int currentStack = body.indexOf("ItemStack currentStack = player.getCurrentEquippedItem();");
        int itemGate = body.indexOf("hasUsableCurrentStack(currentStack)", currentStack);
        int pose = body.indexOf("ServerPlayerPoseTransaction.capture(player)", itemGate);
        int ray = body.indexOf("MovingObjectPosition hit = InteractionRayTrace.trace(", pose);
        int reach = body.indexOf("player.theItemInWorldManager.getBlockReachDistance(), true", ray);
        int exact = body.indexOf("isExactTargetHit(hit, target)", ray);
        int airEvent = body.indexOf("PlayerInteractEvent.Action.RIGHT_CLICK_AIR", exact);
        int deny = body.indexOf("event.useItem == Event.Result.DENY", airEvent);
        int useItem = body.indexOf("theItemInWorldManager.tryUseItem(", deny);
        int finallyBlock = body.indexOf("finally {", useItem);
        int restore = body.indexOf("poseTransaction.close();", finallyBlock);
        int sync = body.indexOf(
                "InteractionInventorySupport.normalizeAndSync(player, target)", restore);

        Assert.assertTrue(currentStack >= 0 && itemGate > currentStack && pose > itemGate);
        Assert.assertTrue(ray > pose && reach > ray && exact > reach && airEvent > exact && deny > airEvent);
        Assert.assertTrue(useItem > deny && finallyBlock > useItem);
        Assert.assertTrue("必须先恢复姿态再同步库存", restore > finallyBlock && sync > restore);
        Assert.assertTrue(body.contains("Failed to restore virtual pose"));
    }

    /** 射线必须调用共享 liquid-inclusive helper，并精确比较三坐标。 */
    @Test
    public void rayTraceIsLiquidInclusiveAndExact() throws Exception {
        String source = readSource();
        Assert.assertTrue(source.contains("InteractionRayTrace.trace("));
        Assert.assertTrue(source.contains(
                "player.theItemInWorldManager.getBlockReachDistance(), true"));
        Assert.assertTrue(source.contains(
                "hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK"));
        Assert.assertTrue(source.contains("hit.blockX == target.getX()"));
        Assert.assertTrue(source.contains("hit.blockY == target.getY()"));
        Assert.assertTrue(source.contains("hit.blockZ == target.getZ()"));
    }

    /** 禁止白名单物品、绕过 Forge event 或直接修改液体世界。 */
    @Test
    public void executorDoesNotUseDirectDrainPlacementOrUntargetedAlternatives() throws Exception {
        String source = readSource();
        Assert.assertFalse(source.contains("activateBlockOrUseItem("));
        Assert.assertFalse(source.contains(".drain("));
        Assert.assertFalse(source.contains("setBlockToAir"));
        Assert.assertFalse(source.contains("new ItemStack("));
        Assert.assertFalse(source.contains("IFluidBlock"));
        Assert.assertFalse(source.contains("FluidContainerRegistry"));
        Assert.assertFalse(source.contains("IFluidContainerItem"));
        Assert.assertFalse(source.contains("LiquidContainerUsePolicy"));
        Assert.assertFalse(source.contains("playerNetServerHandler"));
        Assert.assertTrue(source.contains("mode == ChainMode.INTERACT"));
    }

    /** 液体执行器只覆盖液体源子模式，不替换其它三种范围交互执行器。 */
    @Test
    public void executorIsRegisteredOnlyForLiquidSourceSubMode() {
        ChainModeBootstrap.bootstrap();
        ChainSubModeBootstrap.bootstrap();
        ChainModeDefinition definition = ChainModeRegistry.getDefinition(ChainMode.INTERACT);

        Assert.assertTrue(definition.resolveActionExecutor(ChainSubMode.INTERACT_LIQUID_SOURCE)
                instanceof LiquidSourceInteractActionExecutor);
        Assert.assertFalse(definition.getActionExecutor() instanceof LiquidSourceInteractActionExecutor);
        Assert.assertFalse(definition.resolveActionExecutor(ChainSubMode.INTERACT_BASE)
                instanceof LiquidSourceInteractActionExecutor);
        Assert.assertFalse(definition.resolveActionExecutor(ChainSubMode.INTERACT_CROP)
                instanceof LiquidSourceInteractActionExecutor);
        Assert.assertFalse(definition.resolveActionExecutor(
                ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP)
                instanceof LiquidSourceInteractActionExecutor);
    }

    private static String readSource() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/executor/LiquidSourceInteractActionExecutor.java").toPath()),
                StandardCharsets.UTF_8);
    }
}
