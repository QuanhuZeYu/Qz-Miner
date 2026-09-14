package club.heiqi.qz_miner.chain.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.BlockSeedSnapshot;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.qz_miner.chain.state.ChainStateService;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * seed 租约刷新与生命周期隔离合同（无 GL、无 Minecraft 单例）。
 *
 * <p><b>形态口径</b>：控制器自身是纯逻辑，可 headless 构造，因此「锁不锁、什么时候放租约」一律
 * <strong>行为化</strong>——装出一个真实控制器 + 真实 {@link ClientPhaseProjection} 投影 + 真实
 * {@link ChainStateService}，反射调私有判定入口，断言返回的布尔值；旧写法断言
 * {@code source.contains("localOriginLocked || phase == ChainPhase.PLANNING")} 一类的表达式文本，
 * 加个括号、提个局部变量、换个等价写法就误报。</p>
 *
 * <p>只有真正无行为探针的部分才留结构断言：</p>
 * <ul>
 *   <li>tick 路径内部的调用次序与「本地破坏入口不回读已变为空气的 origin」——需要 Minecraft 单例与
 *       世界实例（1.7.10 不可 headless 构造），只能切方法体后比标识符与位置；</li>
 *   <li>「库存回退刷新已删除」——改成读编译产物常量池：不再引用 {@code AutoToolSwapAction} /
 *       {@code onToolLayoutVerified}，复活旧路径即红，且与命名、排版无关。</li>
 * </ul>
 */
public class ChainPreviewControllerTest {

    private static final String SOURCE_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/client/ChainPreviewController.java";
    private static final String CLASS_NAME =
            "club/heiqi/qz_miner/chain/client/ChainPreviewController";

    private boolean contextInstalled;
    private ChainStateService savedChainStateService;
    private ClientPhaseProjection savedPhaseProjection;

    @After
    public void restoreRuntimeContext() {
        if (contextInstalled) {
            MyMod.chainStateService = savedChainStateService;
            ClientProxy.clientPhaseProjection = savedPhaseProjection;
        }
    }

    @Test
    public void localDestroyLeaseIsEstablishedBeforeLookSamplingWithExactIdentityGates() throws Exception {
        String source = JavaSourceSlices.maskedMainSource(SOURCE_PATH);

        String tick = JavaSourceSlices.methodBody(source,
                "public void onClientTick(TickEvent.ClientTickEvent event)", "ChainPreviewController.onClientTick");
        JavaSourceSlices.assertBefore(tick, "shouldLockCurrentPreview()", "resolveLookHit(",
                "持锁判定必须先于采集视线命中（冻结预览不得被 live 目标改写位置）");

        String destroy = JavaSourceSlices.methodBody(source,
                "public void onLocalBlockDestroyed(int x, int y, int z)", "ChainPreviewController.onLocalBlockDestroyed");
        JavaSourceSlices.assertContains(destroy, "previewState.isActive()",
                "本地破坏入口必须核对预览仍在活动代");
        JavaSourceSlices.assertContains(destroy, "previewSeedSnapshot",
                "本地破坏入口必须核对既有 frozen seed");
        JavaSourceSlices.assertContains(destroy, "previewSeedWorld",
                "本地破坏入口必须核对 seed 所属世界");
        JavaSourceSlices.assertContains(destroy, "currentTarget",
                "本地破坏入口必须核对冻结目标身份");
        JavaSourceSlices.assertContains(destroy, "previewOriginLease.acquire(",
                "通过全部身份核对后必须建立 seed 租约");
        JavaSourceSlices.assertAbsent(destroy, "getBlock(",
                "入口只核对身份，不得回读已变为空气的 origin");
        JavaSourceSlices.assertAbsent(destroy, "getBlockMetadata(",
                "入口只核对身份，不得回读已变为空气的 origin");
    }

    @Test
    public void localLeaseAndPhaseLockAreMergedAndOnlyFullResetClearsLease() throws Exception {
        ChainPreviewController controller = new ChainPreviewController();
        PreviewOriginLease lease = previewOriginLease(controller);
        ClientPhaseProjection projection = new ClientPhaseProjection();
        installRuntimeContext(projection);
        int generation = controller.getPreviewState().begin(new ChainTarget(5, 64, -3));
        setField(controller, "currentTarget", new ChainTarget(5, 64, -3));

        lease.acquire(ChainPhase.IDLE, generation);
        Assert.assertTrue("本地破坏租约必须单独锁定预览（此刻服务端阶段投影仍是 IDLE）",
                shouldLockCurrentPreview(controller));

        lease.reset();
        projection.update(ChainPhase.PLANNING, generation, 0L);
        Assert.assertTrue("active phase 必须锁定预览", shouldLockCurrentPreview(controller));

        projection.clear();
        Assert.assertFalse("既无本地租约又无 active phase 时不得锁定", shouldLockCurrentPreview(controller));

        lease.acquire(ChainPhase.RUNNING, generation);
        BlockSeedSnapshot seed = new BlockSeedSnapshot(
                new ChainTarget(5, 64, -3), null, 0, TileIdentityToken.unresolved());
        setField(controller, "previewSeedSnapshot", seed);
        resetPreview(controller, false);
        Assert.assertTrue("刷新（replaceSeedLease=false）不得释放 seed 租约", lease.isActive());
        Assert.assertSame("刷新不得丢弃 frozen seed", seed, fieldValue(controller, "previewSeedSnapshot"));

        resetPreview(controller, true);
        Assert.assertFalse("完整重置必须释放 seed 租约", lease.isActive());
        Assert.assertNull("完整重置必须丢弃 frozen seed", fieldValue(controller, "previewSeedSnapshot"));
    }

    @Test
    public void inventoryFallbackRefreshIsRemovedAndLeaseIsClearedOnStop() throws Exception {
        CompiledClasses.Refs refs = CompiledClasses.refs(CompiledClasses.forInternalName(CLASS_NAME));
        for (String reference : refs.classRefs) {
            Assert.assertFalse("控制器不得再依赖库存回退协议类型: " + reference,
                    reference.endsWith("AutoToolSwapAction"));
        }
        for (String reference : refs.methodRefs) {
            Assert.assertFalse("控制器不得再调用库存布局回调: " + reference,
                    reference.endsWith("#onToolLayoutVerified"));
        }

        ChainPreviewController controller = new ChainPreviewController();
        PreviewOriginLease lease = previewOriginLease(controller);
        int generation = controller.getPreviewState().begin(new ChainTarget(0, 0, 0));
        lease.acquire(ChainPhase.RUNNING, generation);
        setField(controller, "previewSeedSnapshot",
                new BlockSeedSnapshot(new ChainTarget(0, 0, 0), null, 0, TileIdentityToken.unresolved()));

        controller.stopPreviewForLifecycle();

        Assert.assertFalse("生命周期停止必须释放 seed 租约", lease.isActive());
        Assert.assertNull("生命周期停止必须丢弃 frozen seed", fieldValue(controller, "previewSeedSnapshot"));
        Assert.assertFalse("生命周期停止后预览必须失活", controller.getPreviewState().isActive());
    }

    @Test
    public void worldFaceModeAndSubModeBelongToGenerationIdentity() {
        Object worldA = new Object();
        Object worldB = new Object();
        ChainTarget origin = new ChainTarget(1, 2, 3);
        ChainPreviewVisualSettings settings = ChainPreviewVisualSettings.current();
        PreviewInputSnapshot base = inputSnapshot(
            worldA, origin, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, settings);

        Assert.assertFalse("同 world/face/mode/subMode 的等价快照不得重建",
            ChainPreviewController.shouldRestartPreview(base, inputSnapshot(
                worldA, new ChainTarget(1, 2, 3), 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, settings)));
        Assert.assertTrue("face 变化必须重建", ChainPreviewController.shouldRestartPreview(
            base, inputSnapshot(worldA, new ChainTarget(1, 2, 3), 3, ChainMode.CHAIN,
                ChainSubMode.CHAIN_BASE, settings)));
        Assert.assertTrue("world 变化必须重建", ChainPreviewController.shouldRestartPreview(
            base, inputSnapshot(worldB, new ChainTarget(1, 2, 3), 2, ChainMode.CHAIN,
                ChainSubMode.CHAIN_BASE, settings)));
        Assert.assertTrue("mode 变化必须重建", ChainPreviewController.shouldRestartPreview(
            base, inputSnapshot(worldA, new ChainTarget(1, 2, 3), 2, ChainMode.AREA,
                ChainSubMode.AREA_SAME_BLOCK, settings)));
        Assert.assertTrue("subMode 变化必须重建", ChainPreviewController.shouldRestartPreview(
            base, inputSnapshot(worldA, new ChainTarget(1, 2, 3), 2, ChainMode.CHAIN,
                ChainSubMode.CHAIN_ORE, settings)));
    }

    private static PreviewInputSnapshot inputSnapshot(
            Object world, ChainTarget target, int face, ChainMode mode, ChainSubMode subMode,
            ChainPreviewVisualSettings settings) {
        return new PreviewInputSnapshot(world, target, face, mode, subMode, 8, 4096, settings, 0L, 0L);
    }

    @Test
    public void gtTraverserOwnsItsOriginAndPlannerRegistrationFailureRetries() throws Exception {
        Assert.assertFalse(ChainPreviewController.shouldProjectOriginBeforeTraversal(
                ChainSubMode.SPECIAL_GT_CABLE_REPLACE));
        Assert.assertTrue(ChainPreviewController.shouldProjectOriginBeforeTraversal(ChainSubMode.CHAIN_BASE));

        String source = JavaSourceSlices.maskedMainSource(SOURCE_PATH);
        String startPreview = JavaSourceSlices.methodBody(source,
                "private void startPreview(World world, ChainTarget target, BlockSeedSnapshot seedSnapshot,",
                "ChainPreviewController.startPreview(..., replaceSeedLease)");
        JavaSourceSlices.assertBefore(startPreview, "registerClientPre(", "catch (RuntimeException failure)",
                "注册预览任务必须由 try/catch 兜住（注册失败不能让本代预览悬空）");
        String recovery = JavaSourceSlices.blockAfter(startPreview, "catch (RuntimeException failure)");
        JavaSourceSlices.assertContains(recovery, "resetPreview(true)",
                "注册失败必须完整重置本代预览（含释放 seed 租约）");

        String tick = JavaSourceSlices.methodBody(source,
                "public void onClientTick(TickEvent.ClientTickEvent event)", "ChainPreviewController.onClientTick");
        JavaSourceSlices.assertBefore(tick, "shouldLockCurrentPreview()", "AREA_CUBOID_CLEAR",
                "frozen preview lock 必须先于 live 子模式判断（锁定期间不得被 live 选模式打断）");

        // 冻结身份判定：live 选择模式/子模式与冻结值故意不同，只有读冻结身份才能得到 true
        ChainPreviewController controller = new ChainPreviewController();
        ChainStateService service = installRuntimeContext(new ClientPhaseProjection());
        ChainClientState live = service.getClientState();
        live.setChainKeyPressed(true);
        live.setSelectedMode(ChainMode.AREA);
        live.setSelectedSubMode(ChainSubMode.AREA_CUBOID_CLEAR);
        ChainTarget target = new ChainTarget(5, 64, -3);
        int generation = controller.getPreviewState().begin(target);
        setField(controller, "currentTarget", target);
        setField(controller, "previewConcreteFace", Integer.valueOf(2));
        setField(controller, "previewMode", ChainMode.CHAIN);
        setField(controller, "previewSubMode", ChainSubMode.CHAIN_BASE);

        Assert.assertTrue("冻结身份全等必须有效（live 已切到 AREA 也不得影响）",
                isPreviewStillValid(controller, generation, target, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE));
        Assert.assertFalse("generation 变化必须失效", isPreviewStillValid(
                controller, generation + 1, target, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE));
        Assert.assertFalse("face 变化必须失效", isPreviewStillValid(
                controller, generation, target, 3, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE));
        Assert.assertFalse("mode 变化必须失效", isPreviewStillValid(
                controller, generation, target, 2, ChainMode.AREA, ChainSubMode.CHAIN_BASE));
        Assert.assertFalse("subMode 变化必须失效", isPreviewStillValid(
                controller, generation, target, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_ORE));
        Assert.assertFalse("目标变化必须失效", isPreviewStillValid(
                controller, generation, new ChainTarget(9, 9, 9), 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE));
        live.setChainKeyPressed(false);
        Assert.assertFalse("按键释放必须失效", isPreviewStillValid(
                controller, generation, target, 2, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE));
    }

    @Test
    public void liquidPreviewUsesSharedInclusiveRayAndOtherModesKeepObjectMouseOver() throws Exception {
        String source = JavaSourceSlices.maskedMainSource(SOURCE_PATH);
        String tick = JavaSourceSlices.methodBody(source,
                "public void onClientTick(TickEvent.ClientTickEvent event)", "ChainPreviewController.onClientTick");
        JavaSourceSlices.assertBefore(tick, "getSelectedSubMode()", "resolveLookHit(",
                "必须先取当前子模式再解析视线命中");
        JavaSourceSlices.assertBefore(tick, "resolveLookHit(", "getCurrentLookTarget(",
                "必须先解析视线命中再折算目标");

        String helper = JavaSourceSlices.methodBody(source,
                "private MovingObjectPosition resolveLookHit(", "ChainPreviewController.resolveLookHit");
        JavaSourceSlices.assertContains(helper, "objectMouseOver",
                "非液体源模式必须沿用原版 objectMouseOver");
        List<String> arguments = JavaSourceSlices.splitCallArguments(helper, "InteractionRayTrace.trace");
        Assert.assertEquals("液体源预览必须复用共享射线组件: " + arguments, 3, arguments.size());
        Assert.assertEquals("共享射线必须按调用方语义包含液体（不得写死）：" + arguments,
                "true", arguments.get(2));
    }

    @Test
    public void previewLimitClampAndHardCapAttributionFollowFrozenRules() {
        Assert.assertEquals(4096, ChainPreviewController.clampPreviewMaxTargets(8192, 8192, 4096));
        Assert.assertEquals(1024, ChainPreviewController.clampPreviewMaxTargets(8192, 1024, 4096));
        Assert.assertEquals(512, ChainPreviewController.clampPreviewMaxTargets(512, 8192, 4096));
        Assert.assertEquals(1, ChainPreviewController.clampPreviewMaxTargets(0, 0, 0));
        Assert.assertTrue(ChainPreviewController.isPreviewLimitClampedByHardCap(8192, 8192, 4096));
        Assert.assertFalse(ChainPreviewController.isPreviewLimitClampedByHardCap(8192, 1024, 4096));
        Assert.assertFalse("恰好等于硬顶不算被硬顶收窄",
            ChainPreviewController.isPreviewLimitClampedByHardCap(8192, 4096, 4096));
    }

    @Test
    public void rejectedRemoteProviderCancelsPreviewWithReason() {
        ChainPreviewController controller = new ChainPreviewController();
        ChainPreviewState state = controller.getPreviewState();
        int generation = state.begin(new ChainTarget(1, 2, 3));

        controller.startRemotePreview(
            ChainMode.CHAIN, ChainSubMode.CHAIN_BASE, new ChainTarget(1, 2, 3), 8, 4096, generation);

        Assert.assertFalse("远端请求失败必须清 previewActive", state.isActive());
        Assert.assertEquals(
            ChainPreviewState.CancelReason.REMOTE_UNAVAILABLE, state.getCancelReason());
    }

    @Test
    public void cancelRemotePreviewIsGenerationScopedAndTimeoutCheckIsIdempotent() {
        ChainPreviewController controller = new ChainPreviewController();
        ChainPreviewState state = controller.getPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));

        Assert.assertTrue(controller.cancelRemotePreview(
            generation, ChainPreviewState.CancelReason.REMOTE_TIMEOUT));
        Assert.assertFalse(state.isActive());
        Assert.assertEquals(ChainPreviewState.CancelReason.REMOTE_TIMEOUT, state.getCancelReason());
        Assert.assertFalse("已取消的代不得重复取消", controller.cancelRemotePreview(
            generation, ChainPreviewState.CancelReason.REMOTE_UNAVAILABLE));
        Assert.assertEquals(ChainPreviewState.CancelReason.REMOTE_TIMEOUT, state.getCancelReason());
        Assert.assertFalse("无在途远端请求时超时检查不得误报",
            controller.checkRemotePreviewTimeout(Long.MAX_VALUE));
    }

    // ------------------------------------------------------------------ 夹具

    /** 装出控制器运行所需的全局上下文（状态服务 + 阶段投影），返回本次安装的状态服务。 */
    private ChainStateService installRuntimeContext(ClientPhaseProjection projection) {
        savedChainStateService = MyMod.chainStateService;
        savedPhaseProjection = ClientProxy.clientPhaseProjection;
        contextInstalled = true;
        ChainStateService service = new ChainStateService();
        MyMod.chainStateService = service;
        ClientProxy.clientPhaseProjection = projection;
        return service;
    }

    private static PreviewOriginLease previewOriginLease(ChainPreviewController controller) throws Exception {
        return (PreviewOriginLease) fieldValue(controller, "previewOriginLease");
    }

    private static boolean shouldLockCurrentPreview(ChainPreviewController controller) throws Exception {
        Method method = ChainPreviewController.class.getDeclaredMethod("shouldLockCurrentPreview");
        method.setAccessible(true);
        return ((Boolean) method.invoke(controller)).booleanValue();
    }

    private static void resetPreview(ChainPreviewController controller, boolean clearSeedLease) throws Exception {
        Method method = ChainPreviewController.class.getDeclaredMethod("resetPreview", boolean.class);
        method.setAccessible(true);
        method.invoke(controller, Boolean.valueOf(clearSeedLease));
    }

    private static boolean isPreviewStillValid(ChainPreviewController controller, int generation,
            ChainTarget target, int concreteFace, ChainMode mode, ChainSubMode subMode) throws Exception {
        Method method = ChainPreviewController.class.getDeclaredMethod("isPreviewStillValid",
                int.class, ChainTarget.class, int.class, ChainMode.class, ChainSubMode.class);
        method.setAccessible(true);
        return ((Boolean) method.invoke(controller, Integer.valueOf(generation), target,
                Integer.valueOf(concreteFace), mode, subMode)).booleanValue();
    }

    private static Object fieldValue(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
