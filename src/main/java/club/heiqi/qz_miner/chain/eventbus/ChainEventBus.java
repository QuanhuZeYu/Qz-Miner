package club.heiqi.qz_miner.chain.eventbus;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import club.heiqi.qz_miner.MyMod;

/**
 * 多线程发布、所属主线程串行消费的连锁事件队列。
 *
 * <p>publish 仅入队，drain 按事件具体类型分发；订阅者 RuntimeException 记录后继续后续分发。
 * 主线程绑定用于诊断，业务订阅者负责代际与轮次校验。</p>
 */
public class ChainEventBus {

    /** 待分发事件队列，多线程 publish / 单线程 drain。 */
    private final ConcurrentLinkedQueue<ChainEvent> pendingQueue = new ConcurrentLinkedQueue<ChainEvent>();
    /** 订阅表：事件类型 -> 订阅者列表。 */
    private final ConcurrentMap<Class<? extends ChainEvent>, CopyOnWriteArrayList<EventSubscriber<? extends ChainEvent>>> subscribers =
            new ConcurrentHashMap<Class<? extends ChainEvent>, CopyOnWriteArrayList<EventSubscriber<? extends ChainEvent>>>();
    /** 主线程引用，drain 软校验用，可由外部 {@link #bindMainThread(Thread)} 设置。 */
    private volatile Thread mainThread;

    /**
     * 绑定主线程引用，供 {@link #drain()} 软校验。
     *
     * @param thread 主线程引用，null 表示清除绑定
     */
    public void bindMainThread(Thread thread) {
        this.mainThread = thread;
    }

    /** 任意线程可发布；非 null 事件入队，交由所属主线程 drain 分发。 */
    public void publish(ChainEvent event) {
        if (event == null) {
            return;
        }
        pendingQueue.offer(event);
    }

    /**
     * 订阅指定类型事件。任意线程可调，线程安全。
     *
     * @param type       事件类型（精确匹配，{@code event.getClass()} 必须等于此类型才分发）
     * @param subscriber 订阅者
     * @param <E>        事件类型参数
     */
    public <E extends ChainEvent> void subscribe(Class<E> type, EventSubscriber<E> subscriber) {
        subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<EventSubscriber<? extends ChainEvent>>())
                .add(subscriber);
    }

    /**
     * 所属主线程串行分发待处理事件，逐订阅者隔离 RuntimeException。
     *
     * @return 本次处理的事件条数
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int drain() {
        if (mainThread != null && Thread.currentThread() != mainThread) {
            MyMod.LOG.warn("[ChainEventBus] drain() called from non-main thread {}; expected {}. 主线程契约被违反，仍继续执行。",
                    Thread.currentThread().getName(),
                    mainThread.getName());
        }
        int processed = 0;
        ChainEvent event;
        while ((event = pendingQueue.poll()) != null) {
            processed++;
            List<EventSubscriber<? extends ChainEvent>> list = subscribers.get(event.getClass());
            if (list == null || list.isEmpty()) {
                continue;
            }
            for (EventSubscriber subscriber : list) {
                try {
                    subscriber.onEvent(event);
                } catch (RuntimeException e) {
                    // 记录订阅者异常后继续后续分发。
                    MyMod.LOG.warn("[ChainEventBus] subscriber {} threw on event {}",
                            subscriber.getClass().getName(), event.getClass().getSimpleName(), e);
                }
            }
        }
        return processed;
    }

    /**
     * 清空待分发队列，不影响已注册订阅者。
     *
     * <p>客户端生命周期 cleanup 专用入口：断线/世界卸载后清 pending，防止旧 phase 随后 drain 回写。
     * 服务端 bus 亦可安全调用；不破坏订阅关系。</p>
     */
    public void clearPending() {
        pendingQueue.clear();
    }

    /**
     * 清空待分发队列，不影响已注册订阅者。
     *
     * <p>等价 {@link #clearPending()}，保留旧名兼容既有测试。</p>
     */
    public void clear() {
        clearPending();
    }

    /**
     * @return 当前待分发事件条数（近似值，并发场景下不保证强一致）
     */
    public int pendingCount() {
        return pendingQueue.size();
    }
}
