package club.heiqi.qz_miner.MineModeSelect.PointFonder;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static club.heiqi.qz_miner.MY_LOG.printMessage;

public abstract class PointFonder {
    public long updateLifeTime = System.currentTimeMillis();
    public long lastUpdate = System.currentTimeMillis();
    public static int taskTimeLimit = Config.pointFonderTaskTimeLimit;
    public Point centerP;
    public int x, y, z;  // 循环任务中扫描的当前指针位置, 用于返回循环时继续上次任务
    public int cX, cY, cZ;  // 循环开始前指针的副本
    public int maxX, maxY, maxZ, minX, minY, minZ;  // 边界值
    public int radius = 0;
    public int cacheSizeMAX = Config.pointFounderCacheSize;  // 缓存最大值, 防止OOM
    public boolean doOnce = true;
    public List<Point> cache = new ArrayList<>();
    public PointFonder_Rectangular.ScanModeState mode = PointFonder_Rectangular.ScanModeState.FaceXZLow;  // 默认先扫描XZ面
    public PointFonder_Rectangular.TaskState curState = PointFonder_Rectangular.TaskState.IDLE;  // 默认空闲
    public boolean inLoop = false;

    public enum ScanModeState {
        Stop,  // 目前用法只有达到半径限制才会标记为Stop
        FaceXZLow,
        FaceXZTop,
        FaceYZLeft,
        FaceYZRight,
        FaceXYFront,
        FaceXYBack,;
    }

    public enum TaskState {
        IDLE,
        Start,
        End,
        Complete
    }

    public PointFonder(Point point) {
        centerP = point;
        cache.clear();
        updateLifeTime = System.currentTimeMillis();
        lastUpdate = System.currentTimeMillis();
        x = centerP.x; y = centerP.y; z = centerP.z;
        maxX = point.x; maxY = point.y; maxZ = point.z;
        minX = point.x; minY = point.y; minZ = point.z;
    }

    /**
     * 获取cache中缓存的点, 并清空cache等待下次添加
     * @return cache中缓存的点
     */
    public Collection<Point> getAll() {
        if(cache.isEmpty()) return null;
        List<Point> ret = new ArrayList<>();
        Iterator<Point> it = cache.iterator();
        while(it.hasNext()) {
            Point point = it.next();
            it.remove();
            ret.add(point);
        }
        return ret;
    }

    /**
     * 如果当前状态是完成状态, 跳过.
     * 1.如果当前状态不是IDLE则认为正在忙, 拒绝update
     * 2.更新配置文件中的限制时间
     */
    public void update() {
        if(curState == TaskState.Complete || checkCacheOverSize()) {
            return;
        }
        if(curState != TaskState.IDLE) {
            printMessage("当前状态为" + curState.name() + "拒绝连锁");
            return;
        }
        if(taskTimeLimit != Config.pointFonderTaskTimeLimit) taskTimeLimit = Config.pointFonderTaskTimeLimit;
        curState = TaskState.Start;
        lastUpdate = System.currentTimeMillis();

        do startPhase();
        while (!checkShouldStop() && !checkCacheOverSize());

        endPhase();  // 更新时间
        curState = TaskState.IDLE;
    }

    /**
     * 核心搜索逻辑 在此方法中给cache添加点
     */
    public abstract void startPhase();

    public void endPhase() {

    };

    public void completePhase() {
        curState = TaskState.Complete;
    }

    /**
     * @return 运行时间是否达到限制 是-则达到
     */
    public boolean checkShouldStop() {
        long now = System.currentTimeMillis();
        return now - lastUpdate > taskTimeLimit;
    }

    /**
     * @return 是否超过缓存限制 是-则超过
     */
    public boolean checkCacheOverSize() {
        if(cache.size() >= cacheSizeMAX) {
            return true;
        } else {
            return false;
        }
    }
}
