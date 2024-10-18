package club.heiqi.qz_miner.Util;

public class DistanceCalculate {
    public static double getDistance(int currentX, int currentY, int currentZ, int x, int y, int z) {
        // 计算当前点与中心的距离
        double distance = Math.sqrt(
            Math.pow(currentX - x, 2) +
                Math.pow(currentY - y, 2) +
                Math.pow(currentZ - z, 2)
        );
        return distance;
    }
}
