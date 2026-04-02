package club.heiqi.qz_miner.chain.planner;

/**
 * 连锁目标坐标。
 */
public final class ChainTarget {

    private final int x;
    private final int y;
    private final int z;

    public ChainTarget(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ChainTarget)) {
            return false;
        }
        ChainTarget other = (ChainTarget) obj;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        int result = x;
        result = 31 * result + y;
        result = 31 * result + z;
        return result;
    }
}
