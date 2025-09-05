package club.heiqi.qz_miner.thread;

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

    public void pause() {
        if (!started.get()) {
            throw  new RuntimeException("线程未启动");
        } else if (stopped.get()) {
            throw  new RuntimeException("线程已停止! 无法执行暂停指令");
        }
        paused.set(true);
        resumed.set(false);
    }

    public void unPause() {
        if (!started.get()) {
            throw  new RuntimeException("线程未启动");
        } else if (stopped.get()) {
            throw  new RuntimeException("线程已停止! 无法执行继续指令");
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
    public synchronized void start() {
        started.set(true);
        super.start();
        stopped.set(true);
    }
}
