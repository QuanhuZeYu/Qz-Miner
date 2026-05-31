package club.heiqi.qz_miner.thread;

import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 服务端主线程调度器。
 *
 * 1.7.10 服务端没有像客户端那样直接暴露统一的主线程任务入口，
 * 因此这里通过 ServerTick 事件在服务端主线程排空待执行任务。
 */
public final class ServerMainThreadDispatcher {

    private static final ConcurrentLinkedQueue<Runnable> PENDING_TASKS = new ConcurrentLinkedQueue<Runnable>();
    private static final ServerMainThreadDispatcher INSTANCE = new ServerMainThreadDispatcher();

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
     * 标记服务端启动，刷新线程引用并清空旧队列。
     */
    public static void onServerStarting() {
        stopping = false;
        serverThread = Thread.currentThread();
        PENDING_TASKS.clear();
    }

    /**
     * 标记服务端停止，优先执行剩余主线程任务。
     */
    public static void onServerStopping() {
        stopping = true;
        serverThread = Thread.currentThread();
        INSTANCE.drainPendingTasks();
        PENDING_TASKS.clear();
    }

    /**
     * 在服务端主线程执行任务。
     *
     * @param task 待执行任务
     */
    public static void run(Runnable task) {
        if (task == null) {
            return;
        }

        if (Thread.currentThread() == serverThread) {
            task.run();
            return;
        }

        if (stopping) {
            MyMod.LOG.debug("[ThreadDispatch] Dropping late server task during shutdown");
            return;
        }

        PENDING_TASKS.offer(task);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        serverThread = Thread.currentThread();
        drainPendingTasks();
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
}
