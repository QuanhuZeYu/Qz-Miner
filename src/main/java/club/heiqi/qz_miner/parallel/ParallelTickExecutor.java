package club.heiqi.qz_miner.parallel;

import java.util.EnumMap;
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

    private static final long MIN_TICK_BUDGET_MILLIS = 1L;
    private static final long MAX_TICK_BUDGET_MILLIS = 40L;
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
    private final EnumMap<ParallelTickStage, WindowState> windows = createWindows();

    private volatile boolean running = true;

    public ParallelTickExecutor() {
        this(true);
    }

    /** 纯逻辑测试可跳过 FML bus 注册，生产构造保持原行为。 */
    ParallelTickExecutor(boolean registerEventBus) {
        this.workerPool = new ThreadPoolExecutor(
            CORE_WORKER_THREADS,
            MAX_WORKER_THREADS,
            WORKER_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new ParallelWorkerThreadFactory());
        this.workerPool.allowCoreThreadTimeOut(false);
        this.workerPool.prestartCoreThread();
        if (registerEventBus) {
            FMLCommonHandler.instance().bus().register(this);
        }
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
            for (WindowState window : windows.values()) {
                window.open = false;
            }
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
        long tickId = tickCounter.incrementAndGet();
        long startNanoTime = System.nanoTime();
        long deadlineNanoTime = startNanoTime + getConfiguredTickBudgetNanos();

        stateLock.lock();
        try {
            WindowState window = getWindow(stage);
            if (window.open || window.activeWorkers > 0) {
                throw new IllegalStateException("Parallel tick stage already active: " + stage);
            }
            window.tickId = tickId;
            window.context = new ParallelTickContext(
                tickId,
                startNanoTime,
                deadlineNanoTime,
                stage);
            window.open = true;
            windowChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    public void endStage(ParallelTickStage stage) {
        waitForAvailableWindow(stage);

        stateLock.lock();
        boolean interrupted = false;
        try {
            WindowState window = getWindow(stage);
            if (!window.open) {
                return;
            }

            window.open = false;
            windowChanged.signalAll();

            while (window.activeWorkers > 0) {
                try {
                    workersIdle.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                    MyMod.LOG.warn("[ParallelTick] Interrupted while waiting {} workers to stop on tick {}",
                        stage, Long.valueOf(window.tickId), e);
                }
            }
            window.context = null;
        } finally {
            stateLock.unlock();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 只在该 stage 仍有并行任务时保留窗口；任务提前完成时不为空闲 deadline 强制占满 Tick。
     */
    private void waitForAvailableWindow(ParallelTickStage stage) {
        ParallelTickContext snapshot;
        stateLock.lock();
        try {
            WindowState window = getWindow(stage);
            if (!window.open || window.context == null || getTasks(stage).isEmpty()) {
                return;
            }
            snapshot = window.context;
        } finally {
            stateLock.unlock();
        }

        long remainingNanos = snapshot.getDeadlineNanoTime() - System.nanoTime();
        while (remainingNanos > 0L && !getTasks(stage).isEmpty()) {
            LockSupport.parkNanos(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(1L)));
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return;
            }
            remainingNanos = snapshot.getDeadlineNanoTime() - System.nanoTime();
        }
    }

    private long getConfiguredTickBudgetNanos() {
        long configuredBudgetMillis = Math.max(MIN_TICK_BUDGET_MILLIS,
            Math.min(MAX_TICK_BUDGET_MILLIS, Config.tickBudgetMs));
        return TimeUnit.MILLISECONDS.toNanos(configuredBudgetMillis);
    }

    /**
     * 返回当前 stage 已冻结的共享时间预算。
     *
     * <p>调用方只能在对应 Tick stage 内使用该快照；窗口关闭后旧快照即使仍可读，
     * 也不得据此开始新工作。</p>
     */
    public TickTimeBudget currentTimeBudget(ParallelTickStage stage) {
        stateLock.lock();
        try {
            WindowState window = getWindow(stage);
            return window.open ? window.context : null;
        } finally {
            stateLock.unlock();
        }
    }

    private void unregister(RegisteredTask registeredTask) {
        if (getTasks(registeredTask.stage).contains(registeredTask)) {
            if (registeredTask.requestCancel("subscription-unregister")) {
                MyMod.LOG.debug("[ParallelTick] Cancellation requested for {} task: {}", registeredTask.stage, registeredTask.name);
            }
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

    private WindowState getWindow(ParallelTickStage stage) {
        WindowState window = windows.get(stage);
        if (window == null) {
            throw new IllegalArgumentException("Unsupported stage: " + stage);
        }
        return window;
    }

    private static EnumMap<ParallelTickStage, WindowState> createWindows() {
        EnumMap<ParallelTickStage, WindowState> result =
            new EnumMap<ParallelTickStage, WindowState>(ParallelTickStage.class);
        for (ParallelTickStage stage : ParallelTickStage.values()) {
            result.put(stage, new WindowState());
        }
        return result;
    }

    private static final class WindowState {
        private boolean open;
        private long tickId;
        private ParallelTickContext context;
        private int activeWorkers;
    }

    private final class RegisteredTask implements Runnable {
        private final String name;
        private final ParallelTickStage stage;
        private final ParallelTickTask task;
        private volatile boolean active = true;
        private volatile boolean cancelRequested = false;
        private volatile String cancelReason = "";
        private volatile ParallelTaskState state = ParallelTaskState.REGISTERED;
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
            cancelRequested = true;
            cancelReason = "executor-shutdown";
            state = ParallelTaskState.TERMINATING;
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

        private boolean requestCancel(String reason) {
            if (!active
                || state == ParallelTaskState.COMPLETED
                || state == ParallelTaskState.TERMINATED
                || state == ParallelTaskState.FAILED) {
                return false;
            }

            boolean newlyRequested = !cancelRequested;
            if (newlyRequested) {
                cancelRequested = true;
                cancelReason = reason == null ? "unknown" : reason;
            }
            if (state != ParallelTaskState.RUNNING) {
                state = ParallelTaskState.CANCEL_REQUESTED;
            }

            if (newlyRequested) {
                try {
                    task.onCancelRequested(cancelReason);
                } catch (RuntimeException e) {
                    MyMod.LOG.warn("[ParallelTick] Task cancel callback failed: {}", name, e);
                }
            }

            stateLock.lock();
            try {
                windowChanged.signalAll();
            } finally {
                stateLock.unlock();
            }
            return newlyRequested;
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
                WindowState window = getWindow(stage);
                while (running && active
                    && (!window.open || window.context == null || observedTickId == window.tickId)) {
                    try {
                        windowChanged.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }

                if (!running || !active || !window.open || window.context == null) {
                    return null;
                }

                observedTickId = window.tickId;
                return window.context;
            } finally {
                stateLock.unlock();
            }
        }

        private void runSlicesInCurrentTick(ParallelTickContext context) {
            while (running && active) {
                if (!enterWorker(context)) {
                    return;
                }

                ParallelTaskResult result = ParallelTaskResult.YIELDED;
                try {
                    ParallelTickControl control = new RegisteredTaskControl(this, context);
                    state = cancelRequested ? ParallelTaskState.TERMINATING : ParallelTaskState.RUNNING;
                    result = task.run(control);
                    if (result == null) {
                        result = ParallelTaskResult.YIELDED;
                    }
                    if (cancelRequested && result != ParallelTaskResult.TERMINATED) {
                        result = ParallelTaskResult.TERMINATED;
                    }

                    if (handleTaskResult(result)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    MyMod.LOG.warn("[ParallelTick] Task interrupted: {}", name, e);
                    if (cancelRequested) {
                        finishTask(ParallelTaskState.TERMINATED);
                    } else {
                        finishTask(ParallelTaskState.FAILED);
                    }
                    return;
                } catch (Exception e) {
                    MyMod.LOG.error("[ParallelTick] Task failed: {}", name, e);
                    finishTask(ParallelTaskState.FAILED);
                    return;
                } finally {
                    exitWorker(stage);
                }

                if (result == ParallelTaskResult.YIELDED || !isWindowStillUsable(context)) {
                    return;
                }
            }
        }

        private boolean handleTaskResult(ParallelTaskResult result) {
            switch (result) {
                case CONTINUE:
                    state = cancelRequested ? ParallelTaskState.CANCEL_REQUESTED : ParallelTaskState.REGISTERED;
                    return false;
                case YIELDED:
                    state = cancelRequested ? ParallelTaskState.CANCEL_REQUESTED : ParallelTaskState.YIELDED;
                    return true;
                case COMPLETED:
                    finishTask(ParallelTaskState.COMPLETED);
                    MyMod.LOG.debug("[ParallelTick] {} task completed: {}", stage, name);
                    return true;
                case TERMINATED:
                    finishTask(ParallelTaskState.TERMINATED);
                    MyMod.LOG.debug("[ParallelTick] {} task terminated: {}, reason={}", stage, name, cancelReason);
                    return true;
                default:
                    state = ParallelTaskState.YIELDED;
                    return true;
            }
        }

        private void finishTask(ParallelTaskState finalState) {
            active = false;
            state = finalState;
            getTasks(stage).remove(this);
            if (finalState == ParallelTaskState.TERMINATED) {
                try {
                    task.cleanupAfterTermination();
                } catch (RuntimeException e) {
                    state = ParallelTaskState.FAILED;
                    MyMod.LOG.warn("[ParallelTick] Task termination cleanup failed: {}", name, e);
                }
            }
        }

        private boolean isWindowStillUsable(ParallelTickContext context) {
            stateLock.lock();
            try {
                WindowState window = getWindow(stage);
                return running
                    && active
                    && window.open
                    && window.context == context
                    && window.tickId == context.getTickId()
                    && context.hasTimeLeft();
            } finally {
                stateLock.unlock();
            }
        }
    }

    private boolean enterWorker(ParallelTickContext context) {
        stateLock.lock();
        try {
            WindowState window = getWindow(context.getStage());
            if (!running
                || !window.open
                || window.context != context
                || window.tickId != context.getTickId()
                || !context.hasTimeLeft()) {
                return false;
            }

            window.activeWorkers++;
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    private void exitWorker(ParallelTickStage stage) {
        stateLock.lock();
        try {
            WindowState window = getWindow(stage);
            window.activeWorkers--;
            if (window.activeWorkers <= 0) {
                workersIdle.signalAll();
            }
        } finally {
            stateLock.unlock();
        }
    }

    private final class RegisteredTaskControl implements ParallelTickControl {

        private final RegisteredTask registeredTask;
        private final ParallelTickContext context;

        private RegisteredTaskControl(RegisteredTask registeredTask, ParallelTickContext context) {
            this.registeredTask = registeredTask;
            this.context = context;
        }

        @Override
        public long getTickId() {
            return context.getTickId();
        }

        @Override
        public ParallelTickStage getStage() {
            return context.getStage();
        }

        @Override
        public boolean isWindowOpen() {
            stateLock.lock();
            try {
                WindowState window = getWindow(registeredTask.stage);
                return running
                    && window.open
                    && window.context == context
                    && window.tickId == context.getTickId();
            } finally {
                stateLock.unlock();
            }
        }

        @Override
        public boolean isCancelRequested() {
            return registeredTask.cancelRequested || !running;
        }

        @Override
        public boolean shouldYield() {
            return isCancelRequested() || !context.hasTimeLeft() || !isWindowOpen();
        }

        @Override
        public long getElapsedNanoTime() {
            return context.getElapsedNanoTime();
        }

        @Override
        public String getCancelReason() {
            return registeredTask.cancelReason;
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
