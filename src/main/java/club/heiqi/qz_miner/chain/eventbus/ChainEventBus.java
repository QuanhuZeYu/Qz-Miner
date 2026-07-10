package club.heiqi.qz_miner.chain.eventbus;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import club.heiqi.qz_miner.MyMod;

/**
 * 连锁框架跨线程事件总线。
 *
 * <p>严格 side-agnostic：只依赖 {@code java.util.concurrent}、{@link UUID} 与
 * {@link ChainEvent}，不 import 任何服务端/客户端专属类，便于阶段 3 客户端复用。</p>
 *
 * <h3>线程模型（守 NORTH_STAR 不变量 I1/I2/I4）</h3>
 * <ul>
 *   <li>{@link #publish(ChainEvent)}：任意线程可调，仅入队，不分发，<b>绝不</b>同步 drain</li>
 *   <li>{@link #subscribe(Class, EventSubscriber)}：任意线程可注册，线程安全</li>
 *   <li>{@link #drain()}：契约上仅主线程调用，串行分发入队事件</li>
 *   <li>{@link #mainThread} 为 volatile 软校验锚，不一致仅记 warn，不抛异常</li>
 * </ul>
 *
 * <h3>分发语义</h3>
 * <ul>
 *   <li>按 {@code event.getClass()} 精确匹配查订阅表，不做父类型向上匹配</li>
 *   <li>单个订阅者抛 {@link RuntimeException} 被 catch 隔离，不中断后续事件</li>
 *   <li>阶段 1 不做代际陈旧判定（阶段 2 引入状态机后补）</li>
 * </ul>
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

    /**
     * 发布事件。任意线程可调，仅 {@link #pendingQueue#offer}，不分发。
     *
     * <p>守 I1/I2：发布路径绝不写世界、绝不同步 drain。</p>
     *
     * @param event 事件，null 直接返回不入队
     */
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
     * 主线程串行 drain 待分发事件。契约上仅主线程调用。
     *
     * <p>处理流程：循环 {@link #pendingQueue#poll}，按 {@code event.getClass()} 精确匹配查订阅表，
     * 逐个 onEvent。单个订阅者抛 {@link RuntimeException} 被 catch 并 log warn，不中断后续事件。
     * 不做代际陈旧判定（阶段 2 引入）。</p>
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
                    // catch 具体异常，不中断后续订阅者/事件（守 drain 隔离契约）
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
