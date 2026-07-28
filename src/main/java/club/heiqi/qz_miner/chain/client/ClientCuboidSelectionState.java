package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.chain.selection.CuboidBounds;
import club.heiqi.qz_miner.network.PacketCuboidSelectionSync;

/** 客户端仅由服务端 ACK 发布的选区投影。 */
public final class ClientCuboidSelectionState {

    private long revision = -1L;
    private Point point1;
    private Point point2;

    /** 校验并发布完整 ACK；同 revision 的拒绝 ACK 可呈现但不改点。 */
    public synchronized boolean publish(int protocolVersion, long receivedRevision,
            int acceptedFlag, int pointMask,
            int point1Dimension, int point1X, int point1Y, int point1Z,
            int point2Dimension, int point2X, int point2Y, int point2Z,
            boolean rawValid) {
        if (!rawValid || protocolVersion != PacketCuboidSelectionSync.PROTOCOL_VERSION
                || receivedRevision < 0L || (acceptedFlag != 0 && acceptedFlag != 1)
                || pointMask < 0 || pointMask > 3 || receivedRevision < revision) {
            return false;
        }
        if (acceptedFlag == 0 && receivedRevision == revision) return true;
        Point next1 = (pointMask & 1) == 0 ? null
                : new Point(point1Dimension, point1X, point1Y, point1Z);
        Point next2 = (pointMask & 2) == 0 ? null
                : new Point(point2Dimension, point2X, point2Y, point2Z);
        if (next1 != null && next2 != null && next1.dimensionId != next2.dimensionId) return false;
        revision = receivedRevision;
        point1 = next1;
        point2 = next2;
        return true;
    }

    public synchronized void clear() {
        revision = -1L;
        point1 = null;
        point2 = null;
    }

    public synchronized Point getPoint1() { return point1; }
    public synchronized Point getPoint2() { return point2; }

    /** @return 两点完整时的 immutable bounds，否则 null */
    public synchronized CuboidBounds bounds() {
        if (point1 == null || point2 == null || point1.dimensionId != point2.dimensionId) return null;
        return CuboidBounds.between(point1.dimensionId,
                point1.x, point1.y, point1.z, point2.x, point2.y, point2.z);
    }

    /** 客户端投影点纯值。 */
    public static final class Point {
        private final int dimensionId;
        private final int x;
        private final int y;
        private final int z;

        private Point(int dimensionId, int x, int y, int z) {
            this.dimensionId = dimensionId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int getDimensionId() { return dimensionId; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
    }
}
