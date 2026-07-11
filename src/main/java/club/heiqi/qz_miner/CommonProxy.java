package club.heiqi.qz_miner;

import java.util.List;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import net.minecraft.network.INetHandler;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        // YAML 权威在 config/qz_miner.yaml；旧 suggested cfg 仅作一次性导入源
        MyMod.CONFIG.init(event.getModConfigurationDirectory(), event.getSuggestedConfigurationFile());

        MyMod.LOG.info(Config.greeting);
        MyMod.LOG.info("I am " + MyMod.MOD_NAME + " at version " + Tags.VERSION);
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {}

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {}

    /**
     * 处理客户端扫雷预览结果（dedicated no-op）。
     *
     * <p>签名仅 common 类型（含 {@link INetHandler}），禁止 client-only 类型进入描述符。</p>
     *
     * @param requestId  请求 id
     * @param origin     原点
     * @param targets    目标列表
     * @param netHandler 入包连接 identity（与 FMLNetworkEvent.handler 同实例）
     */
    public void handleClientLootGamesMinesweeperPreview(
            int requestId, ChainTarget origin, List<ChainTarget> targets, INetHandler netHandler) {
    }

    /**
     * 阶段6：处理客户端连锁阶段快照下发（dedicated no-op）。
     *
     * @param phaseOrdinal 目标态 ordinal
     * @param generation   转移后的新代际
     * @param serverTick   发布时服务端 tick（诊断）
     * @param netHandler   入包连接 identity
     */
    public void handleClientChainPhaseSnapshot(
            int phaseOrdinal, int generation, long serverTick, INetHandler netHandler) {
    }

    /**
     * 阶段8 块3 F3-a：处理客户端连锁配置同步下发（dedicated no-op）。
     *
     * <p>服务端保持 no-op。客户端 ClientProxy 覆写后必须先经 ClientMainThreadDispatcher，
     * 按 connection identity capture token，再写 ChainClientState 三字段。</p>
     *
     * @param chainRadius        服务端连锁半径上限
     * @param chainMaxBlocks     服务端连锁目标数上限
     * @param matchedTargetCount 已匹配目标数
     * @param netHandler         入包连接 identity
     */
    public void handleClientChainConfigSync(
            int chainRadius, int chainMaxBlocks, int matchedTargetCount, INetHandler netHandler) {
    }

    /** Dedicated server no-op；客户端实现经 connection identity 和主线程 gate 发布确认。 */
    public void handleClientObjectGroupConfigSync(
            int protocolVersion, long revision, boolean accepted, int groupCount, INetHandler netHandler) {
    }

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {}
}
