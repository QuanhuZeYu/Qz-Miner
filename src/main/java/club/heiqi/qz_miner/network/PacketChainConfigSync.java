package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 连锁配置同步包（阶段8 块3 F3-a：服务端→客户端 config 主线）。
 *
 * <p>服务端 {@code ChainConfigProjectionBridge} 在两类时机下发本包：</p>
 * <ul>
 *   <li>{@code PlanCompleted}：规划完成时下发 matchedCount（= totalTargets）+ radius/maxBlocks（取 {@link club.heiqi.qz_miner.Config}）。</li>
 *   <li>玩家 LOGIN：进服时下发基础 config（matchedCount=0），确保 HUD 初始有值。</li>
 * </ul>
 *
 * <p>客户端 Netty 线程收到后，经 {@code CommonProxy.handleClientChainConfigSync} 收口写入
 * {@code ChainClientState} 的 {@code serverChainRadius}/{@code serverChainMaxBlocks}/
 * {@code serverMatchedTargetCount} 三字段（这三字段随旧八字段链删除后由本包接替写入）。</p>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I1</b>：本包是只读配置下发，不要求客户端切态、不碰世界。</li>
 *   <li><b>I4</b>：Handler 在 Netty 线程只写 volatile 字段（简单 int 字段，Netty 线程写 + 主线程读 volatile 可见），
 *       不直接碰渲染层。</li>
 * </ul>
 */
public class PacketChainConfigSync implements IMessage {

    /** 服务端全局连锁半径上限（取 {@link club.heiqi.qz_miner.Config#chainRadius}）。 */
    public int chainRadius;
    /** 服务端全局连锁目标数上限（取 {@link club.heiqi.qz_miner.Config#chainMaxBlocks}）。 */
    public int chainMaxBlocks;
    /** 本次连锁已匹配目标数（PlanCompleted 时 = totalTargets；LOGIN 时 = 0）。 */
    public int matchedTargetCount;

    /** 反射构造（Netty 反序列化需要）。 */
    public PacketChainConfigSync() {}

    /**
     * @param chainRadius        服务端连锁半径上限
     * @param chainMaxBlocks     服务端连锁目标数上限
     * @param matchedTargetCount 已匹配目标数
     */
    public PacketChainConfigSync(int chainRadius, int chainMaxBlocks, int matchedTargetCount) {
        this.chainRadius = chainRadius;
        this.chainMaxBlocks = chainMaxBlocks;
        this.matchedTargetCount = matchedTargetCount;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        chainRadius = buf.readInt();
        chainMaxBlocks = buf.readInt();
        matchedTargetCount = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(chainRadius);
        buf.writeInt(chainMaxBlocks);
        buf.writeInt(matchedTargetCount);
    }

    /**
     * Netty 线程 Handler：调 proxy.handleClientChainConfigSync 收口到 ChainClientState 三字段。
     *
     * <p>守 I4：Netty 线程只写 volatile int 字段，不直接调渲染层。</p>
     */
    public static class Handler implements IMessageHandler<PacketChainConfigSync, IMessage> {

        @Override
        public IMessage onMessage(PacketChainConfigSync message, MessageContext ctx) {
            int chainRadius = message.chainRadius > 0 ? message.chainRadius : club.heiqi.qz_miner.Config.chainRadius;
            int chainMaxBlocks = message.chainMaxBlocks > 0 ? message.chainMaxBlocks : club.heiqi.qz_miner.Config.chainMaxBlocks;
            int matchedTargetCount = Math.max(0, message.matchedTargetCount);
            MyMod.LOG.debug("[ChainConfigSync] Received chainRadius={} chainMaxBlocks={} matchedTargetCount={}",
                    Integer.valueOf(chainRadius), Integer.valueOf(chainMaxBlocks), Integer.valueOf(matchedTargetCount));
            MyMod.proxy.handleClientChainConfigSync(chainRadius, chainMaxBlocks, matchedTargetCount);
            return null;
        }
    }
}
