package club.heiqi.qz_miner.thread;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 并行tick类
 */
public class ParallelTick {
    public Logger LOG = LogManager.getLogger();
    public AtomicBoolean preTick = new AtomicBoolean(false);
    public AtomicBoolean postTick = new AtomicBoolean(false);

    public ArrayList<Pauseable> preTickTasks = new ArrayList<>();
    public ArrayList<Pauseable> postTickTasks = new ArrayList<>();
    public ArrayList<Pauseable> normalTasks = new ArrayList<>();

    public ParallelTick() {}

    public void processPreTickTasks(boolean shouldRun) {
        // 清理preTickTasks
        if (!shouldRun) {
            List<Pauseable> willRemove = preTickTasks.stream()
                    // 过滤出已经结束的线程
                    .filter(task -> task.stopped.get()).collect(Collectors.toList());
            // 移除已经结束的线程
            preTickTasks.removeAll(willRemove);
            // LOG.info("清理了{}个线程", willRemove.size());
        }
        processTasks(shouldRun, true);
    }

    public void processPostTickTasks(boolean shouldRun) {
        // 清理postTickTasks
        if (!shouldRun) {
            List<Pauseable> willRemove = postTickTasks.stream()
                    // 过滤出已经结束的线程
                    .filter(task -> task.stopped.get()).collect(Collectors.toList());
            // 移除已经结束的线程
            postTickTasks.removeAll(willRemove);
            // LOG.info("清理了{}个线程", willRemove.size());
        }
        processTasks(shouldRun, false);
    }

    /**
     * @param shouldRun 通过Minecraft的tick事件进入或退出传入true或false
     * @param preTick 通过布尔值选择preTickTasks或postTickTasks;<br>1.true为preTickTasks;<br>2.false为postTickTasks
     */
    private void processTasks(boolean shouldRun, boolean preTick) {
        ArrayList<Pauseable> tasks = preTick ? preTickTasks : postTickTasks;
        for (Pauseable task : tasks) {
            if (!task.started.get()) {
                if (shouldRun) {
                    task.start();
                    // LOG.info("启动线程：{}", task.getClass().getSimpleName());
                }
            } else {
                if (shouldRun) {
                    task.unPause();
                    // LOG.info("恢复线程：{}", task.getClass().getSimpleName());
                } else {
                    task.pause();
                    // LOG.info("暂停线程：{}", task.getClass().getSimpleName());
                }
            }
        }
        processNormalTasks();
    }

    /**
     * 普通任务不执行暂停和恢复操作
     */
    public ReentrantLock normalTaskLock = new ReentrantLock();
    public void processNormalTasks() {
        if (normalTaskLock.isLocked()) {
            LOG.warn("通用并行同步线程被阻塞! [General-purpose parallel synchronous threads are blocked!]");
            return;
        };
        normalTaskLock.lock();
        try {
            ArrayList<Pauseable> willRemove = new ArrayList<>();
            for (Pauseable task : normalTasks) {
                if (!task.started.get()) {
                    task.start();
                }
                if (task.stopped.get()) {
                    willRemove.add(task);
                }
            }

            normalTasks.removeAll(willRemove);
        } finally {
            normalTaskLock.unlock();
        }
    }


    public void addPreServerTickTask(Pauseable task) {
        task.setDaemon(true);
        preTickTasks.add(task);
    }

    public void addPostServerTickTask(Pauseable task) {
        task.setDaemon(true);
        postTickTasks.add(task);
    }

    public void addNormalTask(Pauseable task) {
        normalTaskLock.lock();
        try {
            task.setDaemon(true);
            normalTasks.add(task);
        } finally {
            normalTaskLock.unlock();
        }
    }
}
