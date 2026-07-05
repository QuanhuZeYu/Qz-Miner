package club.heiqi.qz_miner;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import club.heiqi.qz_miner.core.PlayerManager;
import club.heiqi.qz_miner.chain.state.ChainStateService;
import club.heiqi.qz_miner.chain.executor.ChainDropCollector;
import club.heiqi.qz_miner.chain.executor.ChainExecutor;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBusDrainer;
import club.heiqi.qz_miner.chain.execution.ChainExecutionContextRegistry;
import club.heiqi.qz_miner.chain.execution.ChainExecutionEventBridge;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainSubModeBootstrap;
import club.heiqi.qz_miner.chain.planner.ChainInteractPlanner;
import club.heiqi.qz_miner.chain.planner.ChainPlanningEventBridge;
import club.heiqi.qz_miner.chain.planner.ChainPlanner;
import club.heiqi.qz_miner.chain.planner.GregTechCableReplacePlanner;
import club.heiqi.qz_miner.chain.statemachine.ChainStateMachine;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.event.EventListener;
import club.heiqi.qz_miner.event.PlayerStateEvent;
import club.heiqi.qz_miner.event.QzEvents;
import club.heiqi.qz_miner.network.NetworkMain;
import club.heiqi.qz_miner.parallel.ParallelTickExecutor;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = MyMod.MODID,
    version = Tags.VERSION,
    name = MyMod.MOD_NAME,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "after:qz_uilib;",
    guiFactory = "club.heiqi.qz_miner.client.configGUI.QzMinerConfigGUIFactory")
public class MyMod {

    public static final String MODID = "qz_miner";
    public static final String MOD_NAME = "Qz Miner";
    public static final Logger LOG = LogManager.getLogger(MODID);
    public static final Config CONFIG = new Config();
    public static PlayerManager playerManager;
    public static ChainStateService chainStateService;
    public static ChainPlanner chainPlanner;
    public static ChainInteractPlanner chainInteractPlanner;
    public static GregTechCableReplacePlanner gregTechCableReplacePlanner;
    public static ChainDropCollector chainDropCollector;
    public static ChainExecutor chainExecutor;
    public static NetworkMain networkMain;
    public static ParallelTickExecutor parallelTickExecutor;
    /** 阶段 2：连锁跨线程事件总线，publish 来自任意线程，drain 仅主线程。 */
    public static ChainEventBus chainEventBus;
    /** 阶段 3：客户端独立事件总线（仅客户端实例化，仅空跑 drain 骨架，预览订阅留阶段6）。 */
    public static ChainEventBus clientChainEventBus;
    /** 阶段 2：连锁状态机，currentPhase/currentGeneration 唯一写权威。 */
    public static ChainStateMachine chainStateMachine;
    /** 阶段 4：规划事件桥，订阅 PlanStarted 发起影子 traverser，完成 publish PlanCompleted 推进 PLANNING→RUNNING。 */
    public static ChainPlanningEventBridge chainPlanningEventBridge;
    /** 阶段5：执行上下文注册表（E1-c），worker 完成 put shadowQueue，执行订阅者 get 领取。 */
    public static ChainExecutionContextRegistry chainExecutionContextRegistry;
    /** 阶段5：执行事件桥（E2-a dry-run），订阅 PlanCompleted + ServerTickEvent 消费队列，完成 publish ExecutionFinished + 临时 LifecycleCleanup 桥（E4-b）。 */
    public static ChainExecutionEventBridge chainExecutionEventBridge;

    /**
     * 确保并行 Tick 执行器可用。
     *
     * 单人世界退出时服务端停止事件会销毁执行器，
     * 重新进入世界后需要按需重建，避免客户端预览或服务端规划空指针。
     *
     * @return 可用的并行 Tick 执行器
     */
    public static synchronized ParallelTickExecutor ensureParallelTickExecutor() {
        if (parallelTickExecutor == null) {
            parallelTickExecutor = new ParallelTickExecutor();
        }
        return parallelTickExecutor;
    }

    @SidedProxy(clientSide = "club.heiqi.qz_miner.ClientProxy", serverSide = "club.heiqi.qz_miner.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {
        networkMain = new NetworkMain();
        networkMain.register();
        ChainModeBootstrap.bootstrap();
        ChainSubModeBootstrap.bootstrap();
        playerManager = new PlayerManager();
        chainStateService = new ChainStateService();
        chainPlanner = new ChainPlanner();
        chainInteractPlanner = new ChainInteractPlanner();
        if (CompatAdapters.isSubModeAvailable(ChainSubMode.SPECIAL_GT_CABLE_REPLACE)) {
            gregTechCableReplacePlanner = new GregTechCableReplacePlanner();
        }
        chainDropCollector = new ChainDropCollector();
        chainExecutor = new ChainExecutor();
        ServerMainThreadDispatcher.bootstrap();
        // 阶段 2：接入事件总线 + 状态机，空跑 drain（此时无 publish 点，每 tick poll 空队列零副作用）。
        // init 在服务端主线程执行，bindMainThread 软校验锚锁定当前线程。
        chainEventBus = new ChainEventBus();
        chainEventBus.bindMainThread(Thread.currentThread());
        chainStateMachine = new ChainStateMachine(chainEventBus);
        // 阶段5：执行上下文注册表（E1-c），先于规划桥实例化（规划桥构造器注入 registry）
        chainExecutionContextRegistry = new ChainExecutionContextRegistry();
        // 阶段 4：规划事件桥，在状态机之后实例化（状态机 publish PlanStarted，bridge 订阅之发起影子 traverser）
        // 注入 registry：worker 完成路径 put shadowQueue，解决"shadowQueue 局部变量断链"卡点
        chainPlanningEventBridge = new ChainPlanningEventBridge(chainEventBus, chainExecutionContextRegistry);
        // 阶段5：执行事件桥，订阅 PlanCompleted + ServerTickEvent 消费（dry-run，E2-a 不破坏）。
        // 接线顺序：状态机 → registry → 规划桥 → 执行桥（构造，订阅 PlanCompleted）→ Drainer.bootstrap() → 执行桥.bootstrap()。
        // Drainer 先注册 FML bus，确保 ServerTickEvent 分发顺序：drainer.onServerTick（drain，同步触发 onPlanCompleted 登记 context）
        // → executionBridge.onServerTick（消费 context），同 tick 完成登记+消费，无延迟（阶段8 接管真实破坏时手感不受影响）。
        chainExecutionEventBridge = new ChainExecutionEventBridge(chainEventBus, chainExecutionContextRegistry);
        new ChainEventBusDrainer(chainEventBus).bootstrap();
        chainExecutionEventBridge.bootstrap();
        ensureParallelTickExecutor();
        QzEvents.register(PlayerStateEvent.class, (EventListener<PlayerStateEvent>) e ->
                LOG.debug("[EventSystem] Received PlayerStateEvent: player={}, reason={}",
                        e.player.getCommandSenderName(), e.reason));
        proxy.init(event);
    }

    @Mod.EventHandler
    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        ServerMainThreadDispatcher.onServerStarting();
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        ServerMainThreadDispatcher.onServerStopping();
        PlayerManager.clearAllPlayers();
        if (parallelTickExecutor != null) {
            parallelTickExecutor.shutdown();
            parallelTickExecutor = null;
        }
    }
}
