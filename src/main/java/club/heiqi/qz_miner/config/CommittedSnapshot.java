package club.heiqi.qz_miner.config;

import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;

/**
 * 一次已验证 Authority 提交的不可变发布令牌。
 */
public final class CommittedSnapshot {

    public final long epoch;
    public final ValidatedSnapshot snapshot;

    CommittedSnapshot(long epoch, ValidatedSnapshot snapshot) {
        if (epoch <= 0L || snapshot == null) {
            throw new IllegalArgumentException("epoch must be positive and snapshot must not be null");
        }
        this.epoch = epoch;
        this.snapshot = snapshot;
    }
}
