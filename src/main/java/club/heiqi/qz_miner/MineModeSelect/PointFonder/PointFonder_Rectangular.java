package club.heiqi.qz_miner.MineModeSelect.PointFonder;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;

/**
 * 该类执行update后必定只执行taskTimeLimit毫秒
 */
public class PointFonder_Rectangular extends PointFonder{

    /**
     * 构造器设置基础值
     * 先清空缓存，并重置时间
     * 将指针指向中心点, 边界值也设置为中心点
     * 注意: 在get()之前请用update()获取点集
     * @param center 中心点
     */
    public PointFonder_Rectangular(Point center) { // 新建对象初始化构造
        super(center);
    }

    /**
     * 核心搜索逻辑
     */
    @Override
    public void startPhase() {
        if(doOnce) {
            if (!updateFieldsAndShouldContinue()) return;
            doOnce = false;
        }
        if(checkCacheOverSize()) {
            return;
        }
        // 扫描之前一定要检查条件, 进入内部后一定会加一个点
        switch(mode) {
            case Stop -> {
                completePhase();
            }
            case FaceXZLow -> {
                if(!inLoop) {
                    x = cX = minX; y = cY = minY; z = cZ = minZ;
                }
                if(scanXZ()) return;
                mode = ScanModeState.FaceYZLeft; // 切换状态
                inLoop = false;
            }
            case FaceXZTop -> {
                if (!inLoop) {
                    x = cX = minX; y = cY = maxY; z = cZ = minZ;
                }
                if(scanXZ()) return;
                mode = ScanModeState.FaceXZLow;  // 完成一轮循环的结束位置
                inLoop = false;
                updateFieldsAndShouldContinue();
            }
            case FaceYZLeft -> {
                if (!inLoop) {
                    x = cX = minX; y = cY = minY; z = cZ = minZ;  // 切换到YZLeft起始点
                }
                if(scanYZ()) return;
                mode = ScanModeState.FaceXYFront;
                inLoop = false;
            }
            case FaceYZRight -> {
                if (!inLoop) {
                    x = cX = maxX; y = cY = minY; z = cZ = minZ; // 切换到YZRight起始点
                }
                if(scanYZ()) return;
                mode = ScanModeState.FaceXYBack;
                inLoop = false;
            }
            case FaceXYFront -> {
                if (!inLoop) {
                    x = cX = minX; y = cY = minY; z = cZ = maxZ; // 切换到XYFront起始点
                }
                if(scanXY()) return;
                mode = ScanModeState.FaceYZRight;
                inLoop = false;
            }
            case FaceXYBack -> {
                if (!inLoop) {
                    x = cX = minX; y = cY = minY; z = cZ = minZ; // 切换到XYFront起始点
                }
                if((scanXY())) return;
                mode = ScanModeState.FaceXZTop;
                inLoop = false;
            }
        }
        curState = TaskState.End;
    }

    /**
     * 更新扫描半径, 更新边界值
     * 如果扫描半径超过限制, 则任务完成
     */
    public boolean updateFieldsAndShouldContinue() {
        if(radius >= Config.radiusLimit) { // 当扫描半径超过限制时，任务完成
            mode = ScanModeState.Stop;
            completePhase();
            return false;
        }
        radius++;
        maxX = centerP.x + radius; maxY = Math.min(centerP.y + radius, 255); maxZ = centerP.z + radius;
        minX = centerP.x - radius; minY = Math.max(centerP.y - radius, 0  ); minZ = centerP.z - radius;
        return true;
    }

    // region 扫描方法
    /**
     * 返回布尔值为是否在限定时间内完成任务
     * @return 如果是-则未完成循环
     */
    public boolean scanXZ() {
        for(int i = x; i <= maxX;) {
            for(int j = z; j <= maxZ;) {
                inLoop = true;
                cache.add(new Point(x, y, z));
                z++; j++;
                if(checkShouldStop() || checkCacheOverSize())
                    return true;
            }
            x++; i++; z = cZ;
        }
        return false;
    }

    public boolean scanYZ() {
        for(int i = z; i <= maxZ;) {
            for(int j = y; j <= maxY;) {
                inLoop = true;
                cache.add(new Point(x, y, z));
                y++; j++;
                if(checkShouldStop() || checkCacheOverSize())
                    return true;
            }
            z++; i++; y = cY;
        }
        return false;
    }

    public boolean scanXY() {
        for(int i = x; i <= maxX;) {
            for(int j = y; j <= maxY;) {
                inLoop = true;
                cache.add(new Point(x, y, z));
                y++; j++;
                if(checkShouldStop() || checkCacheOverSize()) return true;
            }
            x++; i++; y = cY;
        }
        return false;
    }
    // endregion
}
