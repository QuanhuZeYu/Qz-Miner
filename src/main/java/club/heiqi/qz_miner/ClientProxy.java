package club.heiqi.qz_miner;

import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.chain.client.ChainPreviewController;
import club.heiqi.qz_miner.chain.client.ChainPreviewRenderer;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ClientChainEventBusDrainer;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.client.ClientMainThreadDispatcher;
import club.heiqi.qz_miner.client.ClientConnectionListener;
import club.heiqi.qz_miner.client.ClientConfigChangeListener;
import club.heiqi.qz_miner.client.HudOverlay;
import club.heiqi.qz_miner.client.KeyListener;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    public static ChainPreviewController chainPreviewController;
    public static ChainPreviewRenderer chainPreviewRenderer;

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // 守决策4：客户端独立事件总线，仅空跑 drain 骨架；不 publish 客户端事件、不持有独立状态机
        // 预览订阅留阶段6接入；bindMainThread 锁定客户端主线程（ClientProxy.init 在客户端主线程执行）
        MyMod.clientChainEventBus = new ChainEventBus();
        MyMod.clientChainEventBus.bindMainThread(Thread.currentThread());
        new ClientChainEventBusDrainer(MyMod.clientChainEventBus).bootstrap();
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
}
