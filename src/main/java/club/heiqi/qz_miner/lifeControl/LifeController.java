package club.heiqi.qz_miner.lifeControl;

import cpw.mods.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 尝试以并行的方式运行Event
 */
public class LifeController {
    public static Logger LOG = LogManager.getLogger();

    /**待启动线程*/
    public static List<LifeThread> waitStart = new ArrayList<>();
    /**已经启动的线程*/
    public static List<LifeThread> threads = new ArrayList<>();

    public static void addThread(LifeThread thread) {
        //LOG.info("添加任务: {}", thread.getName());
        waitStart.add(thread);
    }

    /**
     * 每帧进入时开始/恢复所有线程并行
     */
    public static void serverTickStartEventHead() {
        for (LifeThread t : waitStart) {
            if (t.side != Side.SERVER) continue;
            //LOG.info("开始任务: {}",t.getName());
            t.start();
            threads.add(t);
        }
        // 清空待启动线程表
        waitStart.clear();
        for (LifeThread t : threads) {
            if (t.side != Side.SERVER) continue;
            //LOG.info("继续任务: {}",t.getName());
            t.unpause();
        }
    }

    /**
     * 帧事件结束暂停所有线程
     */
    public static void serverTickEventTail() {
        List<LifeThread> willRemove = new ArrayList<>();
        for (LifeThread t : threads) {
            if (t.side != Side.SERVER) continue;
            //LOG.info("暂停任务: {}",t.getName());
            t.pause();
            // 如果线程已经被中断，移除它
            if (t.isInterrupted()) {
                /*LOG.info("移除任务: {}",t.getName());*/
                willRemove.add(t);
            }
        }
        // 执行移除
        threads.removeAll(willRemove);
    }

    public static void clientTickEventHead() {
        for (LifeThread t : waitStart) {
            if (t.side != Side.CLIENT) continue;
            //LOG.info("开始任务: {}",t.getName());
            t.start();
            threads.add(t);
        }
        // 清空待启动线程表
        waitStart.clear();
        for (LifeThread t : threads) {
            if (t.side != Side.CLIENT) continue;
            //LOG.info("继续任务: {}",t.getName());
            t.unpause();
        }
    }

    public static void clientTickEventTail() {
        List<LifeThread> willRemove = new ArrayList<>();
        for (LifeThread t : threads) {
            if (t.side != Side.CLIENT) continue;
            //LOG.info("暂停任务: {}",t.getName());
            t.pause();
            // 如果线程已经被中断，移除它
            if (t.isInterrupted()) {
                /*LOG.info("移除任务: {}",t.getName());*/
                willRemove.add(t);
            }
        }
        // 执行移除
        threads.removeAll(willRemove);
    }
}
