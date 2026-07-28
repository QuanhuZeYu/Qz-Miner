package club.heiqi.qz_miner.chain.selection;

/** 两个方块坐标形成的不可变 inclusive 立方体边界。 */
public final class CuboidBounds {

    private final int dimensionId;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    /** 创建并归一化两个同维度坐标。 */
    public static CuboidBounds between(int dimensionId,
            int firstX, int firstY, int firstZ, int secondX, int secondY, int secondZ) {
        return new CuboidBounds(dimensionId,
                Math.min(firstX, secondX), Math.min(firstY, secondY), Math.min(firstZ, secondZ),
                Math.max(firstX, secondX), Math.max(firstY, secondY), Math.max(firstZ, secondZ));
    }

    private CuboidBounds(int dimensionId, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.dimensionId = dimensionId;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    /** 返回饱和到 Long.MAX_VALUE 的方块总数，避免极端坐标乘法溢出。 */
    public long volume() {
        long xy = multiplySaturated(span(minX, maxX), span(minY, maxY));
        return multiplySaturated(xy, span(minZ, maxZ));
    }

    /** @return 完整选区是否能由当前 accepted maxBlocks 原子兑现 */
    public boolean fitsWithin(int maxBlocks) {
        return maxBlocks > 0 && volume() <= (long) maxBlocks;
    }

    private static long span(int min, int max) {
        return (long) max - (long) min + 1L;
    }

    private static long multiplySaturated(long left, long right) {
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    public int getDimensionId() { return dimensionId; }
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CuboidBounds)) return false;
        CuboidBounds that = (CuboidBounds) other;
        return dimensionId == that.dimensionId
                && minX == that.minX && minY == that.minY && minZ == that.minZ
                && maxX == that.maxX && maxY == that.maxY && maxZ == that.maxZ;
    }

    @Override
    public int hashCode() {
        int result = dimensionId;
        result = 31 * result + minX;
        result = 31 * result + minY;
        result = 31 * result + minZ;
        result = 31 * result + maxX;
        result = 31 * result + maxY;
        return 31 * result + maxZ;
    }
}
