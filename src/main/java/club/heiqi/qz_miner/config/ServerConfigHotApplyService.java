package club.heiqi.qz_miner.config;

/** 将成功配置提交按 epoch 幂等发布到服务端运行态。 */
public final class ServerConfigHotApplyService {

    /** 中枢接线边界；本服务不直接查找全局服务或遍历在线玩家。 */
    public interface Callbacks {
        /** 在 general 字段发布后发布依赖该配置的服务端 policy。 */
        void publishPolicy(CommittedSnapshot committed);

        /** 在 policy 发布后重裁在线玩家的 accepted 配置。 */
        void revalidateOnlineAccepted(CommittedSnapshot committed);
    }

    /** 无中枢接线时仅应用 general。 */
    public static final Callbacks NO_CALLBACKS = new Callbacks() {
        @Override
        public void publishPolicy(CommittedSnapshot committed) {
        }

        @Override
        public void revalidateOnlineAccepted(CommittedSnapshot committed) {
        }
    };

    private final Callbacks callbacks;
    private long appliedEpoch;

    /** @param callbacks policy publication 与在线玩家重裁接线 */
    public ServerConfigHotApplyService(Callbacks callbacks) {
        if (callbacks == null) {
            throw new IllegalArgumentException("callbacks must not be null");
        }
        this.callbacks = callbacks;
    }

    /**
     * 仅首次应用更新的 epoch；顺序固定为 general、policy、online accepted 重裁。
     *
     * @return 本次实际应用时 true；重复或旧 epoch 时 false
     */
    public synchronized boolean apply(CommittedSnapshot committed) {
        if (committed == null) {
            throw new IllegalArgumentException("committed must not be null");
        }
        if (committed.epoch <= appliedEpoch) {
            return false;
        }
        ConfigValueBridge.applyGeneralFromSnapshot(committed.snapshot);
        RuntimeException failure = null;
        try {
            callbacks.publishPolicy(committed);
        } catch (RuntimeException error) {
            failure = error;
        }
        try {
            callbacks.revalidateOnlineAccepted(committed);
        } catch (RuntimeException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) {
            throw new IllegalStateException("server config hot apply failed at epoch " + committed.epoch, failure);
        }
        appliedEpoch = committed.epoch;
        return true;
    }

    /** @return 已完成全部发布的最大 epoch */
    public synchronized long appliedEpoch() {
        return appliedEpoch;
    }
}
