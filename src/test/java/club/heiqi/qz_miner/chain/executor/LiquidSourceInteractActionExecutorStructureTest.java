package club.heiqi.qz_miner.chain.executor;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeBootstrap;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * 目标化液体执行器的权限、手持门、精确射线、事件与恢复合同。
 *
 * <p>行为面为什么到不了这里：{@code canExecute}/{@code execute} 需要真实 {@code EntityPlayerMP}
 * 与 world（实测：{@code EntityPlayerMP} 构造链要求 non-null {@code WorldServer}），
 * 纯 JVM 构造不出玩家。可达的部分（手持门、精确命中判定、模式声明）走反射与真实对象断言，
 * 不可达的部分只守结构关系与编译产物调用面。</p>
 */
public class LiquidSourceInteractActionExecutorStructureTest {

    private static final String SOURCE =
            "src/main/java/club/heiqi/qz_miner/chain/executor/LiquidSourceInteractActionExecutor.java";

    /** canExecute 必须依次守通用权限、live source 与当前手持，并把模组异常吸收为拒绝。 */
    @Test
    public void canExecuteOrdersPermissionSourceAndCurrentItemGates() throws Exception {
        String code = JavaSourceSlices.maskedMainSource(SOURCE);
        String body = JavaSourceSlices.methodBodyWithoutSignature(code, "canExecute");

        int permission = JavaSourceSlices.wordIndexOf(body, "canExecute(");
        int sourceGate = JavaSourceSlices.wordIndexOf(body, "ChainLiquidRules.matchesSource");
        int handGate = JavaSourceSlices.wordIndexOf(body, "getCurrentEquippedItem");
        Assert.assertTrue("第一道门必须复用通用交互执行器的权限判定", permission >= 0);
        Assert.assertTrue("live source 重验必须晚于权限门", sourceGate > permission);
        Assert.assertTrue("当前手持门必须晚于 live source 门", handGate > sourceGate);
        Assert.assertTrue("模组异常必须被吸收", JavaSourceSlices.mentions(body, "catch"));
        Assert.assertFalse("模组异常必须 fail-closed，catch 内不得 return true",
                JavaSourceSlices.blockAfter(body, "catch").contains("return true"));
    }

    /** 当前手持门：空引用与零数量一律不可用（反射调真实判定，而不是匹配表达式文本）。 */
    @Test
    public void currentHandGateRejectsNullAndEmptyStack() throws Exception {
        Method gate = LiquidSourceInteractActionExecutor.class
                .getDeclaredMethod("hasUsableCurrentStack", ItemStack.class);
        gate.setAccessible(true);
        ItemStack zeroStack = new ItemStack(Blocks.stone);
        zeroStack.stackSize = 0;

        Assert.assertFalse("空引用手持不可用", usable(gate, null));
        Assert.assertFalse("零数量手持不可用（上一目标已耗尽）", usable(gate, zeroStack));
        Assert.assertTrue("非空手持可用", usable(gate, new ItemStack(Blocks.stone)));
    }

    /** execute 必须按 复检权限 -> 重读当前槽 -> 姿态 -> 精确射线 -> AIR 事件 -> Item -> 恢复 -> 同步 推进。 */
    @Test
    public void executeUsesExactTargetRayAndRestoresBeforeInventorySync() throws Exception {
        String code = JavaSourceSlices.maskedMainSource(SOURCE);
        String body = JavaSourceSlices.methodBodyWithoutSignature(code, "execute");

        int permission = JavaSourceSlices.wordIndexOf(body, "canExecute(");
        int handReread = JavaSourceSlices.wordIndexOf(body, "getCurrentEquippedItem");
        int capture = JavaSourceSlices.wordIndexOf(body, "capture(");
        int ray = JavaSourceSlices.wordIndexOf(body, "InteractionRayTrace");
        int exact = JavaSourceSlices.wordIndexOf(body, "isExactTargetHit");
        int airEvent = JavaSourceSlices.wordIndexOf(body, "RIGHT_CLICK_AIR");
        int deny = JavaSourceSlices.wordIndexOf(body, "DENY");
        int useItem = JavaSourceSlices.wordIndexOf(body, "tryUseItem");
        int finallyBlock = JavaSourceSlices.wordIndexOf(body, "finally", useItem);
        int restore = JavaSourceSlices.wordIndexOf(body, "close()", finallyBlock);
        int sync = JavaSourceSlices.wordIndexOf(body, "normalizeAndSync", restore);

        Assert.assertTrue("必须在 canExecute 之后重新读取真实当前槽", permission >= 0 && handReread > permission);
        Assert.assertTrue("姿态事务必须在重读手持之后建立", capture > handReread);
        Assert.assertTrue("射线必须在虚拟姿态之后发射", ray > capture);
        Assert.assertTrue("精确命中判定必须紧跟射线", exact > ray);
        Assert.assertTrue("AIR 事件必须在确认精确命中之后", airEvent > exact);
        Assert.assertTrue("DENY 判定必须在事件之后、使用物品之前", deny > airEvent && useItem > deny);
        Assert.assertTrue("姿态恢复必须在 finally 内先于库存同步",
                finallyBlock > useItem && restore > finallyBlock && sync > restore);

        List<String> rayArguments = JavaSourceSlices.splitCallArguments(body, "InteractionRayTrace.trace");
        Assert.assertEquals("共享射线入口的实参个数固定", 3, rayArguments.size());
        Assert.assertTrue("触达距离必须取当前侧权威值: " + rayArguments.get(1),
                rayArguments.get(1).contains("getBlockReachDistance()"));
        Assert.assertEquals("必须按 liquid-inclusive 语义发射", "true", rayArguments.get(2));

        String finallyBody = JavaSourceSlices.blockAfter(body, "finally");
        Assert.assertTrue("姿态恢复必须单独守卫", JavaSourceSlices.wordIndexOf(finallyBody, "catch") > 0);
        Assert.assertFalse("姿态恢复失败必须 fail-closed，catch 内不得 return true",
                JavaSourceSlices.blockAfter(finallyBody, "catch").contains("return true"));
    }

    /** 精确命中判定必须同时要求 BLOCK 类型与三坐标全等（反射调真实判定）。 */
    @Test
    public void exactTargetHitRequiresBlockTypeAndMatchingCoordinates() throws Exception {
        Method exact = LiquidSourceInteractActionExecutor.class.getDeclaredMethod(
                "isExactTargetHit", MovingObjectPosition.class, ChainTarget.class);
        exact.setAccessible(true);
        ChainTarget target = new ChainTarget(4, 5, 6);

        Assert.assertFalse("没有命中不可算精确命中", exactHit(exact, null, target));
        Assert.assertFalse("坐标不符不可算精确命中", exactHit(exact, blockHit(4, 5, 7), target));
        Assert.assertFalse("非 BLOCK 命中类型不可算精确命中", exactHit(exact, missHit(4, 5, 6), target));
        Assert.assertTrue("BLOCK 且三坐标全等才算精确命中", exactHit(exact, blockHit(4, 5, 6), target));
    }

    /** 只服务 INTERACT；且不得复用通用激活入口、不得直接改液体世界、不得引入白名单物品依赖。 */
    @Test
    public void executorIsBoundToInteractModeAndAvoidsDirectLiquidWorldAccess() {
        LiquidSourceInteractActionExecutor executor = new LiquidSourceInteractActionExecutor();
        Assert.assertTrue(executor.supports(ChainMode.INTERACT));
        Assert.assertFalse(executor.supports(ChainMode.CHAIN));

        assertNoReference("activateBlockOrUseItem", "液体路径必须走事件化的空气右键，不得复用通用方块激活入口");
        assertNoReference("drain", "禁止直接排液改世界");
        assertNoReference("setBlockToAir", "禁止直接改液体世界");
        assertNoReference("IFluidBlock", "禁止依赖原版流体方块白名单");
        assertNoReference("FluidContainerRegistry", "禁止依赖原版流体容器注册表");
        assertNoReference("IFluidContainerItem", "禁止依赖原版流体容器物品接口");
        assertNoReference("LiquidContainerUsePolicy", "禁止引入物品白名单策略");
        assertNoReference("playerNetServerHandler", "禁止绕过正常同步路径直发网络包");
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

    /** 被禁调用/类型：扫已编译产物的常量池，源码排版与注释都不影响结论。 */
    private static void assertNoReference(String token, String reason) {
        Assert.assertFalse(reason + "（编译产物不得引用 " + token + "）",
                CompiledClasses.references(LiquidSourceInteractActionExecutor.class, token));
    }

    private static boolean usable(Method gate, ItemStack stack) throws Exception {
        return Boolean.TRUE.equals(gate.invoke(null, new Object[] {stack}));
    }

    private static boolean exactHit(Method exact, MovingObjectPosition hit, ChainTarget target)
            throws Exception {
        return Boolean.TRUE.equals(exact.invoke(null, new Object[] {hit, target}));
    }

    private static MovingObjectPosition blockHit(int x, int y, int z) {
        return new MovingObjectPosition(x, y, z, 1, Vec3.createVectorHelper(0.0D, 0.0D, 0.0D));
    }

    private static MovingObjectPosition missHit(int x, int y, int z) {
        MovingObjectPosition hit = blockHit(x, y, z);
        hit.typeOfHit = MovingObjectPosition.MovingObjectType.MISS;
        return hit;
    }
}
