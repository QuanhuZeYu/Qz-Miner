package club.heiqi.qz_miner.config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;

/**
 * 已提交配置快照的分侧 latest-wins 发布协调器。
 */
public final class ConfigSnapshotDispatch {

    private ConfigSnapshotDispatch() {
    }

    /** 任务投递边界。 */
    public interface Dispatcher {
        /**
         * @param task 待投递 drain
         * @return dispatcher 已接受或同步执行时为 true
         */
        boolean dispatch(Runnable task);
    }

    /** 快照发布动作。 */
    public interface Publication {
        /** @param snapshot 同步捕获的提交快照 */
        void publish(ValidatedSnapshot snapshot);
    }

    /**
     * 独立分侧的无锁单消费者 mailbox。
     *
     * <p>pending 只保留最大 epoch；draining 是唯一 drain owner。publication 不持有 bootstrap、manager
     * 或 mailbox monitor。关闭后 queued drain 只清理 owner，不再执行 publication。</p>
     *
     * <p>{@code processedEpoch} 是已处理水位：stale current 未 publication 也会推进；
     * publication 失败不推进。命名刻意不是 applied，避免与“已成功发布”混淆。</p>
     */
    public static final class Mailbox implements AutoCloseable {

        private final String side;
        private final Dispatcher dispatcher;
        private final Publication publication;
        private final AtomicReference<CommittedSnapshot> pending = new AtomicReference<CommittedSnapshot>();
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicBoolean retryRequested = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong processedEpoch = new AtomicLong();

        /**
         * @param side 诊断侧名称
         * @param dispatcher 主线程 dispatcher
         * @param publication 锁外发布动作
         */
        public Mailbox(String side, Dispatcher dispatcher, Publication publication) {
            if (side == null || dispatcher == null || publication == null) {
                throw new IllegalArgumentException("side/dispatcher/publication must not be null");
            }
            this.side = side;
            this.dispatcher = dispatcher;
            this.publication = publication;
        }

        /**
         * 提交一个 epoch；较旧值被 latest 槽拒绝，最多争取一个 drain owner。
         *
         * @param committed 已提交包装
         * @return mailbox 未关闭且该 epoch 不早于现有待处理值
         */
        public boolean submit(CommittedSnapshot committed) {
            if (committed == null) {
                throw new IllegalArgumentException("committed snapshot must not be null");
            }
            if (closed.get() || committed.epoch <= processedEpoch.get()) {
                return false;
            }
            boolean offered = offerLatest(committed);
            if (closed.get()) {
                pending.set(null);
                return false;
            }
            requestDrainFromSubmit();
            return offered;
        }

        /**
         * @return 已处理（含 stale 跳过）的最大 epoch；publication 失败不推进
         */
        public long processedEpoch() {
            return processedEpoch.get();
        }

        /** @return mailbox 是否已关闭 */
        public boolean isClosed() {
            return closed.get();
        }

        /** 关闭并丢弃尚未开始的 publication。 */
        @Override
        public void close() {
            closed.set(true);
            pending.set(null);
            retryRequested.set(false);
        }

        private boolean offerLatest(CommittedSnapshot offered) {
            while (!closed.get()) {
                CommittedSnapshot current = pending.get();
                if (current != null && current.epoch >= offered.epoch) {
                    return false;
                }
                if (pending.compareAndSet(current, offered)) {
                    return true;
                }
            }
            return false;
        }

        private void requestDrainFromSubmit() {
            if (draining.compareAndSet(false, true)) {
                scheduleDrain(true);
                return;
            }
            retryRequested.set(true);
            // owner 可能恰在 submit 的 CAS 失败后释放；再次争抢关闭该窗口。
            if (!draining.get() && draining.compareAndSet(false, true)) {
                scheduleDrain(true);
            }
        }

        private void scheduleDrain(boolean allowConcurrentRetry) {
            boolean accepted = false;
            try {
                accepted = dispatcher.dispatch(new Runnable() {
                    @Override
                    public void run() {
                        drain();
                    }
                });
            } catch (RuntimeException e) {
                MyMod.LOG.error("[ConfigMailbox] {} dispatcher failed; pending retained", side, e);
            } catch (Error error) {
                draining.set(false);
                throw error;
            }
            if (accepted) {
                return;
            }
            MyMod.LOG.warn("[ConfigMailbox] {} dispatcher rejected drain; pending retained", side);
            draining.set(false);
            boolean retry = retryRequested.getAndSet(false);
            if (allowConcurrentRetry && retry && !closed.get() && draining.compareAndSet(false, true)) {
                scheduleDrain(false);
            }
        }

        private void drain() {
            boolean ownerReleased = false;
            try {
                while (true) {
                    if (closed.get()) {
                        pending.set(null);
                        releaseOwnerWithoutRetry();
                        ownerReleased = true;
                        return;
                    }
                    CommittedSnapshot committed = pending.getAndSet(null);
                    if (committed == null) {
                        if (releaseOwnerOrContinue()) {
                            continue;
                        }
                        ownerReleased = true;
                        return;
                    }
                    if (committed.epoch <= processedEpoch.get()) {
                        continue;
                    }
                    if (!ConfigBootstrap.isCurrent(committed)) {
                        // stale current：不 publication，但推进已处理水位
                        advanceProcessedEpoch(committed.epoch);
                        continue;
                    }
                    try {
                        publication.publish(committed.snapshot);
                    } catch (RuntimeException e) {
                        MyMod.LOG.error("[ConfigMailbox] {} publication failed at epoch={}",
                                side, Long.valueOf(committed.epoch), e);
                        CommittedSnapshot newer = pending.get();
                        if (newer != null && newer.epoch > committed.epoch) {
                            continue;
                        }
                        offerLatest(committed);
                        // publication 失败不推进 processedEpoch
                        releaseAfterPublicationFailure(committed.epoch);
                        ownerReleased = true;
                        return;
                    }
                    advanceProcessedEpoch(committed.epoch);
                }
            } finally {
                if (!ownerReleased) {
                    draining.set(false);
                }
            }
        }

        /**
         * idle 前后双检 pending；若释放窗口内出现新值，则当前栈重新取得 owner 继续 drain。
         */
        private boolean releaseOwnerOrContinue() {
            retryRequested.set(false);
            if (pending.get() != null) {
                return true;
            }
            draining.set(false);
            if (closed.get() || (pending.get() == null && !retryRequested.getAndSet(false))) {
                return false;
            }
            return draining.compareAndSet(false, true);
        }

        private void releaseAfterPublicationFailure(long failedEpoch) {
            draining.set(false);
            boolean concurrentRetry = retryRequested.getAndSet(false);
            CommittedSnapshot latest = pending.get();
            boolean newerPending = latest != null && latest.epoch > failedEpoch;
            if ((concurrentRetry || newerPending) && !closed.get() && draining.compareAndSet(false, true)) {
                scheduleDrain(false);
            }
        }

        private void releaseOwnerWithoutRetry() {
            retryRequested.set(false);
            draining.set(false);
        }

        private void advanceProcessedEpoch(long epoch) {
            long previous;
            do {
                previous = processedEpoch.get();
                if (epoch <= previous) {
                    return;
                }
            } while (!processedEpoch.compareAndSet(previous, epoch));
        }
    }

    /**
     * 将同一提交包装 best-effort 提交到两侧 mailbox。
     */
    public static void dispatch(
            CommittedSnapshot committed,
            Mailbox clientMailbox,
            boolean publishServer,
            Mailbox serverMailbox) {
        if (committed == null || clientMailbox == null || (publishServer && serverMailbox == null)) {
            throw new IllegalArgumentException("committed/client mailbox/enabled server mailbox must not be null");
        }
        clientMailbox.submit(committed);
        if (publishServer) {
            serverMailbox.submit(committed);
        }
    }
}
