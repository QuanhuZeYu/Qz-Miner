package club.heiqi.qz_miner.parallel;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 逻辑服务器同步并行执行器。
 *
 * 任务以“分片”形式在多个 Tick 之间持续推进：
 * 1. START 打开本 Tick 的并行执行窗口
 * 2. 后台 worker 在窗口内反复执行任务分片
 * 3. END 关闭窗口，并等待当前分片全部停在边界上
 *
 * 当前实现使用有界线程池，核心线程数为 1，最大线程数为 20，
 * 目的是稳定线程名并减少 Hodgepodge 对异步世界读取的重复告警噪声。
 */
public final class ParallelTickExecutor {

    private static final long MIN_TICK_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);
    private static final int CORE_WORKER_THREADS = 1;
    private static final int MAX_WORKER_THREADS = 20;
    private static final long WORKER_KEEP_ALIVE_SECONDS = 30L;

    private final CopyOnWriteArrayList<RegisteredTask> serverPreTasks = new CopyOnWriteArrayList<RegisteredTask>();
    private final CopyOnWriteArrayList<RegisteredTask> serverPostTasks = new CopyOnWriteArrayList<RegisteredTask>();
    private final CopyOnWriteArrayList<RegisteredTask> clientPreTasks = new CopyOnWriteArrayList<RegisteredTask>();
    private final CopyOnWriteArrayList<RegisteredTask> clientPostTasks = new CopyOnWriteArrayList<RegisteredTask>();
    private final ThreadPoolExecutor workerPool;
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
        this.workerPool = new ThreadPoolExecutor(
            CORE_WORKER_THREADS,
            MAX_WORKER_THREADS,
            WORKER_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new ParallelWorkerThreadFactory());
        this.workerPool.allowCoreThreadTimeOut(false);
        this.workerPool.prestartCoreThread();
        FMLCommonHandler.instance().bus().register(this);
        MyMod.LOG.info("[ParallelTick] Initialized cooperative incremental scheduler with pooled workers (core={}, max={})",
            Integer.valueOf(CORE_WORKER_THREADS), Integer.valueOf(MAX_WORKER_THREADS));
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
        try {
            registeredTask.start();
        } catch (RuntimeException e) {
            getTasks(stage).remove(registeredTask);
            throw e;
        }
        MyMod.LOG.debug("[ParallelTick] Registered {} task: {}", stage, name);
        return () -> unregister(registeredTask);
    }

    public ParallelTickSubscription registerPre(String name, ParallelTickTask task) {
        return register(name, ParallelTickStage.SERVER_PRE, task);
    }

    public ParallelTickSubscription registerPost(String name, ParallelTickTask task) {
        return register(name, ParallelTickStage.SERVER_POST, task);
    }

    public ParallelTickSubscription registerClientPre(String name, ParallelTickTask task) {
        return register(name, ParallelTickStage.CLIENT_PRE, task);
    }

    public ParallelTickSubscription registerClientPost(String name, ParallelTickTask task) {
        return register(name, ParallelTickStage.CLIENT_POST, task);
    }

    /**
     * @return 当前已注册任务数
     */
    public int getRegisteredTaskCount() {
        return serverPreTasks.size() + serverPostTasks.size() + clientPreTasks.size() + clientPostTasks.size();
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

        for (RegisteredTask registeredTask : serverPreTasks) {
            registeredTask.shutdown();
        }

        for (RegisteredTask registeredTask : serverPostTasks) {
            registeredTask.shutdown();
        }

        for (RegisteredTask registeredTask : clientPreTasks) {
            registeredTask.shutdown();
        }

        for (RegisteredTask registeredTask : clientPostTasks) {
            registeredTask.shutdown();
        }

        workerPool.shutdownNow();

        try {
            if (!workerPool.awaitTermination(3L, TimeUnit.SECONDS)) {
                MyMod.LOG.warn("[ParallelTick] Worker pool did not terminate within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        serverPreTasks.clear();
        serverPostTasks.clear();
        clientPreTasks.clear();
        clientPostTasks.clear();
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
        long deadlineNanoTime = startNanoTime + getConfiguredTickBudgetNanos();

        stateLock.lock();
        try {
            currentTickId = tickId;
            currentStage = stage;
            currentContext = new ParallelTickContext(
                tickId,
                startNanoTime,
                deadlineNanoTime,
                stage);
            tickWindowOpen = true;
            windowChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    public void endStage(ParallelTickStage stage) {
        waitForMinimumWindow(stage);

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
                    MyMod.LOG.warn("[ParallelTick] Interrupted while waiting workers to stop on tick {}", Long.valueOf(currentTickId), e);
                    return;
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void waitForMinimumWindow(ParallelTickStage stage) {
        ParallelTickContext snapshot;
        stateLock.lock();
        try {
            if (currentStage != stage || currentContext == null || !tickWindowOpen) {
                return;
            }
            snapshot = currentContext;
        } finally {
            stateLock.unlock();
        }

        long minBudgetNanos = getConfiguredTickBudgetNanos();
        long remainingNanos = (snapshot.getStartNanoTime() + minBudgetNanos) - System.nanoTime();
        while (remainingNanos > 0L) {
            LockSupport.parkNanos(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(1L)));
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return;
            }
            remainingNanos = (snapshot.getStartNanoTime() + minBudgetNanos) - System.nanoTime();
        }
    }

    private long getConfiguredTickBudgetNanos() {
        long configuredBudgetMillis = Math.max(10L, Config.parallelTickMinDurationMs);
        return Math.max(MIN_TICK_BUDGET_NANOS, TimeUnit.MILLISECONDS.toNanos(configuredBudgetMillis));
    }

    private void unregister(RegisteredTask registeredTask) {
        if (getTasks(registeredTask.stage).remove(registeredTask)) {
            registeredTask.shutdown();
            MyMod.LOG.debug("[ParallelTick] Unregistered {} task: {}", registeredTask.stage, registeredTask.name);
        }
    }

    private CopyOnWriteArrayList<RegisteredTask> getTasks(ParallelTickStage stage) {
        switch (stage) {
            case SERVER_PRE:
                return serverPreTasks;
            case SERVER_POST:
                return serverPostTasks;
            case CLIENT_PRE:
                return clientPreTasks;
            case CLIENT_POST:
                return clientPostTasks;
            default:
                throw new IllegalArgumentException("Unsupported stage: " + stage);
        }
    }

    private final class RegisteredTask implements Runnable {
        private final String name;
        private final ParallelTickStage stage;
        private final ParallelTickTask task;
        private volatile boolean active = true;
        private volatile Future<?> future;
        private long observedTickId = -1L;

        private RegisteredTask(String name, ParallelTickStage stage, ParallelTickTask task) {
            this.name = name;
            this.stage = stage;
            this.task = task;
        }

        private void start() {
            try {
                future = workerPool.submit(this);
            } catch (RejectedExecutionException e) {
                MyMod.LOG.error("[ParallelTick] Worker pool exhausted, refuse task: {}", name, e);
                active = false;
                throw e;
            }
        }

        private void shutdown() {
            active = false;
            Future<?> currentFuture = future;
            if (currentFuture != null) {
                currentFuture.cancel(true);
            }
            stateLock.lock();
            try {
                windowChanged.signalAll();
            } finally {
                stateLock.unlock();
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

    private static final class ParallelWorkerThreadFactory implements ThreadFactory {

        private final BlockingQueue<Integer> workerSlots = new java.util.concurrent.ArrayBlockingQueue<Integer>(MAX_WORKER_THREADS);

        private ParallelWorkerThreadFactory() {
            for (int i = 1; i <= MAX_WORKER_THREADS; i++) {
                workerSlots.offer(Integer.valueOf(i));
            }
        }

        @Override
        public Thread newThread(final Runnable runnable) {
            final Integer workerSlot = workerSlots.poll();
            if (workerSlot == null) {
                throw new IllegalStateException("Parallel tick worker slots exhausted");
            }

            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        runnable.run();
                    } finally {
                        workerSlots.offer(workerSlot);
                    }
                }
            }, "Qz-ParallelTick-worker-" + workerSlot.intValue());
            thread.setDaemon(true);
            return thread;
        }
    }
}
