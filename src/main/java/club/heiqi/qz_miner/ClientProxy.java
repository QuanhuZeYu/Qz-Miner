package club.heiqi.qz_miner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
import club.heiqi.qz_miner.client.ClientConnectionListener;
import club.heiqi.qz_miner.client.ClientMainThreadDispatcher;
import club.heiqi.qz_miner.client.HudOverlay;
import club.heiqi.qz_miner.client.KeyListener;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    private static final long CONFIG_SYNC_REJECT_DIAG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(10L);
    private static final AtomicBoolean CONFIG_SYNC_REJECT_DIAG_EMITTED = new AtomicBoolean();
    private static final AtomicLong CONFIG_SYNC_REJECT_COUNT = new AtomicLong();
    private static final AtomicLong CONFIG_SYNC_REJECT_LAST_DIAG_NS = new AtomicLong();

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
     * <p>本方法由 {@code PacketChainConfigSync.Handler} 在 Netty 线程调用。先捕获三个 final int，
     * 再经 {@link ClientMainThreadDispatcher} 投递后写 ChainClientState。
     * dispatcher 拒绝时做限频诊断，不跨 lifecycle 重试。</p>
     *
     * <p>守 I4：volatile 只提供可见性，不授予 Netty 线程客户端状态写主权。</p>
     *
     * @param chainRadius        服务端连锁半径上限
     * @param chainMaxBlocks     服务端连锁目标数上限
     * @param matchedTargetCount 已匹配目标数
     */
    @Override
    public void handleClientChainConfigSync(int chainRadius, int chainMaxBlocks, int matchedTargetCount) {
        final int receivedRadius = chainRadius;
        final int receivedMaxBlocks = chainMaxBlocks;
        final int receivedMatchedTargetCount = matchedTargetCount;
        boolean accepted = ClientChainConfigSyncDispatch.dispatch(
                receivedRadius,
                receivedMaxBlocks,
                receivedMatchedTargetCount,
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
        long count = CONFIG_SYNC_REJECT_COUNT.incrementAndGet();
        long now = System.nanoTime();
        long previous = CONFIG_SYNC_REJECT_LAST_DIAG_NS.get();
        boolean first = CONFIG_SYNC_REJECT_DIAG_EMITTED.compareAndSet(false, true);
        if (!first && previous != 0L && now - previous < CONFIG_SYNC_REJECT_DIAG_INTERVAL_NS) {
            return;
        }
        if (!CONFIG_SYNC_REJECT_LAST_DIAG_NS.compareAndSet(previous, now) && !first) {
            return;
        }
        MyMod.LOG.warn(
                "[ChainConfigSync] Client dispatcher rejected {} packet(s); dropped without cross-lifecycle retry",
                Long.valueOf(count));
        CONFIG_SYNC_REJECT_COUNT.addAndGet(-count);
    }

    @Override
    public void handleClientLootGamesMinesweeperPreview(int requestId, ChainTarget origin, List<ChainTarget> targets) {
        final List<ChainTarget> targetSnapshot = targets == null ? new ArrayList<ChainTarget>() : new ArrayList<ChainTarget>(targets);
        ClientMainThreadDispatcher.run(() -> {
            if (chainPreviewController == null) {
                return;
            }

            chainPreviewController.applyLootGamesMinesweeperPreview(requestId, origin, targetSnapshot);
        });
    }

    /**
     * 阶段6：处理连锁阶段快照下发。
     *
     * <p>本方法由 {@code PacketChainPhaseSnapshot.Handler} 在 Netty 线程调用。<b>不直接改投影容器</b>，
     * 组装 {@link ChainPhaseChanged} 投影事件 publish 到 clientChainEventBus，
     * 靠 ClientTickEvent.START drain 收口客户端主线程（守 I4：跨线程 publish 安全，主线程 drain 收口）。</p>
     *
     * <p>from 字段从当前 projection 读取（update 前的旧态，纯诊断用途；null 时填 IDLE 占位）。</p>
     *
     * @param phaseOrdinal 目标态 ordinal
     * @param generation   转移后的新代际
     * @param serverTick   发布时服务端 tick（诊断）
     */
    @Override
    public void handleClientChainPhaseSnapshot(int phaseOrdinal, int generation, long serverTick) {
        ChainPhase[] phases = ChainPhase.values();
        ChainPhase toPhase = phaseOrdinal >= 0 && phaseOrdinal < phases.length
                ? phases[phaseOrdinal]
                : ChainPhase.IDLE;
        // P2-1 收口：from 字段在客户端是死代码（订阅者 ClientPhaseProjectionSubscriber 只消费 toPhase），
        // 占位 IDLE 避免在 Netty 线程读 projection 容器（守 I4 精神：Netty 线程不碰容器）。
        // 服务端 ChainPhaseChanged 的 from 有诊断价值（事件流即结构化日志），客户端投影事件不消费 from。
        // 守 I4：跨线程 publish 安全（ChainEventBus.publish 仅 offer），主线程 drain 收口
        MyMod.clientChainEventBus.publish(new ChainPhaseChanged(
                null, generation, ChainPhase.IDLE, toPhase, serverTick, System.nanoTime()));
    }
}
