package club.heiqi.qz_miner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import club.heiqi.qz_miner.chain.client.ChainPreviewController;
import club.heiqi.qz_miner.chain.client.ChainPreviewRenderer;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjectionSubscriber;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ClientChainEventBusDrainer;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.client.ClientChainConfigSyncDispatch;
import club.heiqi.qz_miner.client.ClientConfigChangeListener;
import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.client.ClientConnectionListener;
import club.heiqi.qz_miner.client.ClientMainThreadDispatcher;
import club.heiqi.qz_miner.client.HudOverlay;
import club.heiqi.qz_miner.client.KeyListener;
import club.heiqi.qz_miner.client.RateLimitedRejectDiagnostics;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import net.minecraft.network.INetHandler;

public class ClientProxy extends CommonProxy {

    private static final long CONFIG_SYNC_REJECT_DIAG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(10L);
    private static final RateLimitedRejectDiagnostics CONFIG_SYNC_REJECT_DIAG =
            new RateLimitedRejectDiagnostics(CONFIG_SYNC_REJECT_DIAG_INTERVAL_NS);

    private static final ClientChainConfigSyncDispatch.LifecycleGate LIFECYCLE_GATE =
            new ClientChainConfigSyncDispatch.LifecycleGate() {
                @Override
                public boolean isActive(Object token) {
                    return token instanceof ClientConnectionLifecycle.Token
                            && ((ClientConnectionLifecycle.Token) token).isConnectionActive();
                }

                @Override
                public boolean isCurrentAndActive(Object token) {
                    if (!(token instanceof ClientConnectionLifecycle.Token)) {
                        return false;
                    }
                    return ClientConnectionLifecycle.isConnectionCurrentAndActive(
                            (ClientConnectionLifecycle.Token) token);
                }

                @Override
                public boolean publishIfCurrentAndActive(Object token, Runnable publication) {
                    if (!(token instanceof ClientConnectionLifecycle.Token)) {
                        return false;
                    }
                    return ClientConnectionLifecycle.publishIfConnectionCurrentAndActive(
                            (ClientConnectionLifecycle.Token) token, publication);
                }
            };

    public static ChainPreviewController chainPreviewController;
    public static ChainPreviewRenderer chainPreviewRenderer;
    /** 阶段6：客户端连锁阶段投影容器（单玩家，P1-2=A）。 */
    public static ClientPhaseProjection clientPhaseProjection;
    /** 阶段6：客户端投影事件订阅者（订阅 clientChainEventBus 上的 ChainPhaseChanged）。 */
    public static ClientPhaseProjectionSubscriber clientPhaseProjectionSubscriber;

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // 守决策4：客户端独立事件总线，仅空跑 drain 骨架；不 publish 客户端事件、不持有独立状态机
        // 预览订阅留阶段6接入；bindMainThread 锁定客户端主线程（ClientProxy.init 在客户端主线程执行）
        MyMod.clientChainEventBus = new ChainEventBus();
        MyMod.clientChainEventBus.bindMainThread(Thread.currentThread());
        new ClientChainEventBusDrainer(MyMod.clientChainEventBus).bootstrap();
        // 阶段6 A2：客户端投影容器 + 订阅者（订阅 clientChainEventBus 上的 ChainPhaseChanged，
        // ClientTickEvent.START drain 更新容器，守 I4 主线程收口）
        clientPhaseProjection = new ClientPhaseProjection();
        clientPhaseProjectionSubscriber = new ClientPhaseProjectionSubscriber(
                MyMod.clientChainEventBus, clientPhaseProjection);
        chainPreviewController = new ChainPreviewController();
        chainPreviewController.register();
        chainPreviewRenderer = new ChainPreviewRenderer();
        chainPreviewRenderer.register();
        new ClientConnectionListener().register();
        new ClientConfigChangeListener().register();
        HudOverlay hudOverlay = new HudOverlay();
        hudOverlay.register();
        new KeyListener(hudOverlay).register();
    }

    /**
     * 阶段8 块3 F3-a：处理客户端连锁配置同步下发。
     *
     * <p>本方法由 {@code PacketChainConfigSync.Handler} 在 Netty 线程调用。按
     * {@code netHandler} 对象 identity 捕获 connection token（非全局 capture），
     * 不匹配/inactive 直接丢弃。再经 {@link ClientMainThreadDispatcher} 投递。
     * 主线程任务内：先整包校验，再在 lifecycle 线性化边界内复核 connection 仍 current+active
     * 并写 ChainClientState 三字段。旧连接包在 B 已 connect 后即使全局 current=B 也丢弃。</p>
     *
     * <p>守 I4：volatile 只提供可见性，不授予 Netty 线程客户端状态写主权。
     * publication 回调禁阻塞、禁反向调用 lifecycle 入口。</p>
     *
     * @param chainRadius        服务端连锁半径上限
     * @param chainMaxBlocks     服务端连锁目标数上限
     * @param matchedTargetCount 已匹配目标数
     * @param netHandler         入包连接 identity（ctx.netHandler）
     */
    @Override
    public void handleClientChainConfigSync(
            int chainRadius, int chainMaxBlocks, int matchedTargetCount, INetHandler netHandler) {
        final ClientConnectionLifecycle.Token capturedToken =
                ClientConnectionLifecycle.captureForConnection(netHandler);
        // identity 不匹配或连接 inactive：intentional drop，非 dispatcher rejection
        if (capturedToken == null) {
            return;
        }
        final int receivedRadius = chainRadius;
        final int receivedMaxBlocks = chainMaxBlocks;
        final int receivedMatchedTargetCount = matchedTargetCount;
        boolean accepted = ClientChainConfigSyncDispatch.dispatch(
                receivedRadius,
                receivedMaxBlocks,
                receivedMatchedTargetCount,
                capturedToken,
                LIFECYCLE_GATE,
                new ClientChainConfigSyncDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        return ClientMainThreadDispatcher.tryRun(task);
                    }
                },
                new ClientChainConfigSyncDispatch.Publication() {
                    @Override
                    public void publish(int radius, int maxBlocks, int matchedCount) {
                        if (MyMod.chainStateService == null) {
                            return;
                        }
                        MyMod.chainStateService.getClientState().setServerChainRadius(radius);
                        MyMod.chainStateService.getClientState().setServerChainMaxBlocks(maxBlocks);
                        MyMod.chainStateService.getClientState().setServerMatchedTargetCount(matchedCount);
                    }
                });
        if (!accepted) {
            noteConfigSyncDispatchRejected();
        }
    }

    private static void noteConfigSyncDispatchRejected() {
        CONFIG_SYNC_REJECT_DIAG.note(System.nanoTime(), new RateLimitedRejectDiagnostics.BatchLogger() {
            @Override
            public void log(long batchCount) {
                MyMod.LOG.warn(
                        "[ChainConfigSync] Client dispatcher rejected {} packet(s); dropped without cross-lifecycle retry",
                        Long.valueOf(batchCount));
            }
        });
    }

    /**
     * 测试钩子：拒绝诊断聚合器。
     *
     * @return 配置同步拒绝诊断
     */
    static RateLimitedRejectDiagnostics configSyncRejectDiagnosticsForTests() {
        return CONFIG_SYNC_REJECT_DIAG;
    }

    /**
     * 处理 LootGames 扫雷预览响应。
     *
     * <p>Netty 线程按 netHandler 捕获 token；主线程在 world-active gate 内应用 preview。
     * 旧连接/旧世界包 no-op。</p>
     *
     * @param requestId  请求 id
     * @param origin     原点
     * @param targets    目标
     * @param netHandler 入包连接 identity
     */
    @Override
    public void handleClientLootGamesMinesweeperPreview(
            int requestId, ChainTarget origin, List<ChainTarget> targets, INetHandler netHandler) {
        final ClientConnectionLifecycle.Token capturedToken =
                ClientConnectionLifecycle.captureForConnection(netHandler);
        if (capturedToken == null || !capturedToken.isWorldActive()) {
            return;
        }
        final int capturedRequestId = requestId;
        final ChainTarget originSnapshot = origin;
        final List<ChainTarget> targetSnapshot =
                targets == null ? new ArrayList<ChainTarget>() : new ArrayList<ChainTarget>(targets);
        ClientMainThreadDispatcher.run(new Runnable() {
            @Override
            public void run() {
                ClientConnectionLifecycle.runIfWorldCurrentAndActive(capturedToken, new Runnable() {
                    @Override
                    public void run() {
                        if (chainPreviewController == null) {
                            return;
                        }
                        chainPreviewController.applyLootGamesMinesweeperPreview(
                                capturedRequestId, originSnapshot, targetSnapshot);
                    }
                });
            }
        });
    }

    /**
     * 阶段6：处理连锁阶段快照下发。
     *
     * <p>Netty 线程按 netHandler 捕获 token 与包数据，不直接改投影容器、不 publish 语义状态。
     * 主线程在 world-active gate 内再 publish 到 clientChainEventBus（守 I4）。</p>
     *
     * @param phaseOrdinal 目标态 ordinal
     * @param generation   转移后的新代际
     * @param serverTick   发布时服务端 tick（诊断）
     * @param netHandler   入包连接 identity
     */
    @Override
    public void handleClientChainPhaseSnapshot(
            int phaseOrdinal, int generation, long serverTick, INetHandler netHandler) {
        final ClientConnectionLifecycle.Token capturedToken =
                ClientConnectionLifecycle.captureForConnection(netHandler);
        if (capturedToken == null || !capturedToken.isWorldActive()) {
            return;
        }
        final int capturedPhaseOrdinal = phaseOrdinal;
        final int capturedGeneration = generation;
        final long capturedServerTick = serverTick;
        ClientMainThreadDispatcher.run(new Runnable() {
            @Override
            public void run() {
                ClientConnectionLifecycle.runIfWorldCurrentAndActive(capturedToken, new Runnable() {
                    @Override
                    public void run() {
                        if (MyMod.clientChainEventBus == null) {
                            return;
                        }
                        ChainPhase[] phases = ChainPhase.values();
                        ChainPhase toPhase = capturedPhaseOrdinal >= 0 && capturedPhaseOrdinal < phases.length
                                ? phases[capturedPhaseOrdinal]
                                : ChainPhase.IDLE;
                        // from 占位 IDLE：订阅者只消费 toPhase；gate 内短小 offer，禁阻塞
                        MyMod.clientChainEventBus.publish(new ChainPhaseChanged(
                                null,
                                capturedGeneration,
                                ChainPhase.IDLE,
                                toPhase,
                                capturedServerTick,
                                System.nanoTime()));
                    }
                });
            }
        });
    }
}
