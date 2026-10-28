package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 下发服务端已提交的阶段、代际与诊断 tick。
 * Netty 捕获原始字段和真实 handler，客户端主线程经 world-active gate 后发布阶段投影事件。
 */
public class PacketChainPhaseSnapshot implements IMessage {

    /** 目标态 ordinal（{@code ChainPhase.values()[ordinal]}）。 */
    public int phaseOrdinal;
    /** 转移后的新代际。 */
    public int generation;
    /** 发布时服务端 tick（诊断字段）。 */
    public long serverTick;

    /** 反射构造（Netty 反序列化需要）。 */
    public PacketChainPhaseSnapshot() {}

    /**
     * @param phaseOrdinal 目标态 ordinal
     * @param generation   转移后的新代际
     * @param serverTick   发布时服务端 tick（诊断）
     */
    public PacketChainPhaseSnapshot(int phaseOrdinal, int generation, long serverTick) {
        this.phaseOrdinal = phaseOrdinal;
        this.generation = generation;
        this.serverTick = serverTick;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        phaseOrdinal = buf.readInt();
        generation = buf.readInt();
        serverTick = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(phaseOrdinal);
        buf.writeInt(generation);
        buf.writeLong(serverTick);
    }

    /** Netty 捕获快照与 handler，转交 common proxy，由客户端主线程校验并发布。 */
    public static class Handler implements IMessageHandler<PacketChainPhaseSnapshot, IMessage> {

        @Override
        public IMessage onMessage(PacketChainPhaseSnapshot message, MessageContext ctx) {
            MyMod.LOG.debug("[ChainPhaseSnapshot] Received snapshot phaseOrdinal={} gen={} serverTick={}",
                    Integer.valueOf(message.phaseOrdinal),
                    Integer.valueOf(message.generation),
                    Long.valueOf(message.serverTick));
            MyMod.proxy.handleClientChainPhaseSnapshot(
                    message.phaseOrdinal, message.generation, message.serverTick, ctx.netHandler);
            return null;
        }
    }
}
