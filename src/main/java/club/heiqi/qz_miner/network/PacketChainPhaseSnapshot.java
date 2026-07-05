package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 连锁阶段快照下发包（阶段6 A1：服务端→客户端投影主线）。
 *
 * <p>服务端 {@code ChainStateProjectionBridge} 订阅 {@code ChainPhaseChanged} 后，
 * 通过本包下发"状态机已转移"的 to phase + generation + serverTick 给客户端。
 * 客户端 Netty 线程收到后，经 {@code CommonProxy.handleClientChainPhaseSnapshot} 收口到
 * {@code clientChainEventBus}（守 I4：跨线程 publish 安全，主线程 drain 收口）。</p>
 *
 * <p>守 NORTH_STAR 不变量：</p>
 * <ul>
 *   <li><b>I1</b>：本包是只读快照下发，不要求客户端切态；客户端投影容器可见但不夺权（P0-1=A 决议）。</li>
 *   <li><b>I4</b>：Handler 在 Netty 线程只 publish 到 clientChainEventBus，不直接改投影容器。</li>
 *   <li><b>I10</b>：客户端投影容器与状态机物理隔离，客户端没有状态机实例，投影只可见不可切态。</li>
 * </ul>
 *
 * <p>影子并行边界（阶段6-7 共存）：本包与旧 {@link PacketChainStateSync} 并行下发，
 * 旧包八字段同步（HUD/预览锁定权威仍读旧链路态）保留到阶段8 切换。</p>
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

    /**
     * Netty 线程 Handler：调 proxy.handleClientChainPhaseSnapshot（仅 publish 到 clientChainEventBus）。
     *
     * <p>守 I4：不在 Netty 线程改投影容器，靠 ClientTickEvent.START drain 收口主线程。</p>
     */
    public static class Handler implements IMessageHandler<PacketChainPhaseSnapshot, IMessage> {

        @Override
        public IMessage onMessage(PacketChainPhaseSnapshot message, MessageContext ctx) {
            MyMod.LOG.debug("[ChainPhaseSnapshot] Received snapshot phaseOrdinal={} gen={} serverTick={}",
                    Integer.valueOf(message.phaseOrdinal),
                    Integer.valueOf(message.generation),
                    Long.valueOf(message.serverTick));
            MyMod.proxy.handleClientChainPhaseSnapshot(message.phaseOrdinal, message.generation, message.serverTick);
            return null;
        }
    }
}
