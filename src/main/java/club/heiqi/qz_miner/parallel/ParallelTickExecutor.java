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

    private final CopyOnWriteArrayList<RegisteredTask> preTasks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RegisteredTask> postTasks = new CopyOnWriteArrayList<>();
    private final AtomicLong tickCounter = new AtomicLong();
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition windowChanged = stateLock.newCondition();
    private final Condition workersIdle = stateLock.newCondition();

    private volatile boolean running = true;
    private volatile boolean tickWindowOpen = false;
    private volatile ParallelTickContext currentContext;
    private volatile long currentTickId = 0L;
    private volatile ParallelTickStage currentStage;
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
    public ParallelTickSubscription register(String name, ParallelTickStage stage, ParallelTickTask task) {
        RegisteredTask registeredTask = new RegisteredTask(name, stage, task);
        getTasks(stage).add(registeredTask);
        registeredTask.start();
        MyMod.LOG.debug("[ParallelTick] Registered {} task: {}", stage, name);
        return () -> unregister(registeredTask);
    }

    public ParallelTickSubscription registerPre(String name, ParallelTickTask task) {
        return register(name, ParallelTickStage.PRE, task);
    }

    public ParallelTickSubscription registerPost(String name, ParallelTickTask task) {
        return register(name, ParallelTickStage.POST, task);
    }

    /**
     * @return 当前已注册任务数
     */
    public int getRegisteredTaskCount() {
        return preTasks.size() + postTasks.size();
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

        for (RegisteredTask registeredTask : preTasks) {
            registeredTask.shutdown();
        }

        for (RegisteredTask registeredTask : postTasks) {
            registeredTask.shutdown();
        }

        for (RegisteredTask registeredTask : preTasks) {
            registeredTask.joinQuietly();
        }

        for (RegisteredTask registeredTask : postTasks) {
            registeredTask.joinQuietly();
        }

        preTasks.clear();
        postTasks.clear();
    }

    public void beginStage(ParallelTickStage stage) {
        if (getTasks(stage).isEmpty()) {
            stateLock.lock();
            try {
                currentStage = stage;
                currentContext = null;
                tickWindowOpen = false;
            } finally {
                stateLock.unlock();
            }
            return;
        }

        long tickId = tickCounter.incrementAndGet();
        long startNanoTime = System.nanoTime();
        long deadlineNanoTime = startNanoTime + DEFAULT_TICK_BUDGET_NANOS;

        stateLock.lock();
        try {
            currentTickId = tickId;
            currentStage = stage;
            currentContext = new ParallelTickContext(
                tickId,
                startNanoTime,
                deadlineNanoTime,
                stage == ParallelTickStage.PRE ? ParallelTickContext.Stage.PRE : ParallelTickContext.Stage.POST);
            tickWindowOpen = true;
            windowChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    public void endStage(ParallelTickStage stage) {
        stateLock.lock();
        try {
            if (currentStage != stage) {
                return;
            }

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
        if (getTasks(registeredTask.stage).remove(registeredTask)) {
            registeredTask.shutdown();
            MyMod.LOG.debug("[ParallelTick] Unregistered {} task: {}", registeredTask.stage, registeredTask.name);
        }
    }

    private CopyOnWriteArrayList<RegisteredTask> getTasks(ParallelTickStage stage) {
        return stage == ParallelTickStage.PRE ? preTasks : postTasks;
    }

    private final class RegisteredTask implements Runnable {
        private final String name;
        private final ParallelTickStage stage;
        private final ParallelTickTask task;
        private final Thread thread;
        private volatile boolean active = true;
        private long observedTickId = -1L;

        private RegisteredTask(String name, ParallelTickStage stage, ParallelTickTask task) {
            this.name = name;
            this.stage = stage;
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

                if (!running || !active || !tickWindowOpen || currentContext == null || currentStage != stage) {
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
                        getTasks(stage).remove(this);
                        active = false;
                        MyMod.LOG.debug("[ParallelTick] {} task completed: {}", stage, name);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    MyMod.LOG.warn("[ParallelTick] Task interrupted: {}", name, e);
                    active = false;
                    getTasks(stage).remove(this);
                    return;
                } catch (Exception e) {
                    MyMod.LOG.error("[ParallelTick] Task failed: {}", name, e);
                    active = false;
                    getTasks(stage).remove(this);
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
