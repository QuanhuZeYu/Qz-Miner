package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 客户端向服务端提交建议连锁参数。
 *
 * <p>Handler 只捕获原始 int 与端点身份，经 {@link ServerChainConfigRequestDispatch}
 * 进入 keyed latest-wins 泳道；不直接每包入普通 FIFO。</p>
 */
public class PacketChainConfigRequest implements IMessage {

    public static final int LEGACY_PAYLOAD_BYTES = 8;
    public static final int EXTENDED_PAYLOAD_BYTES = 16;
    public static final int PROTOCOL_VERSION = 2;
    public static final int LEGACY_PROTOCOL_VERSION = 0;

    public int requestedChainRadius;
    public int requestedChainMaxBlocks;
    public int protocolVersion = PROTOCOL_VERSION;
    public int tunnelDirectionCode = TunnelDirectionSource.legacyDefault().wireCode();
    public boolean rawValid = true;

    public PacketChainConfigRequest() {}

    public PacketChainConfigRequest(int requestedChainRadius, int requestedChainMaxBlocks) {
        this(requestedChainRadius, requestedChainMaxBlocks, TunnelDirectionSource.legacyDefault());
    }

    public PacketChainConfigRequest(int requestedChainRadius, int requestedChainMaxBlocks,
            TunnelDirectionSource tunnelDirectionSource) {
        this.requestedChainRadius = requestedChainRadius;
        this.requestedChainMaxBlocks = requestedChainMaxBlocks;
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
        requestedChainRadius = buf.readInt();
        requestedChainMaxBlocks = buf.readInt();
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
        buf.writeInt(requestedChainRadius);
        buf.writeInt(requestedChainMaxBlocks);
        buf.writeInt(PROTOCOL_VERSION);
        buf.writeInt(tunnelDirectionCode);
    }

    /**
     * 服务端记录客户端建议参数。
     */
    public static class Handler implements IMessageHandler<PacketChainConfigRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketChainConfigRequest message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            final int requestedChainRadius = message.requestedChainRadius;
            final int requestedChainMaxBlocks = message.requestedChainMaxBlocks;
            final int protocolVersion = message.protocolVersion;
            final int tunnelDirectionCode = message.tunnelDirectionCode;
            final boolean rawValid = message.rawValid;
            // 守 I4：只捕获原始数据与端点身份；校验/写入在 keyed lane 的服务端主线程消费中完成
            ServerChainConfigRequestDispatch.submit(player, requestedChainRadius, requestedChainMaxBlocks,
                    protocolVersion, tunnelDirectionCode, rawValid);
            return null;
        }
    }
}
