package club.heiqi.qz_miner;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import club.heiqi.qz_miner.core.PlayerManager;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ServerConfigHotApplyService;
import club.heiqi.qz_miner.chain.state.ChainStateService;
import club.heiqi.qz_miner.chain.executor.ChainDropCollector;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBusDrainer;
import club.heiqi.qz_miner.chain.execution.ChainExecutionContextRegistry;
import club.heiqi.qz_miner.chain.execution.ChainExecutionEventBridge;
import club.heiqi.qz_miner.chain.lifecycle.ChainLifecycleBridge;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainSubModeBootstrap;
import club.heiqi.qz_miner.chain.planner.ChainInteractPlanner;
import club.heiqi.qz_miner.chain.planner.ChainPlanningEventBridge;
import club.heiqi.qz_miner.chain.planner.ChainPlanner;
import club.heiqi.qz_miner.chain.planner.GregTechCableReplacePlanner;
import club.heiqi.qz_miner.chain.state.projection.ChainConfigProjectionBridge;
import club.heiqi.qz_miner.chain.state.projection.ChainStateProjectionBridge;
import club.heiqi.qz_miner.chain.statemachine.ChainStateMachine;
import club.heiqi.qz_miner.chain.watchdog.ChainWatchdog;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.event.EventListener;
import club.heiqi.qz_miner.event.PlayerStateEvent;
import club.heiqi.qz_miner.event.QzEvents;
import club.heiqi.qz_miner.network.NetworkMain;
import club.heiqi.qz_miner.parallel.ParallelTickExecutor;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapRoundPhaseProjectionBridge;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapRoundService;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLModIdMappingEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = MyMod.MODID,
    version = Tags.VERSION,
    name = MyMod.MOD_NAME,
    guiFactory = "club.heiqi.qz_miner.client.settings.MinerConfigGuiFactory",
    acceptedMinecraftVersions = "[1.7.10]",
    // 构建生成同 major.minor 的 FML Maven 版本区间。FML 的 NetworkModHolder 优先选择
    // @NetworkCheckHandler，直接声明区间可保留其范围比较与接受自身版本的检查。
    acceptableRemoteVersions = NetworkVersionRange.VALUE,
    dependencies = "required-after:qz_uilib@[5.0.0-beta3-miner-ui-r13,6.0.0);")
public class MyMod {

    public static final String MODID = "qz_miner";
    public static final String MOD_NAME = "Qz Miner";
    public static final Logger LOG = LogManager.getLogger(MODID);
    public static final Config CONFIG = new Config();
    public static PlayerManager playerManager;
    /** 服务端自动工具换位唯一 round 账本。 */
    public static AutoToolSwapRoundService autoToolSwapRoundService;
    /** ordinary CHAIN/AREA 唯一 local session/physical ledger owner。 */
    public static AutoToolSwapServerBatchService autoToolSwapServerBatchService;
    public static ChainStateService chainStateService;
    public static ChainPlanner chainPlanner;
    public static ChainInteractPlanner chainInteractPlanner;
    public static GregTechCableReplacePlanner gregTechCableReplacePlanner;
    public static ChainDropCollector chainDropCollector;
    public static NetworkMain networkMain;
    /** 当前服务端 session 的配置热发布入口，命令与集成服 listener 共用。 */
    public static ServerConfigHotApplyService serverConfigHotApplyService;
    public static ParallelTickExecutor parallelTickExecutor;
    /** 连锁跨线程事件总线，任意线程 publish，服务端主线程 drain。 */
    public static ChainEventBus chainEventBus;
    /** 客户端独立事件总线，主线程 drain 驱动阶段投影。 */
    public static ChainEventBus clientChainEventBus;
    /** 连锁状态机，统一写入 phase 与 generation。 */
    public static ChainStateMachine chainStateMachine;
    /** 规划桥，冻结启动事实并发起只读 traverser worker。 */
    public static ChainPlanningEventBridge chainPlanningEventBridge;
    /** 执行上下文注册表，主线程登记后连接 worker 生产与主线程消费。 */
    public static ChainExecutionContextRegistry chainExecutionContextRegistry;
    /** 主线程执行桥，消费规划目标并发布执行终局。 */
    public static ChainExecutionEventBridge chainExecutionEventBridge;
    /** 阶段投影桥，将状态机转移广播下发到客户端。 */
    public static ChainStateProjectionBridge chainStateProjectionBridge;
    /** 配置投影桥，登录及规划完成时同步已接受配置与匹配数。 */
    public static ChainConfigProjectionBridge chainConfigProjectionBridge;
    /** 将状态机不可变阶段事件投影到当前工具换位 round。 */
    public static AutoToolSwapRoundPhaseProjectionBridge autoToolSwapRoundPhaseProjectionBridge;
    /** 连锁 watchdog，超时后发布事件交统一协作收尾。 */
    public static ChainWatchdog chainWatchdog;
    /** 连锁生命周期桥，将玩家生命周期转为统一清理事件。 */
    public static ChainLifecycleBridge chainLifecycleBridge;

    /**
     * 确保并行 Tick 执行器可用。
     *
     * 单人世界退出时服务端停止事件会销毁执行器，
     * 重进世界时由客户端预览或服务端规划按需重建。
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
        autoToolSwapRoundService = new AutoToolSwapRoundService();
        autoToolSwapServerBatchService = new AutoToolSwapServerBatchService();
        autoToolSwapServerBatchService.publishPolicy(ConfigBootstrap.currentCommittedSnapshot());
        chainStateService = new ChainStateService();
        chainPlanner = new ChainPlanner();
        chainInteractPlanner = new ChainInteractPlanner();
        if (CompatAdapters.isSubModeAvailable(ChainSubMode.SPECIAL_GT_CABLE_REPLACE)) {
            gregTechCableReplacePlanner = new GregTechCableReplacePlanner();
        }
        chainDropCollector = new ChainDropCollector();
        ServerMainThreadDispatcher.bootstrap();
        // 两侧总线分别绑定所属主线程：服务端在 serverStarting，客户端在 ClientProxy.init。
        chainEventBus = new ChainEventBus();
        // restore barrier 必须早于状态机、执行桥等会清理 state/registry 的订阅者。
        autoToolSwapServerBatchService.subscribeLifecycle(chainEventBus);
        chainStateMachine = new ChainStateMachine(chainEventBus);
        chainExecutionContextRegistry = new ChainExecutionContextRegistry();
        // 执行桥在规划前置校验通过后统一登记 context 并开启掉落窗口。
        chainExecutionEventBridge = ChainExecutionEventBridge.withLocalToolSwap(
                chainEventBus, chainExecutionContextRegistry, autoToolSwapServerBatchService);
        chainPlanningEventBridge = new ChainPlanningEventBridge(chainEventBus, chainExecutionEventBridge);
        // 已提交阶段下发客户端，供 HUD 与预览读取。
        chainStateProjectionBridge = new ChainStateProjectionBridge(chainEventBus);
        // 登录和规划完成时下发已接受配置，匹配数来自本次规划确认目标。
        chainConfigProjectionBridge = new ChainConfigProjectionBridge(chainEventBus);
        autoToolSwapRoundPhaseProjectionBridge = new AutoToolSwapRoundPhaseProjectionBridge(
                chainEventBus, autoToolSwapRoundService);
        // 先注册 drainer，再注册执行桥与 watchdog，使同 tick 先消费事件，再执行与检查超时。
        chainWatchdog = new ChainWatchdog(chainEventBus);
        chainLifecycleBridge = new ChainLifecycleBridge(chainEventBus);
        new ChainEventBusDrainer(chainEventBus).bootstrap();
        chainExecutionEventBridge.bootstrap();
        autoToolSwapServerBatchService.bootstrap();
        chainWatchdog.bootstrap();
        chainLifecycleBridge.bootstrap();
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

    /**
     * FML 加载完成事件：转发到侧代理（客户端侧用于候选源注册表标脏）。
     *
     * <p><b>为什么必须放在这里</b>：{@link FMLLoadCompleteEvent} / {@link FMLModIdMappingEvent} 是 FML
     * 生命周期事件，不是 {@code cpw.mods.fml.common.eventhandler.Event} 的子类——用 {@code @SubscribeEvent}
     * 注册进 EventBus 会在装配期抛 {@code IllegalArgumentException} 并导致客户端启动崩溃（真机实证）。
     * FML 生命周期事件的正确通道是 {@code @Mod} 类上的 {@code @Mod.EventHandler} 方法。</p>
     *
     * @param event FML 加载完成事件
     */
    @Mod.EventHandler
    public void onLoadComplete(FMLLoadCompleteEvent event) {
        proxy.onLoadComplete(event);
    }

    /**
     * FML ID 重映射事件：转发到侧代理（客户端侧用于候选源注册表标脏）。
     *
     * @param event FML ID 重映射事件
     */
    @Mod.EventHandler
    public void onModIdMapping(FMLModIdMappingEvent event) {
        proxy.onModIdMapping(event);
    }

    @Mod.EventHandler
    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        // FMLServerStartingEvent 与服务端 tick 共用线程，在此绑定服务端总线。
        if (chainEventBus != null) {
            chainEventBus.bindMainThread(Thread.currentThread());
        }
        ServerMainThreadDispatcher.onServerStarting();
        // 主菜单保存后集成服启动：在 dispatcher 就绪后从 Authority 再发布 general 与下一 session policy。
        ConfigBootstrap.reapplyGeneralOnServerStarting();
        if (autoToolSwapServerBatchService != null) {
            autoToolSwapServerBatchService.publishPolicy(ConfigBootstrap.currentCommittedSnapshot());
        }
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        // local physical restore 必须早于玩家映射、projection、state 与 dispatcher 清理。
        if (autoToolSwapServerBatchService != null) {
            autoToolSwapServerBatchService.finalizeAll(
                    AutoToolSwapServerBatchService.CloseCause.SERVER_STOP,
                    Math.max(0L, club.heiqi.qz_miner.chain.eventbus.ChainTickSource.currentServerTick()));
        }
        // 玩家生命周期清理先完成，再关闭 dispatcher，保证清理任务可分发。
        PlayerManager.clearAllPlayersOnServerStopping();
        if (chainStateService != null) {
            chainStateService.suspendDropsOnServerStopping();
        }
        if (autoToolSwapRoundService != null) {
            autoToolSwapRoundService.clearAll();
        }
        ServerMainThreadDispatcher.onServerStopping();
        serverConfigHotApplyService = null;
        if (parallelTickExecutor != null) {
            parallelTickExecutor.shutdown();
            parallelTickExecutor = null;
        }
    }
}
