package club.heiqi.qz_miner.chain.eventbus;

/**
 * 连锁事件订阅者函数式接口。
 *
 * <p>命名刻意区别于 {@code club.heiqi.qz_miner.event.EventListener}，与既有同步总线硬隔离。</p>
 *
 * <p><b>线程契约</b>：{@link #onEvent(ChainEvent)} 仅在主线程被
 * {@link ChainEventBus#drain()} 调用，订阅者可假定单线程执行，无需自行加锁。
 * 阶段 1 不区分只读投影与写权威订阅者（阶段 2 由状态机语义约束）。</p>
 *
 * @param <E> 订阅的事件类型
 */
@FunctionalInterface
public interface EventSubscriber<E extends ChainEvent> {

    /**
     * 事件被主线程 drain 时回调。
     *
     * <p>实现者抛出的 {@link RuntimeException} 会被 {@link ChainEventBus#drain()} 捕获并
     * 记录警告日志，不会中断后续事件分发。</p>
     *
     * @param event 被分发的事件，非 null
     */
    void onEvent(E event);
}
