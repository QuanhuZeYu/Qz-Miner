package club.heiqi.qz_miner.thread;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 服务端主线程调度器。
 *
 * <p>1.7.10 服务端没有像客户端那样直接暴露统一的主线程任务入口，
 * 因此这里通过 ServerTick 事件在服务端主线程排空待执行任务。</p>
 *
 * <p>普通 FIFO（{@link #run}/{@link #tryRun}）语义保持不变；
 * keyed latest-wins 泳道（{@link #tryRunLatest}）与 FIFO 完全隔离，
 * 仅在 ServerTick START 每 tick 最多 drain 64 槽，END 不 drain。
 * START keyed drain 外层有主线程不可重入 guard：嵌套 START 跳过 keyed lane，
 * 普通 FIFO 不受影响。</p>
 */
public final class ServerMainThreadDispatcher {

    private static final ConcurrentLinkedQueue<Runnable> PENDING_TASKS = new ConcurrentLinkedQueue<Runnable>();
    private static final KeyedLatestTaskLane<Object> KEYED_LANE =
            new KeyedLatestTaskLane<Object>(KeyedLatestTaskLane.DEFAULT_CAPACITY, KeyedLatestTaskLane.DEFAULT_DRAIN_BUDGET);
    private static final ServerMainThreadDispatcher INSTANCE = new ServerMainThreadDispatcher();
    /** START keyed drain 不可重入：嵌套 drainKeyedLane 直接跳过。 */
    private static final AtomicBoolean KEYED_DRAIN_ACTIVE = new AtomicBoolean(false);

    private static volatile boolean registered;
    private static volatile boolean stopping;
    private static volatile Thread serverThread;

    private ServerMainThreadDispatcher() {}

    /**
     * 注册服务端主线程调度监听。
     */
    public static synchronized void bootstrap() {
        if (registered) {
            return;
        }

        FMLCommonHandler.instance().bus().register(INSTANCE);
        registered = true;
    }

    /**
     * 标记服务端启动，刷新线程引用、清空旧 FIFO，并开启新 keyed lane identity。
     */
    public static void onServerStarting() {
        stopping = false;
        serverThread = Thread.currentThread();
        PENDING_TASKS.clear();
        KEYED_LANE.start();
    }

    /**
     * 标记服务端停止：先排空 FIFO，再关闭 keyed lane（清空并拒绝旧提交）。
     */
    public static void onServerStopping() {
        stopping = true;
        serverThread = Thread.currentThread();
        INSTANCE.drainPendingTasks();
        PENDING_TASKS.clear();
        KEYED_LANE.stop();
    }

    /**
     * 在服务端主线程执行任务。
     *
     * @param task 待执行任务
     */
    public static void run(Runnable task) {
        tryRun(task);
    }

    /**
     * 尝试在服务端主线程执行任务（普通 FIFO，语义不变）。
     *
     * @param task 待执行任务
     * @return 服务端已启动且任务已执行或入队时为 true
     */
    public static boolean tryRun(Runnable task) {
        if (task == null) {
            return false;
        }

        if (stopping || serverThread == null) {
            MyMod.LOG.debug("[ThreadDispatch] Dropping late server task during shutdown");
            return false;
        }

        if (Thread.currentThread() == serverThread) {
            task.run();
            return true;
        }

        PENDING_TASKS.offer(task);
        if (stopping) {
            PENDING_TASKS.remove(task);
            MyMod.LOG.debug("[ThreadDispatch] Rejected server task racing with shutdown");
            return false;
        }
        return true;
    }

    /**
     * 提交 keyed latest-wins 任务：同 key 只占一个槽，更新覆盖旧值。
     *
     * <p>与普通 FIFO 完全隔离；仅在 ServerTick START 按预算 drain。</p>
     *
     * @param key  槽位键（调用方负责端点身份）
     * @param task 最新任务
     * @return 已接受时为 true；关闭、容量满（新 key）或空参时为 false
     */
    public static boolean tryRunLatest(Object key, Runnable task) {
        if (key == null || task == null) {
            return false;
        }
        if (stopping || serverThread == null) {
            return false;
        }
        return KEYED_LANE.submit(key, task);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        serverThread = Thread.currentThread();
        if (event.phase == TickEvent.Phase.START) {
            drainKeyedLaneGuarded();
        }
        // 普通 FIFO 语义不变：继续在每个 ServerTick 相位排空
        drainPendingTasks();
    }

    /**
     * 带不可重入 guard 的 keyed drain：嵌套调用跳过 keyed lane。
     *
     * @return 本层实际 drain 的槽位数；因 reentry 跳过时为 0
     */
    static int drainKeyedLaneGuarded() {
        if (!KEYED_DRAIN_ACTIVE.compareAndSet(false, true)) {
            return 0;
        }
        try {
            return KEYED_LANE.drain(new KeyedLatestTaskLane.ErrorHandler() {
                @Override
                public void onError(RuntimeException error) {
                    MyMod.LOG.error("[ThreadDispatch] Server keyed task failed", error);
                }
            });
        } finally {
            KEYED_DRAIN_ACTIVE.set(false);
        }
    }

    private void drainPendingTasks() {
        Runnable task;
        while ((task = PENDING_TASKS.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                MyMod.LOG.error("[ThreadDispatch] Server main thread task failed", e);
            }
        }
    }

    /**
     * 测试钩子：直接访问 keyed lane（纯 JVM 测试用）。
     *
     * @return 内部 keyed lane
     */
    static KeyedLatestTaskLane<Object> keyedLaneForTests() {
        return KEYED_LANE;
    }

    /**
     * 测试钩子：keyed drain 是否处于外层执行中。
     *
     * @return reentry guard 是否占用
     */
    static boolean keyedDrainActiveForTests() {
        return KEYED_DRAIN_ACTIVE.get();
    }
}
