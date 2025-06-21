package club.heiqi.qz_miner.lifeControl;

import club.heiqi.qz_miner.minerMode.Pausable;
import cpw.mods.fml.relauncher.Side;

import java.util.concurrent.atomic.AtomicBoolean;

public class LifeThread extends Thread {
    public AtomicBoolean pause = new AtomicBoolean(false);
    public AtomicBoolean running = new AtomicBoolean(false);
    public Side side = Side.SERVER;

    public Runnable loop = () -> {};
    public Runnable loopOut = () -> {};

    public LifeThread(Runnable loopIn, String name) {
        super();
        this.loop = loopIn;
        this.setName(name);
    }

    /**
     * 由LifeThread接管Run以控制其运行生命周期
     */
    @Override
    public void run() {
        running.set(true);
        // 当没有暂停时运行任务
        while (running.get()) {
            if (!pause.get()) {
                loop.run();
            } else {
                // 如果检测到暂停，休眠10ms轮询
                try {
                    if (loop instanceof Pausable pausable) {
                        pausable.pause();
                    }
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    // 如果在暂停时间中被终止了，就执行终止逻辑
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        loopOut.run();
    }

    /**
     * 由Run内部调用，可以视作while定义<br/>
     * 该函数定义每次循环任务尽量不要太耗时，过于耗时可以考虑使用异步机制
     */
    /*public abstract void loopIn();*/

    /**
     * 当Run结束前时调用一次，可以视作扫尾函数
     */
    /*public abstract void loopOut();*/

    public void pause() {
        pause.set(true);
    }

    public void unpause() {
        pause.set(false);
        if (loop instanceof Pausable pausable) {
            pausable.unpause();
        }
    }

    public void setLoopOut(Runnable task) {
        loopOut = task;
    }
}
