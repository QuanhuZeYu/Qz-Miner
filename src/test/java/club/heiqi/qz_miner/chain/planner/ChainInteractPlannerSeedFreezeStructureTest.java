package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ChainKeyPressed;
import club.heiqi.qz_miner.chain.eventbus.event.PlanStarted;
import club.heiqi.qz_miner.chain.eventbus.event.RightClickObserved;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.statemachine.ChainStateMachine;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/** 宽泛右键目标解析与原事件窗口冻结 seed 的结构合同。 */
public class ChainInteractPlannerSeedFreezeStructureTest {

    private static final String PLANNER_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/planner/ChainInteractPlanner.java";
    private static final String BRIDGE_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java";
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000B2");
    private static final long TICK = 7L;
    private static final long NANOS = 11L;
    private static final long SERVER_ROUND = 42L;

    /**
     * 仅宽泛 RIGHT_CLICK 子模式接收 BLOCK/AIR，其他 Forge action 不进入解析。
     *
     * <p>断言的是<b>准入边界集合</b>而不是某段比较表达式：处理器内出现过的
     * {@code PlayerInteractEvent.Action.*} 常量集合必须恰为 {BLOCK, AIR}，
     * {@code ChainSubModeTrigger.*} 常量集合必须恰为 {RIGHT_CLICK}。
     * 旧写法只否定一个 {@code RIGHT_CLICK_BLOCK} 字样——换成 switch、换用别的触发常量即漏检，
     * 现在增删任何一个被接受的常量都会红，且加空白/换行不会误报。</p>
     */
    @Test
    public void plannerAcceptsBlockAndAirOnlyForBroadRightClickTrigger() {
        String handler = handlerBody();

        Assert.assertEquals("handler 只允许 BLOCK/AIR 两种动作进入目标解析",
                new LinkedHashSet<String>(Arrays.asList("RIGHT_CLICK_BLOCK", "RIGHT_CLICK_AIR")),
                JavaSourceSlices.identifiersAfter(handler, "PlayerInteractEvent.Action."));
        Assert.assertEquals("handler 只允许宽泛 RIGHT_CLICK 触发类型",
                Collections.singleton("RIGHT_CLICK"),
                JavaSourceSlices.identifiersAfter(handler, "ChainSubModeTrigger."));
        JavaSourceSlices.assertBefore(handler, "ChainSubModeRegistry.getTrigger(",
                "resolveInteractTarget(", "触发门必须先于目标解析");
    }

    /**
     * 非液体 BLOCK 保留 event 坐标；AIR 与所有液体动作只读共享 ray 命中。
     *
     * <p>路由判定已下沉为包级纯函数 {@link ChainInteractPlanner#resolvesBySharedRay}（与 T54
     * {@code originRelativeTo} 同形），因此这条语义由真值表直接证伪；委派与「ray 解析不得读事件坐标」
     * 仍是方法体区间内的结构契约。</p>
     */
    @Test
    public void blockAndAirUseSeparateTargetSourcesWithLiquidFlag() {
        assertRoute(ChainSubMode.INTERACT_LIQUID_SOURCE, PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK, true);
        assertRoute(ChainSubMode.INTERACT_LIQUID_SOURCE, PlayerInteractEvent.Action.RIGHT_CLICK_AIR, true);
        assertRoute(ChainSubMode.INTERACT_BASE, PlayerInteractEvent.Action.RIGHT_CLICK_AIR, true);
        assertRoute(ChainSubMode.INTERACT_BASE, PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK, false);
        assertRoute(ChainSubMode.INTERACT_CROP, PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK, false);
        assertRoute(ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP,
                PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK, false);

        String dispatch = dispatchBody();
        JavaSourceSlices.assertContains(dispatch, "ChainSubMode.INTERACT_LIQUID_SOURCE",
                "液体子模式必须被显式识别");
        JavaSourceSlices.assertContains(dispatch, "resolveRayTarget(", "dispatch 必须委派共享 ray 解析");
        JavaSourceSlices.assertContains(dispatch, "resolveBlockEventTarget(",
                "BLOCK 分支必须委派事件坐标解析");

        String planner = JavaSourceSlices.stripped(PLANNER_PATH);
        JavaSourceSlices.assertAbsent(rayBody(), "event.", "ray 解析不得读取 Forge 占位坐标");
        Assert.assertEquals("共享射线只允许一个调用点", 1,
                JavaSourceSlices.count(planner, "InteractionRayTrace.trace("));

        // 已删（断言形态改造，实测取证）：原逐字实参快照「hit.blockX, hit.blockY, hit.blockZ,
        // normalizeFace(hit.sideHit), hit.hitVec」「event.x, event.y, event.z」「normalizeFace(event.face)」
        // 属于「换行或提取局部变量即误报」的表达式文本快照；其语义无法行为化——实测
        // new ChainInteractPlanner() 在纯 JVM 抛 ClassCastException(AppClassLoader → LaunchClassLoader)
        // （MinecraftForge.EVENT_BUS.register 需要 FML 类加载器），私有目标解析方法取不到实例。
        // 保留下来的可证伪部分是本用例的路由真值表、两条委派断言与 ray 禁读事件坐标边界。
    }

    /** block、完整 metadata 与 live TileEntity 必须按已解析目标在 publish 前立即纯值化。 */
    @Test
    public void plannerCapturesCompleteSeedBeforePublishingObservation() {
        String handler = handlerBody();
        JavaSourceSlices.assertBefore(handler, "resolveInteractTarget(", "freezeInteractSeed(",
                "目标解析必须先于 seed 冻结");
        JavaSourceSlices.assertBefore(handler, "freezeInteractSeed(", "new RightClickObserved(",
                "seed 冻结必须先于事件构造");

        int targetResolve = handler.indexOf("resolveInteractTarget(");
        int freeze = handler.indexOf("freezeInteractSeed(");
        String resolvedTargetVariable = JavaSourceSlices.assignmentTarget(handler, targetResolve, "目标解析赋值");
        String frozenSeedVariable = JavaSourceSlices.assignmentTarget(handler, freeze, "seed 冻结赋值");
        String observedArguments = JavaSourceSlices.callArguments(handler, "new RightClickObserved(",
                "RightClickObserved 实参");
        JavaSourceSlices.assertContains(observedArguments, resolvedTargetVariable,
                "事件必须携带解析出的语义目标");
        JavaSourceSlices.assertContains(observedArguments, frozenSeedVariable,
                "事件必须携带冻结 seed（不得用后来 world 值覆盖）");

        String seed = freezeSeedBody();
        JavaSourceSlices.assertBefore(seed, "player.worldObj.getBlock(", "player.worldObj.getBlockMetadata(",
                "block→meta 读取顺序");
        JavaSourceSlices.assertBefore(seed, "player.worldObj.getBlockMetadata(", "player.worldObj.getTileEntity(",
                "meta→tile 读取顺序");
        JavaSourceSlices.assertBefore(seed, "player.worldObj.getTileEntity(", "CompatAdapters.captureTileIdentity(",
                "live TileEntity 读取后必须立即纯值化");

        int caught = seed.indexOf("catch (");
        Assert.assertTrue("freezeInteractSeed 必须兜住世界读取异常", caught > 0);
        Assert.assertTrue("读取异常必须收敛为 UNRESOLVED，而不是下一 tick 重猜",
                seed.lastIndexOf("TileIdentityToken.unresolved()") > caught);
    }

    /**
     * 状态机与规划桥必须优先传播冻结 seed，不得用后来 world 值覆盖。
     *
     * <p>状态机一侧升级为真实行为断言：纯 JVM 驱动 {@link ChainEventBus}
     * （ARMED → 右键观测）后，断言广播出的 {@link PlanStarted} 原样携带事件窗口冻结的
     * block/metadata/身份 token 与工具轮次——旧写法只是断言源码里写了一串 getter 调用。</p>
     */
    @Test
    public void stateMachineAndBridgePreferFrozenSeed() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        new ChainStateMachine(bus);
        final List<PlanStarted> started = new ArrayList<PlanStarted>();
        bus.subscribe(PlanStarted.class, started::add);

        Block seedBlock = new TestBlock();
        TileIdentityToken seedIdentity = TileIdentityToken.present("qz-test", "qz.test.TileEntity", "identity");
        bus.publish(new ChainKeyPressed(PLAYER, 0, TICK, NANOS, true));
        bus.drain();
        bus.publish(new RightClickObserved(PLAYER, SERVER_ROUND, 0, TICK, NANOS, 1, 2, 3, 0, 1,
                0.5F, 0.5F, 0.5F, seedBlock, 7, seedIdentity));
        bus.drain();

        Assert.assertEquals("ARMED 右键观测必须广播一次 PlanStarted", 1, started.size());
        PlanStarted planStarted = started.get(0);
        Assert.assertSame("冻结 seed 方块必须原样传播", seedBlock, planStarted.getSeedBlock());
        Assert.assertEquals("冻结 seed metadata 必须原样传播", 7, planStarted.getSeedMeta());
        Assert.assertSame("冻结 seed 身份 token 必须原样传播", seedIdentity, planStarted.getSeedTileIdentity());
        Assert.assertEquals("工具轮次必须原样传播", SERVER_ROUND, planStarted.getServerRoundId());

        String onPlanStarted = JavaSourceSlices.methodBody(JavaSourceSlices.stripped(BRIDGE_PATH),
                "private void onPlanStarted(", "ChainPlanningEventBridge.onPlanStarted");
        JavaSourceSlices.assertBefore(onPlanStarted, "event.getSeedBlock() != null",
                "event.getSeedTileIdentity()", "冻结分支必须使用事件携带的身份 token");
        JavaSourceSlices.assertBefore(onPlanStarted, "event.getSeedBlock() != null",
                "new WorldBlockSeedResolver()", "冻结 seed 分支必须先于兼容 resolver");
    }

    private static void assertRoute(ChainSubMode subMode, PlayerInteractEvent.Action action, boolean byRay) {
        Assert.assertEquals(subMode + " x " + action + " 的路由判定",
                byRay, ChainInteractPlanner.resolvesBySharedRay(subMode, action));
    }

    private static String handlerBody() {
        return JavaSourceSlices.methodBody(JavaSourceSlices.stripped(PLANNER_PATH),
                "public void onPlayerInteract(", "ChainInteractPlanner.onPlayerInteract");
    }

    private static String dispatchBody() {
        return JavaSourceSlices.methodBody(JavaSourceSlices.stripped(PLANNER_PATH),
                "private ResolvedInteractTarget resolveInteractTarget(", "ChainInteractPlanner.resolveInteractTarget");
    }

    private static String rayBody() {
        return JavaSourceSlices.methodBody(JavaSourceSlices.stripped(PLANNER_PATH),
                "private ResolvedInteractTarget resolveRayTarget(", "ChainInteractPlanner.resolveRayTarget");
    }

    private static String freezeSeedBody() {
        return JavaSourceSlices.methodBody(JavaSourceSlices.stripped(PLANNER_PATH),
                "private FrozenInteractSeed freezeInteractSeed(", "ChainInteractPlanner.freezeInteractSeed");
    }

    /** 冻结 seed 用的最小方块（纯身份载体）。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.wood);
        }
    }
}
