package club.heiqi.qz_miner;

import club.heiqi.qz_miner.chain.client.ChainPreviewController;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.client.HudOverlay;
import club.heiqi.qz_miner.client.KeyListener;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    public static ChainPreviewController chainPreviewController;

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        chainPreviewController = new ChainPreviewController();
        chainPreviewController.register();
        HudOverlay hudOverlay = new HudOverlay();
        hudOverlay.register();
        new KeyListener(hudOverlay).register();
    }

    @Override
    public void handleClientChainStateSync(boolean chainKeyPressed, boolean executing, ChainMode mode, ChainExecutionStatus executionStatus, int chainRadius, int chainMaxBlocks, int matchedTargetCount) {
        if (MyMod.chainStateService == null) {
            return;
        }

        MyMod.chainStateService.getClientState().setServerChainKeyPressed(chainKeyPressed);
        MyMod.chainStateService.getClientState().setServerExecuting(executing);
        MyMod.chainStateService.getClientState().setServerExecutionStatus(executionStatus);
        MyMod.chainStateService.getClientState().setSelectedMode(mode);
        MyMod.chainStateService.getClientState().setServerChainRadius(chainRadius);
        MyMod.chainStateService.getClientState().setServerChainMaxBlocks(chainMaxBlocks);
        MyMod.chainStateService.getClientState().setServerMatchedTargetCount(matchedTargetCount);
    }
}
