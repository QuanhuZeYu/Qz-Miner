package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/** 服务端与预览共享的单任务冻结模式扩展谓词。 */
public final class FrozenModePredicate {
    private final ModeExtensionSnapshot snapshot;

    public FrozenModePredicate(ModeExtensionSnapshot snapshot) {
        this.snapshot = snapshot == null ? ModeExtensionSnapshot.EMPTY : snapshot;
    }

    public boolean matches(World world, ChainTarget target) {
        if (world == null || target == null || snapshot.isEmpty()) return false;
        Block block = world.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null || block == Blocks.air) return false;
        return snapshot.matches(ObjectGroupBlockPredicate.registryName(block),
                world.getBlockMetadata(target.getX(), target.getY(), target.getZ()));
    }

    public ModeExtensionSnapshot snapshot() { return snapshot; }
}
