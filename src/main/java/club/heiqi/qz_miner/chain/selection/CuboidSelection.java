package club.heiqi.qz_miner.chain.selection;

/** 服务端确认的不可变双点选区快照。 */
public final class CuboidSelection {

    private static final CuboidSelection EMPTY = new CuboidSelection(0L, null, null);

    private final long revision;
    private final Point point1;
    private final Point point2;

    private CuboidSelection(long revision, Point point1, Point point2) {
        this.revision = revision;
        this.point1 = point1;
        this.point2 = point2;
    }

    public static CuboidSelection empty() { return EMPTY; }

    /**
     * 尝试覆盖一个点。两点完整后必须同维度且整体不超过 accepted maxBlocks；拒绝时原快照不变。
     */
    public Update select(int pointIndex, int dimensionId, int x, int y, int z, int maxBlocks) {
        if (pointIndex != 1 && pointIndex != 2) {
            return Update.rejected(this, "invalid-point-index");
        }
        Point point = new Point(dimensionId, x, y, z);
        Point next1 = pointIndex == 1 ? point : point1;
        Point next2 = pointIndex == 2 ? point : point2;
        if (next1 != null && next2 != null) {
            if (next1.dimensionId != next2.dimensionId) {
                return Update.rejected(this, "dimension-mismatch");
            }
            if (!CuboidBounds.between(next1.dimensionId,
                    next1.x, next1.y, next1.z, next2.x, next2.y, next2.z).fitsWithin(maxBlocks)) {
                return Update.rejected(this, "selection-too-large");
            }
        }
        long nextRevision = revision == Long.MAX_VALUE ? Long.MAX_VALUE : revision + 1L;
        return Update.accepted(new CuboidSelection(nextRevision, next1, next2));
    }

    public long getRevision() { return revision; }
    public Point getPoint1() { return point1; }
    public Point getPoint2() { return point2; }
    public boolean isComplete() { return point1 != null && point2 != null; }

    /** @return 完整时的归一化边界，否则 null */
    public CuboidBounds bounds() {
        if (!isComplete() || point1.dimensionId != point2.dimensionId) return null;
        return CuboidBounds.between(point1.dimensionId,
                point1.x, point1.y, point1.z, point2.x, point2.y, point2.z);
    }

    /** Authority 因策略变化失效当前选择时，返回 revision 单调推进的空快照。 */
    public CuboidSelection clearForAuthority() {
        long nextRevision = revision == Long.MAX_VALUE ? Long.MAX_VALUE : revision + 1L;
        return new CuboidSelection(nextRevision, null, null);
    }

    /** 单个选择点的纯值。 */
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

    /** 一次服务端选点提交的原子结果。 */
    public static final class Update {
        private final boolean accepted;
        private final CuboidSelection selection;
        private final String reason;

        private Update(boolean accepted, CuboidSelection selection, String reason) {
            this.accepted = accepted;
            this.selection = selection;
            this.reason = reason;
        }

        private static Update accepted(CuboidSelection selection) {
            return new Update(true, selection, "accepted");
        }

        private static Update rejected(CuboidSelection selection, String reason) {
            return new Update(false, selection, reason);
        }

        public boolean isAccepted() { return accepted; }
        public CuboidSelection getSelection() { return selection; }
        public String getReason() { return reason; }
    }
}
