package club.heiqi.qz_miner.thread;

import club.heiqi.qz_miner.Constant;
import club.heiqi.qz_miner.MyMod;

import java.util.concurrent.atomic.AtomicBoolean;

public class Pauseable extends Thread {

    /**请不要手动操作这个标志位<br>请使用pause()和unPause()方法操作*/
    public AtomicBoolean started =  new AtomicBoolean(false);
    /**请不要手动操作这个标志位<br>请使用pause()和unPause()方法操作*/
    public AtomicBoolean stopped =  new AtomicBoolean(false);
    /**请不要手动操作这个标志位<br>请使用pause()和unPause()方法操作*/
    public AtomicBoolean paused  =  new AtomicBoolean(false);
    /**请不要手动操作这个标志位<br>请使用pause()和unPause()方法操作*/
    public AtomicBoolean resumed =  new AtomicBoolean(false);
    /**错误执行次数*/
    public int errorCount = 0;


    public Pauseable() {
        super("可暂停线程");
    }

    public void pause() {
        if (!started.get()) {
            throw new RuntimeException("线程未启动");
        } else if (stopped.get()) {
            Constant.LOG.error("线程已停止! 无法执行暂停指令");
            errorCount++;
            if (errorCount > 10) {
                throw new RuntimeException("连续在线程停止后尝试错误的操作10次");
            }
            return;
        }
        paused.set(true);
        resumed.set(false);
    }

    public void unPause() {
        if (!started.get()) {
            throw new RuntimeException("线程未启动");
        } else if (stopped.get()) {
            Constant.LOG.error("线程已停止! 无法执行继续指令");
            errorCount++;
            if (errorCount > 10) {
                throw new RuntimeException("连续在线程停止后尝试错误的操作10次");
            }
            return;
        }
        paused.set(false);
        resumed.set(true);
    }

    /**
     * 如果线程暂停了，就等待直到线程继续运行
     */
    public void waitUntil() {
        while (paused.get()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    @Override
    public void run() {
        super.run();
        run1();
        stopped.set(true);
    }

    public void run1() {}

    @Override
    public synchronized void start() {
        started.set(true);
        super.start();
    }
}
