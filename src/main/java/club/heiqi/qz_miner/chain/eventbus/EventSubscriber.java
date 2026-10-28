package club.heiqi.qz_miner.chain.eventbus;

/**
 * 连锁事件的主线程订阅回调，由所属总线 drain 串行调用。
 *
 * @param <E> 订阅的事件类型
 */
@FunctionalInterface
public interface EventSubscriber<E extends ChainEvent> {

    /**
     * 事件被主线程 drain 时回调。
     *
     * <p>实现者抛出的 {@link RuntimeException} 会被 {@link ChainEventBus#drain()} 捕获并
     * 记录警告日志，随后继续分发后续事件。</p>
     *
     * @param event 被分发的事件，非 null
     */
    void onEvent(E event);
}
