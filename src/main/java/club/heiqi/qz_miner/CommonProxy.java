package club.heiqi.qz_miner;

import java.util.List;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        MyMod.CONFIG.init(event.getSuggestedConfigurationFile());

        MyMod.LOG.info(Config.greeting);
        MyMod.LOG.info("I am " + MyMod.MOD_NAME + " at version " + Tags.VERSION);
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {}

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {}

    /**
     * 处理客户端连锁状态同步。
     */
    public void handleClientChainStateSync(boolean chainKeyPressed, boolean executing, ChainMode mode, ChainSubMode subMode, ChainExecutionStatus executionStatus, int chainRadius, int chainMaxBlocks, int matchedTargetCount) {
    }

    /**
     * 处理客户端扫雷预览结果。
     */
    public void handleClientLootGamesMinesweeperPreview(int requestId, ChainTarget origin, List<ChainTarget> targets) {
    }

    /**
     * 阶段6：处理客户端连锁阶段快照下发（空实现，服务端不处理）。
     *
     * <p>客户端 ClientProxy 覆写此方法，把快照 publish 到 clientChainEventBus
     * （守 I4：跨线程 publish 安全，主线程 drain 收口）。</p>
     *
     * @param phaseOrdinal 目标态 ordinal
     * @param generation   转移后的新代际
     * @param serverTick   发布时服务端 tick（诊断）
     */
    public void handleClientChainPhaseSnapshot(int phaseOrdinal, int generation, long serverTick) {
    }

    /**
     * 阶段8 块3 F3-a：处理客户端连锁配置同步下发（空实现，服务端不处理）。
     *
     * <p>客户端 ClientProxy 覆写此方法，写 ChainClientState 的 serverChainRadius/
     * serverChainMaxBlocks/serverMatchedTargetCount 三字段（守 I4：Netty 线程只写 volatile 字段）。</p>
     *
     * @param chainRadius        服务端连锁半径上限
     * @param chainMaxBlocks     服务端连锁目标数上限
     * @param matchedTargetCount 已匹配目标数
     */
    public void handleClientChainConfigSync(int chainRadius, int chainMaxBlocks, int matchedTargetCount) {
    }

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {}
}
