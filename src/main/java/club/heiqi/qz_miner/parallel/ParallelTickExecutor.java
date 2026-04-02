package club.heiqi.qz_miner.parallel;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 逻辑服务器同步并行执行器。
 *
 * 任务以“分片”形式在多个 Tick 之间持续推进：
 * 1. START 打开本 Tick 的并行执行窗口
 * 2. 后台工作线程在窗口内反复执行任务分片
 * 3. END 关闭窗口，并等待当前分片全部停在边界上
 *
 * 因此长耗时任务不会被要求在单个 Tick 内完成，而是可以跨 Tick 增量执行。
 */
public final class ParallelTickExecutor {

    private static final long DEFAULT_TICK_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(45L);

    private final CopyOnWriteArrayList<RegisteredTask> registeredTasks = new CopyOnWriteArrayList<>();
    private final AtomicLong tickCounter = new AtomicLong();
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition windowChanged = stateLock.newCondition();
    private final Condition workersIdle = stateLock.newCondition();

    private volatile boolean running = true;
    private volatile boolean tickWindowOpen = false;
    private volatile ParallelTickContext currentContext;
    private volatile long currentTickId = 0L;
    private int activeWorkers = 0;

    public ParallelTickExecutor() {
        FMLCommonHandler.instance().bus().register(this);
        MyMod.LOG.info("[ParallelTick] Initialized cooperative incremental scheduler");
    }

    /**
     * 注册一个跨 Tick 增量执行的并行任务。
     *
     * @param name 任务名称，用于日志定位
     * @param task 任务实现
     * @return 任务注销句柄
     */
    public ParallelTickSubscription register(String name, ParallelTickTask task) {
        RegisteredTask registeredTask = new RegisteredTask(name, task);
        registeredTasks.add(registeredTask);
        registeredTask.start();
        MyMod.LOG.debug("[ParallelTick] Registered task: {}", name);
        return () -> unregister(registeredTask);
    }

    /**
     * @return 当前已注册任务数
     */
    public int getRegisteredTaskCount() {
        return registeredTasks.size();
    }

    /**
     * 关闭执行器并清空任务。
     */
    public void shutdown() {
        running = false;
        stateLock.lock();
        try {
            tickWindowOpen = false;
            windowChanged.signalAll();
            workersIdle.signalAll();
        } finally {
            stateLock.unlock();
        }

        for (RegisteredTask registeredTask : registeredTasks) {
            registeredTask.shutdown();
        }

        for (RegisteredTask registeredTask : registeredTasks) {
            registeredTask.joinQuietly();
        }

        registeredTasks.clear();
    }

    /**
     * 在 Tick 开始时启动并行区。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            beginTick();
            return;
        }

        endTick();
    }

    private void beginTick() {
        long tickId = tickCounter.incrementAndGet();
        long startNanoTime = System.nanoTime();
        long deadlineNanoTime = startNanoTime + DEFAULT_TICK_BUDGET_NANOS;

        stateLock.lock();
        try {
            currentTickId = tickId;
            currentContext = new ParallelTickContext(tickId, startNanoTime, deadlineNanoTime);
            tickWindowOpen = true;
            windowChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    private void endTick() {
        stateLock.lock();
        try {
            tickWindowOpen = false;
            windowChanged.signalAll();

            while (activeWorkers > 0) {
                try {
                    workersIdle.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    MyMod.LOG.warn("[ParallelTick] Interrupted while waiting workers to stop on tick {}", currentTickId, e);
                    return;
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void unregister(RegisteredTask registeredTask) {
        if (registeredTasks.remove(registeredTask)) {
            registeredTask.shutdown();
            MyMod.LOG.debug("[ParallelTick] Unregistered task: {}", registeredTask.name);
        }
    }

    private final class RegisteredTask implements Runnable {
        private final String name;
        private final ParallelTickTask task;
        private final Thread thread;
        private volatile boolean active = true;
        private long observedTickId = -1L;

        private RegisteredTask(String name, ParallelTickTask task) {
            this.name = name;
            this.task = task;
            this.thread = new ParallelThread(name).create(this);
        }

        private void start() {
            thread.start();
        }

        private void shutdown() {
            active = false;
            thread.interrupt();
            stateLock.lock();
            try {
                windowChanged.signalAll();
            } finally {
                stateLock.unlock();
            }
        }

        private void joinQuietly() {
            try {
                thread.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            while (running && active && !Thread.currentThread().isInterrupted()) {
                ParallelTickContext context = awaitNextWindow();
                if (context == null) {
                    break;
                }

                runSlicesInCurrentTick(context);
            }
        }

        private ParallelTickContext awaitNextWindow() {
            stateLock.lock();
            try {
                while (running && active && (!tickWindowOpen || observedTickId == currentTickId)) {
                    try {
                        windowChanged.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }

                if (!running || !active || !tickWindowOpen || currentContext == null) {
                    return null;
                }

                observedTickId = currentTickId;
                return currentContext;
            } finally {
                stateLock.unlock();
            }
        }

        private void runSlicesInCurrentTick(ParallelTickContext context) {
            while (running && active && context.hasTimeLeft()) {
                enterWorker();
                try {
                    boolean shouldContinue = task.run(context);
                    if (!shouldContinue) {
                        registeredTasks.remove(this);
                        active = false;
                        MyMod.LOG.debug("[ParallelTick] Task completed: {}", name);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    MyMod.LOG.warn("[ParallelTick] Task interrupted: {}", name, e);
                    active = false;
                    registeredTasks.remove(this);
                    return;
                } catch (Exception e) {
                    MyMod.LOG.error("[ParallelTick] Task failed: {}", name, e);
                    active = false;
                    registeredTasks.remove(this);
                    return;
                } finally {
                    exitWorker();
                }

                if (!tickWindowOpen) {
                    return;
                }
            }
        }
    }

    private void enterWorker() {
        stateLock.lock();
        try {
            activeWorkers++;
        } finally {
            stateLock.unlock();
        }
    }

    private void exitWorker() {
        stateLock.lock();
        try {
            activeWorkers--;
            if (activeWorkers <= 0) {
                workersIdle.signalAll();
            }
        } finally {
            stateLock.unlock();
        }
    }

    private static final class ParallelThread {
        private static final AtomicInteger THREAD_ID = new AtomicInteger(1);
        private final String taskName;

        private ParallelThread(String taskName) {
            this.taskName = taskName;
        }

        private Thread create(Runnable runnable) {
            Thread thread = new Thread(runnable, "Qz-ParallelTick-" + THREAD_ID.getAndIncrement() + "-" + taskName);
            thread.setDaemon(true);
            return thread;
        }
    }
}
