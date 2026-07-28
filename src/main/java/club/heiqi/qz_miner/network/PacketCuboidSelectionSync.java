package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.selection.CuboidSelection;
import club.heiqi.qz_miner.chain.selection.CuboidSelection.Point;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 服务端向当前连接确认完整双点选区快照。 */
public final class PacketCuboidSelectionSync implements IMessage {

    public static final int PROTOCOL_VERSION = 1;
    public static final int PAYLOAD_BYTES = 56;
    public static final int REASON_ACCEPTED = 0;
    public static final int REASON_SELECTION_TOO_LARGE = 1;
    public static final int REASON_REJECTED = 2;

    public int protocolVersion = PROTOCOL_VERSION;
    public long revision;
    public int acceptedFlag;
    public int reasonCode;
    public int pointMask;
    public int point1Dimension;
    public int point1X;
    public int point1Y;
    public int point1Z;
    public int point2Dimension;
    public int point2X;
    public int point2Y;
    public int point2Z;
    public boolean rawValid = true;

    public PacketCuboidSelectionSync() {}

    public PacketCuboidSelectionSync(CuboidSelection selection, boolean accepted, String reason) {
        CuboidSelection snapshot = selection == null ? CuboidSelection.empty() : selection;
        revision = snapshot.getRevision();
        acceptedFlag = accepted ? 1 : 0;
        reasonCode = accepted ? REASON_ACCEPTED
                : "selection-too-large".equals(reason) ? REASON_SELECTION_TOO_LARGE : REASON_REJECTED;
        Point point1 = snapshot.getPoint1();
        Point point2 = snapshot.getPoint2();
        if (point1 != null) {
            pointMask |= 1;
            point1Dimension = point1.getDimensionId();
            point1X = point1.getX();
            point1Y = point1.getY();
            point1Z = point1.getZ();
        }
        if (point2 != null) {
            pointMask |= 2;
            point2Dimension = point2.getDimensionId();
            point2X = point2.getX();
            point2Y = point2.getY();
            point2Z = point2.getZ();
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        rawValid = buf.readableBytes() == PAYLOAD_BYTES;
        if (!rawValid) {
            buf.skipBytes(buf.readableBytes());
            return;
        }
        protocolVersion = buf.readInt();
        revision = buf.readLong();
        acceptedFlag = buf.readInt();
        reasonCode = buf.readInt();
        pointMask = buf.readInt();
        point1Dimension = buf.readInt();
        point1X = buf.readInt();
        point1Y = buf.readInt();
        point1Z = buf.readInt();
        point2Dimension = buf.readInt();
        point2X = buf.readInt();
        point2Y = buf.readInt();
        point2Z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(PROTOCOL_VERSION);
        buf.writeLong(revision);
        buf.writeInt(acceptedFlag);
        buf.writeInt(reasonCode);
        buf.writeInt(pointMask);
        buf.writeInt(point1Dimension);
        buf.writeInt(point1X);
        buf.writeInt(point1Y);
        buf.writeInt(point1Z);
        buf.writeInt(point2Dimension);
        buf.writeInt(point2X);
        buf.writeInt(point2Y);
        buf.writeInt(point2Z);
    }

    /** Netty 线程只转交 primitive 与 connection identity。 */
    public static final class Handler implements IMessageHandler<PacketCuboidSelectionSync, IMessage> {
        @Override
        public IMessage onMessage(PacketCuboidSelectionSync message, MessageContext context) {
            MyMod.proxy.handleClientCuboidSelectionSync(
                    message.protocolVersion, message.revision, message.acceptedFlag, message.reasonCode,
                    message.pointMask,
                    message.point1Dimension, message.point1X, message.point1Y, message.point1Z,
                    message.point2Dimension, message.point2X, message.point2Y, message.point2Z,
                    message.rawValid, context.netHandler);
            return null;
        }
    }
}
