package club.heiqi.qz_miner;

import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.chain.client.ChainPreviewController;
import club.heiqi.qz_miner.chain.client.ChainPreviewRenderer;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjectionSubscriber;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ClientChainEventBusDrainer;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.client.ClientMainThreadDispatcher;
import club.heiqi.qz_miner.client.ClientConnectionListener;
import club.heiqi.qz_miner.client.ClientConfigChangeListener;
import club.heiqi.qz_miner.client.HudOverlay;
import club.heiqi.qz_miner.client.KeyListener;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

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

    @Override
    public void handleClientChainStateSync(boolean chainKeyPressed, boolean executing, ChainMode mode, ChainSubMode subMode, ChainExecutionStatus executionStatus, int chainRadius, int chainMaxBlocks, int matchedTargetCount) {
        ClientMainThreadDispatcher.run(() -> {
            if (MyMod.chainStateService == null) {
                return;
            }

            MyMod.chainStateService.getClientState().setServerChainKeyPressed(chainKeyPressed);
            MyMod.chainStateService.getClientState().setServerExecuting(executing);
            MyMod.chainStateService.getClientState().setServerExecutionStatus(executionStatus);
            MyMod.chainStateService.getClientState().setSelectedMode(mode);
            MyMod.chainStateService.getClientState().setSelectedSubMode(subMode);
            MyMod.chainStateService.getClientState().setServerChainRadius(chainRadius);
            MyMod.chainStateService.getClientState().setServerChainMaxBlocks(chainMaxBlocks);
            MyMod.chainStateService.getClientState().setServerMatchedTargetCount(matchedTargetCount);
        });
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
