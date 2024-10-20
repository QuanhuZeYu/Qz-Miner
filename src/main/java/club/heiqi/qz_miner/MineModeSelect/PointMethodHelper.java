package club.heiqi.qz_miner.MineModeSelect;

import club.heiqi.qz_miner.CustomData.Point;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

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
        ret.sort(Comparator.comparing(point -> BlockMethodHelper.manhattanDistance(point, center))); // 由内到外排序
        return ret;
    }
}
