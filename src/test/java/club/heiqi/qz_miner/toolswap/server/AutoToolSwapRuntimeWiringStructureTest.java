package club.heiqi.qz_miner.toolswap.server;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 自动工具换位服务、阶段桥和停止清理顺序的结构门禁。
 *
 * <p>断言对象是「装配顺序 + 数据流」，不是整文件字符位置：所有 indexOf 都先裁到
 * {@code MyMod} 对应方法体内再比较，注释形态与无关方法的同名串不会误命中；
 * 「唯一账本 / 唯一 local owner」改成读 {@link MyMod} 的静态字段类型清单
 * （编译产物层事实），不再数源码里 {@code new X()} 的拼写。</p>
 */
public class AutoToolSwapRuntimeWiringStructureTest {

    private static final String MY_MOD_SOURCE = "src/main/java/club/heiqi/qz_miner/MyMod.java";

    @Test
    public void myModOwnsOneProjectionAndOneLocalOwnerBeforeStateService() throws Exception {
        String init = initBody();

        assertSingleOwnerField(AutoToolSwapRoundService.class);
        assertSingleOwnerField(AutoToolSwapServerBatchService.class);

        JavaSourceSlices.assertBefore(init, "playerManager = new PlayerManager()",
                "autoToolSwapRoundService = new AutoToolSwapRoundService()",
                "玩家映射必须先于 round 账本");
        JavaSourceSlices.assertBefore(init, "autoToolSwapRoundService = new AutoToolSwapRoundService()",
                "autoToolSwapServerBatchService = new AutoToolSwapServerBatchService()",
                "round 账本必须先于 local owner");
        JavaSourceSlices.assertBefore(init, "autoToolSwapServerBatchService = new AutoToolSwapServerBatchService()",
                "chainStateService = new ChainStateService()",
                "local owner 必须先于 ChainStateService");

        JavaSourceSlices.assertContains(
                JavaSourceSlices.callArguments(init, "autoToolSwapServerBatchService.publishPolicy",
                        "装配期策略发布"),
                "ConfigBootstrap.currentCommittedSnapshot()",
                "装配期必须用当前已提交快照发布策略，不得用默认/陈旧快照");
    }

    @Test
    public void localLifecycleBarrierSubscribesBeforeStateMachineAndExecutionUsesLocalOwner() throws Exception {
        String init = initBody();

        JavaSourceSlices.assertBefore(init, "autoToolSwapServerBatchService.subscribeLifecycle(chainEventBus)",
                "chainStateMachine = new ChainStateMachine(chainEventBus)",
                "restore barrier 必须先于状态机订阅（否则状态被清后才订阅）");
        JavaSourceSlices.assertContains(
                JavaSourceSlices.callArguments(init, "ChainExecutionEventBridge.withLocalToolSwap", "执行桥装配"),
                "autoToolSwapServerBatchService",
                "执行桥必须注入唯一 local owner");
    }

    @Test
    public void phaseBridgeSubscribesAfterStateMachineAndBeforeDrainer() throws Exception {
        String init = initBody();

        JavaSourceSlices.assertBefore(init, "chainStateMachine = new ChainStateMachine(chainEventBus)",
                "chainConfigProjectionBridge = new ChainConfigProjectionBridge",
                "配置投影桥必须在状态机之后实例化");
        JavaSourceSlices.assertBefore(init, "chainConfigProjectionBridge = new ChainConfigProjectionBridge",
                "autoToolSwapRoundPhaseProjectionBridge =",
                "阶段投影桥必须在配置投影桥之后实例化");
        JavaSourceSlices.assertBefore(init, "autoToolSwapRoundPhaseProjectionBridge =",
                "new ChainEventBusDrainer(chainEventBus).bootstrap()",
                "投影桥必须先于 Drainer.bootstrap，保证 drain 顺序");
    }

    @Test
    public void serverStopFinalizesLocalOwnerBeforePlayerProjectionAndDispatcherCleanup() throws Exception {
        String stopping = methodBody("public void serverStopping(FMLServerStoppingEvent event)");

        JavaSourceSlices.assertBefore(stopping, "autoToolSwapServerBatchService.finalizeAll(",
                "PlayerManager.clearAllPlayersOnServerStopping()",
                "停机必须先收口 local owner（restore）再清玩家映射");
        JavaSourceSlices.assertBefore(stopping, "PlayerManager.clearAllPlayersOnServerStopping()",
                "autoToolSwapRoundService.clearAll()",
                "玩家清理必须先于 round 账本清空");
        JavaSourceSlices.assertBefore(stopping, "autoToolSwapRoundService.clearAll()",
                "ServerMainThreadDispatcher.onServerStopping()",
                "账本清空必须先于 dispatcher 关闭（否则 stop 后 FIFO 拒绝清理任务）");
    }

    @Test
    public void serverStartRepublishesCurrentPolicyAfterDispatcherBecomesReady() throws Exception {
        String starting = methodBody("public void serverStarting(FMLServerStartingEvent event)");

        JavaSourceSlices.assertBefore(starting, "ServerMainThreadDispatcher.onServerStarting()",
                "ConfigBootstrap.reapplyGeneralOnServerStarting()",
                "dispatcher 就绪后才重放 general 配置");
        JavaSourceSlices.assertBefore(starting, "ConfigBootstrap.reapplyGeneralOnServerStarting()",
                "autoToolSwapServerBatchService.publishPolicy(",
                "配置重放后才重发布当前会话策略");
        JavaSourceSlices.assertContains(
                JavaSourceSlices.callArguments(starting, "autoToolSwapServerBatchService.publishPolicy",
                        "开服策略重发布"),
                "ConfigBootstrap.currentCommittedSnapshot()",
                "开服重发布必须取自当前已提交快照");
    }

    /** MyMod 必须恰好持有一个该类型的静态字段（唯一账本 / 唯一 owner）。 */
    private static void assertSingleOwnerField(Class<?> ownerType) {
        int count = 0;
        for (Field field : MyMod.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ownerType) {
                count++;
            }
        }
        Assert.assertEquals("MyMod 必须恰好持有 1 个 " + ownerType.getSimpleName() + " 静态字段",
                1, count);
    }

    private static String initBody() {
        return methodBody("public void init(FMLInitializationEvent event)");
    }

    private static String methodBody(String signature) {
        return JavaSourceSlices.methodBody(JavaSourceSlices.stripped(MY_MOD_SOURCE), signature, "MyMod");
    }
}
