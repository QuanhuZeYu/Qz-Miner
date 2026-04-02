package club.heiqi.qz_miner.parallel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 逻辑服务器同步并行执行器。
 *
 * 在每个服务端 Tick 的 START 阶段提交所有任务，
 * 在 END 阶段统一等待任务完成，确保所有并行逻辑都被限制在单个 Tick 的边界内。
 */
public final class ParallelTickExecutor {

    private final CopyOnWriteArrayList<RegisteredTask> registeredTasks = new CopyOnWriteArrayList<>();
    private final ExecutorService executorService;
    private final AtomicLong tickCounter = new AtomicLong();
    private volatile TickBatch currentBatch;

    public ParallelTickExecutor() {
        this(createDefaultWorkerCount());
    }

    public ParallelTickExecutor(int workerCount) {
        this.executorService = Executors.newFixedThreadPool(workerCount, new ParallelThreadFactory());
        FMLCommonHandler.instance().bus().register(this);
        MyMod.LOG.info("[ParallelTick] Initialized with {} worker thread(s)", workerCount);
    }

    /**
     * 注册一个每 Tick 执行的并行任务。
     *
     * @param name 任务名称，用于日志定位
     * @param task 任务实现
     * @return 任务注销句柄
     */
    public ParallelTickSubscription register(String name, ParallelTickTask task) {
        RegisteredTask registeredTask = new RegisteredTask(name, task);
        registeredTasks.add(registeredTask);
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
        TickBatch batch = currentBatch;
        if (batch != null) {
            awaitBatch(batch);
            currentBatch = null;
        }

        registeredTasks.clear();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
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
        if (registeredTasks.isEmpty()) {
            currentBatch = null;
            return;
        }

        if (currentBatch != null) {
            MyMod.LOG.warn("[ParallelTick] Previous tick batch was not finished before next START, forcing wait");
            awaitBatch(currentBatch);
        }

        long tickId = tickCounter.incrementAndGet();
        long startNanoTime = System.nanoTime();
        ParallelTickContext context = new ParallelTickContext(tickId, startNanoTime);
        List<Future<?>> futures = new ArrayList<>(registeredTasks.size());

        for (RegisteredTask registeredTask : registeredTasks) {
            futures.add(executorService.submit(() -> runTask(registeredTask, context)));
        }

        currentBatch = new TickBatch(tickId, futures);
    }

    private void endTick() {
        TickBatch batch = currentBatch;
        currentBatch = null;
        if (batch == null) {
            return;
        }

        awaitBatch(batch);
    }

    private void awaitBatch(TickBatch batch) {
        for (Future<?> future : batch.futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                MyMod.LOG.warn("[ParallelTick] Interrupted while waiting for tick {} tasks to finish", batch.tickId, e);
                return;
            } catch (ExecutionException e) {
                MyMod.LOG.error("[ParallelTick] Task failed during tick {}", batch.tickId, e.getCause());
            }
        }
    }

    private void runTask(RegisteredTask registeredTask, ParallelTickContext context) {
        try {
            registeredTask.task.run(context);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            MyMod.LOG.warn("[ParallelTick] Task interrupted: {}", registeredTask.name, e);
        } catch (Exception e) {
            throw new RuntimeException("Parallel tick task failed: " + registeredTask.name, e);
        }
    }

    private void unregister(RegisteredTask registeredTask) {
        if (registeredTasks.remove(registeredTask)) {
            MyMod.LOG.debug("[ParallelTick] Unregistered task: {}", registeredTask.name);
        }
    }

    private static int createDefaultWorkerCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    private static final class RegisteredTask {
        private final String name;
        private final ParallelTickTask task;

        private RegisteredTask(String name, ParallelTickTask task) {
            this.name = name;
            this.task = task;
        }
    }

    private static final class TickBatch {
        private final long tickId;
        private final List<Future<?>> futures;

        private TickBatch(long tickId, List<Future<?>> futures) {
            this.tickId = tickId;
            this.futures = futures;
        }
    }

    private static final class ParallelThreadFactory implements ThreadFactory {
        private final AtomicInteger threadId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Qz-ParallelTick-" + threadId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
