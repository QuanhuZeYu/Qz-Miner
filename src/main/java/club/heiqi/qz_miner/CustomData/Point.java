package club.heiqi.qz_miner.CustomData;

public class Point {
    public int x;
    public int y;
    public int z;

    public Point(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Point topPoint() {
        return new Point(x, y + 1, z);
    }

    public Point bottomPoint() {
        return new Point(x, y - 1, z);
    }

    public Point xPlusPoint() {
        return new Point(x + 1, y, z);
    }

    public Point xMinusPoint() {
        return new Point(x - 1, y, z);
    }

    public Point zPlusPoint() {
        return new Point(x, y, z + 1);
    }

    public Point zMinusPoint() {
        return new Point(x, y, z - 1);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true; // 如果是同一个对象，直接返回true
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false; // 如果对象为null或者类型不匹配，返回false
        }
        Point other = (Point) obj;
        return this.x == other.x && this.y == other.y && this.z == other.z; // 判断各个坐标是否相同
    }

    @Override
    public String toString() {
        return "(x: " + x + ", y: " + y + ", z: " + z + ")";
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + x; // 乘以质数31来组合哈希码
        result = 97 * result + y;
        result = 23 * result + z;
        return result;
    }
}
