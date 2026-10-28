package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 下发玩家已接受的半径、数量、隧道方向与规划匹配数，支持 legacy 和 v2 帧。
 * Netty 捕获有界原始值与 handler，客户端主线程整包校验后经连接 gate 发布。
 */
public class PacketChainConfigSync implements IMessage {

    public static final int LEGACY_PAYLOAD_BYTES = 12;
    public static final int EXTENDED_PAYLOAD_BYTES = 20;
    public static final int PROTOCOL_VERSION = 2;
    public static final int LEGACY_PROTOCOL_VERSION = 0;

    /** 服务端为该玩家接受的连锁半径。 */
    public int chainRadius;
    /** 服务端为该玩家接受的目标数上限。 */
    public int chainMaxBlocks;
    /** 本次连锁已匹配目标数（PlanCompleted 时 = totalTargets；LOGIN 时 = 0）。 */
    public int matchedTargetCount;
    public int protocolVersion = PROTOCOL_VERSION;
    public int tunnelDirectionCode = TunnelDirectionSource.legacyDefault().wireCode();
    public boolean rawValid = true;

    /** 反射构造（Netty 反序列化需要）。 */
    public PacketChainConfigSync() {}

    /**
     * @param chainRadius        服务端连锁半径上限
     * @param chainMaxBlocks     服务端连锁目标数上限
     * @param matchedTargetCount 已匹配目标数
     */
    public PacketChainConfigSync(int chainRadius, int chainMaxBlocks, int matchedTargetCount) {
        this(chainRadius, chainMaxBlocks, matchedTargetCount, TunnelDirectionSource.legacyDefault());
    }

    public PacketChainConfigSync(int chainRadius, int chainMaxBlocks, int matchedTargetCount,
            TunnelDirectionSource tunnelDirectionSource) {
        this.chainRadius = chainRadius;
        this.chainMaxBlocks = chainMaxBlocks;
        this.matchedTargetCount = matchedTargetCount;
        this.tunnelDirectionCode = (tunnelDirectionSource == null
                ? TunnelDirectionSource.legacyDefault() : tunnelDirectionSource).wireCode();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int payloadBytes = buf.readableBytes();
        rawValid = payloadBytes == LEGACY_PAYLOAD_BYTES || payloadBytes == EXTENDED_PAYLOAD_BYTES;
        if (!rawValid) {
            buf.skipBytes(payloadBytes);
            return;
        }
        chainRadius = buf.readInt();
        chainMaxBlocks = buf.readInt();
        matchedTargetCount = buf.readInt();
        if (payloadBytes == LEGACY_PAYLOAD_BYTES) {
            protocolVersion = LEGACY_PROTOCOL_VERSION;
            tunnelDirectionCode = TunnelDirectionSource.legacyDefault().wireCode();
            return;
        }
        protocolVersion = buf.readInt();
        tunnelDirectionCode = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(chainRadius);
        buf.writeInt(chainMaxBlocks);
        buf.writeInt(matchedTargetCount);
        buf.writeInt(PROTOCOL_VERSION);
        buf.writeInt(tunnelDirectionCode);
    }

    /** Netty 仅捕获字段与 common INetHandler，侧代理承担客户端主线程校验和发布。 */
    public static class Handler implements IMessageHandler<PacketChainConfigSync, IMessage> {

        @Override
        public IMessage onMessage(PacketChainConfigSync message, MessageContext ctx) {
            final int chainRadius = message.chainRadius;
            final int chainMaxBlocks = message.chainMaxBlocks;
            final int matchedTargetCount = message.matchedTargetCount;
            final int protocolVersion = message.protocolVersion;
            final int tunnelDirectionCode = message.tunnelDirectionCode;
            final boolean rawValid = message.rawValid;
            MyMod.proxy.handleClientChainConfigSync(
                    chainRadius, chainMaxBlocks, matchedTargetCount,
                    protocolVersion, tunnelDirectionCode, rawValid, ctx.netHandler);
            return null;
        }
    }
}
