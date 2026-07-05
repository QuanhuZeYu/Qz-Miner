package club.heiqi.qz_miner;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import club.heiqi.qz_miner.core.PlayerManager;
import club.heiqi.qz_miner.chain.state.ChainStateService;
import club.heiqi.qz_miner.chain.executor.ChainDropCollector;
import club.heiqi.qz_miner.chain.executor.ChainExecutor;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBusDrainer;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainSubModeBootstrap;
import club.heiqi.qz_miner.chain.planner.ChainInteractPlanner;
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
    /** 阶段 2：连锁状态机，currentPhase/currentGeneration 唯一写权威。 */
    public static ChainStateMachine chainStateMachine;

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
        new ChainEventBusDrainer(chainEventBus).bootstrap();
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
