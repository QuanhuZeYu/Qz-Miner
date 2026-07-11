package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import net.minecraft.entity.player.EntityPlayer;

/**
 * 对象组可挖掘匹配器，共用对象组显式身份谓词并保留普通可挖掘规则。
 */
public final class ObjectGroupMatcher implements ChainBlockMatcher {

    private final ObjectGroupBlockPredicate predicate;

    public ObjectGroupMatcher(ObjectGroup group) {
        this(new ObjectGroupBlockPredicate(group));
    }

    public ObjectGroupMatcher(ObjectGroupBlockPredicate predicate) {
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must not be null");
        }
        this.predicate = predicate;
    }

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        return player != null && target != null
                && predicate.matches(player.worldObj, target)
                && ChainHarvestRules.canHarvest(player, target);
    }
}
