package club.heiqi.qz_miner;

import java.util.List;

import club.heiqi.qz_miner.chain.selection.CuboidSelection;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.command.QzMinerCommand;
import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.config.ServerConfigHotApplyService;
import club.heiqi.qz_miner.config.ServerConfigMutationService;
import club.heiqi.qz_miner.network.PacketCuboidSelectionSync;
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

    /**
     * v2 配置同步入口；dedicated no-op，语义校验由客户端主线程 dispatcher 完成。
     */
    public void handleClientChainConfigSync(
            int chainRadius, int chainMaxBlocks, int matchedTargetCount,
            int protocolVersion, int tunnelDirectionCode, boolean rawValid, INetHandler netHandler) {
    }

    /**
     * Dedicated server no-op；客户端实现经 connection identity 和主线程 gate 发布确认。
     *
     * @param protocolVersion 协议版本原始值
     * @param requestedRevision 客户端提交对应的本地 epoch
     * @param authoritativeRevision 服务端当前接受规则的 revision
     * @param acceptedFlag 原始 boolean byte，语义校验在客户端主线程完成
     * @param groupCount 服务端确认的对象组数量
     * @param rawValid 是否完整捕获固定长度包
     * @param netHandler 入包连接 identity
     */
    public void handleClientObjectGroupConfigSync(
            int protocolVersion, long requestedRevision, long authoritativeRevision,
            int acceptedFlag, int groupCount, boolean rawValid, INetHandler netHandler) {
    }

    /**
     * 处理自动工具换位 round 结果（dedicated no-op）。
     *
     * @param protocolVersion 原始协议版本
     * @param clientNonce 客户端 nonce
     * @param serverRoundId 服务端 round id
     * @param resultCode 原始结果码
     * @param roundState 原始 round 状态
     * @param nextActionSequence 下一动作序号
     * @param serverTick 服务端 tick
     * @param rawValid 是否完整捕获固定长度包
     * @param netHandler 入包连接 identity
     */
    public void handleClientAutoToolSwapRoundResult(
            int protocolVersion, long clientNonce, long serverRoundId, int resultCode, int roundState,
            long nextActionSequence, long serverTick, boolean rawValid, INetHandler netHandler) {
    }

    /**
     * 处理自动工具换位动作结果（dedicated no-op）。
     *
     * @param protocolVersion 原始协议版本
     * @param serverRoundId 服务端 round id
     * @param actionSequence 动作序号
     * @param actionCode 原始动作码
     * @param resultCode 原始结果码
     * @param roundState 原始 round 状态
     * @param anchorSlot 锚定槽位
     * @param candidateSlot 候选槽位
     * @param nextActionSequence 下一动作序号
     * @param serverTick 服务端 tick
     * @param rawValid 是否完整捕获固定长度包
     * @param netHandler 入包连接 identity
     */
    public void handleClientAutoToolSwapActionResult(
            int protocolVersion, long serverRoundId, long actionSequence, int actionCode, int resultCode,
            int roundState, int anchorSlot, int candidateSlot, long nextActionSequence, long serverTick,
            boolean rawValid, INetHandler netHandler) {
    }

    /**
     * 处理自动工具换位 round 阶段快照（dedicated no-op）。
     *
     * @param protocolVersion 原始协议版本
     * @param serverRoundId 服务端 round id
     * @param phaseSequence 阶段序号
     * @param phaseOrdinal 阶段原始值
     * @param generation 阶段代际
     * @param serverTick 服务端 tick
     * @param rawValid 是否完整捕获固定长度包
     * @param netHandler 入包连接 identity
     */
    public void handleClientAutoToolSwapRoundPhase(
            int protocolVersion, long serverRoundId, long phaseSequence, int phaseOrdinal, int generation,
            long serverTick, boolean rawValid, INetHandler netHandler) {
    }

    /** 处理服务端确认的完整框选投影（dedicated no-op）。 */
    public void handleClientCuboidSelectionSync(
            int protocolVersion, long revision, int acceptedFlag, int reasonCode, int pointMask,
            int point1Dimension, int point1X, int point1Y, int point1Z,
            int point2Dimension, int point2X, int point2Y, int point2Z,
            boolean rawValid, INetHandler netHandler) {
    }

    /** 注册 operator-only 配置命令及本服务端 session 唯一 hot-apply 服务。 */
    public void serverStarting(FMLServerStartingEvent event) {
        MyMod.serverConfigHotApplyService = new ServerConfigHotApplyService(
                new ServerConfigHotApplyService.Callbacks() {
                    @Override
                    public void publishPolicy(CommittedSnapshot committed) {
                        if (MyMod.autoToolSwapServerBatchService != null) {
                            MyMod.autoToolSwapServerBatchService.publishPolicy(committed);
                        }
                    }

                    @Override
                    public void revalidateOnlineAccepted(CommittedSnapshot committed) {
                        revalidateOnlineAcceptedConfig();
                    }
                });
        event.registerServerCommand(new QzMinerCommand(
                ServerConfigMutationService.fromBootstrap(), MyMod.serverConfigHotApplyService));
    }

    private static void revalidateOnlineAcceptedConfig() {
        if (MyMod.chainStateService == null) return;
        for (ChainPlayerState state : MyMod.chainStateService.getPlayerStates()) {
            int radius = state.getRequestedChainRadius() > 0
                    ? Math.min(Config.chainRadius, state.getRequestedChainRadius()) : Config.chainRadius;
            int maxBlocks = state.resolveAcceptedChainMaxBlocks();
            state.setAcceptedChainConfig(radius, maxBlocks, state.getAcceptedTunnelDirectionSource());
            if (MyMod.chainConfigProjectionBridge != null) {
                MyMod.chainConfigProjectionBridge.sendAcceptedConfig(state.getPlayerUUID(), 0);
            }
            CuboidSelection invalidated = state.invalidateOversizedCuboidSelection();
            net.minecraft.entity.player.EntityPlayer player = MyMod.playerManager == null
                    ? null : MyMod.playerManager.getPlayer(state.getPlayerUUID());
            if (invalidated != null && player instanceof net.minecraft.entity.player.EntityPlayerMP
                    && MyMod.networkMain != null) {
                MyMod.networkMain.network.sendTo(
                        new PacketCuboidSelectionSync(invalidated, false, "selection-too-large"),
                        (net.minecraft.entity.player.EntityPlayerMP) player);
            }
        }
    }
}
