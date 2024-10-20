package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.CustomData.Point;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PointMethodHelper {
    public static List<Point> getAllPointInBox(Point center, int range) {
        List<Point> ret = new ArrayList<>();
        // 需要选取的范围 立方体对角
        Point TRF = new Point(center.x + range, center.y + range, center.z + range);
        Point BLB = new Point(center.x - range, center.y - range, center.z - range);
        // 遍历指定范围内的所有点
        for (int x = BLB.x; x <= TRF.x; x++) {
            for (int y = BLB.y; y <= TRF.y; y++) {
                for (int z = BLB.z; z <= TRF.z; z++) {
                    // 排除中心点本身
                    if (x == center.x && y == center.y && z == center.z) {
                        continue;
                    }
                    Point currentPoint = new Point(x, y, z);
                    ret.add(currentPoint);
                }
            }
        }
        ret.sort(Comparator.comparing(point -> manhattanDistance(point, center))); // 由内到外排序
        return ret;
    }

    /**
     * 获取半径为radius的所有点
     * @param center
     * @param radius
     * @return
     */
    public static List<Point> getAllPointInRadius(Point center, int radius) {
        List<Point> ret = new ArrayList<>();
        int radiusSqr = radius * radius;
        // 需要选取的范围 立方体对角
        Point TRF = new Point(center.x + radius, center.y + radius, center.z + radius);
        Point BLB = new Point(center.x - radius, center.y - radius, center.z - radius);
        // 遍历指定范围内的所有点
        for (int x = BLB.x; x <= TRF.x; x++) {
            for (int y = BLB.y; y <= TRF.y; y++) {
                for (int z = BLB.z; z <= TRF.z; z++) {
                    // 排除中心点本身
                    if (x == center.x && y == center.y && z == center.z) {
                        continue;
                    }
                    Point currentPoint = new Point(x, y, z);
                    int distanceSqr = PointMethodHelper.calculateDistance_Sqz(currentPoint, center);
                    if (distanceSqr <= radiusSqr) {
                        ret.add(currentPoint);
                    }
                }
            }
        }
        ret.sort(Comparator.comparing(point -> manhattanDistance(point, center))); // 由内到外排序
        return ret;
    }

    public static List<Point> getAllPointUpperHorizons(Point center, int radius) {
        List<Point> ret = new ArrayList<>();
        // 需要选取的范围 立方体对角
        Point TRF = new Point(center.x + radius, center.y + radius, center.z + radius);
        Point BLB = new Point(center.x - radius, center.y - radius, center.z - radius);
        int horizon = center.y;
        // 遍历指定范围内的所有点
        for (int x = BLB.x; x <= TRF.x; x++) {
            for (int y = BLB.y; y <= TRF.y; y++) {
                for (int z = BLB.z; z <= TRF.z; z++) {
                    // 排除中心点本身
                    if (x == center.x && y == center.y && z == center.z) {
                        continue;
                    }
                    if(y < horizon) continue;
                    Point currentPoint = new Point(x, y, z);
                    ret.add(currentPoint);
                }
            }
        }
        ret.sort(Comparator.comparing(point -> manhattanDistance(point, center))); // 由内到外排序
        return ret;
    }

    // 计算曼哈顿距离的方法
    public static int manhattanDistance(Point p1, Point p2) {
        return Math.abs(p1.x - p2.x) + Math.abs(p1.y - p2.y) + Math.abs(p1.z - p2.z);
    }

    public static double calculateDistance(Point pointA, Point pointB) {
        return Math.sqrt(Math.pow(pointA.x - pointB.x, 2) + Math.pow(pointA.y - pointB.y, 2) + Math.pow(pointA.z - pointB.z, 2));
    }

    public static int calculateDistance_Sqz(Point pointA, Point pointB) {
        return (pointA.x - pointB.x)*(pointA.x - pointB.x) + (pointA.y - pointB.y)*(pointA.y - pointB.y) + (pointA.z - pointB.z)*(pointA.z - pointB.z);
    }

    /**
     * 无遍历, 放心使用; 检测点是否在框选立方体之内
     * @param waitCheck
     * @param center
     * @param radius
     * @return
     */
    public static boolean checkPointIsInBox(Point waitCheck, Point center, int radius) {
        int maxX = center.x + radius;
        int minX = center.x - radius;
        int maxY = Math.max((center.y + radius), 255);
        int minY = Math.min((center.y - radius), 0);
        int maxZ = center.z + radius;
        int minZ = center.z - radius;
        int x = waitCheck.x;
        int y = waitCheck.y;
        int z = waitCheck.z;
        if (x <= maxX && x >= minX && y <= maxY && y >= minY && z <= maxZ && z >= minZ) {
            return true;
        } else {
            return false;
        }
    }
}
