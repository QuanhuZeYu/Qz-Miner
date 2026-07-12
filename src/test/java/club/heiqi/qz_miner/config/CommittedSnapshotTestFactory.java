package club.heiqi.qz_miner.config;

import java.util.HashMap;
import java.util.Map;

import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.autotool.AutoToolSelectionConfig;

/** 仅供纯 JVM 测试构造不可变提交快照。 */
public final class CommittedSnapshotTestFactory {

    private CommittedSnapshotTestFactory() {
    }

    public static CommittedSnapshot create(long epoch, ObjectGroupRuleSet rules) {
        Map<String, Object> values = new HashMap<String, Object>();
        QzMinerConfigDefaults.putAllDefaults(values);
        values.put("client.objectGroups", rules);
        values.put("general.autoToolSelection", new AutoToolSelectionConfig(false, "inventory", true,
                AutoToolSelectionConfig.EnchantmentPolicy.PRESERVE_CURRENT, 2, 2, 2));
        return new CommittedSnapshot(epoch, new ValidatedSnapshot(values));
    }
}
