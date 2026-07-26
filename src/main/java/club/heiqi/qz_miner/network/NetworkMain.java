package club.heiqi.qz_miner.network;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * 网络通信主类。
 *
 * 管理所有网络包的注册和发送。
 */
public final class NetworkMain {

    private static final int DISCRIMINATOR_KEY_STATE = 0;
    private static final int DISCRIMINATOR_CHAIN_MODE_SWITCH = 1;
    private static final int DISCRIMINATOR_CHAIN_SUB_MODE_SWITCH = 2;
    private static final int DISCRIMINATOR_CHAIN_CONFIG_REQUEST = 3;
    private static final int DISCRIMINATOR_OBJECT_GROUP_CONFIG_REQUEST = 4;
    private static final int DISCRIMINATOR_LOOTGAMES_PREVIEW_REQUEST = 5;
    private static final int DISCRIMINATOR_LOOTGAMES_PREVIEW_RESPONSE = 6;
    private static final int DISCRIMINATOR_CHAIN_PHASE_SNAPSHOT = 7;
    private static final int DISCRIMINATOR_CHAIN_CONFIG_SYNC = 8;
    private static final int DISCRIMINATOR_OBJECT_GROUP_CONFIG_SYNC = 9;
    private static final int DISCRIMINATOR_AUTO_TOOL_ROUND_START = 10;
    private static final int DISCRIMINATOR_AUTO_TOOL_INTENT = 11;
    private static final int DISCRIMINATOR_AUTO_TOOL_ROUND_RESULT = 12;
    private static final int DISCRIMINATOR_AUTO_TOOL_ACTION_RESULT = 13;
    private static final int DISCRIMINATOR_AUTO_TOOL_ROUND_PHASE = 14;
    private static final int DISCRIMINATOR_AUTO_TOOL_TAKEOVER_REQUEST = 15;

    /**
     * SimpleNetworkWrapper 实例，用于发送和接收网络包。
     */
    public final SimpleNetworkWrapper network = NetworkRegistry.INSTANCE.newSimpleChannel("qz_miner");

    /**
     * 注册所有网络包。
     */
    public void register() {
        network.registerMessage(
                PacketKeyState.KeyStatePacketHandler.class,
                PacketKeyState.class,
                DISCRIMINATOR_KEY_STATE,
                Side.SERVER);
        network.registerMessage(
                PacketChainModeSwitch.Handler.class,
                PacketChainModeSwitch.class,
                DISCRIMINATOR_CHAIN_MODE_SWITCH,
                Side.SERVER);
        network.registerMessage(
                PacketChainSubModeSwitch.Handler.class,
                PacketChainSubModeSwitch.class,
                DISCRIMINATOR_CHAIN_SUB_MODE_SWITCH,
                Side.SERVER);
        network.registerMessage(
                PacketChainConfigRequest.Handler.class,
                PacketChainConfigRequest.class,
                DISCRIMINATOR_CHAIN_CONFIG_REQUEST,
                Side.SERVER);
        network.registerMessage(
                PacketObjectGroupConfigRequest.Handler.class,
                PacketObjectGroupConfigRequest.class,
                DISCRIMINATOR_OBJECT_GROUP_CONFIG_REQUEST,
                Side.SERVER);
        network.registerMessage(
                PacketLootGamesMinesweeperPreviewRequest.Handler.class,
                PacketLootGamesMinesweeperPreviewRequest.class,
                DISCRIMINATOR_LOOTGAMES_PREVIEW_REQUEST,
                Side.SERVER);
        network.registerMessage(
                PacketLootGamesMinesweeperPreviewResponse.Handler.class,
                PacketLootGamesMinesweeperPreviewResponse.class,
                DISCRIMINATOR_LOOTGAMES_PREVIEW_RESPONSE,
                Side.CLIENT);
        // 阶段6：连锁阶段快照下发（服务端 ChainStateProjectionBridge → 客户端投影容器）
        network.registerMessage(
                PacketChainPhaseSnapshot.Handler.class,
                PacketChainPhaseSnapshot.class,
                DISCRIMINATOR_CHAIN_PHASE_SNAPSHOT,
                Side.CLIENT);
        // 阶段8 块3 F3-a：连锁配置同步下发（服务端 ChainConfigProjectionBridge → 客户端 ChainClientState 三字段）
        network.registerMessage(
                PacketChainConfigSync.Handler.class,
                PacketChainConfigSync.class,
                DISCRIMINATOR_CHAIN_CONFIG_SYNC,
                Side.CLIENT);
        network.registerMessage(
                PacketObjectGroupConfigSync.Handler.class,
                PacketObjectGroupConfigSync.class,
                DISCRIMINATOR_OBJECT_GROUP_CONFIG_SYNC,
                Side.CLIENT);
        network.registerMessage(
                PacketAutoToolSwapRoundStart.Handler.class,
                PacketAutoToolSwapRoundStart.class,
                DISCRIMINATOR_AUTO_TOOL_ROUND_START,
                Side.SERVER);
        network.registerMessage(
                PacketAutoToolSwapIntent.Handler.class,
                PacketAutoToolSwapIntent.class,
                DISCRIMINATOR_AUTO_TOOL_INTENT,
                Side.SERVER);
        network.registerMessage(
                PacketAutoToolSwapRoundResult.Handler.class,
                PacketAutoToolSwapRoundResult.class,
                DISCRIMINATOR_AUTO_TOOL_ROUND_RESULT,
                Side.CLIENT);
        network.registerMessage(
                PacketAutoToolSwapActionResult.Handler.class,
                PacketAutoToolSwapActionResult.class,
                DISCRIMINATOR_AUTO_TOOL_ACTION_RESULT,
                Side.CLIENT);
        network.registerMessage(
                PacketAutoToolSwapRoundPhase.Handler.class,
                PacketAutoToolSwapRoundPhase.class,
                DISCRIMINATOR_AUTO_TOOL_ROUND_PHASE,
                Side.CLIENT);
        network.registerMessage(
                PacketAutoToolSwapTakeoverRequest.Handler.class,
                PacketAutoToolSwapTakeoverRequest.class,
                DISCRIMINATOR_AUTO_TOOL_TAKEOVER_REQUEST,
                Side.CLIENT);
    }
}
