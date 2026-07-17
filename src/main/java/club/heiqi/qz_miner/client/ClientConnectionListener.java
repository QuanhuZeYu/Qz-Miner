package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.network.PacketChainConfigRequest;
import club.heiqi.qz_miner.network.PacketObjectGroupConfigRequest;
import club.heiqi.qz_miner.network.ObjectGroupWireConfig;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

/**
 * 客户端连接与世界生命周期监听。
 *
 * <p>connect/disconnect 以 {@code event.handler}（{@code INetHandler}）对象 identity
 * 绑定 {@link ClientConnectionLifecycle}；world load/unload 绑定远端 world 对象。
 * 初始化与清理均携带转移 token，主线程 gate 后执行；旧连接/旧世界迟到事件 no-op。</p>
 *
 * <h3>接管收敛（I7）</h3>
 * <ul>
 *   <li>仅 {@code TransitionResult.transitioned=true} 时调度连接初始化 / world 接管</li>
 *   <li>相同 active handler 重复 connect：不 reset 投影、不重复发 C2S</li>
 *   <li>相同 world 重复 load：不重复清理</li>
 *   <li>新 connection B 接管：B 主线程 init 在 B token gate 内先统一 takeover cleanup，
 *       再 reset 连接投影并发 C2S；旧 A disconnect cleanup 在 B 建立后 no-op 也不残留</li>
 *   <li>新 world B 替换 active A：仅 {@code replacedPreviousLifecycle=true} 时在 B world gate
 *       内接管清理（不发连接级 C2S、不重置无关连接状态）；首次 bind 不调度清理</li>
 * </ul>
 *
 * <p>生产 callback 目前在 lifecycle monitor 内：须短小、禁阻塞、禁反向 lifecycle 入口。
 * 网络 C2S 仍在 connect 初始化路径内发送（P2 残余：I/O 在 monitor 内；未扩大本轮架构）。</p>
 *
 * <p>可测性：{@code @SubscribeEvent} 方法只做字段提取后委托 package-private
 * {@link #handleConnected}/{@link #handleDisconnected}/{@link #handleWorldLoad}/
 * {@link #handleWorldUnload}；纯 JVM 测试经同一委托入口 + 可注入主线程调度边界，
 * 覆盖 transition 判断、dispatcher 排队、token gate 与 takeover/init/cleanup 分支。</p>
 */
@SideOnly(Side.CLIENT)
public class ClientConnectionListener {

    /**
     * 主线程任务调度边界。
     *
     * <p>生产委托 {@link ClientMainThreadDispatcher}；测试可注入队列实现以确定性 drain。</p>
     */
    interface TaskDispatcher {
        /**
         * @param task 待在客户端主线程语义下执行的任务
         */
        void run(Runnable task);
    }

    /** 生命周期清理子项边界；生产调用真实资源，测试可逐项注入故障。 */
    interface CleanupActions {
        void clearObjectGroupPending();
        void stopPreviewTask();
        void disposeRenderer();
        void clearPhase();
        void clearEventPending();
        void resetToolSwap();
    }

    private static final TaskDispatcher PRODUCTION_DISPATCHER = new TaskDispatcher() {
        @Override
        public void run(Runnable task) {
            ClientMainThreadDispatcher.run(task);
        }
    };

    private final TaskDispatcher dispatcher;
    private final CleanupActions cleanupActions;

    /**
     * 测试钩子：非 null 时替代真实资源清理（不停订阅关系）。
     * 生产路径保持 null。
     */
    volatile java.util.function.Consumer<String> cleanupHookForTests;

    /**
     * 测试钩子：非 null 时替代真实连接初始化（reset 投影 + C2S）。
     * 生产路径保持 null。
     */
    volatile Runnable initHookForTests;

    /**
     * 生产默认构造：调度经 {@link ClientMainThreadDispatcher}。
     */
    public ClientConnectionListener() {
        this(PRODUCTION_DISPATCHER);
    }

    /**
     * 可注入主线程调度边界（测试用；生产走无参构造）。
     *
     * @param dispatcher 主线程调度；null 时回落生产 dispatcher
     */
    ClientConnectionListener(TaskDispatcher dispatcher) {
        this(dispatcher, null);
    }

    /** 可注入调度与清理子项，供 listener 真实入口故障隔离测试使用。 */
    ClientConnectionListener(TaskDispatcher dispatcher, CleanupActions cleanupActions) {
        this.dispatcher = dispatcher != null ? dispatcher : PRODUCTION_DISPATCHER;
        this.cleanupActions = cleanupActions;
    }

    /**
     * 注册客户端连接与世界生命周期监听。
     */
    public void register() {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 连上服务器：字段提取后委托 {@link #handleConnected(Object)}。
     *
     * @param event 客户端连服事件
     */
    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        handleConnected(event.handler);
    }

    /**
     * 断线：字段提取后委托 {@link #handleDisconnected(Object)}。
     *
     * @param event 客户端断线事件
     */
    @SubscribeEvent
    public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        handleDisconnected(event.handler);
    }

    /**
     * 世界加载：字段提取后委托 {@link #handleWorldLoad(Object, boolean)}。
     *
     * @param event 世界加载事件
     */
    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.world == null) {
            return;
        }
        handleWorldLoad(event.world, event.world.isRemote);
    }

    /**
     * 世界卸载：字段提取后委托 {@link #handleWorldUnload(Object, boolean)}。
     *
     * @param event 世界卸载事件
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world == null) {
            return;
        }
        handleWorldUnload(event.world, event.world.isRemote);
    }

    /**
     * 连上服务器：以 handler 建 connection token；仅 transitioned 时调度主线程
     * 接管清理 + 连接初始化。重复 connect no-op。
     *
     * <p>生产 {@link #onClientConnected} 与纯 JVM 测试共用本入口。</p>
     *
     * @param handler 连接 identity（生产为 {@code event.handler}）
     */
    void handleConnected(Object handler) {
        final ClientConnectionLifecycle.TransitionResult result =
                ClientConnectionLifecycle.connect(handler);
        if (!shouldScheduleConnectionInit(result)) {
            return;
        }
        final ClientConnectionLifecycle.Token token = result.token();
        dispatcher.run(new Runnable() {
            @Override
            public void run() {
                runConnectionTakeoverAndInit(token);
            }
        });
    }

    /**
     * 断线：仅 handler 为当前连接且 active 时转 inactive 并排队 cleanup。
     * 迟到/重复 disconnect no-op，不调度新清理。
     *
     * <p>生产 {@link #onClientDisconnected} 与纯 JVM 测试共用本入口。</p>
     *
     * @param handler 断线事件的 handler
     */
    void handleDisconnected(Object handler) {
        ClientConnectionLifecycle.DisconnectResult result =
                ClientConnectionLifecycle.disconnect(handler);
        if (!result.transitioned()) {
            return;
        }
        final ClientConnectionLifecycle.Token cleanupToken = result.token();
        dispatcher.run(new Runnable() {
            @Override
            public void run() {
                ClientConnectionLifecycle.runIfInactiveDisconnectCurrent(cleanupToken, new Runnable() {
                    @Override
                    public void run() {
                        // 受控主线程 callback（lifecycle monitor 内）：禁阻塞、禁反向 lifecycle 入口
                        clearObjectGroupSyncPendingIsolated();
                        cleanupLifecycleResources("client-disconnect");
                    }
                });
            }
        });
    }

    /**
     * 客户端远端世界加载：绑定 world identity；仅 transitioned 且 replaced 时调度接管清理。
     * 首次 bind 不调度清理；重复 load no-op。
     *
     * <p>生产 {@link #onWorldLoad} 与纯 JVM 测试共用本入口。</p>
     *
     * @param world 世界 identity
     * @param remote 是否客户端远端世界（{@code world.isRemote}）
     */
    void handleWorldLoad(Object world, boolean remote) {
        if (world == null || !remote) {
            return;
        }
        final ClientConnectionLifecycle.TransitionResult result =
                ClientConnectionLifecycle.bindWorld(world);
        if (!shouldScheduleWorldTakeoverCleanup(result)) {
            return;
        }
        final ClientConnectionLifecycle.Token token = result.token();
        dispatcher.run(new Runnable() {
            @Override
            public void run() {
                runWorldTakeoverCleanup(token);
            }
        });
    }

    /**
     * 客户端世界卸载：仅当前 world identity 且连接仍 current 时解绑并清理。
     * 旧 world / 重复 unload / disconnect 后 unload no-op。
     *
     * <p>生产 {@link #onWorldUnload} 与纯 JVM 测试共用本入口。</p>
     *
     * @param world 卸载的 world
     * @param remote 是否客户端远端世界
     */
    void handleWorldUnload(Object world, boolean remote) {
        if (world == null || !remote) {
            return;
        }
        ClientConnectionLifecycle.WorldUnbindResult result =
                ClientConnectionLifecycle.unbindWorld(world);
        if (!result.transitioned()) {
            return;
        }
        final ClientConnectionLifecycle.Token cleanupToken = result.token();
        dispatcher.run(new Runnable() {
            @Override
            public void run() {
                ClientConnectionLifecycle.runIfWorldUnbindCurrent(cleanupToken, new Runnable() {
                    @Override
                    public void run() {
                        // 受控主线程 callback（lifecycle monitor 内）：禁阻塞、禁反向 lifecycle 入口
                        cleanupLifecycleResources("client-world-unload");
                    }
                });
            }
        });
    }

    /**
     * 是否应调度连接初始化：仅 transitioned=true。
     *
     * @param result connect 转移结果
     * @return 应调度时为 true
     */
    static boolean shouldScheduleConnectionInit(ClientConnectionLifecycle.TransitionResult result) {
        return result != null && result.transitioned();
    }

    /**
     * 是否应调度 world 接管清理：transitioned 且替换了先前 active world。
     *
     * <p>首次 world bind（replaced=false）不调度，避免首 load 空清理；
     * 幂等 cleanup 本身安全，但以 replaced flag 收敛重复。</p>
     *
     * @param result bindWorld 转移结果
     * @return 应调度时为 true
     */
    static boolean shouldScheduleWorldTakeoverCleanup(ClientConnectionLifecycle.TransitionResult result) {
        return result != null && result.transitioned() && result.replacedPreviousLifecycle();
    }

    /**
     * 在 connection current+active gate 内执行统一接管清理 + 连接初始化。
     *
     * <p>受控主线程 callback 在 lifecycle monitor 内：先 takeover cleanup（停预览/释 GPU/
     * 清 phase/pending），再 reset 投影并发 C2S；任一清理子项失败仍继续其余清理与初始化。
     * 禁阻塞、禁反向 lifecycle 入口。
     * 不清订阅关系。</p>
     *
     * @param token connect 返回的 active token
     * @return gate 放行并执行时为 true
     */
    boolean runConnectionTakeoverAndInit(ClientConnectionLifecycle.Token token) {
        return ClientConnectionLifecycle.runIfConnectionCurrentAndActive(token, new Runnable() {
            @Override
            public void run() {
                // 受控主线程 callback（lifecycle monitor 内）：禁阻塞、禁反向 lifecycle 入口
                clearObjectGroupSyncPendingIsolated();
                cleanupLifecycleResources("connection-takeover");
                initializeConnectionState(token);
            }
        });
    }

    /**
     * 在 world current+active gate 内执行统一接管清理。
     *
     * <p>不发送连接级 C2S、不重置无关连接投影。受控主线程 callback 在 lifecycle monitor 内。</p>
     *
     * @param token bindWorld 返回的 active world token
     * @return gate 放行并执行时为 true
     */
    boolean runWorldTakeoverCleanup(ClientConnectionLifecycle.Token token) {
        return ClientConnectionLifecycle.runIfWorldCurrentAndActive(token, new Runnable() {
            @Override
            public void run() {
                // 受控主线程 callback（lifecycle monitor 内）：禁阻塞、禁反向 lifecycle 入口
                cleanupLifecycleResources("world-takeover");
            }
        });
    }

    /**
     * 连接初始化：requested 取本地 validated snapshot；server 投影回落 snapshot 且 matchedCount=0，
     * 防止新服务器首包前沿用旧值。非单人发 C2S。
     *
     * <p>须在 connection-active gate 内调用。网络发送仍在本路径（P2：monitor 内 I/O）。</p>
     */
    void initializeConnectionState(ClientConnectionLifecycle.Token connectionToken) {
        if (initHookForTests != null) {
            initHookForTests.run();
            return;
        }
        if (MyMod.chainStateService == null) {
            return;
        }
        // 一个连接初始化只捕获一次，revision、rules、groupCount 必须来自同一提交包装。
        final CommittedSnapshot committed = ConfigBootstrap.currentCommittedSnapshot();
        final ValidatedSnapshot snapshot = committed.snapshot;
        MyMod.chainStateService.getClientState().beginObjectGroupSync(
                connectionToken.connectionGeneration(), committed);
        MyMod.chainStateService.setClientRequestedChainConfig(snapshot.chainRadius, snapshot.chainMaxBlocks);
        // 只改必要客户端投影：server radius/maxBlocks 回落本地 snapshot，matchedCount 清零
        MyMod.chainStateService.getClientState().setServerChainRadius(snapshot.chainRadius);
        MyMod.chainStateService.getClientState().setServerChainMaxBlocks(snapshot.chainMaxBlocks);
        MyMod.chainStateService.getClientState().setServerMatchedTargetCount(0);

        if (MyMod.networkMain == null) {
            return;
        }
        if (!FMLClientHandler.instance().getClient().isSingleplayer()) {
            MyMod.networkMain.network.sendToServer(
                    new PacketChainConfigRequest(snapshot.chainRadius, snapshot.chainMaxBlocks));
        }
        MyMod.networkMain.network.sendToServer(new PacketObjectGroupConfigRequest(
                ObjectGroupWireConfig.fromRuleSet(committed.epoch, snapshot.objectGroups)));
    }

    /**
     * 统一清理：reset 无网络点击的工具换位、停预览、释放 GPU、清 phase、清 client event bus pending。
     *
     * <p>须在对应 lifecycle gate 内调用；禁反向调用 lifecycle 入口。
     * disconnect / unload / connection-takeover / world-takeover 共用，避免重复逻辑。
     * 不清 event-bus 订阅关系。</p>
     *
     * @param reason 清理原因
     */
    void cleanupLifecycleResources(String reason) {
        if (cleanupHookForTests != null) {
            runCleanupStep("test-hook", new Runnable() { @Override public void run() {
                cleanupHookForTests.accept(reason);
            }});
            return;
        }
        MyMod.LOG.debug("[ChainPreview] Cleaning preview lifecycle resources, reason={}", reason);
        runCleanupStep("auto-tool-swap", new Runnable() { @Override public void run() {
            if (cleanupActions != null) cleanupActions.resetToolSwap();
            else if (ClientProxy.autoToolSwapAdapter != null) ClientProxy.autoToolSwapAdapter.resetForLifecycle();
        }});
        runCleanupStep("preview-task", new Runnable() { @Override public void run() {
            if (cleanupActions != null) cleanupActions.stopPreviewTask();
            else if (ClientProxy.chainPreviewController != null) ClientProxy.chainPreviewController.stopPreviewForLifecycle();
        }});
        runCleanupStep("gpu-renderer", new Runnable() { @Override public void run() {
            if (cleanupActions != null) cleanupActions.disposeRenderer();
            else if (ClientProxy.chainPreviewRenderer != null) ClientProxy.chainPreviewRenderer.disposeForLifecycle();
        }});
        runCleanupStep("phase", new Runnable() { @Override public void run() {
            if (cleanupActions != null) cleanupActions.clearPhase();
            else if (ClientProxy.clientPhaseProjection != null) ClientProxy.clientPhaseProjection.clear();
        }});
        // 防旧 phase 在 cleanup 后仍 drain 回写投影；不清订阅
        runCleanupStep("event-pending", new Runnable() { @Override public void run() {
            if (cleanupActions != null) cleanupActions.clearEventPending();
            else if (MyMod.clientChainEventBus != null) MyMod.clientChainEventBus.clearPending();
        }});
    }

    /** 隔离一个生命周期清理子项；常规故障与可选链接故障均不得逃逸主线程 callback。 */
    static void runCleanupStep(String step, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException exception) {
            MyMod.LOG.warn("[Lifecycle] Cleanup step {} failed; continuing", step, exception);
        } catch (LinkageError error) {
            MyMod.LOG.warn("[Lifecycle] Cleanup step {} linkage failed; continuing", step, error);
        }
    }

    /** 连接接管或断开时清掉旧连接的对象组请求记录。 */
    private void clearObjectGroupSyncPendingIsolated() {
        runCleanupStep("object-group-pending", new Runnable() { @Override public void run() {
            if (cleanupActions != null) cleanupActions.clearObjectGroupPending();
            else if (MyMod.chainStateService != null) MyMod.chainStateService.getClientState().clearObjectGroupSyncPending();
        }});
    }
}
